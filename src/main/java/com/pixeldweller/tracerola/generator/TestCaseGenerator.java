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
        String testName = session.getThrownExceptionType() != null
                ? "test" + capitalize(session.getMethodName()) + "_throws"
                  + session.getThrownExceptionType() + "_generated"
                : "test" + capitalize(session.getMethodName()) + "_generated";

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
                    Set<String> paramUsedNames = new HashSet<>();
                    appendCompositeConstruction(sb, "    ", p.getName(), p.getType(),
                            p.getFields(), paramUsedNames);
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

            // First pass: assign generated variable names and build source → generated mapping.
            // This lets us rewrite references like "book" → "findByIdResult" everywhere.
            Map<String, String> varMapping = new LinkedHashMap<>();
            Map<TracedCall, String> callVarNames = new LinkedHashMap<>();
            for (TracedCall c : mockable) {
                if (c.hasCapturedReturnFields() || c.hasCapturedReturnListElements()) {
                    String varName = makeUniqueReturnVarName(c, usedReturnVars);
                    callVarNames.put(c, varName);
                    // Map the source-code result variable to the generated name
                    if (c.getResultVariable() != null) {
                        varMapping.putIfAbsent(c.getResultVariable(), varName);
                    }
                    // Also map backtrace expressions
                    for (String bt : c.getBacktraceExpressions()) {
                        varMapping.putIfAbsent(bt, varName);
                    }
                }
            }

            // Collect all referenced identity paths from captured fields, then
            // derive mappings for nested paths (e.g. "bookAuthor" → "findByIdResultAuthor")
            // by matching source-variable prefixes.
            Set<String> allRefs = new LinkedHashSet<>();
            for (TracedCall c : mockable) {
                collectReferences(c.getCapturedReturnFields(), allRefs);
            }
            for (String ref : allRefs) {
                if (varMapping.containsKey(ref)) continue;
                // Try to match a prefix: "bookAuthor" starts with "book" + uppercase
                for (Map.Entry<String, String> e : varMapping.entrySet()) {
                    String srcPrefix = e.getKey();
                    if (ref.length() > srcPrefix.length()
                            && ref.startsWith(srcPrefix)
                            && Character.isUpperCase(ref.charAt(srcPrefix.length()))) {
                        String suffix = ref.substring(srcPrefix.length());
                        varMapping.put(ref, e.getValue() + suffix);
                        break;
                    }
                }
            }

            // Second pass: emit code with rewritten references
            for (TracedCall c : mockable) {
                String returnValue;
                String traceComment = null;

                if (c.hasCapturedReturnListElements()) {
                    String varName = callVarNames.get(c);
                    appendListConstruction(sb, "    ", varName,
                            c.getReturnType(), c.getReturnElementType(),
                            c.getCapturedReturnListElements(), usedReturnVars);
                    returnValue = varName;
                } else if (c.hasCapturedReturnFields()) {
                    String emitType = c.getCapturedReturnRuntimeType() != null
                            ? c.getCapturedReturnRuntimeType() : c.getReturnType();
                    String varName = callVarNames.get(c);
                    appendCompositeConstruction(sb, "    ", varName, emitType,
                            c.getCapturedReturnFields(), usedReturnVars, varMapping,
                            c.getReturnConstructorParams());
                    returnValue = varName;
                } else if (c.getCapturedReturnValue() != null) {
                    returnValue = c.getCapturedReturnValue();
                    traceComment = c.getCapturedReturnValue();
                } else {
                    returnValue = c.getMockReturnPlaceholder();
                }

                // When runtime type differs from declared return type (e.g. Optional<Book> → Book),
                // the thenReturn value may need wrapping (e.g. Optional.of(...)).
                boolean needsWrapHint = c.getCapturedReturnRuntimeType() != null
                        && !c.getReturnType().equals(c.getCapturedReturnRuntimeType())
                        && c.hasCapturedReturnFields();

                // Rewrite mock argument expressions using the variable mapping
                List<String> rewrittenArgs = c.getArgExpressions().stream()
                        .map(arg -> varMapping.getOrDefault(arg, arg))
                        .toList();
                sb.append("    when(").append(c.getQualifierName()).append('.')
                  .append(c.getMethodName()).append('(')
                  .append(String.join(", ", rewrittenArgs))
                  .append(")).thenReturn(").append(returnValue).append(");");
                if (needsWrapHint) {
                    sb.append(" // TODO: may need wrapping, e.g. Optional.of(").append(returnValue).append(")");
                }
                if (traceComment != null) {
                    sb.append(" // traced ").append(traceComment);
                }
                sb.append('\n');
            }
            sb.append('\n');
        }

        // --- Execute + Assert ---
        List<String> paramNames = session.getParameters().stream()
                .map(CapturedParameter::getName).toList();
        String invocation = decapitalize(session.getClassName()) + "."
                + session.getMethodName() + "(" + String.join(", ", paramNames) + ")";

        if (session.getThrownExceptionType() != null) {
            // Unhappy path — method threw an exception
            sb.append("    // Execute & Assert — expects exception\n");
            sb.append("    assertThrows(").append(session.getThrownExceptionType())
              .append(".class, () ->\n");
            sb.append("            ").append(invocation).append(");\n");
        } else {
            boolean returnsValue = !"void".equals(session.getReturnType());

            sb.append("    // Execute\n");
            if (returnsValue) {
                sb.append("    ").append(session.getReturnType()).append(" result = ");
            } else {
                sb.append("    ");
            }
            sb.append(invocation).append(";\n\n");

            // --- Assertions ---
            sb.append("    // Assert\n");
            if (returnsValue) {
                sb.append("    assertNotNull(result);\n");

                // Captured-value assertions: prefer the most informative failure shape.
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
     * Emits a composite POJO construction block, recursively handling nested
     * objects, back-references, and list-field references.
     *
     * <pre>
     * Book findByIdResult = new Book(); // traced
     * findByIdResult.setTitle("The Hobbit");
     * Author findByIdResultAuthor = new Author(); // traced (nested)
     * findByIdResultAuthor.setName("J.R.R. Tolkien");
     * findByIdResultAuthor.getBooks().add(findByIdResult);
     * findByIdResult.setAuthor(findByIdResultAuthor);
     * </pre>
     */
    /** Overload without varMapping or constructor params — used by parameter emission. */
    private static void appendCompositeConstruction(@NotNull StringBuilder sb,
                                                     @NotNull String indent,
                                                     @NotNull String varName,
                                                     @NotNull String typeName,
                                                     @NotNull List<CapturedField> fields,
                                                     @NotNull Set<String> usedNames) {
        appendCompositeConstruction(sb, indent, varName, typeName, fields, usedNames,
                Collections.emptyMap(), Collections.emptyList());
    }

    /** Overload without constructor params — backwards compat. */
    private static void appendCompositeConstruction(@NotNull StringBuilder sb,
                                                     @NotNull String indent,
                                                     @NotNull String varName,
                                                     @NotNull String typeName,
                                                     @NotNull List<CapturedField> fields,
                                                     @NotNull Set<String> usedNames,
                                                     @NotNull Map<String, String> varMapping) {
        appendCompositeConstruction(sb, indent, varName, typeName, fields, usedNames,
                varMapping, Collections.emptyList());
    }

    private static void appendCompositeConstruction(@NotNull StringBuilder sb,
                                                     @NotNull String indent,
                                                     @NotNull String varName,
                                                     @NotNull String typeName,
                                                     @NotNull List<CapturedField> fields,
                                                     @NotNull Set<String> usedNames,
                                                     @NotNull Map<String, String> varMapping,
                                                     @NotNull List<TracedCall.ConstructorParam> ctorParams) {
        // Build a field-name → CapturedField lookup for constructor arg matching
        Map<String, CapturedField> fieldByName = new LinkedHashMap<>();
        for (CapturedField f : fields) {
            fieldByName.put(f.fieldName(), f);
        }

        // Determine constructor arguments
        Set<String> usedInConstructor = new LinkedHashSet<>();
        if (!ctorParams.isEmpty()) {
            // Match constructor params to captured field values by name
            List<String> ctorArgs = new ArrayList<>();
            for (TracedCall.ConstructorParam p : ctorParams) {
                CapturedField match = fieldByName.get(p.name());
                if (match != null && match.value() != null) {
                    ctorArgs.add(match.value());
                    usedInConstructor.add(p.name());
                } else {
                    ctorArgs.add("null /* " + p.name() + " */");
                }
            }
            sb.append(indent).append(typeName).append(' ').append(varName)
              .append(" = new ").append(typeName).append("(")
              .append(String.join(", ", ctorArgs))
              .append("); // traced\n");
        } else {
            sb.append(indent).append(typeName).append(' ').append(varName)
              .append(" = new ").append(typeName).append("(); // traced\n");
        }

        // First pass: emit nested objects (declared before being used in setters)
        // Also collect deferred setter calls for nested/reference fields
        List<String> deferredSetters = new ArrayList<>();

        for (CapturedField f : fields) {
            // Skip fields already passed via constructor
            if (usedInConstructor.contains(f.fieldName())) continue;

            if (f.isNested()) {
                String nestedVar = varName + capitalize(f.fieldName());
                int dedup = 2;
                while (!usedNames.add(nestedVar)) {
                    nestedVar = varName + capitalize(f.fieldName()) + (dedup++);
                }
                appendCompositeConstruction(sb, indent, nestedVar,
                        f.nestedRuntimeType(), f.nestedFields(), usedNames, varMapping,
                        f.nestedConstructorParams());
                if (f.hasSetter()) {
                    deferredSetters.add(indent + varName + "." + f.setterName() + "(" + nestedVar + ");\n");
                } else {
                    deferredSetters.add(indent + "// " + varName + "." + f.setterName() + "(" + nestedVar + "); // no public setter\n");
                }
            } else if (f.isReference()) {
                String ref = varMapping.getOrDefault(f.referenceTo(), f.referenceTo());
                if (f.hasSetter()) {
                    deferredSetters.add(indent + varName + "." + f.setterName() + "(" + ref + ");\n");
                } else {
                    deferredSetters.add(indent + "// " + varName + "." + f.setterName() + "(" + ref + "); // no public setter\n");
                }
            } else if (f.hasListReferences()) {
                for (String rawRef : f.listElementReferences()) {
                    String ref = varMapping.getOrDefault(rawRef, rawRef);
                    deferredSetters.add(indent + varName + "." + f.listGetterName() + "().add(" + ref + ");\n");
                }
            } else if (f.value() != null) {
                if (f.hasSetter()) {
                    sb.append(indent).append(varName).append('.')
                      .append(f.setterName()).append('(').append(f.value()).append(");\n");
                } else {
                    sb.append(indent).append("// ").append(varName).append('.')
                      .append(f.setterName()).append('(').append(f.value()).append("); // no public setter\n");
                }
            }
        }

        // Second pass: emit deferred setters
        for (String setter : deferredSetters) {
            sb.append(setter);
        }
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
                // Prefer the runtime type when subclass dispatch picked up a more
                // specific class than the declared element type — that's the only
                // way `new Cat()` shows up inside a `List<Animal>` etc.
                String thisElemType = elem.runtimeType() != null ? elem.runtimeType() : elemTypeForNew;
                String elemVar = makeUniqueElementVarName(varName, i, usedNames);

                // Build field-name lookup for constructor matching
                Map<String, CapturedField> elemFieldByName = new LinkedHashMap<>();
                for (CapturedField f : elem.fields()) elemFieldByName.put(f.fieldName(), f);

                Set<String> elemCtorUsed = new LinkedHashSet<>();
                List<TracedCall.ConstructorParam> elemCtorParams = elem.constructorParams();
                if (!elemCtorParams.isEmpty()) {
                    List<String> ctorArgs = new ArrayList<>();
                    for (TracedCall.ConstructorParam p : elemCtorParams) {
                        CapturedField match = elemFieldByName.get(p.name());
                        if (match != null && match.value() != null) {
                            ctorArgs.add(match.value());
                            elemCtorUsed.add(p.name());
                        } else {
                            ctorArgs.add("null /* " + p.name() + " */");
                        }
                    }
                    sb.append(indent).append(thisElemType).append(' ').append(elemVar)
                      .append(" = new ").append(thisElemType).append("(")
                      .append(String.join(", ", ctorArgs)).append(");\n");
                } else {
                    sb.append(indent).append(thisElemType).append(' ').append(elemVar)
                      .append(" = new ").append(thisElemType).append("();\n");
                }
                for (CapturedField f : elem.fields()) {
                    if (elemCtorUsed.contains(f.fieldName())) continue;
                    if (f.value() != null) {
                        if (f.hasSetter()) {
                            sb.append(indent).append(elemVar).append('.')
                              .append(f.setterName()).append('(').append(f.value()).append(");\n");
                        } else {
                            sb.append(indent).append("// ").append(elemVar).append('.')
                              .append(f.setterName()).append('(').append(f.value()).append("); // no public setter\n");
                        }
                    }
                }
                sb.append(indent).append(varName).append(".add(").append(elemVar).append(");\n");
            } else if (elem.isUnknown()) {
                // Heterogeneous list — declared element type didn't have usable
                // field signatures (e.g. List<Object>). Emit a placeholder add
                // with the runtime type so the user knows what was lost.
                sb.append(indent).append(varName).append(".add(null); // TODO element ").append(i)
                  .append(": runtime type was ").append(elem.runtimeType())
                  .append(" — declared element type couldn't decompose it\n");
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
            } else if (elem.isUnknown()) {
                sb.append(indent).append("// TODO assert ").append(accessor)
                  .append(": runtime type was ").append(elem.runtimeType()).append('\n');
            }
        }
    }

    /** Recursively collects all reference and list-reference identity paths from fields. */
    private static void collectReferences(@Nullable List<CapturedField> fields, @NotNull Set<String> out) {
        if (fields == null) return;
        for (CapturedField f : fields) {
            if (f.isReference()) {
                out.add(f.referenceTo());
            } else if (f.hasListReferences()) {
                out.addAll(f.listElementReferences());
            } else if (f.isNested()) {
                collectReferences(f.nestedFields(), out);
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
