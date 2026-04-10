package com.pixeldweller.tracerola.model;

import org.jetbrains.annotations.Nullable;

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
     *
     * <p>Three shapes:
     * <ul>
     *   <li><b>Literal</b> — {@code value} is non-null, rest is default.</li>
     *   <li><b>Nested composite</b> — {@code nestedFields} is non-empty,
     *       {@code nestedRuntimeType} names the runtime class (e.g. "Author").</li>
     *   <li><b>Reference</b> — {@code referenceTo} names the variable of an
     *       already-captured object (cycle detection, e.g. author.books[0] → "findByIdResult").</li>
     *   <li><b>List-of-references</b> — {@code listElementReferences} names
     *       variables for known objects in a list field (e.g. author.books → ["findByIdResult"]).</li>
     * </ul>
     */
    public record CapturedField(
            String fieldName,
            String fieldType,
            @Nullable String value,
            List<CapturedField> nestedFields,
            @Nullable String nestedRuntimeType,
            @Nullable String referenceTo,
            List<String> listElementReferences,
            boolean hasSetter,
            List<TracedCall.ConstructorParam> nestedConstructorParams) {

        /** Backwards-compatible 3-arg constructor for literal fields (assumes setter exists). */
        public CapturedField(String fieldName, String fieldType, @Nullable String value) {
            this(fieldName, fieldType, value, Collections.emptyList(), null, null,
                    Collections.emptyList(), true, Collections.emptyList());
        }

        /** Literal field with explicit setter flag. */
        public CapturedField(String fieldName, String fieldType, @Nullable String value, boolean hasSetter) {
            this(fieldName, fieldType, value, Collections.emptyList(), null, null,
                    Collections.emptyList(), hasSetter, Collections.emptyList());
        }

        public static CapturedField ofNested(String fieldName, String fieldType,
                                              String runtimeType, List<CapturedField> fields,
                                              List<TracedCall.ConstructorParam> constructorParams) {
            return new CapturedField(fieldName, fieldType, null, fields, runtimeType, null,
                    Collections.emptyList(), true, constructorParams);
        }

        /** Backwards-compatible ofNested without constructor params. */
        public static CapturedField ofNested(String fieldName, String fieldType,
                                              String runtimeType, List<CapturedField> fields) {
            return ofNested(fieldName, fieldType, runtimeType, fields, Collections.emptyList());
        }

        public static CapturedField ofReference(String fieldName, String fieldType, String varName) {
            return new CapturedField(fieldName, fieldType, null, Collections.emptyList(), null, varName,
                    Collections.emptyList(), true, Collections.emptyList());
        }

        public static CapturedField ofListReferences(String fieldName, String fieldType, List<String> refs) {
            return new CapturedField(fieldName, fieldType, null, Collections.emptyList(), null, null,
                    refs, true, Collections.emptyList());
        }

        public boolean isNested() { return !nestedFields.isEmpty(); }
        public boolean isReference() { return referenceTo != null; }
        public boolean hasListReferences() { return !listElementReferences.isEmpty(); }

        public String setterName() {
            return "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        }

        public String getterName() {
            String suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            boolean isBool = "boolean".equals(fieldType) || "Boolean".equals(fieldType)
                    || "java.lang.Boolean".equals(fieldType);
            return (isBool ? "is" : "get") + suffix;
        }

        /** Getter name for list fields — used by generator to emit .getBooks().add(...). */
        public String listGetterName() {
            return getterName();
        }
    }
}
