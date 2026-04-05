package com.pixeldweller.tracerola.generator;

import com.intellij.ide.util.PsiNavigationSupport;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import java.io.IOException;
import java.util.List;

/**
 * Locates an existing test class for a given subject class, or creates a new
 * one in the module's test source root.
 *
 * <p>Convention: subject class {@code MyService} → test class {@code MyServiceTest}
 * in the same package, under the test source root.
 *
 * <h3>Threading</h3>
 * {@link #findTestClass} performs PSI reads — call it from a read action or the
 * EDT. {@link #findOrCreateTestClass} may run a {@link WriteCommandAction}
 * internally; call it from the EDT only, never from inside an existing read
 * action.
 */
public final class TestClassLocator {

    private TestClassLocator() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Searches for {@code <ClassName>Test} in the project scope.
     * Returns the first match, or {@code null} if none found.
     * Must be called with read access (EDT or inside {@code runReadAction}).
     */
    @Nullable
    public static PsiClass findTestClass(@NotNull PsiClass subjectClass,
                                         @NotNull Project project) {
        String testName = subjectClass.getName() + "Test";
        PsiClass[] found = PsiShortNamesCache.getInstance(project)
                .getClassesByName(testName, GlobalSearchScope.projectScope(project));
        return found.length > 0 ? found[0] : null;
    }

    /**
     * Returns an existing test class or creates a skeleton one in the test
     * source root of the same module.  Returns {@code null} if no test source
     * root is configured or creation fails.
     *
     * <p><b>Must be called from the EDT.</b> It may internally schedule a
     * {@link WriteCommandAction} to create a new class file.
     */
    @Nullable
    public static PsiClass findOrCreateTestClass(@NotNull PsiClass subjectClass,
                                                  @NotNull Project project) {
        PsiClass existing = findTestClass(subjectClass, project);
        if (existing != null) return existing;
        return createTestClass(subjectClass, project);
    }

    /**
     * Inserts {@code methodSource} (a complete {@code @Test} method string) into
     * {@code testClass} using the PSI element factory and navigates to it.
     *
     * <p>Must be called inside a {@link WriteCommandAction}.
     */
    public static void insertTestMethod(@NotNull PsiClass testClass,
                                        @NotNull String methodSource,
                                        @NotNull Project project) {
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiMethod newMethod = factory.createMethodFromText(methodSource, testClass);
        PsiElement added = testClass.add(newMethod);

        if (added instanceof PsiMethod addedMethod) {
            VirtualFile vf = addedMethod.getContainingFile().getVirtualFile();
            if (vf != null) {
                ApplicationManager.getApplication().invokeLater(() ->
                        PsiNavigationSupport.getInstance()
                                .createNavigatable(project, vf, addedMethod.getTextOffset())
                                .navigate(true));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    @Nullable
    private static PsiClass createTestClass(@NotNull PsiClass subjectClass,
                                             @NotNull Project project) {
        Module module = ModuleUtil.findModuleForPsiElement(subjectClass);
        if (module == null) return null;

        List<VirtualFile> testRoots = ModuleRootManager.getInstance(module)
                .getSourceRoots(JavaSourceRootType.TEST_SOURCE);
        if (testRoots.isEmpty()) return null;

        VirtualFile testRoot = testRoots.get(0);
        String packageName = ((PsiJavaFile) subjectClass.getContainingFile()).getPackageName();
        String testClassName = subjectClass.getName() + "Test";

        PsiClass[] result = new PsiClass[1];

        WriteCommandAction.runWriteCommandAction(project,
                "TrAcerola: Create Test Class", null, () -> {
                    try {
                        String relPath = packageName.isEmpty() ? "" : packageName.replace('.', '/');
                        VirtualFile pkgDir = relPath.isEmpty()
                                ? testRoot
                                : VfsUtil.createDirectoryIfMissing(testRoot, relPath);
                        if (pkgDir == null) return;

                        PsiDirectory directory = PsiManager.getInstance(project).findDirectory(pkgDir);
                        if (directory == null) return;

                        String content = buildClassSkeleton(packageName, testClassName);
                        PsiFile file = PsiFileFactory.getInstance(project).createFileFromText(
                                testClassName + ".java",
                                com.intellij.lang.java.JavaLanguage.INSTANCE,
                                content);
                        PsiFile added = (PsiFile) directory.add(file);
                        if (added instanceof PsiJavaFile jf && jf.getClasses().length > 0) {
                            result[0] = jf.getClasses()[0];
                        }
                    } catch (IOException ignored) {
                        // result[0] stays null — caller shows the error
                    }
                });

        return result[0];
    }

    private static String buildClassSkeleton(String packageName, String testClassName) {
        StringBuilder sb = new StringBuilder();
        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        sb.append("import org.junit.jupiter.api.Test;\n");
        sb.append("import org.junit.jupiter.api.extension.ExtendWith;\n");
        sb.append("import org.mockito.InjectMocks;\n");
        sb.append("import org.mockito.Mock;\n");
        sb.append("import org.mockito.junit.jupiter.MockitoExtension;\n");
        sb.append("import static org.junit.jupiter.api.Assertions.*;\n");
        sb.append("import static org.mockito.Mockito.*;\n\n");
        sb.append("@ExtendWith(MockitoExtension.class)\n");
        sb.append("class ").append(testClassName).append(" {\n\n}\n");
        return sb.toString();
    }
}
