package com.pixeldweller.tracerola.model;

import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of everything TrAcerola captured during one trace run:
 * the target class/method, the parameters visible at the breakpoint, and every
 * external (injected-dependency) call found in the method body.
 */
public final class TraceSession {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String packageName;
    private final String className;
    private final String methodName;
    private final String returnType;
    /** Element type for {@code List<E>} returns, or {@code null}. Used by the generator for per-element assertions. */
    private final String returnElementType;
    private final List<CapturedParameter> parameters;
    private final List<TracedCall> tracedCalls;
    private final String timestamp;

    /** Literal captured at the executed return statement, or {@code null}. */
    private final String capturedReturnValue;

    /** Per-field decomposition for composite returns. Empty when not applicable. */
    private final List<CapturedField> capturedReturnFields;

    /** Element-wise capture for {@code List<E>} returns. Empty when not applicable. */
    private final List<CapturedListElement> capturedReturnListElements;

    public TraceSession(String packageName,
                        String className,
                        String methodName,
                        String returnType,
                        List<CapturedParameter> parameters,
                        List<TracedCall> tracedCalls) {
        this(packageName, className, methodName, returnType, null, parameters, tracedCalls,
                null, Collections.emptyList(), Collections.emptyList());
    }

    public TraceSession(String packageName,
                        String className,
                        String methodName,
                        String returnType,
                        @Nullable String returnElementType,
                        List<CapturedParameter> parameters,
                        List<TracedCall> tracedCalls,
                        @Nullable String capturedReturnValue,
                        List<CapturedField> capturedReturnFields,
                        List<CapturedListElement> capturedReturnListElements) {
        this.packageName = packageName;
        this.className = className;
        this.methodName = methodName;
        this.returnType = returnType;
        this.returnElementType = returnElementType;
        this.parameters = List.copyOf(parameters);
        this.tracedCalls = List.copyOf(tracedCalls);
        this.timestamp = LocalDateTime.now().format(TIME_FMT);
        this.capturedReturnValue = capturedReturnValue;
        this.capturedReturnFields = capturedReturnFields != null
                ? List.copyOf(capturedReturnFields)
                : Collections.emptyList();
        this.capturedReturnListElements = capturedReturnListElements != null
                ? List.copyOf(capturedReturnListElements)
                : Collections.emptyList();
    }

    public String getPackageName()               { return packageName; }
    public String getClassName()                 { return className; }
    public String getMethodName()                { return methodName; }
    public String getReturnType()                { return returnType; }
    @Nullable
    public String getReturnElementType()         { return returnElementType; }
    public List<CapturedParameter> getParameters() { return parameters; }
    public List<TracedCall> getTracedCalls()     { return tracedCalls; }
    public String getTimestamp()                 { return timestamp; }

    @Nullable
    public String getCapturedReturnValue()       { return capturedReturnValue; }

    public List<CapturedField> getCapturedReturnFields() { return capturedReturnFields; }

    public List<CapturedListElement> getCapturedReturnListElements() { return capturedReturnListElements; }

    /** True when at least one return-field value was captured at the executed {@code return}. */
    public boolean hasCapturedReturnFields() {
        if (capturedReturnFields.isEmpty()) return false;
        for (CapturedField f : capturedReturnFields) {
            if (f.value() != null) return true;
        }
        return false;
    }

    /** True when at least one list element was captured at the executed {@code return}. */
    public boolean hasCapturedReturnListElements() {
        return !capturedReturnListElements.isEmpty();
    }

    /** Fully-qualified name of the class under test. */
    public String getFullyQualifiedClassName() {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    @Override
    public String toString() {
        return timestamp + "  " + className + "." + methodName + "()  ["
                + tracedCalls.size() + " mock(s)]";
    }
}
