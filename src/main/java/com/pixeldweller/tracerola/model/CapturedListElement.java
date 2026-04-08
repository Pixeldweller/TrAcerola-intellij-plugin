package com.pixeldweller.tracerola.model;

import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;

import java.util.Collections;
import java.util.List;

/**
 * One element captured from a {@link java.util.Collection}-typed return value.
 *
 * <p>Three element shapes, mirroring the literal/composite split used everywhere
 * else in the model plus an "unknown" escape hatch for heterogeneous lists:
 * <ul>
 *   <li>{@link #literal()} non-null → primitive, String, or enum element
 *       ({@code "42"}, {@code "Priority.HIGH"}).</li>
 *   <li>{@link #fields()} non-empty → POJO element decomposed into per-field
 *       values so the generator can emit {@code new + setters}.</li>
 *   <li>{@link #runtimeType()} non-null with both other slots empty → the element
 *       couldn't be decomposed against the declared element type (typically because
 *       the list is declared as {@code List<Object>} or some interface and the
 *       runtime element is a subclass with fields the tracer doesn't know about).
 *       The generator emits this as a {@code TODO} comment with the runtime
 *       class name so the user can fill it in by hand.</li>
 * </ul>
 *
 * <p>If all three slots are empty/null, the element is treated as fully
 * unrecoverable and the generator emits {@code add(null)}.
 */
public record CapturedListElement(String literal, List<CapturedField> fields, String runtimeType) {

    public CapturedListElement {
        fields = fields != null ? List.copyOf(fields) : Collections.emptyList();
    }

    public static CapturedListElement ofLiteral(String literal) {
        return new CapturedListElement(literal, Collections.emptyList(), null);
    }

    public static CapturedListElement ofFields(List<CapturedField> fields) {
        return new CapturedListElement(null, fields, null);
    }

    /**
     * Composite element captured against a runtime class that wasn't the same
     * as the declared element type — e.g. a {@code Notification} inside a
     * {@code List<Object>}, or a {@code Cat} inside a {@code List<Animal>}.
     * The generator uses {@link #runtimeType()} as the type for the
     * {@code new ...()} expression instead of the declared element type, so
     * subclass-specific fields aren't lost.
     */
    public static CapturedListElement ofRuntimeFields(String runtimeType, List<CapturedField> fields) {
        return new CapturedListElement(null, fields, runtimeType);
    }

    /**
     * Element whose declared-type capture failed but whose runtime simple class
     * name was successfully read via {@code element.getClass().getSimpleName()}.
     * The generator emits this as a {@code TODO} marker so the user sees what
     * was lost rather than having the element silently dropped.
     */
    public static CapturedListElement ofUnknown(String runtimeType) {
        return new CapturedListElement(null, Collections.emptyList(), runtimeType);
    }

    public boolean isLiteral() {
        return literal != null;
    }

    public boolean isComposite() {
        return literal == null && !fields.isEmpty();
    }

    public boolean isUnknown() {
        return literal == null && fields.isEmpty() && runtimeType != null;
    }
}
