package com.pixeldweller.tracerola.action;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import com.pixeldweller.tracerola.debug.MethodTracer;
import com.pixeldweller.tracerola.debug.ParameterEvaluator;
import com.pixeldweller.tracerola.generator.TestCaseGenerator;
import com.pixeldweller.tracerola.model.CapturedParameter;
import com.pixeldweller.tracerola.model.TracedCall;
import com.pixeldweller.tracerola.model.TraceSession;
import com.pixeldweller.tracerola.service.TracerolaStateService;
import com.pixeldweller.tracerola.ui.GeneratedTestDialog;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The single entry-point action for TrAcerola.
 *
 * <p>When the debugger is paused at a breakpoint this action:
 * <ol>
 *   <li>Resolves the breakpoint's source position to a {@link PsiMethod}.</li>
 *   <li>Extracts parameter names/types (and runtime values where available).</li>
 *   <li>Traces every call made on injected dependencies in the method body.</li>
 *   <li>Generates a JUnit 5 + Mockito test skeleton.</li>
 *   <li>Opens {@link GeneratedTestDialog} for review / insertion.</li>
 * </ol>
 *
 * <p>The action is disabled automatically when no debug session is paused.
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

        XDebugSession debugSession = XDebuggerManager.getInstance(project).getCurrentSession();
        if (debugSession == null || !debugSession.isPaused()) {
            project.getService(TracerolaStateService.class)
                    .notify("No active paused debug session found. Pause at a breakpoint first.",
                            NotificationType.WARNING);
            return;
        }

        XSourcePosition position = debugSession.getCurrentPosition();
        if (position == null) {
            project.getService(TracerolaStateService.class)
                    .notify("Cannot determine current source position.", NotificationType.WARNING);
            return;
        }

        // PSI reads must happen inside a read action
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(position.getFile());
            if (!(psiFile instanceof PsiJavaFile javaFile)) {
                project.getService(TracerolaStateService.class)
                        .notify("TrAcerola only works with Java source files.", NotificationType.WARNING);
                return;
            }

            PsiElement element = psiFile.findElementAt(position.getOffset());
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (method == null) {
                project.getService(TracerolaStateService.class)
                        .notify("No method found at the current breakpoint position.", NotificationType.WARNING);
                return;
            }

            PsiClass containingClass = method.getContainingClass();
            String className = containingClass != null ? containingClass.getName() : "Unknown";
            String packageName = javaFile.getPackageName();
            String returnType = method.getReturnType() != null
                    ? method.getReturnType().getPresentableText() : "void";

            // --- Capture parameters ---
            List<CapturedParameter> params = ParameterEvaluator.evaluate(method, debugSession);

            // --- Trace external calls ---
            List<TracedCall> calls = MethodTracer.trace(method);

            // --- Build session ---
            TraceSession traceSession = new TraceSession(
                    packageName, className, method.getName(), returnType, params, calls);

            // --- Generate source ---
            String code = TestCaseGenerator.generateFullClass(traceSession);

            // --- Persist ---
            TracerolaStateService service = project.getService(TracerolaStateService.class);
            service.addSession(traceSession, code);

            // --- Open dialog on the EDT ---
            ApplicationManager.getApplication().invokeLater(
                    () -> new GeneratedTestDialog(project, traceSession, code).show());
        });
    }
}
