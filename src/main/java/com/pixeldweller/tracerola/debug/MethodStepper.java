package com.pixeldweller.tracerola.debug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;
import com.pixeldweller.tracerola.model.ReturnAnalysis;
import com.pixeldweller.tracerola.model.TracedCall;
import com.pixeldweller.tracerola.model.TracedCall.ReturnFieldSignature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Automatically steps over (F8) each line within a method, capturing the
 * return value of each traced dependency call after it executes.
 *
 * <h3>Usage</h3>
 * <pre>
 * MethodStepper stepper = new MethodStepper(session, calls, startLine, endLine, onDone);
 * stepper.start();
 * // ... onDone is called when the method exits or stepping finishes
 * </pre>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Registers an {@link XDebugSessionListener} on the session.</li>
 *   <li>Calls {@code session.stepOver(false)} to execute one line.</li>
 *   <li>When {@code sessionPaused()} fires, checks if we just passed a
 *       line containing a dependency call with a known result variable.</li>
 *   <li>If so, evaluates the result variable via the debugger and stores
 *       the value on the {@link TracedCall}.</li>
 *   <li>Repeats until the current position leaves the method's line range.</li>
 * </ol>
 */
public final class MethodStepper {

    /** Pause after each step before reading XValues — lets the variables view finish populating. */
    private static final long SETTLE_MS = 1000;

    /** Extra wait when an evaluation comes back as IntelliJ's "Collecting data…" placeholder. */
    private static final long COLLECTING_RETRY_MS = 2000;

    /**
     * Substrings IntelliJ inserts while a value is still being computed. Both the
     * unicode-ellipsis form and the ASCII fallback are checked because both have
     * been observed in the wild depending on font/locale.
     */
    private static final String[] COLLECTING_PLACEHOLDERS = { "Collecting data\u2026", "Collecting data..." };

    private final XDebugSession session;
    private final List<TracedCall> calls;
    private final int methodStartLine; // 0-based inclusive
    private final int methodEndLine;   // 0-based inclusive
    private final ReturnAnalysis returnAnalysis;
    private final Runnable onComplete;

    private int previousLine;

    /** Captured method return — primitive/string/enum literal, or null. */
    private String capturedMethodReturnValue;

    /** Captured method return — per-field decomposition for composite returns. */
    private List<CapturedField> capturedMethodReturnFields = Collections.emptyList();

    public MethodStepper(@NotNull XDebugSession session,
                         @NotNull List<TracedCall> calls,
                         int methodStartLine,
                         int methodEndLine,
                         @NotNull ReturnAnalysis returnAnalysis,
                         @NotNull Runnable onComplete) {
        this.session = session;
        this.calls = calls;
        this.methodStartLine = methodStartLine;
        this.methodEndLine = methodEndLine;
        this.returnAnalysis = returnAnalysis;
        this.onComplete = onComplete;
        this.previousLine = currentLine();
    }

    /** Literal captured at the executed return statement, or {@code null}. */
    @Nullable
    public String getCapturedMethodReturnValue() {
        return capturedMethodReturnValue;
    }

    /** Per-field decomposition captured at the executed return statement. Empty if none. */
    @NotNull
    public List<CapturedField> getCapturedMethodReturnFields() {
        return capturedMethodReturnFields;
    }

    /**
     * Starts the automatic stepping loop.
     */
    public void start() {
        session.addSessionListener(new StepListener());
        session.stepOver(false);
    }

    // -------------------------------------------------------------------------

    private final class StepListener implements XDebugSessionListener {

