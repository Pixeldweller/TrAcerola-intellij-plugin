package com.pixeldweller.tracerola.model;

import java.util.List;

/**
 * Represents a single method call made on an injected dependency that was
 * detected by {@link com.pixeldweller.tracerola.debug.MethodTracer} while
 * analysing the body of the method under the breakpoint.
 */
public final class TracedCall {

    private final String qualifierName; // e.g. "inventoryService"
    private final String qualifierType; // e.g. "InventoryService"
    private final String methodName;    // e.g. "checkStock"
    private final String returnType;    // e.g. "boolean"
    private final List<String> argExpressions; // source text of each argument

    /** Set after evaluation if the debugger captured the actual return value. */
    private String capturedReturnValue;

    public TracedCall(String qualifierName,
                      String qualifierType,
                      String methodName,
                      String returnType,
                      List<String> argExpressions) {
        this.qualifierName = qualifierName;
        this.qualifierType = qualifierType;
        this.methodName = methodName;
        this.returnType = returnType;
        this.argExpressions = List.copyOf(argExpressions);
    }

    public String getQualifierName()        { return qualifierName; }
    public String getQualifierType()        { return qualifierType; }
    public String getMethodName()           { return methodName; }
    public String getReturnType()           { return returnType; }
    public List<String> getArgExpressions() { return argExpressions; }
    public String getCapturedReturnValue()  { return capturedReturnValue; }

    public void setCapturedReturnValue(String v) {
        this.capturedReturnValue = v;
    }

    /**
     * Returns the captured runtime return value when available, otherwise
     * a type-appropriate placeholder literal for use in {@code thenReturn(…)}.
     * Returns {@code null} for {@code void} methods.
     */
    public String getMockReturnPlaceholder() {
        if (capturedReturnValue != null) return capturedReturnValue;
        return switch (returnType) {
            case "void"              -> null;
            case "boolean", "Boolean" -> "true";
            case "int", "Integer"    -> "1";
            case "long", "Long"      -> "1L";
            case "double", "Double"  -> "1.0";
            case "float", "Float"    -> "1.0f";
            case "String"            -> "\"TODO\"";
            default                  -> "null /* TODO */";
        };
    }

    /** Unique key used for deduplication within a single method body. */
    public String callKey() {
        return qualifierName + "." + methodName + "(" + String.join(", ", argExpressions) + ")";
    }
}
