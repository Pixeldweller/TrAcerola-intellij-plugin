package com.pixeldweller.tracerola.debug;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.*;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import com.pixeldweller.tracerola.model.CapturedParameter;
import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads method parameter metadata from PSI and captures their runtime values
 * from the active debug session via {@link XDebuggerEvaluator}.
 *
 * <p>For primitive/String/enum parameters, captures the literal directly.
 * For complex objects (POJOs), uses PSI to discover the class's fields
 * and evaluates each one as {@code paramName.fieldName} through the debugger,
 * producing a list of {@link CapturedField} entries so the generator can
 * reconstruct the object via {@code new + setters}.
 */
public final class ParameterEvaluator {

    private static final long EVAL_TIMEOUT_MS = 6000;

    /** Types that can be represented as a single Java literal. */
    private static final Set<String> SIMPLE_TYPES = Set.of(
            "int", "long", "short", "byte", "float", "double", "boolean", "char",
            "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Character",
            "String",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
            "java.lang.String"
    );

    private ParameterEvaluator() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns one {@link CapturedParameter} per formal parameter, enriched
     * with actual runtime values from the debugger. Complex objects are
     * decomposed into individual fields via PSI-guided evaluation.
     */
    @NotNull
    public static List<CapturedParameter> evaluate(@NotNull PsiMethod method,
                                                   @NotNull XDebugSession session) {
        PsiParameter[] params = method.getParameterList().getParameters();
        List<CapturedParameter> result = new ArrayList<>(params.length);

        XDebuggerEvaluator evaluator = getEvaluator(session);

        for (PsiParameter p : params) {
            String name = p.getName();
            String typeName = p.getType().getPresentableText();

            if (evaluator == null) {
                result.add(new CapturedParameter(name, typeName, null));
                continue;
            }

            if (isSimpleType(typeName)) {
                // Primitives, wrappers, strings — evaluate directly.
                // For boxed types, try unboxing first (e.g. bookId.longValue())
                // because the debugger presents wrappers as object references
                // like {Long@1234} which formatForCode rejects.
                String unboxExpr = unboxExpression(typeName, name);
                String value = null;
                if (unboxExpr != null) {
                    value = evaluateAndFormat(evaluator, unboxExpr);
                }
                if (value == null) {
                    value = evaluateAndFormat(evaluator, name);
                }
                result.add(new CapturedParameter(name, typeName, value));
            } else if (isEnumType(p.getType())) {
                // Enums — evaluate and format as EnumType.VALUE
                String value = evaluateAndFormat(evaluator, name);
                result.add(new CapturedParameter(name, typeName, value));
            } else {
                // Complex object — drill into fields via PSI
                List<CapturedField> fields = evaluateObjectFields(evaluator, name, p.getType());
                if (!fields.isEmpty()) {
                    result.add(new CapturedParameter(name, typeName, null, fields));
                } else {
                    result.add(new CapturedParameter(name, typeName, null));
                }
            }
        }

        return result;
    }

