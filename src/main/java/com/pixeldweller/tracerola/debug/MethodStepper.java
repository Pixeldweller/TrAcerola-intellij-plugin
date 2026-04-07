package com.pixeldweller.tracerola.debug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;
import com.pixeldweller.tracerola.model.TracedCall;
import com.pixeldweller.tracerola.model.TracedCall.ReturnFieldSignature;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
//import java.util.function.Runnable;

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

    private final XDebugSession session;
    private final List<TracedCall> calls;
    private final int methodStartLine; // 0-based inclusive
    private final int methodEndLine;   // 0-based inclusive
    private final Runnable onComplete;

    private int previousLine;

    public MethodStepper(@NotNull XDebugSession session,
                         @NotNull List<TracedCall> calls,
                         int methodStartLine,
                         int methodEndLine,
                         @NotNull Runnable onComplete) {
        this.session = session;
        this.calls = calls;
        this.methodStartLine = methodStartLine;
        this.methodEndLine = methodEndLine;
        this.onComplete = onComplete;
        this.previousLine = currentLine();
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

        //int currentLine = currentLine();
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

//            // Capture return values for any dependency calls between previousLine and currentLine
            //captureReturnValues(previousLine, currentLine);
//
          //previousLine = currentLine;

            // Continue stepping
            ApplicationManager.getApplication().invokeLater(() -> {
                // kleiner Delay von 50ms, damit XValueTree vollständig geladen ist
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    captureReturnValues(previousLine-1, currentLine-1);
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
     * For each traced call whose line number falls between previousLine (exclusive)
     * and currentLine (inclusive), evaluate the resultVariable if one exists.
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
            if (resultVar == null) continue;

            System.out.println("Call:"+call.getMethodName());
            // 1. Try the cheap path — works for primitives, boxed types, String.

            String simple = ParameterEvaluator.evaluateAndFormat(evaluator, resultVar);
            if (simple != null && !simple.contains("Collecting data…")) {
                call.setCapturedReturnValue(simple);
                continue;
            } else {
//                ApplicationManager.getApplication().invokeLater(() -> session.stepOver(false));
//                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                simple = ParameterEvaluator.evaluateAndFormat(evaluator, resultVar);
                if(simple != null && simple.contains("Collecting data…")){
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    simple = ParameterEvaluator.evaluateAndFormat(evaluator, resultVar);
                }
                if (simple != null) {
                    call.setCapturedReturnValue(simple);
                    continue;
                }
            }

            // 2. Enum — ask the debugger for the constant name directly.
            if (call.isReturnTypeEnum()) {
                String enumLiteral = evaluateEnumLiteral(evaluator, resultVar, call.getReturnType());
                if (enumLiteral != null) {
                    call.setCapturedReturnValue(enumLiteral);
                } else {
                    int maxTry = 1;
                    while(enumLiteral == null && maxTry>0){
                        maxTry--;
//                        ApplicationManager.getApplication().invokeLater(() -> session.stepOver(false));
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        enumLiteral = evaluateEnumLiteral(evaluator, resultVar, call.getReturnType());
//                        if(enumLiteral != null && enumLiteral.contains("Collecting data…")){
//                            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
//                            enumLiteral = evaluateEnumLiteral(evaluator, resultVar, call.getReturnType());
//                        }
                    }
                    if (enumLiteral != null) {
                        call.setCapturedReturnValue(enumLiteral);
                    }
                }
                continue;
            }

            // 3. POJO — decompose into per-field captures using the pre-computed signatures.
            List<ReturnFieldSignature> sigs = call.getReturnFieldSignatures();
            if (!sigs.isEmpty()) {
                List<CapturedField> captured = new ArrayList<>(sigs.size());
                for (ReturnFieldSignature sig : sigs) {
                    String fieldExpr = resultVar + "." + sig.fieldName();
                    String val = ParameterEvaluator.evaluateAndFormat(evaluator, fieldExpr);

                    if(val != null && val.contains("Collecting data…")){
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        val = ParameterEvaluator.evaluateAndFormat(evaluator, fieldExpr);
                    }

                    captured.add(new CapturedField(sig.fieldName(), sig.fieldType(), val));
                }
                call.setCapturedReturnFields(captured);
            }
        }
    }

    /**
     * Evaluates {@code resultVar.name()} on a paused debugger to get the enum
     * constant name, then formats it as {@code ShortType.NAME} for the generator.
     * Returns {@code null} on evaluation failure.
     */
    @org.jetbrains.annotations.Nullable
    private static String evaluateEnumLiteral(@NotNull XDebuggerEvaluator evaluator,
                                               @NotNull String resultVar,
                                               @NotNull String declaredType) {
        String nameLiteral = ParameterEvaluator.evaluateAndFormat(evaluator, resultVar + ".name()");
        if (nameLiteral == null) return null;

        // evaluateAndFormat wraps Strings in quotes — peel them back off.
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

    private int currentLine() {
        XSourcePosition pos = session.getCurrentPosition();
        return pos != null ? pos.getLine() : -1;
    }
}
