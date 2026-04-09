package com.pixeldweller.tracerola.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.pixeldweller.tracerola.service.TracerolaStateService;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Registers a small status-bar widget that shows a rotating acerola icon
 * while TrAcerola is actively tracing a method. Hover the icon to see
 * the current tracing status in the tooltip.
 */
public class TracingStatusBarWidgetFactory implements StatusBarWidgetFactory {

    static final String WIDGET_ID = "TrAcerola.TracingIndicator";

    @Override
    public @NonNls @NotNull String getId() {
        return WIDGET_ID;
    }

    @Override
    public @Nls @NotNull String getDisplayName() {
        return "TrAcerola Tracing Indicator";
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new TracingWidget(project);
    }

    // ---------------------------------------------------------------------

    private static final class TracingWidget implements StatusBarWidget, StatusBarWidget.IconPresentation {

        private static final Icon BASE_ICON =
                IconLoader.getIcon("/icons/toolWindowAcerola.svg", TracingWidget.class);

        private final Project project;
        private final RotatingIcon rotatingIcon = new RotatingIcon(BASE_ICON);
        private final Timer timer;
        private final Runnable tracingListener;

        private StatusBar statusBar;
        private boolean visible;
        private String currentStatus;

        TracingWidget(@NotNull Project project) {
            this.project = project;

            timer = new Timer(1000, e -> {
                rotatingIcon.nextStep();
                if (statusBar != null) {
                    statusBar.updateWidget(ID());
                }
            });
            timer.setRepeats(true);

            tracingListener = () -> SwingUtilities.invokeLater(this::syncState);
        }

        // -- StatusBarWidget --

        @Override
        public @NonNls @NotNull String ID() {
            return WIDGET_ID;
        }

        @Override
        public void install(@NotNull StatusBar statusBar) {
            this.statusBar = statusBar;
            TracerolaStateService svc = project.getService(TracerolaStateService.class);
            svc.addTracingListener(tracingListener);
            syncState();
        }

        @Override
        public void dispose() {
            timer.stop();
            rotatingIcon.reset();
            TracerolaStateService svc = project.getServiceIfCreated(TracerolaStateService.class);
            if (svc != null) {
                svc.removeTracingListener(tracingListener);
            }
        }

        // -- IconPresentation --

        @Override
        public @Nullable Icon getIcon() {
            return visible ? rotatingIcon : null;
        }

        @Override
        public @Nullable String getTooltipText() {
            return currentStatus;
        }

        @Override
        public @Nullable WidgetPresentation getPresentation() {
            return this;
        }

        // -- internal --

        private void syncState() {
            TracerolaStateService svc = project.getService(TracerolaStateService.class);
            String status = svc.getTracingStatus();
            currentStatus = status != null ? "TrAcerola: " + status : null;
            boolean tracing = status != null;

            if (tracing && !visible) {
                visible = true;
                rotatingIcon.reset();
                timer.start();
            } else if (!tracing && visible) {
                visible = false;
                timer.stop();
                rotatingIcon.reset();
            }
            if (statusBar != null) {
                statusBar.updateWidget(ID());
            }
        }
    }
}