    /**
     * Evaluates a single expression and returns the formatted Java literal,
     * or {@code null} if evaluation fails. Used for mock return values.
     */
    @Nullable
    public static String evaluateAndFormat(@NotNull XDebuggerEvaluator evaluator,
                                           @NotNull String expression) {
        CompletableFuture<String> future = new CompletableFuture<>();

        evaluator.evaluate(expression, new XDebuggerEvaluator.XEvaluationCallback() {
            @Override
            public void evaluated(@NotNull XValue xValue) {
                xValue.computePresentation(new XValueNode() {
                    @Override
                    public void setPresentation(@Nullable Icon icon,
                                                @Nullable String type,
                                                @NotNull String value,
                                                boolean hasChildren) {
                        future.complete(formatForCode(type, value));
                    }

                    @Override
                    public void setPresentation(@Nullable Icon icon,
                                                @NotNull XValuePresentation presentation,
                                                boolean hasChildren) {
                        String raw = extractValue(presentation);
                        future.complete(formatForCode(presentation.getType(), raw));
                    }

                    @Override
                    public void setFullValueEvaluator(@NotNull XFullValueEvaluator e) {}

                    @Override
                    public boolean isObsolete() { return future.isDone(); }
                }, XValuePlace.TREE);
            }

            @Override
            public void errorOccurred(@NotNull String errorMessage) {
                future.complete(null);
            }
        }, null);

        try {
            return future.get(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // PSI-guided object field evaluation
    // =========================================================================

    /**
     * Resolves the PsiType to its PsiClass, enumerates declared fields,
     * and evaluates each as {@code paramName.fieldName} through the debugger.
     */
    private static List<CapturedField> evaluateObjectFields(
            @NotNull XDebuggerEvaluator evaluator,
            @NotNull String paramName,
            @NotNull PsiType psiType) {

        PsiClass psiClass = resolveClass(psiType);
        if (psiClass == null) return List.of();

        List<CapturedField> fields = new ArrayList<>();

        for (PsiField field : psiClass.getAllFields()) {
            // Skip static fields, synthetic fields, constants
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (field.getName().startsWith("this$")) continue;

            String fieldName = field.getName();
            String fieldType = field.getType().getPresentableText();

            // Evaluate "paramName.fieldName" through the debugger
            String value = evaluateAndFormat(evaluator, paramName + "." + fieldName);

            fields.add(new CapturedField(fieldName, fieldType, value));
        }

        return fields;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @Nullable
    private static XDebuggerEvaluator getEvaluator(@NotNull XDebugSession session) {
        XStackFrame frame = session.getCurrentStackFrame();
        return frame != null ? frame.getEvaluator() : null;
    }

    private static boolean isSimpleType(@NotNull String typeName) {
        return SIMPLE_TYPES.contains(typeName);
    }

    /**
     * Returns an unboxing expression for wrapper types so we get a clean
     * primitive presentation from the debugger (e.g. {@code bookId.longValue()})
     * instead of an object reference like {@code {Long@1234}}.
     * Returns {@code null} for primitives and String — they evaluate cleanly.
     */
    @Nullable
    private static String unboxExpression(@NotNull String typeName, @NotNull String varName) {
        return switch (typeName) {
            case "Long", "java.lang.Long"         -> varName + ".longValue()";
            case "Integer", "java.lang.Integer"   -> varName + ".intValue()";
            case "Short", "java.lang.Short"       -> varName + ".shortValue()";
            case "Byte", "java.lang.Byte"         -> varName + ".byteValue()";
            case "Float", "java.lang.Float"       -> varName + ".floatValue()";
            case "Double", "java.lang.Double"     -> varName + ".doubleValue()";
            case "Boolean", "java.lang.Boolean"   -> varName + ".booleanValue()";
            case "Character", "java.lang.Character" -> varName + ".charValue()";
            default -> null;
        };
    }

    private static boolean isEnumType(@NotNull PsiType type) {
        PsiClass psiClass = resolveClass(type);
        return psiClass != null && psiClass.isEnum();
    }

    @Nullable
    private static PsiClass resolveClass(@NotNull PsiType type) {
        if (type instanceof PsiClassType classType) {
            return classType.resolve();
        }
        return null;
    }

    /**
     * Formats the raw debugger presentation into a Java-source-compatible literal.
     * Returns {@code null} for object references and unrecognized shapes.
     */
    @Nullable
    static String formatForCode(@Nullable String type, @NotNull String value) {
        // Object references like "Todo@6910" or "Priority@6a3b" — unquoted @hex.
        // Returning null here forces the caller (MethodStepper) into the
        // composite-return path so the object gets decomposed into fields.
        // Exclude quoted strings — they may contain @ in content (e.g. emails).
        if (!value.startsWith("\"") && value.matches(".*@[0-9a-fA-F]+.*")) {
            return null;
        }

        if ("null".equals(value)) return "null";

        if (type == null) {
            if (value.equals("true") || value.equals("false")) return value;
            if (value.matches("-?\\d+")) return value;
            if (value.matches("-?\\d+\\.\\d+")) return value;
            if (value.startsWith("\"") && value.endsWith("\"")) return value;
            return null;
        }

        return switch (type) {
            case "String", "java.lang.String" -> {
                String unquoted = value.startsWith("\"") && value.endsWith("\"")
                        ? value.substring(1, value.length() - 1) : value;
                yield "\"" + unquoted + "\"";
            }
            case "char", "Character", "java.lang.Character" -> {
                String unquoted = value.startsWith("'") && value.endsWith("'")
                        ? value.substring(1, value.length() - 1) : value;
                yield "'" + unquoted + "'";
            }
            case "long", "Long", "java.lang.Long" ->
                    value.endsWith("L") || value.endsWith("l") ? value : value + "L";
            case "float", "Float", "java.lang.Float" ->
                    value.endsWith("f") || value.endsWith("F") ? value : value + "f";
            case "double", "Double", "java.lang.Double" ->
                    value.endsWith("d") || value.endsWith("D") ? value : value;
            case "boolean", "Boolean", "java.lang.Boolean" -> value;
            case "int", "Integer", "java.lang.Integer",
                 "short", "Short", "java.lang.Short",
                 "byte", "Byte", "java.lang.Byte" -> value;
            default -> {
                if (value.matches("[A-Z][A-Z0-9_]*")) {
                    String shortType = type.contains(".")
                            ? type.substring(type.lastIndexOf('.') + 1) : type;
                    yield shortType + "." + value;
                }
                yield null;
            }
        };
    }

    public static String extractValue(@NotNull XValuePresentation presentation) {
        StringBuilder sb = new StringBuilder();
        presentation.renderValue(new XValuePresentation.XValueTextRenderer() {
            @Override public void renderValue(@NotNull String v) { sb.append(v); }
            @Override public void renderValue(@NotNull String v, @NotNull TextAttributesKey k) { sb.append(v); }
            @Override public void renderStringValue(@NotNull String v) { sb.append('"').append(v).append('"'); }
            @Override public void renderNumericValue(@NotNull String v) { sb.append(v); }
            @Override public void renderKeywordValue(@NotNull String v) { sb.append(v); }
            @Override public void renderStringValue(@NotNull String v, @Nullable String c, int m) { sb.append('"').append(v).append('"'); }
            @Override public void renderComment(@NotNull String c) {}
            @Override public void renderSpecialSymbol(@NotNull String s) { sb.append(s); }
            @Override public void renderError(@NotNull String e) { sb.append("null"); }
        });
        return sb.toString();
    }

    @Nullable
    public static XValue evaluateRaw(@NotNull XDebuggerEvaluator evaluator,
                                     @NotNull String expression) {
        CompletableFuture<XValue> future = new CompletableFuture<>();

        evaluator.evaluate(expression, new XDebuggerEvaluator.XEvaluationCallback() {
            @Override
            public void evaluated(@NotNull XValue xValue) {
                future.complete(xValue);
            }

            @Override
            public void errorOccurred(@NotNull String errorMessage) {
                future.complete(null);
            }
        }, null);

        try {
            return future.get(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads the raw presentation string from an already-evaluated {@link XValue},
     * bypassing {@link #formatForCode}. Waits for the debugger to finish its async
     * toString computation — if the first {@code setPresentation} call delivers a
     * "Collecting data…" placeholder, the future stays open so that the follow-up
     * call (with the real value) can complete it.
     */
    @Nullable
    public static String readRawPresentation(@NotNull XValue xValue) {
        CompletableFuture<String> future = new CompletableFuture<>();
        xValue.computePresentation(new XValueNode() {
            private void offer(@NotNull String value) {
                if (value.contains("Collecting data")) return; // placeholder — wait for the real value
                future.complete(value);
            }

            @Override
            public void setPresentation(@Nullable Icon icon,
                                        @Nullable String type,
                                        @NotNull String value,
                                        boolean hasChildren) {
                offer(value);
            }

            @Override
            public void setPresentation(@Nullable Icon icon,
                                        @NotNull XValuePresentation presentation,
                                        boolean hasChildren) {
                offer(extractValue(presentation));
            }

            @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator e) {}
            @Override public boolean isObsolete() { return future.isDone(); }
        }, XValuePlace.TREE);

        try {
            return future.get(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static String toDisplayString(@NotNull XValue value) {
        CompletableFuture<String> future = new CompletableFuture<>();

        value.computePresentation(new XValueNode() {
            @Override
            public void setPresentation(@Nullable Icon icon,
                                        @Nullable String type,
                                        @NotNull String valueStr,
                                        boolean hasChildren) {

                if (hasChildren) {
                    // object → return something meaningful
                    future.complete(type != null ? type : valueStr);
                } else {
                    future.complete(formatForCode(type, valueStr));
                }
            }

            @Override
            public void setPresentation(@Nullable Icon icon,
                                        @NotNull XValuePresentation presentation,
                                        boolean hasChildren) {

                String raw = extractValue(presentation);

                if (hasChildren) {
                    future.complete(presentation.getType());
                } else {
                    future.complete(formatForCode(presentation.getType(), raw));
                }
            }

            @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator e) {}
            @Override public boolean isObsolete() { return future.isDone(); }

        }, XValuePlace.TREE);

        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
