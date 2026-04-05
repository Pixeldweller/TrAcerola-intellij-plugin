package com.pixeldweller.tracerola.debug;

import com.intellij.psi.*;
import com.pixeldweller.tracerola.model.TracedCall;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Statically analyses the body of a {@link PsiMethod} to discover every call
 * made on an injected Spring / CDI / Mockito dependency.
 *
 * <p>A field is considered "injected" when it carries any of:
 * {@code @Autowired}, {@code @Inject}, {@code @Resource}, {@code @Mock},
 * {@code @MockBean}, or {@code @SpyBean}.
 *
 * <p>All analysis is read-only PSI traversal — no debugger interaction needed.
 */
public final class MethodTracer {

    /** Simple annotation name suffixes that flag a field as an injected dependency. */
    private static final Set<String> INJECTION_SUFFIXES = Set.of(
            "Autowired", "Inject", "Resource", "Mock", "MockBean", "SpyBean"
    );

    private MethodTracer() {}

    /**
     * Returns every unique external call found inside {@code method}, in
     * declaration order.  Duplicate call signatures (same qualifier, name, and
     * argument text) are collapsed into one entry.
     */
    @NotNull
    public static List<TracedCall> trace(@NotNull PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return Collections.emptyList();

        // Map fieldName -> PsiField for every injected dependency in the class
        Map<String, PsiField> injected = new LinkedHashMap<>();
        for (PsiField field : containingClass.getAllFields()) {
            if (isInjected(field)) {
                injected.put(field.getName(), field);
            }
        }
        if (injected.isEmpty()) return Collections.emptyList();

        PsiCodeBlock body = method.getBody();
        if (body == null) return Collections.emptyList();

        List<TracedCall> calls = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        body.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expr) {
                super.visitMethodCallExpression(expr);

                PsiReferenceExpression ref = expr.getMethodExpression();
                PsiExpression qualifier = ref.getQualifierExpression();
                String qualName = qualifierName(qualifier);
                if (qualName == null || !injected.containsKey(qualName)) return;

                String calledMethod = ref.getReferenceName();
                if (calledMethod == null) return;

                List<String> args = new ArrayList<>();
                for (PsiExpression arg : expr.getArgumentList().getExpressions()) {
                    args.add(arg.getText());
                }

                // Resolve return type via the resolved PsiMethod
                String returnType = "Object";
                PsiMethod resolved = expr.resolveMethod();
                if (resolved != null && resolved.getReturnType() != null) {
                    returnType = resolved.getReturnType().getPresentableText();
                }

                PsiField field = injected.get(qualName);
                String qualType = field.getType().getPresentableText();

                TracedCall tc = new TracedCall(qualName, qualType, calledMethod, returnType, args);
                if (seen.add(tc.callKey())) {
                    calls.add(tc);
                }
            }
        });

        return calls;
    }

    // -------------------------------------------------------------------------

    private static boolean isInjected(PsiField field) {
        for (PsiAnnotation ann : field.getAnnotations()) {
            String qn = ann.getQualifiedName();
            if (qn == null) continue;
            // Match by full qualified name suffix for resilience across javax/jakarta splits
            for (String suffix : INJECTION_SUFFIXES) {
                if (qn.endsWith(suffix)) return true;
            }
        }
        return false;
    }

    private static String qualifierName(PsiExpression qualifier) {
        if (qualifier instanceof PsiReferenceExpression ref) {
            return ref.getReferenceName();
        }
        return null;
    }
}
