package com.pixeldweller.tracerola.model;

import com.pixeldweller.tracerola.model.TracedCall.ReturnFieldSignature;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Static analysis of a method's own return type and return statements,
 * computed by {@link com.pixeldweller.tracerola.debug.MethodTracer} so the
 * {@link com.pixeldweller.tracerola.debug.MethodStepper} can capture the
 * value the method returns at the moment it executes its {@code return}.
 *
 * <p>Only side-effect-free return expressions are recorded in
 * {@link #returnPoints}: the tracer filters out anything that isn't a bare
 * {@code PsiReferenceExpression} so re-evaluating it during stepping never
 * fires a method call twice.
 *
 * @param returnType            presentable text of the declared return type
 * @param isEnum                true when the return type is a Java enum
 * @param fieldSignatures       non-static instance fields of the return type for POJO decomposition
 * @param returnPoints          0-based source line → return-expression text (only safe-to-evaluate forms)
 * @param isList                true when the return type implements {@code java.util.List}
 * @param elementType           presentable text of the list element type, or {@code null}
 * @param elementIsEnum         true when the list element type is a Java enum
 * @param elementFieldSignatures non-static instance fields of the list element type for POJO decomposition
 */
public record ReturnAnalysis(
        String returnType,
        boolean isEnum,
        List<ReturnFieldSignature> fieldSignatures,
        Map<Integer, String> returnPoints,
        boolean isList,
        String elementType,
        boolean elementIsEnum,
        List<ReturnFieldSignature> elementFieldSignatures) {

    public static final ReturnAnalysis VOID =
            new ReturnAnalysis("void", false, Collections.emptyList(), Collections.emptyMap(),
                    false, null, false, Collections.emptyList());

    /** Convenience for the common non-list case. */
    public ReturnAnalysis(String returnType,
                          boolean isEnum,
                          List<ReturnFieldSignature> fieldSignatures,
                          Map<Integer, String> returnPoints) {
        this(returnType, isEnum, fieldSignatures, returnPoints,
                false, null, false, Collections.emptyList());
    }

    public boolean isVoid() {
        return "void".equals(returnType);
    }

    public boolean isComposite() {
        return !isEnum && !fieldSignatures.isEmpty();
    }
}
