package com.pixeldweller.tracerola.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.pixeldweller.tracerola.model.TraceSession;
import com.pixeldweller.tracerola.service.TracerolaStateService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Tool window panel that lists all {@link TraceSession trace sessions} captured
 * during the current IDE session, newest first.
 *
 * <p>Double-clicking a row re-opens the {@link GeneratedTestDialog} for that session.
 */
public class TracerolaToolWindowPanel extends JPanel {

    private final Project project;
    private final DefaultListModel<TraceSession> listModel = new DefaultListModel<>();
    private final JBList<TraceSession> sessionList = new JBList<>(listModel);

    public TracerolaToolWindowPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        configureList();
        buildLayout();
        subscribeToService();
        refreshList();
    }

    // -------------------------------------------------------------------------

    private void configureList() {
        sessionList.setEmptyText(
                "No traces yet.  Pause at a breakpoint and click 'Generate Test from Breakpoint'.");
        sessionList.setCellRenderer(new SessionCellRenderer());
        sessionList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int idx = sessionList.getSelectedIndex();
                    if (idx >= 0) openSession(listModel.getElementAt(idx));
                }
            }
        });
    }

    private void buildLayout() {
        JBLabel header = new JBLabel("Recent Traces — double-click to review");
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        add(header, BorderLayout.NORTH);
        add(new JBScrollPane(sessionList), BorderLayout.CENTER);
    }

    private void subscribeToService() {
        TracerolaStateService service = project.getService(TracerolaStateService.class);
        service.addListener(this::refreshList);
    }

    private void refreshList() {
        ApplicationManager.getApplication().invokeLater(() -> {
            listModel.clear();
            project.getService(TracerolaStateService.class)
                    .getSessions()
                    .forEach(listModel::addElement);
        });
    }

    private void openSession(TraceSession session) {
        String code = project.getService(TracerolaStateService.class).getGeneratedCode(session);
        if (code != null) {
            new GeneratedTestDialog(project, session, code).show();
        }
    }

    // -------------------------------------------------------------------------

    private static final class SessionCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                       int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof TraceSession s) {
                setText(s.toString());
                setToolTipText(s.getFullyQualifiedClassName() + "." + s.getMethodName()
                        + " — " + s.getTracedCalls().size() + " mock(s)");
            }
            return this;
        }
    }
}
