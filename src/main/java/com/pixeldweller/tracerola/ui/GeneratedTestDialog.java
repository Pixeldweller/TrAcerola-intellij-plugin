package com.pixeldweller.tracerola.ui;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.EditorTextField;
import com.pixeldweller.tracerola.generator.TestCaseGenerator;
import com.pixeldweller.tracerola.generator.TestClassLocator;
import com.pixeldweller.tracerola.model.TraceSession;
import com.pixeldweller.tracerola.service.TracerolaStateService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Modal dialog that shows the generated JUnit test code and lets the developer:
 * <ul>
 *   <li><b>Copy to Clipboard</b> — for manual pasting.</li>
 *   <li><b>Insert into Test File</b> (OK button) — appends the generated
 *       {@code @Test} method to the corresponding test class, creating
 *       a new skeleton class if one does not yet exist.</li>
 * </ul>
 *
 * <h3>Threading</h3>
 * This dialog is always created and shown on the EDT. {@link #doOKAction}
 * runs its PSI reads via {@link ReadAction#compute} and its writes via
 * {@link WriteCommandAction}; both are compatible with EDT execution.
 */
public class GeneratedTestDialog extends DialogWrapper {

    private final Project project;
    private final TraceSession session;
    private final String fullClassCode;

    public GeneratedTestDialog(@NotNull Project project,
                               @NotNull TraceSession session,
                               @NotNull String fullClassCode) {
        super(project, true);
        this.project = project;
        this.session = session;
        this.fullClassCode = fullClassCode;

        setTitle("TrAcerola \u2014 " + session.getClassName() + "." + session.getMethodName() + "()");
        setOKButtonText("Insert into Test File");
        setCancelButtonText("Close");
        init();
    }

    // -------------------------------------------------------------------------
    // Dialog layout
    // -------------------------------------------------------------------------

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setPreferredSize(new Dimension(820, 540));

        // Info bar
        JLabel info = new JLabel(
                "  Class: " + session.getClassName()
                + "   Method: " + session.getMethodName()
                + "   Mocks detected: " + session.getTracedCalls().size());
        info.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        root.add(info, BorderLayout.NORTH);

        // Syntax-highlighted read-only code viewer
        Document document = EditorFactory.getInstance().createDocument(fullClassCode);
        var javaType = FileTypeManager.getInstance().getFileTypeByExtension("java");
        EditorTextField editor = new EditorTextField(document, project, javaType, true, false);
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        root.add(editor, BorderLayout.CENTER);

        // Hint
        JLabel hint = new JLabel(
                "  Review the code, then click 'Insert into Test File' or 'Copy to Clipboard'.");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        root.add(hint, BorderLayout.SOUTH);

        return root;
    }

    @Override
    protected Action[] createLeftSideActions() {
        return new Action[]{new CopyAction()};
    }

    // -------------------------------------------------------------------------
    // Insert into test file (OK button)
    // -------------------------------------------------------------------------

    @Override
    protected void doOKAction() {
        // doOKAction runs on the EDT. Read-only PSI ops use ReadAction.compute;
        // write ops use WriteCommandAction. findOrCreateTestClass must NOT be
        // called from inside a runReadAction as it may schedule a write action.

        // Step 1: locate the subject class (read)
        PsiClass subjectClass = ReadAction.compute(() ->
                JavaPsiFacade.getInstance(project).findClass(
                        session.getFullyQualifiedClassName(),
                        GlobalSearchScope.projectScope(project)));

        if (subjectClass == null) {
            Messages.showErrorDialog(project,
                    "Cannot find class: " + session.getFullyQualifiedClassName()
                            + "\nMake sure the source is compiled and indexed.",
                    "TrAcerola");
            return;
        }

        // Step 2: find or create the test class (may run a WriteCommandAction internally)
        PsiClass testClass = TestClassLocator.findOrCreateTestClass(subjectClass, project);
        if (testClass == null) {
            Messages.showErrorDialog(project,
                    "Cannot find or create a test class.\n"
                            + "Make sure your module has a test source root configured.",
                    "TrAcerola");
            return;
        }

        // Step 3: insert the generated @Test method
        String methodSource = TestCaseGenerator.generateTestMethod(session);
        WriteCommandAction.runWriteCommandAction(project,
                "TrAcerola: Insert Test Method", null,
                () -> TestClassLocator.insertTestMethod(testClass, methodSource, project));

        project.getService(TracerolaStateService.class)
                .notify("Test method inserted into " + testClass.getName() + ".",
                        NotificationType.INFORMATION);

        super.doOKAction();
    }

    // -------------------------------------------------------------------------
    // Copy to clipboard
    // -------------------------------------------------------------------------

    private final class CopyAction extends AbstractAction {

        CopyAction() {
            super("Copy to Clipboard");
        }

        @Override
        public void actionPerformed(java.awt.event.ActionEvent e) {
            CopyPasteManager.getInstance().setContents(new StringSelection(fullClassCode));
            project.getService(TracerolaStateService.class)
                    .notify("Generated test code copied to clipboard.", NotificationType.INFORMATION);
        }
    }
}
