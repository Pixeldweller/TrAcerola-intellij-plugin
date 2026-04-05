package com.pixeldweller.tracerola.debug;

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManagerListener;
import com.intellij.xdebugger.XSourcePosition;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Listens for new debug sessions and registers a per-session listener that
 * shows a balloon notification when the debugger pauses inside a Spring
 * {@code @Service} or {@code @Repository} class.
 *
 * <p>The balloon includes a clickable "Generate Test" action so the user
 * can trigger TrAcerola directly from the notification.
 */
public class DebugSessionListener implements XDebuggerManagerListener {

    private static final Set<String> INTERESTING_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Component"
    );

    private final Project project;

    public DebugSessionListener(Project project) {
        this.project = project;
    }

    @Override
    public void processStarted(@NotNull XDebugProcess debugProcess) {
        debugProcess.getSession().addSessionListener(new SessionPauseListener());
    }

    // -------------------------------------------------------------------------

    private final class SessionPauseListener implements XDebugSessionListener {

        @Override
        public void sessionPaused() {
            XDebugSession session = findCurrentSession();
            if (session == null) return;

            XSourcePosition position = session.getCurrentPosition();
            if (position == null) return;

            ReadAction.run(() -> {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(position.getFile());
                if (!(psiFile instanceof PsiJavaFile)) return;

                PsiElement element = psiFile.findElementAt(position.getOffset());
                PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                if (method == null) return;

                PsiClass clazz = method.getContainingClass();
                if (clazz == null || !isInteresting(clazz)) return;

                showBalloon(clazz.getName(), method.getName());
            });
        }

        private boolean isInteresting(PsiClass clazz) {
            for (PsiAnnotation ann : clazz.getAnnotations()) {
                String qn = ann.getQualifiedName();
                if (qn != null) {
                    for (String target : INTERESTING_ANNOTATIONS) {
                        if (qn.equals(target)) return true;
                    }
                }
            }
            return false;
        }

        private void showBalloon(String className, String methodName) {
            var notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup("TrAcerola Notifications")
                    .createNotification(
                            "TrAcerola",
                            "Paused in " + className + "." + methodName + "() — click to trace and generate a test.",
                            NotificationType.INFORMATION);

            notification.addAction(NotificationAction.createSimple(
                    "Trace this Method", () -> {
                        AnAction action = ActionManager.getInstance().getAction("TrAcerola.TraceMethod");
                        if (action != null) {
                            DataContext context = SimpleDataContext.builder()
                                    .add(CommonDataKeys.PROJECT, project)
                                    .build();
                            AnActionEvent event = AnActionEvent.createFromDataContext(
                                    "TrAcerola.Notification", null, context);
                            action.actionPerformed(event);
                        }
                    }));

            notification.notify(project);
        }

        private XDebugSession findCurrentSession() {
            return com.intellij.xdebugger.XDebuggerManager.getInstance(project).getCurrentSession();
        }
    }
}
