package com.pixeldweller.tracerola.debug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.pixeldweller.tracerola.model.CapturedListElement;
import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;
import com.pixeldweller.tracerola.model.ReturnAnalysis;
import com.pixeldweller.tracerola.model.TracedCall;
import com.pixeldweller.tracerola.model.TracedCall.ReturnFieldSignature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Automatically steps over (F8) each line within a method, capturing the
 * return value of each traced dependency call after it executes.
 *
 * <h3>Usage</h3>
 * <pre>
 * MethodStepper stepper = new MethodStepper(session, calls, startLine, endLine, returnAnalysis, onDone);
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

    /** Hard cap on list-element captures so a {@code findAll()} of 100k rows doesn't hang the stepper. */
    private static final int MAX_LIST_ELEMENTS = 50;

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

    /** Captured method return — element list for {@code List<E>} returns. */
    private List<CapturedListElement> capturedMethodReturnListElements = Collections.emptyList();

    /**
     * Per-trace cache of runtime FQN → field signatures, used by the list-element
     * subclass dispatch path. Populated lazily the first time we see each runtime
     * type so the PSI {@code findClass} round-trip happens at most once per class
     * regardless of how many list elements share that type. Empty list means
     * "lookup attempted but no usable PsiClass found" — sentinel to avoid retries.
     */
    private final Map<String, List<ReturnFieldSignature>> runtimeFieldSignatureCache = new HashMap<>();

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

    /** List elements captured at the executed return statement. Empty if none. */
    @NotNull
    public List<CapturedListElement> getCapturedMethodReturnListElements() {
        return capturedMethodReturnListElements;
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
     * <p>Four capture strategies, chosen by the return type metadata that
     * {@link MethodTracer} pre-computed:
     * <ol>
     *   <li>Primitives / String → single expression eval + formatting.</li>
     *   <li>Enums → {@code resultVar.name()} to get a clean constant name,
     *       then emit as {@code Type.NAME}.</li>
     *   <li>POJOs → evaluate each field as {@code resultVar.fieldName} and
     *       store a list of {@link CapturedField}s for the generator to turn
     *       into a {@code new + setters} block.</li>
     *   <li>Lists → read {@code resultVar.size()} then recursively capture
     *       each {@code resultVar.get(i)} as a literal/POJO element.</li>
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

            CaptureSpec spec = CaptureSpec.from(call);

            String resultVar = call.getResultVariable();
            if (resultVar != null) {
                applyCapture(call, captureExpression(evaluator, resultVar, spec));
                continue;
            }

            // No local variable held the return value. Try the backtrace expressions
            // (e.g. target.getX() / target.isX() inferred from a setter pattern).
            // Each candidate is tried via the full capture ladder; the first one
            // that yields a value wins. Failures are silent — the evaluator already
            // returns null for missing methods/getters.
            for (String backtraceExpr : call.getBacktraceExpressions()) {
                CaptureResult result = captureExpression(evaluator, backtraceExpr, spec);
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
        if (capturedMethodReturnValue != null
                || !capturedMethodReturnFields.isEmpty()
                || !capturedMethodReturnListElements.isEmpty()) {
            return; // already captured at an earlier return statement
        }
        String returnExpr = returnAnalysis.returnPoints().get(currentLine);
        if (returnExpr == null) return;

        XStackFrame frame = session.getCurrentStackFrame();
        if (frame == null) return;
        XDebuggerEvaluator evaluator = frame.getEvaluator();
        if (evaluator == null) return;

        CaptureResult result = captureExpression(evaluator, returnExpr, CaptureSpec.from(returnAnalysis));
        if (result == null) return;

        if (result.literal != null) {
            capturedMethodReturnValue = result.literal;
        } else if (!result.fields.isEmpty()) {
            capturedMethodReturnFields = result.fields;
        } else if (!result.listElements.isEmpty()) {
            capturedMethodReturnListElements = result.listElements;
        }
    }

    /**
     * Capture ladder shared by per-call resultVar capture, backtrace capture,
     * and method-return capture:
     * <ol>
     *   <li>List — when {@code spec.isList()}, read {@code expression.size()}
     *       then recurse on {@code expression.get(i)} for each element. Tried
     *       first because list references would fail the cheap eval anyway.</li>
     *   <li>Cheap eval — wins for primitives, boxed types, and String.</li>
     *   <li>Enum — {@code expression.name()} formatted as {@code Type.NAME}.</li>
     *   <li>POJO — one eval per pre-computed field signature.</li>
     * </ol>
     * Returns {@code null} when nothing was captured (so a fallback can be tried).
     *
     * <p>Instance method (not static) because the list path needs access to the
     * runtime field-signature cache for subclass dispatch.
     */
    @Nullable
    private CaptureResult captureExpression(@NotNull XDebuggerEvaluator evaluator,
                                            @NotNull String expression,
                                            @NotNull CaptureSpec spec) {
        if (spec.isList()) {
            List<CapturedListElement> elements = captureListElements(evaluator, expression, spec);
            return elements != null ? CaptureResult.list(elements) : null;
        }

        String simple = evaluateWithRetry(evaluator, expression);
        if (simple != null) return CaptureResult.literal(simple);

        if (spec.isEnum()) {
            String enumLiteral = evaluateEnumLiteralWithRetry(evaluator, expression, spec.declaredType());
            return enumLiteral != null ? CaptureResult.literal(enumLiteral) : null;
        }

        return capturePojoFields(evaluator, expression, spec.fieldSignatures());
    }

    /**
     * Captures the elements of a {@code List<E>} expression by reading
     * {@code expression.size()} and then dispatching each {@code expression.get(i)}
     * through {@link #captureSingleListElement}. Returns {@code null} when the
     * size couldn't be read (i.e. the expression didn't resolve to a list);
     * returns an empty list when the runtime list itself was empty.
     */
    @Nullable
    private List<CapturedListElement> captureListElements(@NotNull XDebuggerEvaluator evaluator,
                                                          @NotNull String expression,
                                                          @NotNull CaptureSpec spec) {
        String sizeStr = evaluateWithRetry(evaluator, expression + ".size()");
        if (sizeStr == null) return null;

        int size;
        try {
            size = Integer.parseInt(sizeStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (size == 0) return Collections.emptyList();

        int captureCount = Math.min(size, MAX_LIST_ELEMENTS);
        CaptureSpec elementSpec = spec.asElement();
        List<CapturedListElement> elements = new ArrayList<>(captureCount);

        for (int i = 0; i < captureCount; i++) {
            CapturedListElement captured = captureSingleListElement(
                    evaluator, expression + ".get(" + i + ")", elementSpec);
            if (captured != null) elements.add(captured);
        }
        return elements;
    }

    /**
     * Captures a single list element with full subclass dispatch:
     * <ol>
     *   <li>Cheap eval — primitives, String, enums; the {@link ParameterEvaluator}
     *       formatting handles all three so we don't need any class info.</li>
     *   <li>Runtime FQN lookup — read {@code element.getClass().getName()} so we
     *       know what the element <em>actually</em> is, regardless of how the
     *       list was declared.</li>
     *   <li>PSI dispatch — resolve that FQN to a {@link PsiClass} via
     *       {@link JavaPsiFacade} (in a read action) and collect its instance
     *       fields. Cached per FQN so a 50-element {@code List<Animal>} of
     *       Cats only does the lookup once.</li>
     *   <li>Runtime-class capture — re-run per-field capture with the runtime
     *       signatures. The result carries the runtime type so the generator
     *       can emit {@code new Cat()} instead of {@code new Animal()}.</li>
     *   <li>Declared-type fallback — only used when PSI couldn't resolve the
     *       runtime FQN (e.g. JDK or third-party classes outside any source root).
     *       Captures whatever fields the declared element type knows about.</li>
     *   <li>Unknown marker — last resort, surfaces a {@code TODO} comment with
     *       the runtime simple name so the user sees what was lost.</li>
     * </ol>
     */
    @Nullable
    private CapturedListElement captureSingleListElement(@NotNull XDebuggerEvaluator evaluator,
                                                          @NotNull String elementExpr,
                                                          @NotNull CaptureSpec elementSpec) {
        // 1. Cheap eval — wins for primitives, String, enum literals.
        String simple = evaluateWithRetry(evaluator, elementExpr);
        if (simple != null) return CapturedListElement.ofLiteral(simple);

        // 2. Runtime FQN — needed for both subclass dispatch and the fallback marker.
        String runtimeFqn = evaluateRuntimeFqn(evaluator, elementExpr);
        String runtimeSimple = runtimeFqn != null ? simpleNameOf(runtimeFqn) : null;

        // 3 + 4. Try the runtime class's field signatures (cached per FQN).
        if (runtimeFqn != null) {
            List<ReturnFieldSignature> runtimeSigs = resolveRuntimeFieldSignatures(runtimeFqn);
            if (!runtimeSigs.isEmpty()) {
                CaptureResult res = capturePojoFields(evaluator, elementExpr, runtimeSigs);
                if (res != null && !res.fields.isEmpty()) {
                    return CapturedListElement.ofRuntimeFields(runtimeSimple, res.fields);
                }
            }
        }

        // 5. Declared-type fallback — useful when the runtime class isn't visible
        //    via PSI (third-party / JDK types) but the declared type has fields.
        if (!elementSpec.fieldSignatures().isEmpty()) {
            CaptureResult res = capturePojoFields(evaluator, elementExpr, elementSpec.fieldSignatures());
            if (res != null && !res.fields.isEmpty()) {
                return CapturedListElement.ofFields(res.fields);
            }
        }

        // 6. Unknown marker — at least surface the runtime type if we have it.
        return runtimeSimple != null ? CapturedListElement.ofUnknown(runtimeSimple) : null;
    }

    /**
     * Evaluates each field in {@code sigs} as {@code expression.fieldName} and
     * bundles them into a {@link CaptureResult}. Returns {@code null} when no
     * field came back with a value (so the caller knows to try a fallback).
     */
    @Nullable
    private static CaptureResult capturePojoFields(@NotNull XDebuggerEvaluator evaluator,
                                                    @NotNull String expression,
                                                    @NotNull List<ReturnFieldSignature> sigs) {
        if (sigs.isEmpty()) return null;
        List<CapturedField> captured = new ArrayList<>(sigs.size());
        boolean anyValue = false;
        for (ReturnFieldSignature sig : sigs) {
            String val = evaluateWithRetry(evaluator, expression + "." + sig.fieldName());
            if (val != null) anyValue = true;
            captured.add(new CapturedField(sig.fieldName(), sig.fieldType(), val));
        }
        return anyValue ? CaptureResult.composite(captured) : null;
    }

    /**
     * Looks up the field signatures of a runtime FQN via PSI, caching the result
     * for the lifetime of this stepper. The PSI lookup happens inside a read
     * action so it's safe to call from the pooled stepper thread.
     *
     * <p>An empty list is cached as a sentinel for "PSI couldn't find this class"
     * (e.g. JDK types, classes from a jar that isn't on the project's source
     * roots) so we don't repeatedly hammer {@code findClass} for the same miss.
     */
    @NotNull
    private List<ReturnFieldSignature> resolveRuntimeFieldSignatures(@NotNull String fqn) {
        return runtimeFieldSignatureCache.computeIfAbsent(fqn, qn -> {
            Project project = session.getProject();
            return ApplicationManager.getApplication().runReadAction(
                    (Computable<List<ReturnFieldSignature>>) () -> {
                        PsiClass cls = JavaPsiFacade.getInstance(project)
                                .findClass(qn, GlobalSearchScope.allScope(project));
                        return cls != null
                                ? MethodTracer.collectInstanceFieldSignatures(cls)
                                : Collections.emptyList();
                    });
        });
    }

    /**
     * Reads {@code expression.getClass().getName()} via the debugger and strips
     * the surrounding quotes that {@link ParameterEvaluator#formatForCode} adds
     * for String values. Returns the FQN with {@code $} separators preserved
     * (so inner-class lookups work) or {@code null} on failure.
     */
    @Nullable
    private static String evaluateRuntimeFqn(@NotNull XDebuggerEvaluator evaluator,
                                              @NotNull String expression) {
        String quoted = evaluateWithRetry(evaluator, expression + ".getClass().getName()");
        if (quoted == null) return null;

        String unquoted = quoted;
        if (unquoted.length() >= 2 && unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        return unquoted.isEmpty() ? null : unquoted;
    }

    /**
     * Extracts the simple class name from a JVM FQN. Handles inner classes by
     * keeping only the part after the last {@code $} so {@code com.foo.Outer$Inner}
     * becomes {@code Inner}. The result is what the generator emits in
     * {@code new Foo()} expressions, so it must be a valid Java identifier.
     */
    @NotNull
    private static String simpleNameOf(@NotNull String fqn) {
        int dot = fqn.lastIndexOf('.');
        String afterDot = dot < 0 ? fqn : fqn.substring(dot + 1);
        int dollar = afterDot.lastIndexOf('$');
        return dollar < 0 ? afterDot : afterDot.substring(dollar + 1);
    }

    /** Stores a {@link CaptureResult} on the call, mapping each shape to the right slot. */
    private static void applyCapture(@NotNull TracedCall call, @Nullable CaptureResult result) {
        if (result == null) return;
        if (result.literal != null) {
            call.setCapturedReturnValue(result.literal);
        } else if (!result.fields.isEmpty()) {
            call.setCapturedReturnFields(result.fields);
        } else if (!result.listElements.isEmpty()) {
            call.setCapturedReturnListElements(result.listElements);
        }
    }

    /** Sum-type for {@link #captureExpression}: literal, single POJO, or list of elements. */
    private static final class CaptureResult {
        final String literal;
        final List<CapturedField> fields;
        final List<CapturedListElement> listElements;

        private CaptureResult(String literal, List<CapturedField> fields, List<CapturedListElement> listElements) {
            this.literal = literal;
            this.fields = fields;
            this.listElements = listElements;
        }

        static CaptureResult literal(String l)              { return new CaptureResult(l, Collections.emptyList(), Collections.emptyList()); }
        static CaptureResult composite(List<CapturedField> f) { return new CaptureResult(null, f, Collections.emptyList()); }
        static CaptureResult list(List<CapturedListElement> e) { return new CaptureResult(null, Collections.emptyList(), e); }
    }

    /**
     * Bundle of return-type metadata used to drive {@link #captureExpression}.
     * Lets us produce a uniform spec from either a {@link TracedCall} (for
     * dependency-call captures) or a {@link ReturnAnalysis} (for the method's
     * own return) without dragging seven parameters through every call site.
     */
    private record CaptureSpec(
            String declaredType,
            boolean isEnum,
            List<ReturnFieldSignature> fieldSignatures,
            boolean isList,
            String elementType,
            boolean elementIsEnum,
            List<ReturnFieldSignature> elementSignatures) {

        /** Spec used when recursing into a single element of a list — list flags cleared. */
        CaptureSpec asElement() {
            return new CaptureSpec(elementType, elementIsEnum, elementSignatures,
                    false, null, false, Collections.emptyList());
        }

        static CaptureSpec from(@NotNull TracedCall c) {
            return new CaptureSpec(c.getReturnType(), c.isReturnTypeEnum(), c.getReturnFieldSignatures(),
                    c.isReturnList(), c.getReturnElementType(), c.isReturnElementEnum(), c.getReturnElementSignatures());
        }

        static CaptureSpec from(@NotNull ReturnAnalysis a) {
            return new CaptureSpec(a.returnType(), a.isEnum(), a.fieldSignatures(),
                    a.isList(), a.elementType(), a.elementIsEnum(), a.elementFieldSignatures());
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
