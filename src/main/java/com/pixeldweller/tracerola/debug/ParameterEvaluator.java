package com.pixeldweller.tracerola.debug;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.xdebugger.XDebugSession;
import com.pixeldweller.tracerola.model.CapturedParameter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads method parameter metadata (name + type) from PSI and, where possible,
 * captures the runtime value from the active debug session.
 *
 * <h3>Current behaviour (v0.1)</h3>
 * Parameter names and types are always extracted from PSI. Runtime value
 * capture via {@link com.intellij.xdebugger.evaluation.XDebuggerEvaluator}
 * is planned for a future release; until then {@link CapturedParameter#getValue()}
 * returns {@code null} and the generator falls back to typed placeholders.
 *
 * <h3>Extension point</h3>
 * To add live evaluation, implement the {@code evaluateRuntimeValues} helper
 * below and call it from {@link #evaluate}.  The evaluator is available via
 * {@code session.getCurrentStackFrame().getEvaluator()}.
 */
public final class ParameterEvaluator {

    private ParameterEvaluator() {}

    /**
     * Returns one {@link CapturedParameter} per formal parameter of
     * {@code method}, enriched with a runtime value if one can be obtained.
     */
    @NotNull
    public static List<CapturedParameter> evaluate(@NotNull PsiMethod method,
                                                   @NotNull XDebugSession session) {
        PsiParameter[] params = method.getParameterList().getParameters();
        List<CapturedParameter> result = new ArrayList<>(params.length);

        for (PsiParameter p : params) {
            String name = p.getName();
            String type = p.getType().getPresentableText();
            // TODO (v0.2): use session.getCurrentStackFrame().getEvaluator()
            //              to capture the actual runtime value via XDebuggerEvaluator.
            result.add(new CapturedParameter(name, type, null));
        }

        return result;
    }
}