        @Override
        public void sessionPaused() {
            int currentLine = currentLine();

            // Check if we've left the method (stepped out or method returned)
            if (currentLine < 0 || currentLine < methodStartLine || currentLine > methodEndLine) {
                finish();
                return;
            }

            // Check if the source file changed (stepped into a different class)
            XSourcePosition pos = session.getCurrentPosition();
            if (pos == null) {
                finish();
                return;
            }

            // Continue stepping. Capture must run on a pooled thread (sleep + JDI eval),
            // and the actual stepOver() must be re-dispatched onto the EDT afterwards.
            ApplicationManager.getApplication().invokeLater(() -> {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    // Give the XValueTree a moment to settle before evaluating result vars.
                    sleepQuietly(SETTLE_MS);

                    // The -1 shift on both bounds is intentional: after stepOver(), IntelliJ
                    // pauses on the *next* statement to be executed, so a call on line N is
                    // only "done" once we've moved off it. Shifting the window back by one
                    // makes the inclusion test fire after the call has actually executed.
                    captureReturnValues(previousLine - 1, currentLine - 1);

                    // Method-return capture uses the *unshifted* current line, because
                    // we want to read the return expression while paused AT the return
                    // statement, before the next stepOver leaves the method.
                    captureMethodReturnIfApplicable(currentLine);

                    previousLine = currentLine;

                    ApplicationManager.getApplication().invokeLater(() -> session.stepOver(false));
                });
            });
        }

        @Override
        public void sessionResumed() {
            // stepping triggers resume → pause cycle, nothing to do
        }

        @Override
        public void sessionStopped() {
            finish();
        }

        private void finish() {
            session.removeSessionListener(this);
            onComplete.run();
        }
    }

    /**
     * For each traced call whose line number falls in the just-stepped window,
     * evaluate the {@code resultVariable} if one exists.
     *
     * <p>Three capture strategies, chosen by the return type metadata that
     * {@link MethodTracer} pre-computed:
     * <ol>
     *   <li>Primitives / String → single expression eval + formatting.</li>
     *   <li>Enums → {@code resultVar.name()} to get a clean constant name,
     *       then emit as {@code Type.NAME}.</li>
     *   <li>POJOs → evaluate each field as {@code resultVar.fieldName} and
     *       store a list of {@link CapturedField}s for the generator to turn
     *       into a {@code new + setters} block.</li>
     * </ol>
     *
     * <p>All evaluations go through {@link #evaluateWithRetry} so that the
     * "Collecting data…" placeholder IntelliJ returns when the value isn't
     * ready yet automatically retries once after a short wait.
     */
    private void captureReturnValues(int previousLine, int currentLine) {
        XStackFrame frame = session.getCurrentStackFrame();
        if (frame == null) return;
        XDebuggerEvaluator evaluator = frame.getEvaluator();
        if (evaluator == null) return;

        for (TracedCall call : calls) {
            int callLine = call.getLineNumber();
            if (callLine < 0) continue;

            // Was this call just stepped over?
            if (callLine <= previousLine || callLine > currentLine) continue;
            if ("void".equals(call.getReturnType())) continue;

            String resultVar = call.getResultVariable();
            if (resultVar != null) {
                applyCapture(call, captureExpression(evaluator, resultVar,
                        call.getReturnType(), call.isReturnTypeEnum(), call.getReturnFieldSignatures()));
                continue;
            }

            // No local variable held the return value. Try the backtrace expressions
            // (e.g. target.getX() / target.isX() inferred from a setter pattern).
            // Each candidate is tried via the full cheap→enum→POJO ladder; the
            // first one that yields a value wins. Failures are silent — the
            // evaluator already returns null for missing methods/getters.
            for (String backtraceExpr : call.getBacktraceExpressions()) {
                CaptureResult result = captureExpression(evaluator, backtraceExpr,
                        call.getReturnType(), call.isReturnTypeEnum(), call.getReturnFieldSignatures());
                if (result != null) {
                    applyCapture(call, result);
                    break;
                }
            }
        }
    }

    /**
     * Captures the value of a return expression at a {@code return} statement
     * line. Uses the unshifted current line (we're paused AT the return, before
     * stepping out). Only fires when the tracer recorded a side-effect-free
     * return expression for this line.
     */
    private void captureMethodReturnIfApplicable(int currentLine) {
        if (capturedMethodReturnValue != null || !capturedMethodReturnFields.isEmpty()) {
            return; // already captured at an earlier return statement
        }
        String returnExpr = returnAnalysis.returnPoints().get(currentLine);
        if (returnExpr == null) return;

        XStackFrame frame = session.getCurrentStackFrame();
        if (frame == null) return;
        XDebuggerEvaluator evaluator = frame.getEvaluator();
        if (evaluator == null) return;

        CaptureResult result = captureExpression(evaluator, returnExpr,
                returnAnalysis.returnType(), returnAnalysis.isEnum(), returnAnalysis.fieldSignatures());
        if (result == null) return;

        if (result.literal != null) {
            capturedMethodReturnValue = result.literal;
        } else if (!result.fields.isEmpty()) {
            capturedMethodReturnFields = result.fields;
        }
    }

    /**
     * Three-stage capture ladder shared by per-call resultVar capture, backtrace
     * capture, and method-return capture:
     * <ol>
     *   <li>Cheap eval — wins for primitives, boxed types, and String.</li>
     *   <li>Enum — {@code expression.name()} formatted as {@code Type.NAME}.</li>
     *   <li>POJO — one eval per pre-computed field signature.</li>
     * </ol>
     * Returns {@code null} when nothing was captured (so a fallback can be tried).
     */
    @Nullable
    private static CaptureResult captureExpression(@NotNull XDebuggerEvaluator evaluator,
                                                   @NotNull String expression,
                                                   @NotNull String declaredType,
                                                   boolean isEnum,
                                                   @NotNull List<ReturnFieldSignature> fieldSignatures) {
        String simple = evaluateWithRetry(evaluator, expression);
        if (simple != null) return new CaptureResult(simple, Collections.emptyList());

        if (isEnum) {
            String enumLiteral = evaluateEnumLiteralWithRetry(evaluator, expression, declaredType);
            return enumLiteral != null
                    ? new CaptureResult(enumLiteral, Collections.emptyList())
                    : null;
        }

        if (!fieldSignatures.isEmpty()) {
            List<CapturedField> captured = new ArrayList<>(fieldSignatures.size());
            boolean anyValue = false;
            for (ReturnFieldSignature sig : fieldSignatures) {
                String val = evaluateWithRetry(evaluator, expression + "." + sig.fieldName());
                if (val != null) anyValue = true;
                captured.add(new CapturedField(sig.fieldName(), sig.fieldType(), val));
            }
            // Only treat the POJO capture as successful if we actually got at
            // least one field — otherwise the expression probably didn't resolve
            // and the caller should be free to try its next fallback.
            return anyValue ? new CaptureResult(null, captured) : null;
        }
        return null;
    }

    /** Stores a {@link CaptureResult} on the call, mapping literal vs field-list to the right slot. */
    private static void applyCapture(@NotNull TracedCall call, @Nullable CaptureResult result) {
        if (result == null) return;
        if (result.literal != null) {
            call.setCapturedReturnValue(result.literal);
        } else if (!result.fields.isEmpty()) {
            call.setCapturedReturnFields(result.fields);
        }
    }

    /** Sum-type for {@link #captureExpression}: either a single literal or a list of field captures. */
    private static final class CaptureResult {
        final String literal;
        final List<CapturedField> fields;
        CaptureResult(String literal, List<CapturedField> fields) {
            this.literal = literal;
            this.fields = fields;
        }
    }

    /**
     * Evaluates {@code expression} via the debugger and retries once after a
     * short pause when IntelliJ returns its "Collecting data…" placeholder
     * (which means the JDI request is still in flight). Returns {@code null}
     * on hard failure or when the placeholder is still present after the retry.
     */
    @Nullable
    private static String evaluateWithRetry(@NotNull XDebuggerEvaluator evaluator,
                                            @NotNull String expression) {
        String value = ParameterEvaluator.evaluateAndFormat(evaluator, expression);
        if (!isCollectingPlaceholder(value)) return value;

        sleepQuietly(COLLECTING_RETRY_MS);
        value = ParameterEvaluator.evaluateAndFormat(evaluator, expression);
        return isCollectingPlaceholder(value) ? null : value;
    }

    /**
     * Same retry semantics as {@link #evaluateWithRetry}, but produces an enum
     * literal of the form {@code ShortType.CONSTANT} from {@code resultVar.name()}.
     */
    @Nullable
    private static String evaluateEnumLiteralWithRetry(@NotNull XDebuggerEvaluator evaluator,
                                                       @NotNull String resultVar,
                                                       @NotNull String declaredType) {
        String nameLiteral = evaluateWithRetry(evaluator, resultVar + ".name()");
        if (nameLiteral == null) return null;

        // evaluateAndFormat wraps Strings in quotes — peel them off.
        String constantName = nameLiteral;
        if (constantName.length() >= 2 && constantName.startsWith("\"") && constantName.endsWith("\"")) {
            constantName = constantName.substring(1, constantName.length() - 1);
        }
        if (constantName.isEmpty()) return null;

        String shortType = declaredType.contains(".")
                ? declaredType.substring(declaredType.lastIndexOf('.') + 1)
                : declaredType;
        return shortType + "." + constantName;
    }

    private static boolean isCollectingPlaceholder(@Nullable String value) {
        if (value == null) return false;
        for (String marker : COLLECTING_PLACEHOLDERS) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private int currentLine() {
        XSourcePosition pos = session.getCurrentPosition();
        return pos != null ? pos.getLine() : -1;
    }
}
