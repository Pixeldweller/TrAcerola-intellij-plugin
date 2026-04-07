package com.pixeldweller.tracerola.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.*;
import com.pixeldweller.tracerola.model.TracedCall;
import com.pixeldweller.tracerola.model.TracedCall.ReturnFieldSignature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Statically analyses the body of a {@link PsiMethod} to discover every call
 * made on an injected Spring / CDI / Mockito dependency.
 *
 * <p>For each call, also records:
 * <ul>
 *   <li>The 0-based source line number (so the stepper knows when we've passed it).</li>
 *   <li>The local variable name the return value is assigned to (so the stepper
 *       can evaluate it after stepping past the call).</li>
 * </ul>
 */
public final class MethodTracer {

    private static final Set<String> INJECTION_SUFFIXES = Set.of(
            "Autowired", "Inject", "Resource", "Mock", "MockBean", "SpyBean"
    );

    /** Types that should never be decomposed into fields for return-value capture. */
    private static final Set<String> SIMPLE_RETURN_TYPES = Set.of(
            "void",
            "int", "long", "short", "byte", "float", "double", "boolean", "char",
            "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Character",
            "String",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
            "java.lang.String"
    );

    private MethodTracer() {}

    @NotNull
    public static List<TracedCall> trace(@NotNull PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return Collections.emptyList();

        Map<String, PsiField> injected = new LinkedHashMap<>();
        for (PsiField field : containingClass.getAllFields()) {
            if (isInjected(field)) {
                injected.put(field.getName(), field);
            }
        }
        if (injected.isEmpty()) return Collections.emptyList();

        PsiCodeBlock body = method.getBody();
        if (body == null) return Collections.emptyList();

        // Get the document for line number resolution
        PsiFile file = method.getContainingFile();
        Document document = file != null
                ? PsiDocumentManager.getInstance(file.getProject()).getDocument(file)
                : null;

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

                String returnType = "Object";
                PsiType resolvedReturnType = null;
                PsiMethod resolved = expr.resolveMethod();
                if (resolved != null && resolved.getReturnType() != null) {
                    resolvedReturnType = resolved.getReturnType();
                    returnType = resolvedReturnType.getPresentableText();
                }

                PsiField field = injected.get(qualName);
                String qualType = field.getType().getPresentableText();

                TracedCall tc = new TracedCall(qualName, qualType, calledMethod, returnType, args);

                // Composite / enum return-type metadata — used by the stepper + generator
                populateReturnTypeMetadata(tc, resolvedReturnType);

                // Line number
                if (document != null) {
                    tc.setLineNumber(document.getLineNumber(expr.getTextOffset()));
                }

                // Result variable — check if the call's return is assigned to a local var
                tc.setResultVariable(findResultVariable(expr));

                if (seen.add(tc.callKey())) {
                    calls.add(tc);
                }
            }
        });

        return calls;
    }

    /**
     * Returns the start and end line (0-based, inclusive) of the method body.
     * Returns {@code null} if lines cannot be determined.
     */
    @Nullable
    public static int[] getMethodLineRange(@NotNull PsiMethod method) {
        PsiFile file = method.getContainingFile();
        if (file == null) return null;
        Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (doc == null) return null;

        int startLine = doc.getLineNumber(method.getTextRange().getStartOffset());
        int endLine = doc.getLineNumber(method.getTextRange().getEndOffset());
        return new int[]{startLine, endLine};
    }

    // -------------------------------------------------------------------------

    /**
     * Walks up from the method call expression to find the variable the return
     * value is assigned to.
     *
     * Handles:
     *   String result = service.call();     → "result"
     *   result = service.call();            → "result"
     *   Type x = service.call();            → "x"
     *
     * Returns null for:
     *   service.call();                     → no assignment
     *   return service.call();              → return, no local var
     *   if (service.call()) { ... }         → inline use
     */
    @Nullable
    private static String findResultVariable(@NotNull PsiMethodCallExpression expr) {
        PsiElement parent = expr.getParent();

        // Case: Type result = service.call();
        if (parent instanceof PsiLocalVariable localVar) {
            return localVar.getName();
        }

        // Case: result = service.call();
        if (parent instanceof PsiAssignmentExpression assignment) {
            PsiExpression lhs = assignment.getLExpression();
            if (lhs instanceof PsiReferenceExpression ref) {
                return ref.getReferenceName();
            }
        }

        // Case: Type result = someCondition ? service.call() : other;
        // (parent is PsiConditionalExpression → grandparent might be a variable)
        if (parent instanceof PsiConditionalExpression) {
            PsiElement grandparent = parent.getParent();
            if (grandparent instanceof PsiLocalVariable localVar) {
                return localVar.getName();
            }
        }

        return null;
    }

    /**
     * Fills in enum-flag and (for POJOs) the list of writable-field signatures so
     * the stepper can evaluate {@code resultVar.fieldName} for each one and the
     * generator can emit {@code new Type(); obj.setField(...)} blocks.
     */
    private static void populateReturnTypeMetadata(@NotNull TracedCall tc, @Nullable PsiType returnType) {
        if (returnType == null) return;
        String presentable = returnType.getPresentableText();
        if (SIMPLE_RETURN_TYPES.contains(presentable)) return;
        if (!(returnType instanceof PsiClassType classType)) return;

        PsiClass psiClass = classType.resolve();
        if (psiClass == null) return;

        if (psiClass.isEnum()) {
            tc.setReturnTypeEnum(true);
            return;
        }

        // POJO — collect instance fields (same shape as ParameterEvaluator.evaluateObjectFields)
        List<ReturnFieldSignature> sigs = new ArrayList<>();
        for (PsiField f : psiClass.getAllFields()) {
            if (f.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (f.getName().startsWith("this$")) continue;
            sigs.add(new ReturnFieldSignature(f.getName(), f.getType().getPresentableText()));
        }
        tc.setReturnFieldSignatures(sigs);
    }

    private static boolean isInjected(PsiField field) {
        for (PsiAnnotation ann : field.getAnnotations()) {
            String qn = ann.getQualifiedName();
            if (qn == null) continue;
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
