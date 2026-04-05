package com.pixeldweller.tracerola.model;

/**
 * Holds metadata and the runtime-captured value of a single method parameter.
 * When the value could not be evaluated via the debugger, {@link #getValue()}
 * returns {@code null} and {@link #getValueOrPlaceholder()} falls back to a
 * type-appropriate literal so generated code at least compiles.
 */
public final class CapturedParameter {

    private final String name;
    private final String type;
    private final String value; // null = could not be evaluated at runtime

    public CapturedParameter(String name, String type, String value) {
        this.name = name;
        this.type = type;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    /** The string representation captured from the debugger, or {@code null}. */
    public String getValue() {
        return value;
    }

    /**
     * Returns the captured value if available; otherwise a typed literal
     * placeholder that the developer should replace with the real expected value.
     */
    public String getValueOrPlaceholder() {
        if (value != null) return value;
        return switch (type) {
            case "int", "short", "byte" -> "0";
            case "long"                 -> "0L";
            case "double"               -> "0.0";
            case "float"                -> "0.0f";
            case "boolean"              -> "false";
            case "char"                 -> "'\\0'";
            case "String"               -> "\"TODO\"";
            default                     -> "null /* TODO */";
        };
    }
}
