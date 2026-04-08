package com.pixeldweller.tracerola.generator;

import com.pixeldweller.tracerola.model.CapturedListElement;
import com.pixeldweller.tracerola.model.CapturedParameter;
import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;
import com.pixeldweller.tracerola.model.TracedCall;
import com.pixeldweller.tracerola.model.TraceSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
            Set<String> usedReturnVars = new HashSet<>();
            for (TracedCall c : mockable) {
                String returnValue;
                String traceComment = null;

                if (c.hasCapturedReturnListElements()) {
                    // List<E> return — build an ArrayList, populate, use it in thenReturn.
                    String varName = makeUniqueReturnVarName(c, usedReturnVars);
                    appendListConstruction(sb, "    ", varName,
                            c.getReturnType(), c.getReturnElementType(),
                            c.getCapturedReturnListElements(), usedReturnVars);
                    returnValue = varName;
                } else if (c.hasCapturedReturnFields()) {
                    // Composite POJO return — emit `new Type(); var.setX(...)` block, then use var in thenReturn.
                    String varName = makeUniqueReturnVarName(c, usedReturnVars);
                    sb.append("    ").append(c.getReturnType()).append(' ').append(varName)
                      .append(" = new ").append(c.getReturnType()).append("(); // traced\n");
                    for (CapturedField f : c.getCapturedReturnFields()) {
                        if (f.value() != null) {
                            sb.append("    ").append(varName).append('.')
                              .append(f.setterName()).append('(').append(f.value()).append(");\n");
                        }
                    }
                    returnValue = varName;
                } else if (c.getCapturedReturnValue() != null) {
                    returnValue = c.getCapturedReturnValue();
                    traceComment = c.getCapturedReturnValue();
                } else {
                    returnValue = c.getMockReturnPlaceholder();
                }

                sb.append("    when(").append(c.getQualifierName()).append('.')
                  .append(c.getMethodName()).append('(')
                  .append(String.join(", ", c.getArgExpressions()))
                  .append(")).thenReturn(").append(returnValue).append(");");
                if (traceComment != null) {
                    sb.append(" // traced ").append(traceComment);
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
        sb.append("    // Assert\n");
        if (returnsValue) {
            sb.append("    assertNotNull(result);\n");

            // Captured-value assertions: prefer the most informative failure shape.
            //   List<E>     → size + per-element (literal or per-field) assertions
            //   composite   → per-field assertions on result
            //   primitive   → single assertEquals on result
            if (session.hasCapturedReturnListElements()) {
                appendListAssertions(sb, "    ", "result", session.getCapturedReturnListElements());
            } else if (session.hasCapturedReturnFields()) {
                for (CapturedField f : session.getCapturedReturnFields()) {
                    if (f.value() != null) {
                        sb.append("    assertEquals(").append(f.value())
                          .append(", result.").append(f.getterName()).append("());\n");
                    }
                }
            } else if (session.getCapturedReturnValue() != null) {
                sb.append("    assertEquals(").append(session.getCapturedReturnValue())
                  .append(", result);\n");
            }
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

        // Detect whether the generated body emits any List<…>/ArrayList literal
        // (either as a mock return value or as the method-under-test's own
        // return shape) so we know whether to add the java.util.* imports.
        boolean usesList = session.hasCapturedReturnListElements()
                || session.getTracedCalls().stream().anyMatch(TracedCall::hasCapturedReturnListElements);

        // Imports
        sb.append("import org.junit.jupiter.api.Test;\n");
        sb.append("import org.junit.jupiter.api.extension.ExtendWith;\n");
        if (!mocksByField.isEmpty()) {
            sb.append("import org.mockito.InjectMocks;\n");
            sb.append("import org.mockito.Mock;\n");
            sb.append("import org.mockito.junit.jupiter.MockitoExtension;\n");
        }
        if (usesList) {
            sb.append("import java.util.ArrayList;\n");
            sb.append("import java.util.List;\n");
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

    /**
     * Builds a method-body-local variable name for a composite mock return value,
     * ensuring no clash with other mocks in the same test. E.g. {@code suggestCategoryResult},
     * {@code suggestCategoryResult2}, …
     */
    static String makeUniqueReturnVarName(@NotNull TracedCall call, @NotNull Set<String> used) {
        String base = call.getMethodName() + "Result";
        String candidate = base;
        int i = 2;
        while (!used.add(candidate)) {
            candidate = base + i++;
        }
        return candidate;
    }

    /** Generates {@code listVar + i} that doesn't clash with anything in {@code used}. */
    private static String makeUniqueElementVarName(@NotNull String listVar, int index, @NotNull Set<String> used) {
        String candidate = listVar + "Elem" + index;
        int dedup = 2;
        while (!used.add(candidate)) {
            candidate = listVar + "Elem" + index + "_" + (dedup++);
        }
        return candidate;
    }

    /**
     * Emits the construction block for a captured {@code List<E>} value.
     *
     * <p>Literal elements collapse to a single {@code list.add(value)} line.
     * Composite (POJO) elements get their own temp variable so the {@code new
     * + setters} block stays readable.
     *
     * <p>Always uses {@code new ArrayList<>()} regardless of the declared
     * collection type — that gives a concrete, mutable, ordered container that
     * any {@code List<? super E>} declaration will accept.
     */
    private static void appendListConstruction(@NotNull StringBuilder sb,
                                                @NotNull String indent,
                                                @NotNull String varName,
                                                @NotNull String declaredType,
                                                @Nullable String elementType,
                                                @NotNull List<CapturedListElement> elements,
                                                @NotNull Set<String> usedNames) {
        sb.append(indent).append(declaredType).append(' ').append(varName)
          .append(" = new ArrayList<>(); // traced (")
          .append(elements.size()).append(elements.size() == 1 ? " element" : " elements").append(")\n");

        String elemTypeForNew = elementType != null ? elementType : "Object";

        for (int i = 0; i < elements.size(); i++) {
            CapturedListElement elem = elements.get(i);
            if (elem.isLiteral()) {
                sb.append(indent).append(varName).append(".add(").append(elem.literal()).append(");\n");
            } else if (elem.isComposite()) {
                String elemVar = makeUniqueElementVarName(varName, i, usedNames);
                sb.append(indent).append(elemTypeForNew).append(' ').append(elemVar)
                  .append(" = new ").append(elemTypeForNew).append("();\n");
                for (CapturedField f : elem.fields()) {
                    if (f.value() != null) {
                        sb.append(indent).append(elemVar).append('.')
                          .append(f.setterName()).append('(').append(f.value()).append(");\n");
                    }
                }
                sb.append(indent).append(varName).append(".add(").append(elemVar).append(");\n");
            } else {
                sb.append(indent).append(varName).append(".add(null); // TODO element ").append(i)
                  .append(" couldn't be captured\n");
            }
        }
    }

    /**
     * Emits assertions for a captured-list method return:
     * <ul>
     *   <li>{@code assertEquals(N, result.size())} so the count is locked in.</li>
     *   <li>For literal elements: {@code assertEquals(value, result.get(i))}.</li>
     *   <li>For composite elements: one {@code assertEquals(...result.get(i).getX())} per non-null field.</li>
     * </ul>
     */
    private static void appendListAssertions(@NotNull StringBuilder sb,
                                              @NotNull String indent,
                                              @NotNull String resultVar,
                                              @NotNull List<CapturedListElement> elements) {
        sb.append(indent).append("assertEquals(").append(elements.size())
          .append(", ").append(resultVar).append(".size());\n");

        for (int i = 0; i < elements.size(); i++) {
            CapturedListElement elem = elements.get(i);
            String accessor = resultVar + ".get(" + i + ")";
            if (elem.isLiteral()) {
                sb.append(indent).append("assertEquals(").append(elem.literal())
                  .append(", ").append(accessor).append(");\n");
            } else if (elem.isComposite()) {
                for (CapturedField f : elem.fields()) {
                    if (f.value() != null) {
                        sb.append(indent).append("assertEquals(").append(f.value())
                          .append(", ").append(accessor).append('.')
                          .append(f.getterName()).append("());\n");
                    }
                }
            }
        }
    }

    static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
