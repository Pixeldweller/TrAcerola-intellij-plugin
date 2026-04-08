package com.pixeldweller.tracerola.model;

import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;

import java.util.Collections;
import java.util.List;

/**
 * One element captured from a {@link java.util.Collection}-typed return value.
 *
 * <p>Mirrors the literal-vs-composite split used everywhere else in the model:
 * <ul>
 *   <li>{@link #literal()} non-null → primitive, String, or enum element ({@code "42"}, {@code "Priority.HIGH"}).</li>
 *   <li>{@link #fields()} non-empty → POJO element decomposed into per-field values
 *       so the generator can emit {@code new + setters}.</li>
 * </ul>
 *
 * <p>Both slots null/empty means the element couldn't be captured (e.g. evaluation
 * failed mid-list). The generator falls back to a TODO placeholder in that case.
 */
public record CapturedListElement(String literal, List<CapturedField> fields) {

    public CapturedListElement {
        fields = fields != null ? List.copyOf(fields) : Collections.emptyList();
    }

    public static CapturedListElement ofLiteral(String literal) {
        return new CapturedListElement(literal, Collections.emptyList());
    }

    public static CapturedListElement ofFields(List<CapturedField> fields) {
        return new CapturedListElement(null, fields);
    }

    public boolean isLiteral() {
        return literal != null;
    }

    public boolean isComposite() {
        return literal == null && !fields.isEmpty();
    }
}
