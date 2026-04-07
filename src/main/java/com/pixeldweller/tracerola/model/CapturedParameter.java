package com.pixeldweller.tracerola.model;

import java.util.Collections;
import java.util.List;

/**
 * Holds metadata and the runtime-captured value of a single method parameter.
 *
 * <p>For primitive / String / enum parameters, {@link #getValue()} holds the
 * literal representation and {@link #getFields()} is empty.
 *
 * <p>For complex object parameters (e.g. a POJO), {@link #getValue()} is
 * {@code null} and {@link #getFields()} contains one entry per inspected field
 * so the generator can reconstruct the object via {@code new + setters}.
 */
public final class CapturedParameter {

    private final String name;
    private final String type;
    private final String value; // null for complex objects
    private final List<CapturedField> fields; // empty for primitives

    public CapturedParameter(String name, String type, String value) {
        this(name, type, value, Collections.emptyList());
    }

    public CapturedParameter(String name, String type, String value, List<CapturedField> fields) {
        this.name = name;
        this.type = type;
        this.value = value;
        this.fields = fields != null ? List.copyOf(fields) : Collections.emptyList();
    }

    public String getName() { return name; }
    public String getType() { return type; }

    /** The string literal representation, or {@code null} for complex objects. */
    public String getValue() { return value; }

    /** Field values for complex objects. Empty for primitives/strings. */
    public List<CapturedField> getFields() { return fields; }

    /** True if this parameter is a complex object with traced fields. */
    public boolean isComposite() {
        return value == null && !fields.isEmpty();
    }

    /**
     * Returns the captured value if available; otherwise a typed placeholder.
     * For composite objects, returns {@code null} — the generator handles
     * those via {@link #getFields()}.
     */
    public String getValueOrPlaceholder() {
        if (value != null) return value;
        if (!fields.isEmpty()) return null; // generator will use setters
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

    /**
     * A single field inside a complex traced object.
     */
    public record CapturedField(String fieldName, String fieldType, String value) {

        /**
         * The setter method name for this field (e.g. "title" -> "setTitle").
         */
        public String setterName() {
            return "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        }

        /**
         * The getter method name for this field. Booleans use the {@code is}
         * prefix; everything else uses {@code get}. Used by the test generator
         * when emitting {@code assertEquals(value, result.getX())} lines.
         */
        public String getterName() {
            String suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            boolean isBool = "boolean".equals(fieldType) || "Boolean".equals(fieldType)
                    || "java.lang.Boolean".equals(fieldType);
            return (isBool ? "is" : "get") + suffix;
        }
    }
}
