package com.pixeldweller.tracerola.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final List<CapturedParameter> parameters;
    private final List<TracedCall> tracedCalls;
    private final String timestamp;

    public TraceSession(String packageName,
                        String className,
                        String methodName,
                        String returnType,
                        List<CapturedParameter> parameters,
                        List<TracedCall> tracedCalls) {
        this.packageName = packageName;
        this.className = className;
        this.methodName = methodName;
        this.returnType = returnType;
        this.parameters = List.copyOf(parameters);
        this.tracedCalls = List.copyOf(tracedCalls);
        this.timestamp = LocalDateTime.now().format(TIME_FMT);
    }

    public String getPackageName()               { return packageName; }
    public String getClassName()                 { return className; }
    public String getMethodName()                { return methodName; }
    public String getReturnType()                { return returnType; }
    public List<CapturedParameter> getParameters() { return parameters; }
    public List<TracedCall> getTracedCalls()     { return tracedCalls; }
    public String getTimestamp()                 { return timestamp; }

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
