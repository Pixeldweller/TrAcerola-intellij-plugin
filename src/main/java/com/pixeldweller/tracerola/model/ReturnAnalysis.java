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
 * @param returnType        presentable text of the declared return type
 * @param isEnum            true when the return type is a Java enum
 * @param fieldSignatures   non-static instance fields of the return type for POJO decomposition
 * @param returnPoints      0-based source line → return-expression text (only safe-to-evaluate forms)
 */
public record ReturnAnalysis(
        String returnType,
        boolean isEnum,
        List<ReturnFieldSignature> fieldSignatures,
        Map<Integer, String> returnPoints) {

    public static final ReturnAnalysis VOID =
            new ReturnAnalysis("void", false, Collections.emptyList(), Collections.emptyMap());

    public boolean isVoid() {
        return "void".equals(returnType);
    }

    public boolean isComposite() {
        return !isEnum && !fieldSignatures.isEmpty();
    }
}
