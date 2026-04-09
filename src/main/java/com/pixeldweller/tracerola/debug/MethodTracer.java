package com.pixeldweller.tracerola.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.*;
import com.intellij.psi.util.InheritanceUtil;
import com.pixeldweller.tracerola.model.ReturnAnalysis;
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
                String resultVar = findResultVariable(expr);
                tc.setResultVariable(resultVar);

                // Check if this call is in a return statement:
                //   return orderRepository.save(order);
                tc.setInReturnPosition(isInReturnPosition(expr));

                // Backtrace — only if we couldn't find a direct result variable.
                if (resultVar == null && !tc.isInReturnPosition()) {
                    List<String> backtrace = findBacktraceExpressions(expr);
                    // Fallback: for calls whose return is discarded (e.g. repo.save(book)),
                    // use the first argument as a proxy. Works well for save/persist/merge
                    // patterns where the return is the same entity.
                    if (backtrace.isEmpty()) {
                        backtrace = findFirstArgAsBacktrace(expr);
                    }
                    tc.setBacktraceExpressions(backtrace);
                }

                String key = tc.callKey() + "@" + tc.toExpression();
                //if (seen.add(key)) {
                    calls.add(tc);
                //}
//                if (seen.add(tc.callKey())) {
//                    calls.add(tc);
//                }
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
        PsiElement context = expr;

        // Walk up past method chains: a.findById(id).orElseThrow(...)
        // Each link in the chain has the structure:
        //   PsiMethodCallExpression  ← us
        //     → parent: PsiReferenceExpression (we're the qualifier of the next call)
        //       → parent: PsiMethodCallExpression (the chained call)
        // Keep climbing until the chain ends.
        while (true) {
            PsiElement p = context.getParent();
            if (p instanceof PsiReferenceExpression) {
                PsiElement pp = p.getParent();
                if (pp instanceof PsiMethodCallExpression) {
                    context = pp;
                    continue;
                }
            }
            break;
        }

        PsiElement parent = context.getParent();

        // Case: Type result = service.call();  (or end of chain)
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
        if (parent instanceof PsiConditionalExpression) {
            PsiElement grandparent = parent.getParent();
            if (grandparent instanceof PsiLocalVariable localVar) {
                return localVar.getName();
            }
        }

        return null;
    }

    /**
     * Detects the {@code target.setX(thisCall())} pattern and returns one or more
     * candidate expressions that the stepper can evaluate after the line executes
     * to recover what {@code thisCall()} returned.
     *
     * <p>Two candidates are emitted (getter and is-prefix) so the stepper can try
     * both without needing to know whether the underlying field is boolean.
     * Returns an empty list when the parent isn't a single-arg setter call.
     */
    @NotNull
    private static List<String> findBacktraceExpressions(@NotNull PsiMethodCallExpression call) {
        PsiElement parent = call.getParent();
        if (!(parent instanceof PsiExpressionList exprList)) return Collections.emptyList();

        // Must be the *only* argument — otherwise we can't unambiguously map
        // a getter back to this specific slot.
        PsiExpression[] args = exprList.getExpressions();
        if (args.length != 1 || args[0] != call) return Collections.emptyList();

        PsiElement grandparent = parent.getParent();
        if (!(grandparent instanceof PsiMethodCallExpression setterCall)) return Collections.emptyList();

        PsiReferenceExpression setterRef = setterCall.getMethodExpression();
        String setterName = setterRef.getReferenceName();
        if (setterName == null || setterName.length() <= 3 || !setterName.startsWith("set")) {
            return Collections.emptyList();
        }

        PsiExpression targetExpr = setterRef.getQualifierExpression();
        if (targetExpr == null) return Collections.emptyList();

        String targetText = targetExpr.getText();
        String suffix = setterName.substring(3); // "setId" → "Id"
        return List.of(
                targetText + ".get" + suffix + "()",
                targetText + ".is" + suffix + "()"
        );
    }

    /**
     * Static analysis of the method's own return type and (safe-to-evaluate)
     * return statements. The result is consumed by {@link MethodStepper} to
     * capture the value the method actually produces, which the test generator
     * then turns into {@code assertEquals} lines.
     *
     * <p>Only return expressions that are bare {@link PsiReferenceExpression}s
     * are recorded — anything containing a method call or arithmetic is skipped
     * to avoid double-executing side effects when the stepper re-evaluates them.
     */
    @NotNull
    public static ReturnAnalysis analyzeMethodReturn(@NotNull PsiMethod method) {
        PsiType psiReturnType = method.getReturnType();
        if (psiReturnType == null) return ReturnAnalysis.VOID;

        String returnTypeText = psiReturnType.getPresentableText();
        if ("void".equals(returnTypeText)) return ReturnAnalysis.VOID;

        boolean isEnum = false;
        List<ReturnFieldSignature> sigs = Collections.emptyList();
        boolean isList = false;
        String elementType = null;
        boolean elementIsEnum = false;
        List<ReturnFieldSignature> elementSigs = Collections.emptyList();

        if (!SIMPLE_RETURN_TYPES.contains(returnTypeText) && psiReturnType instanceof PsiClassType classType) {
            PsiClass psiClass = classType.resolve();
            if (psiClass != null) {
                ListShape listShape = analyzeListShape(classType, psiClass);
                if (listShape != null) {
                    isList = true;
                    elementType = listShape.elementType;
                    elementIsEnum = listShape.elementIsEnum;
                    elementSigs = listShape.elementSignatures;
                } else if (psiClass.isEnum()) {
                    isEnum = true;
                } else {
                    sigs = collectInstanceFieldSignatures(psiClass);
                }
            }
        }

        Map<Integer, String> returnPoints = findReturnPoints(method);
        Map<Integer, String> throwPoints = findThrowPoints(method);
        return new ReturnAnalysis(returnTypeText, isEnum, sigs, returnPoints,
                isList, elementType, elementIsEnum, elementSigs, throwPoints);
    }

    /**
     * Walks the method body for {@code return} statements and records
     * safe-to-evaluate expressions keyed by 0-based line number.
     *
     * <p>Handles two forms:
     * <ul>
     *   <li>{@code return result;} — bare reference, evaluate directly.</li>
     *   <li>{@code return dep.call(arg);} — method call; we can't re-execute it,
     *       but if the first argument is a reference, use it as a proxy. Works
     *       well for save/persist patterns where the return ≈ the input.</li>
     * </ul>
     */
    @NotNull
    private static Map<Integer, String> findReturnPoints(@NotNull PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return Collections.emptyMap();

        PsiFile file = method.getContainingFile();
        Document document = file != null
                ? PsiDocumentManager.getInstance(file.getProject()).getDocument(file)
                : null;
        if (document == null) return Collections.emptyMap();

        Map<Integer, String> points = new LinkedHashMap<>();
        body.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
                super.visitReturnStatement(statement);
                PsiExpression returnExpr = statement.getReturnValue();
                if (returnExpr == null) return;

                int line = document.getLineNumber(statement.getTextOffset());

                // Direct: return result;
                if (returnExpr instanceof PsiReferenceExpression) {
                    points.put(line, returnExpr.getText());
                    return;
                }

                // return dep.call(arg) — use first arg as proxy
                if (returnExpr instanceof PsiMethodCallExpression methodCall) {
                    PsiExpression[] args = methodCall.getArgumentList().getExpressions();
                    if (args.length > 0 && args[0] instanceof PsiReferenceExpression) {
                        points.put(line, args[0].getText());
                    }
                }
            }
        });
        return points;
    }

    /**
     * Walks the method body for {@code throw} statements and records the
     * exception type keyed by 0-based line number. Handles both single-line
     * and multi-line throw statements — the line of the {@code throw} keyword
     * is used as the key.
     *
     * <p>Supports:
     * <ul>
     *   <li>{@code throw new IllegalStateException("msg");}</li>
     *   <li>{@code throw new IllegalArgumentException("Book not found: " + bookId);}</li>
     *   <li>Multi-line throws where the constructor args span several lines.</li>
     * </ul>
     */
    @NotNull
    private static Map<Integer, String> findThrowPoints(@NotNull PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return Collections.emptyMap();

        PsiFile file = method.getContainingFile();
        Document document = file != null
                ? PsiDocumentManager.getInstance(file.getProject()).getDocument(file)
                : null;
        if (document == null) return Collections.emptyMap();

        Map<Integer, String> points = new LinkedHashMap<>();
        body.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitThrowStatement(@NotNull PsiThrowStatement statement) {
                super.visitThrowStatement(statement);
                PsiExpression exception = statement.getException();
                if (exception == null) return;

                String exceptionType = null;
                // throw new SomeException(...)
                if (exception instanceof PsiNewExpression newExpr) {
                    PsiJavaCodeReferenceElement classRef = newExpr.getClassReference();
                    if (classRef != null) {
                        exceptionType = classRef.getReferenceName();
                    }
                }
                // throw someVariable — resolve its type
                if (exceptionType == null) {
                    PsiType type = exception.getType();
                    if (type != null) {
                        exceptionType = type.getPresentableText();
                    }
                }
                if (exceptionType == null) return;

                int line = document.getLineNumber(statement.getTextOffset());
                points.put(line, exceptionType);
            }
        });
        return points;
    }

    /**
     * Fills in enum-flag and (for POJOs) the list of writable-field signatures so
     * the stepper can evaluate {@code resultVar.fieldName} for each one and the
     * generator can emit {@code new Type(); obj.setField(...)} blocks.
     *
     * <p>If the return type implements {@code java.util.List}, also fills in the
     * list-element metadata (element type, enum flag, element field signatures)
     * so the stepper can decompose {@code list.get(i)} after the call executes.
     */
    private static void populateReturnTypeMetadata(@NotNull TracedCall tc, @Nullable PsiType returnType) {
        if (returnType == null) return;
        String presentable = returnType.getPresentableText();
        if (SIMPLE_RETURN_TYPES.contains(presentable)) return;
        if (!(returnType instanceof PsiClassType classType)) return;

        PsiClass psiClass = classType.resolve();
        if (psiClass == null) return;

        // List<E> takes precedence over generic POJO decomposition: a List is also
        // a class with fields (size, elementData, …), but we never want to call
        // setSize(...) — we want to populate elements via list.get(i).
        ListShape listShape = analyzeListShape(classType, psiClass);
        if (listShape != null) {
            tc.setReturnList(true);
            tc.setReturnElementType(listShape.elementType);
            tc.setReturnElementEnum(listShape.elementIsEnum);
            tc.setReturnElementSignatures(listShape.elementSignatures);
            return;
        }

        if (psiClass.isEnum()) {
            tc.setReturnTypeEnum(true);
            return;
        }

        // POJO — collect instance fields (same shape as ParameterEvaluator.evaluateObjectFields)
        tc.setReturnFieldSignatures(collectInstanceFieldSignatures(psiClass));
    }

    /**
     * Detects {@code List<E>}-shaped types and resolves the element shape
     * (literal vs enum vs POJO field signatures). Returns {@code null} if the
     * type isn't a List, the element type can't be determined, or the element
     * type is itself an unsupported shape (e.g. nested generics).
     */
    @Nullable
    private static ListShape analyzeListShape(@NotNull PsiClassType classType, @NotNull PsiClass psiClass) {
        if (!InheritanceUtil.isInheritor(psiClass, "java.util.List")) return null;

        PsiType[] params = classType.getParameters();
        if (params.length != 1) return null;

        PsiType elementType = params[0];
        // Unwrap "? extends Foo" / "? super Foo" → take the bound; raw "?" yields null.
        if (elementType instanceof PsiWildcardType wc) {
            elementType = wc.getBound();
            if (elementType == null) return null;
        }

        String elementPresentable = elementType.getPresentableText();
        if (SIMPLE_RETURN_TYPES.contains(elementPresentable)) {
            return new ListShape(elementPresentable, false, Collections.emptyList());
        }

        if (!(elementType instanceof PsiClassType elementClassType)) return null;
        PsiClass elementClass = elementClassType.resolve();
        if (elementClass == null) return null;

        if (elementClass.isEnum()) {
            return new ListShape(elementPresentable, true, Collections.emptyList());
        }

        return new ListShape(elementPresentable, false, collectInstanceFieldSignatures(elementClass));
    }

    /**
     * Non-static, non-synthetic instance fields of {@code psiClass} as signatures.
     * Package-private so {@link MethodStepper} can reuse it for runtime-class
     * field discovery during list-element subclass dispatch.
     */
    @NotNull
    static List<ReturnFieldSignature> collectInstanceFieldSignatures(@NotNull PsiClass psiClass) {
        List<ReturnFieldSignature> sigs = new ArrayList<>();
        for (PsiField f : psiClass.getAllFields()) {
            if (f.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (f.getName().startsWith("this$")) continue;
            sigs.add(new ReturnFieldSignature(f.getName(), f.getType().getPresentableText()));
        }
        return sigs;
    }

    /** Internal carrier for the result of {@link #analyzeListShape}. */
    private record ListShape(String elementType, boolean elementIsEnum, List<ReturnFieldSignature> elementSignatures) {}

    /**
     * For calls whose return value is discarded (no variable, no backtrace,
     * not in return position), returns the first argument as a backtrace
     * expression when it's a simple local variable reference.
     *
     * <p>Captures the argument AFTER the call executes, so it reflects any
     * mutations the method made. Works well for save/persist/merge patterns
     * where the return is the same (or similar) entity as the input.
     * Returns empty when the call has no args or the arg isn't a simple reference.
     */
    @NotNull
    private static List<String> findFirstArgAsBacktrace(@NotNull PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) return Collections.emptyList();
        PsiExpression firstArg = args[0];
        if (firstArg instanceof PsiReferenceExpression) {
            return List.of(firstArg.getText());
        }
        return Collections.emptyList();
    }

    /**
     * Checks whether the call expression is a direct child of a {@code return}
     * statement (possibly through a chain).
     * <ul>
     *   <li>{@code return repo.save(order);} → true</li>
     *   <li>{@code return repo.findById(id).orElseThrow(...);} → true (for findById)</li>
     *   <li>{@code Book b = repo.findById(id).orElseThrow(...);} → false</li>
     * </ul>
     */
    private static boolean isInReturnPosition(@NotNull PsiMethodCallExpression expr) {
        PsiElement context = expr;
        // Walk up past method chains
        while (true) {
            PsiElement p = context.getParent();
            if (p instanceof PsiReferenceExpression) {
                PsiElement pp = p.getParent();
                if (pp instanceof PsiMethodCallExpression) {
                    context = pp;
                    continue;
                }
            }
            break;
        }
        return context.getParent() instanceof PsiReturnStatement;
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
