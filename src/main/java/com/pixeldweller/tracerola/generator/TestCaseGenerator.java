package com.pixeldweller.tracerola.generator;

import com.pixeldweller.tracerola.model.CapturedParameter;
import com.pixeldweller.tracerola.model.TracedCall;
import com.pixeldweller.tracerola.model.TraceSession;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Converts a {@link TraceSession} into JUnit 5 + Mockito source code.
 *
 * <p>Two public entry points:
 * <ul>
 *   <li>{@link #generateTestMethod} — just the {@code @Test} method body,
 *       suitable for insertion into an existing test class.</li>
 *   <li>{@link #generateFullClass} — a complete test class skeleton including
 *       package declaration, imports, {@code @ExtendWith}, {@code @Mock} fields,
 *       and {@code @InjectMocks}.</li>
 * </ul>
 */
public final class TestCaseGenerator {

    private TestCaseGenerator() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates the full {@code @Test} method as a self-contained string.
     * Indentation uses 4-space blocks (standard Java style).
     */
    @NotNull
    public static String generateTestMethod(@NotNull TraceSession session) {
        StringBuilder sb = new StringBuilder();
        String testName = "test" + capitalize(session.getMethodName()) + "_generated";

        sb.append("@Test\n");
        sb.append("void ").append(testName).append("() {\n");

        // --- Parameters ---
        if (!session.getParameters().isEmpty()) {
            boolean anyTraced = session.getParameters().stream()
                    .anyMatch(p -> p.getValue() != null || p.isComposite());
            sb.append("    // Parameters ").append(anyTraced ? "traced" : "captured")
              .append(" at breakpoint\n");
            for (CapturedParameter p : session.getParameters()) {
                if (p.isComposite()) {
                    sb.append("    ").append(p.getType()).append(' ').append(p.getName())
                      .append(" = new ").append(p.getType()).append("(); // traced\n");
                    for (CapturedParameter.CapturedField f : p.getFields()) {
                        if (f.value() != null) {
                            sb.append("    ").append(p.getName()).append('.')
                              .append(f.setterName()).append('(').append(f.value()).append(");\n");
                        }
                    }
                } else {
                    sb.append("    ").append(p.getType()).append(' ').append(p.getName())
                      .append(" = ").append(p.getValueOrPlaceholder()).append(';');
                    if (p.getValue() != null) {
                        sb.append(" // traced");
                    }
                    sb.append('\n');
                }
            }
            sb.append('\n');
        }

        // --- Mock setup ---
        List<TracedCall> mockable = session.getTracedCalls().stream()
                .filter(c -> !"void".equals(c.getReturnType()))
                .toList();
        if (!mockable.isEmpty()) {
            sb.append("    // Mock setup\n");
            for (TracedCall c : mockable) {
                String returnValue = c.getMockReturnPlaceholder();
                if(c.getCapturedReturnValue() != null){
                    returnValue = c.getCapturedReturnValue();
                }
                sb.append("    when(").append(c.getQualifierName()).append('.')
                  .append(c.getMethodName()).append('(')
                  .append(String.join(", ", c.getArgExpressions()))
                  .append(")).thenReturn(").append(returnValue).append(");");
                if (c.getCapturedReturnValue() != null) {
                    sb.append(" // traced " + c.getCapturedReturnValue().toString());
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        // --- Execute ---
        sb.append("    // Execute\n");
        boolean returnsValue = !"void".equals(session.getReturnType());
        if (returnsValue) {
            sb.append("    ").append(session.getReturnType()).append(" result = ");
        } else {
            sb.append("    ");
        }
        sb.append(decapitalize(session.getClassName())).append('.').append(session.getMethodName()).append('(');
        List<String> paramNames = session.getParameters().stream()
                .map(CapturedParameter::getName).toList();
        sb.append(String.join(", ", paramNames));
        sb.append(");\n\n");

        // --- Assertions ---
        sb.append("    // Assert - TODO: replace placeholders with expected values\n");
        if (returnsValue) {
            sb.append("    assertNotNull(result);\n");
        }
        for (TracedCall c : session.getTracedCalls()) {
            if ("void".equals(c.getReturnType())) {
                sb.append("    verify(").append(c.getQualifierName()).append(").")
                  .append(c.getMethodName()).append('(')
                  .append(String.join(", ", c.getArgExpressions()))
                  .append(");\n");
            }
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Generates a complete, ready-to-compile test class containing one generated
     * test method.  Duplicate mock fields (same qualifier name, different calls)
     * are collapsed into a single {@code @Mock} declaration.
     */
    @NotNull
    public static String generateFullClass(@NotNull TraceSession session) {
        String pkg = session.getPackageName();
        String testClass = session.getClassName() + "Test";
        String subjectClass = session.getClassName();
        String subjectField = decapitalize(subjectClass);

        // Deduplicate mocks by qualifier name, preserving insertion order
        Map<String, TracedCall> mocksByField = new LinkedHashMap<>();
        for (TracedCall c : session.getTracedCalls()) {
            mocksByField.putIfAbsent(c.getQualifierName(), c);
        }

        StringBuilder sb = new StringBuilder();

        // Package
        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }

        // Imports
        sb.append("import org.junit.jupiter.api.Test;\n");
        sb.append("import org.junit.jupiter.api.extension.ExtendWith;\n");
        if (!mocksByField.isEmpty()) {
            sb.append("import org.mockito.InjectMocks;\n");
            sb.append("import org.mockito.Mock;\n");
            sb.append("import org.mockito.junit.jupiter.MockitoExtension;\n");
        }
        sb.append("import static org.junit.jupiter.api.Assertions.*;\n");
        if (!mocksByField.isEmpty()) {
            sb.append("import static org.mockito.Mockito.*;\n");
        }
        sb.append('\n');

        // Class declaration
        sb.append("@ExtendWith(MockitoExtension.class)\n");
        sb.append("class ").append(testClass).append(" {\n\n");

        // @Mock fields
        for (TracedCall c : mocksByField.values()) {
            sb.append("    @Mock\n");
            sb.append("    private ").append(c.getQualifierType()).append(' ')
              .append(c.getQualifierName()).append(";\n");
        }
        if (!mocksByField.isEmpty()) sb.append('\n');

        // @InjectMocks
        sb.append("    @InjectMocks\n");
        sb.append("    private ").append(subjectClass).append(' ').append(subjectField).append(";\n\n");

        // The generated test method — re-indent for class body
        String method = generateTestMethod(session);
        for (String line : method.split("\n", -1)) {
            if (!line.isEmpty()) {
                sb.append("    ").append(line);
            }
            sb.append('\n');
        }

        sb.append("}\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
