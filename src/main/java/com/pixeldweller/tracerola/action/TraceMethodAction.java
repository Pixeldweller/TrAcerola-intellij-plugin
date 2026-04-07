package com.pixeldweller.tracerola.action;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import com.pixeldweller.tracerola.debug.MethodStepper;
import com.pixeldweller.tracerola.debug.MethodTracer;
import com.pixeldweller.tracerola.debug.ParameterEvaluator;
import com.pixeldweller.tracerola.generator.TestCaseGenerator;
import com.pixeldweller.tracerola.model.CapturedParameter;
import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;
import com.pixeldweller.tracerola.model.ReturnAnalysis;
import com.pixeldweller.tracerola.model.TracedCall;
import com.pixeldweller.tracerola.model.TraceSession;
import com.pixeldweller.tracerola.service.TracerolaStateService;
import com.pixeldweller.tracerola.ui.GeneratedTestDialog;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * "Trace this Method" — the main entry-point action for TrAcerola.
 *
 * <p>When the debugger is paused at a breakpoint (ideally at the start of
 * a method), this action:
 * <ol>
 *   <li>Captures parameter values (drilling into object fields via PSI).</li>
 *   <li>Analyses the method body to find all dependency calls (PSI).</li>
 *   <li>Automatically steps over (F8) each line until the method returns.</li>
 *   <li>After each step, captures return values from dependency calls.</li>
 *   <li>Generates a JUnit 5 + Mockito test skeleton with all traced values.</li>
 *   <li>Opens the review dialog.</li>
 * </ol>
 */
public class TraceMethodAction extends AnAction {

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            e.getPresentation().setEnabled(false);
            return;
        }
        XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
        e.getPresentation().setEnabled(session != null && session.isPaused());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        TracerolaStateService stateService = project.getService(TracerolaStateService.class);

        XDebugSession debugSession = XDebuggerManager.getInstance(project).getCurrentSession();
        if (debugSession == null || !debugSession.isPaused()) {
            stateService.notify("No active paused debug session. Pause at a breakpoint first.",
                    NotificationType.WARNING);
            return;
        }

        XSourcePosition position = debugSession.getCurrentPosition();
        if (position == null) {
            stateService.notify("Cannot determine current source position.",
                    NotificationType.WARNING);
            return;
        }

        // --- Resolve PSI (read action on EDT) ---
        Object[] psi = ApplicationManager.getApplication().runReadAction(
                (Computable<Object[]>) () -> {
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(position.getFile());
                    if (!(psiFile instanceof PsiJavaFile javaFile)) return null;

                    PsiElement element = psiFile.findElementAt(position.getOffset());
                    PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                    if (method == null) return null;

                    return new Object[]{javaFile, method};
                });

        if (psi == null) {
            stateService.notify("No Java method found at the current breakpoint position.",
                    NotificationType.WARNING);
            return;
        }

        PsiJavaFile javaFile = (PsiJavaFile) psi[0];
        PsiMethod method = (PsiMethod) psi[1];

        // --- Read PSI metadata + capture parameters ---
        String[] meta = ApplicationManager.getApplication().runReadAction(
                (Computable<String[]>) () -> {
                    PsiClass cls = method.getContainingClass();
                    String className = cls != null ? cls.getName() : "Unknown";
                    String packageName = javaFile.getPackageName();
                    String methodName = method.getName();
                    String returnType = method.getReturnType() != null
                            ? method.getReturnType().getPresentableText() : "void";
                    return new String[]{className, packageName, methodName, returnType};
                });

        String className = meta[0];
        String packageName = meta[1];
        String methodName = meta[2];
        String returnType = meta[3];

        stateService.notify("Tracing " + className + "." + methodName + "()…",
                NotificationType.INFORMATION);

        // Capture parameters (evaluates via debugger — needs read action for PSI)
        List<CapturedParameter> params = ApplicationManager.getApplication().runReadAction(
                (Computable<List<CapturedParameter>>) () ->
                        ParameterEvaluator.evaluate(method, debugSession));

        // Trace calls + get method line range (read action)
        List<TracedCall> calls = ApplicationManager.getApplication().runReadAction(
                (Computable<List<TracedCall>>) () -> MethodTracer.trace(method));

        int[] lineRange = ApplicationManager.getApplication().runReadAction(
                (Computable<int[]>) () -> MethodTracer.getMethodLineRange(method));

        // Static analysis of the method's own return type — needed by the stepper
        // to capture the value the method actually returns at its return statement.
        ReturnAnalysis returnAnalysis = ApplicationManager.getApplication().runReadAction(
                (Computable<ReturnAnalysis>) () -> MethodTracer.analyzeMethodReturn(method));

        if (lineRange == null) {
            // Can't determine line range — fall back to no-stepping mode
            buildAndShow(project, stateService, packageName, className, methodName,
                    returnType, params, calls, null, List.of());
            return;
        }

        // --- Start auto-stepping ---
        // Holder lets the onComplete lambda reach back into the stepper for the
        // values it captured during stepping (chicken-and-egg with effectively-final).
        MethodStepper[] stepperHolder = new MethodStepper[1];
        Runnable onComplete = () -> ApplicationManager.getApplication().invokeLater(() ->
                buildAndShow(project, stateService, packageName, className, methodName,
                        returnType, params, calls,
                        stepperHolder[0].getCapturedMethodReturnValue(),
                        stepperHolder[0].getCapturedMethodReturnFields()));

        stepperHolder[0] = new MethodStepper(
                debugSession, calls, lineRange[0], lineRange[1], returnAnalysis, onComplete);
        stepperHolder[0].start();
    }

    private void buildAndShow(@NotNull Project project,
                              @NotNull TracerolaStateService stateService,
                              String packageName, String className, String methodName,
                              String returnType, List<CapturedParameter> params,
                              List<TracedCall> calls,
                              String capturedReturnValue,
                              List<CapturedField> capturedReturnFields) {
        TraceSession traceSession = new TraceSession(
                packageName, className, methodName, returnType, params, calls,
                capturedReturnValue, capturedReturnFields);
        String code = TestCaseGenerator.generateFullClass(traceSession);

        stateService.addSession(traceSession, code);
        new GeneratedTestDialog(project, traceSession, code).show();
    }
}
