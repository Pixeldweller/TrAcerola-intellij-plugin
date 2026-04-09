package com.pixeldweller.tracerola.model;

import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;

import java.util.Collections;
import java.util.List;

/**
 * Represents a single method call made on an injected dependency that was
 * detected by {@link com.pixeldweller.tracerola.debug.MethodTracer} while
 * analysing the body of the method under the breakpoint.
 */
public final class TracedCall {

    /** Signature of a field inside a composite return type (name + declared type). */
    public record ReturnFieldSignature(String fieldName, String fieldType) {
        public String setterName() {
            return "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        }
        public String getterName() {
            String suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            boolean isBool = "boolean".equals(fieldType) || "Boolean".equals(fieldType)
                    || "java.lang.Boolean".equals(fieldType);
            return (isBool ? "is" : "get") + suffix;
        }
    }

    private final String qualifierName; // e.g. "inventoryService"
    private final String qualifierType; // e.g. "InventoryService"
    private final String methodName;    // e.g. "checkStock"
    private final String returnType;    // e.g. "boolean"
    private final List<String> argExpressions; // source text of each argument

    /** 0-based line number of this call in the source file, or -1 if unknown. */
    private int lineNumber = -1;

    /** Variable name the return value is assigned to (e.g. "result"), or null. */
    private String resultVariable;

    /**
     * True when this dependency call is the direct return expression of the method
     * (e.g. {@code return orderRepository.save(order)}). Lets the stepper feed
     * the method-return capture back into this call's mock return value.
     */
    private boolean inReturnPosition;

    /** Set after evaluation if the debugger captured the actual return value. */
    private String capturedReturnValue;

    /**
     * Runtime simple name of the captured return object (e.g. "Book" when the
     * declared return type is "Optional&lt;T&gt;" or "S"). Set by the stepper when
     * runtime-class dispatch resolves a more specific type. The generator uses
     * this instead of the declared return type for {@code new ...() + setters}.
     */
    private String capturedReturnRuntimeType;

    /** True when the declared return type is a Java enum. */
    private boolean returnTypeEnum;

    /**
     * Pre-computed list of fields on the declared return type (for POJOs).
     * Populated by {@link com.pixeldweller.tracerola.debug.MethodTracer} at trace
     * time; consumed by the stepper to know which per-field expressions to evaluate.
     */
    private List<ReturnFieldSignature> returnFieldSignatures = Collections.emptyList();

    /**
     * Runtime-captured field values for a composite return object. Empty for
     * primitives/strings/enums. When non-empty, {@link #capturedReturnValue} is
     * {@code null} and the generator emits a {@code new + setters} block.
     */
    private List<CapturedField> capturedReturnFields = Collections.emptyList();

    /**
     * Candidate expressions to evaluate when {@link #resultVariable} is null.
     * Populated by {@link com.pixeldweller.tracerola.debug.MethodTracer} for
     * patterns like {@code target.setX(thisCall())} — in which case the result
     * can be recovered after the step by reading {@code target.getX()} (or
     * {@code target.isX()} for booleans). Tried in order; first non-null wins.
     */
    private List<String> backtraceExpressions = Collections.emptyList();

    // ---- List/Collection return metadata --------------------------------------
    // Set by MethodTracer when the declared return type implements java.util.List.
    // Phase 1 only supports List (clean .get(i) access + unambiguous ArrayList
    // output); Set/Iterable can be added later if there's demand.

    /** True when the declared return type is a {@code java.util.List}. */
    private boolean returnList;

    /** Element type's presentable name, e.g. "Todo" or "String". */
    private String returnElementType;

    /** True when the list element type is a Java enum. */
    private boolean returnElementEnum;

    /** Field signatures of the list element type when it's a POJO. */
    private List<ReturnFieldSignature> returnElementSignatures = Collections.emptyList();

    /** Runtime-captured list elements. Empty unless this call returns a List that was actually captured. */
    private List<CapturedListElement> capturedReturnListElements = Collections.emptyList();

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

    public int getLineNumber()              { return lineNumber; }
    public void setLineNumber(int ln)       { this.lineNumber = ln; }

    public String getResultVariable()       { return resultVariable; }
    public void setResultVariable(String v) { this.resultVariable = v; }

    public boolean isInReturnPosition()    { return inReturnPosition; }
    public void setInReturnPosition(boolean v) { this.inReturnPosition = v; }

    public String getCapturedReturnValue()  { return capturedReturnValue; }
    public void setCapturedReturnValue(String v) { this.capturedReturnValue = v; }

    public String getCapturedReturnRuntimeType()  { return capturedReturnRuntimeType; }
    public void setCapturedReturnRuntimeType(String v) { this.capturedReturnRuntimeType = v; }

    public boolean isReturnTypeEnum()       { return returnTypeEnum; }
    public void setReturnTypeEnum(boolean e) { this.returnTypeEnum = e; }

    public List<ReturnFieldSignature> getReturnFieldSignatures() { return returnFieldSignatures; }
    public void setReturnFieldSignatures(List<ReturnFieldSignature> sigs) {
        this.returnFieldSignatures = sigs != null ? List.copyOf(sigs) : Collections.emptyList();
    }

    public List<CapturedField> getCapturedReturnFields() { return capturedReturnFields; }
    public void setCapturedReturnFields(List<CapturedField> fields) {
        this.capturedReturnFields = fields != null ? List.copyOf(fields) : Collections.emptyList();
    }

    /** True when the call has a decomposed composite return value ready for emission. */
    public boolean hasCapturedReturnFields() {
        return !capturedReturnFields.isEmpty();
    }

    public List<String> getBacktraceExpressions() { return backtraceExpressions; }
    public void setBacktraceExpressions(List<String> exprs) {
        this.backtraceExpressions = exprs != null ? List.copyOf(exprs) : Collections.emptyList();
    }

    public boolean isReturnList()           { return returnList; }
    public void setReturnList(boolean v)    { this.returnList = v; }

    public String getReturnElementType()    { return returnElementType; }
    public void setReturnElementType(String t) { this.returnElementType = t; }

    public boolean isReturnElementEnum()    { return returnElementEnum; }
    public void setReturnElementEnum(boolean e) { this.returnElementEnum = e; }

    public List<ReturnFieldSignature> getReturnElementSignatures() { return returnElementSignatures; }
    public void setReturnElementSignatures(List<ReturnFieldSignature> sigs) {
        this.returnElementSignatures = sigs != null ? List.copyOf(sigs) : Collections.emptyList();
    }

    public List<CapturedListElement> getCapturedReturnListElements() { return capturedReturnListElements; }
    public void setCapturedReturnListElements(List<CapturedListElement> elements) {
        this.capturedReturnListElements = elements != null ? List.copyOf(elements) : Collections.emptyList();
    }

    /** True when the call has captured list elements ready for emission. */
    public boolean hasCapturedReturnListElements() {
        return !capturedReturnListElements.isEmpty();
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

    public String toExpression() {
        return qualifierName + "." +
                methodName +
                "(" + String.join(", ", argExpressions) + ")";
    }

    /** Unique key used for deduplication within a single method body. */
    public String callKey() {
        return qualifierName + "." + methodName + "(" + String.join(", ", argExpressions) + ")";
    }
}
