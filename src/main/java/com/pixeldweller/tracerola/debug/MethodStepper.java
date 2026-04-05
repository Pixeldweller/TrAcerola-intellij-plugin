package com.pixeldweller.tracerola.debug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import com.jetbrains.cef.remote.thrift.annotation.Nullable;
import com.pixeldweller.tracerola.model.TracedCall;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.List;

import static com.pixeldweller.tracerola.debug.ParameterEvaluator.extractValue;
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
//            captureReturnValues(previousLine, currentLine);
//
//            previousLine = currentLine;

            // Continue stepping
            ApplicationManager.getApplication().invokeLater(() -> {
                // kleiner Delay von 50ms, damit XValueTree vollständig geladen ist
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    captureReturnValues(previousLine, currentLine);
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
            if (callLine > previousLine && callLine <= currentLine) {
                String resultVar = call.getResultVariable();
                if (resultVar != null && !"void".equals(call.getReturnType())) {

                    ApplicationManager.getApplication().invokeLater(() -> session.stepOver(false));
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}

                    // 1. Try variable (cheap, no side effects)

                    String value = ParameterEvaluator.evaluateAndFormat(
                                evaluator,
                                call.getResultVariable()
                    );


                    // 2. Fallback: expression (reliable, but may re-execute)
                    if (value == null) {
                        XValue raw = ParameterEvaluator.evaluateRaw(
                                evaluator,
                                call.toExpression()
                        );

                        if (raw != null) {
                            raw.computePresentation(new XValueNode() {
                                @Override
                                public void setPresentation(@Nullable Icon icon,
                                                            @Nullable String type,
                                                            @NotNull String value,
                                                            boolean hasChildren) {

                                    if (hasChildren) {
                                        // 👉 it's an object → DON'T discard it
                                        call.setCapturedReturnValue(type); // or keep null and expand later
                                    } else {
                                        String formatted = ParameterEvaluator.formatForCode(type, value);
                                        call.setCapturedReturnValue(formatted);
                                    }
                                }

                                @Override
                                public void setPresentation(@Nullable Icon icon,
                                                            @NotNull XValuePresentation presentation,
                                                            boolean hasChildren) {

                                    String rawVal = extractValue(presentation);

                                    if (hasChildren) {
                                        call.setCapturedReturnValue(presentation.getType());
                                    } else {
                                        String formatted = ParameterEvaluator.formatForCode(
                                                presentation.getType(),
                                                rawVal
                                        );
                                        call.setCapturedReturnValue(formatted);
                                    }
                                }

                                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator e) {}
                                @Override public boolean isObsolete() { return false; }

                            }, XValuePlace.TREE);
                        }

                        value = raw != null
                                ? ParameterEvaluator.toDisplayString(raw)
                                : null;
                    }



                    if (value != null) {
                        call.setCapturedReturnValue(value);
                    }
                }
            }
        }
    }

    private int currentLine() {
        XSourcePosition pos = session.getCurrentPosition();
        return pos != null ? pos.getLine() : -1;
    }
}
