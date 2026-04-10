package com.pixeldweller.tracerola.debug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.pixeldweller.tracerola.model.CapturedListElement;
import com.pixeldweller.tracerola.model.CapturedParameter.CapturedField;
import com.pixeldweller.tracerola.model.ReturnAnalysis;
import com.pixeldweller.tracerola.model.TracedCall;
import com.pixeldweller.tracerola.model.TracedCall.ReturnFieldSignature;
import com.pixeldweller.tracerola.service.TracerolaStateService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Automatically steps over (F8) each line within a method, capturing the
 * return value of each traced dependency call after it executes.
 *
 * <h3>Usage</h3>
 * <pre>
 * MethodStepper stepper = new MethodStepper(session, calls, startLine, endLine, returnAnalysis, onDone);
 * stepper.start();
 * // ... onDone is called when the method exits or stepping finishes
 * </pre>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Registers an {@link XDebugSessionListener} on the session.</li>
 *   <li>Calls {@code session.stepOver(false)} to execute one line.</li>
 *   <li>When {@code sessionPaused()} fires, checks if we just passed a
 *       line containing a dependency call with a known result variable.</li>
 *   <li>If so, evaluates the result variable via the debugger and stores
 *       the value on the {@link TracedCall}.</li>
 *   <li>Repeats until the current position leaves the method's line range.</li>
 * </ol>
 */
public final class MethodStepper {

    /** Pause after each step before reading XValues — lets the variables view finish populating. */
    private static final long SETTLE_MS = 1000;

    /** Extra wait when an evaluation comes back as IntelliJ's "Collecting data…" placeholder. */
    private static final long COLLECTING_RETRY_MS = 1500;

    /** Hard cap on list-element captures so a {@code findAll()} of 100k rows doesn't hang the stepper. */
    private static final int MAX_LIST_ELEMENTS = 50;

    /**
     * Substrings IntelliJ inserts while a value is still being computed. Both the
     * unicode-ellipsis form and the ASCII fallback are checked because both have
     * been observed in the wild depending on font/locale.
     */
    private static final String[] COLLECTING_PLACEHOLDERS = { "Collecting data\u2026", "Collecting data..." };

    private final XDebugSession session;
    private final List<TracedCall> calls;
    private final int methodStartLine; // 0-based inclusive
    private final int methodEndLine;   // 0-based inclusive
    private final ReturnAnalysis returnAnalysis;
    private final TracerolaStateService stateService;
    private final Runnable onComplete;

    private int previousLine;

    /** Exception type when the method exits via a {@code throw} statement (e.g. "IllegalStateException"). */
    private String thrownExceptionType;

    /** Captured method return — primitive/string/enum literal, or null. */
    private String capturedMethodReturnValue;

    /** Captured method return — per-field decomposition for composite returns. */
    private List<CapturedField> capturedMethodReturnFields = Collections.emptyList();

    /** Runtime type of the method return when resolved via runtime dispatch. */
    private String capturedMethodReturnRuntimeType;

    /** Captured method return — element list for {@code List<E>} returns. */
    private List<CapturedListElement> capturedMethodReturnListElements = Collections.emptyList();

    /**
     * Per-trace cache of runtime FQN → field signatures, used by the list-element
     * subclass dispatch path. Populated lazily the first time we see each runtime
     * type so the PSI {@code findClass} round-trip happens at most once per class
     * regardless of how many list elements share that type. Empty list means
     * "lookup attempted but no usable PsiClass found" — sentinel to avoid retries.
     */
    private final Map<String, List<ReturnFieldSignature>> runtimeFieldSignatureCache = new HashMap<>();

    /** Max nesting depth for recursive POJO capture (Book → Author → stop). */
    private static final int MAX_NESTING_DEPTH = 2;

    /**
     * JDK types whose internals should never be decomposed. For these types,
     * capture via {@code .toString()} and format as a type-aware literal instead
     * of recursing into private fields like {@code intVal}, {@code coder}, etc.
     */
    private static final Set<String> OPAQUE_TYPES = Set.of(
            // Boxed primitives
            "java.lang.Long", "java.lang.Integer", "java.lang.Short", "java.lang.Byte",
            "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
            "java.lang.String",
            // Math
            "java.math.BigDecimal", "java.math.BigInteger",
            // Date/Time
            "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
            "java.time.ZonedDateTime", "java.time.Instant", "java.time.Duration",
            "java.time.Period", "java.time.OffsetDateTime",
            // Other common value types
            "java.util.UUID", "java.util.Date", "java.sql.Timestamp",
            "java.net.URL", "java.net.URI"
    );

    /**
     * Identity registry: maps {@code System.identityHashCode(obj)} to the variable
     * path assigned to that object (e.g. "findByIdResult", "findByIdResultAuthor").
     * Used to detect cycles in bidirectional JPA relationships.
     */
    private final Map<String, String> objectIdentityRegistry = new HashMap<>();

    private int stepCount;

    public MethodStepper(@NotNull XDebugSession session,
                         @NotNull List<TracedCall> calls,
                         int methodStartLine,
                         int methodEndLine,
                         @NotNull ReturnAnalysis returnAnalysis,
                         @NotNull TracerolaStateService stateService,
                         @NotNull Runnable onComplete) {
        this.session = session;
        this.calls = calls;
        this.methodStartLine = methodStartLine;
        this.methodEndLine = methodEndLine;
        this.returnAnalysis = returnAnalysis;
        this.stateService = stateService;
        this.onComplete = onComplete;
        this.previousLine = currentLine();
    }

    /** Exception type if the method exited via {@code throw}, or {@code null}. */
    @Nullable
    public String getThrownExceptionType() {
        return thrownExceptionType;
    }

    /** Literal captured at the executed return statement, or {@code null}. */
    @Nullable
    public String getCapturedMethodReturnValue() {
        return capturedMethodReturnValue;
    }

    /** Per-field decomposition captured at the executed return statement. Empty if none. */
    @NotNull
    public List<CapturedField> getCapturedMethodReturnFields() {
        return capturedMethodReturnFields;
    }

    /** List elements captured at the executed return statement. Empty if none. */
    @NotNull
    public List<CapturedListElement> getCapturedMethodReturnListElements() {
        return capturedMethodReturnListElements;
    }

    /**
     * Starts the automatic stepping loop.
     */
    public void start() {
        session.addSessionListener(new StepListener());
        session.stepOver(false);
    }

    // -------------------------------------------------------------------------

    private final class StepListener implements XDebugSessionListener {

        @Override
        public void sessionPaused() {
            int currentLine = currentLine();

            // Check if we've left the method (stepped out or method returned)
            if (currentLine < 0 || currentLine < methodStartLine || currentLine > methodEndLine) {
                finish();
                return;
            }

            // Check if the source file changed (stepped into a different class)
            XSourcePosition pos = session.getCurrentPosition();
            if (pos == null) {
                finish();
                return;
            }

            // Continue stepping. Capture must run on a pooled thread (sleep + JDI eval),
            // and the actual stepOver() must be re-dispatched onto the EDT afterwards.
            ApplicationManager.getApplication().invokeLater(() -> {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    stepCount++;
                    stateService.setTracingStatus("Stepping… (line " + (currentLine + 1) + ")");

                    // Give the XValueTree a moment to settle before evaluating result vars.
                    sleepQuietly(SETTLE_MS);

                    // The -1 shift on both bounds is intentional: after stepOver(), IntelliJ
                    // pauses on the *next* statement to be executed, so a call on line N is
                    // only "done" once we've moved off it. Shifting the window back by one
                    // makes the inclusion test fire after the call has actually executed.
                    stateService.setTracingStatus("Capturing return values… (line " + (currentLine + 1) + ")");
                    captureReturnValues(previousLine - 1, currentLine - 1);

                    // Method-return capture uses the *unshifted* current line, because
                    // we want to read the return expression while paused AT the return
                    // statement, before the next stepOver leaves the method.
                    captureMethodReturnIfApplicable(currentLine);

                    // Throw detection — check if we're paused at a throw statement.
                    // The next stepOver will leave the method via the exception.
                    detectThrowIfApplicable(currentLine);

                    previousLine = currentLine;

                    stateService.setTracingStatus("Stepping… (step " + stepCount + ")");
                    ApplicationManager.getApplication().invokeLater(() -> session.stepOver(false));
                });
            });
        }

        @Override
        public void sessionResumed() {
            // stepping triggers resume → pause cycle, nothing to do
        }

        @Override
        public void sessionStopped() {
            finish();
        }

        private void finish() {
            session.removeSessionListener(this);
            // If any traced call was in a return position (e.g. return repo.save(order)),
            // propagate the method-return capture to that call's mock return value.
            feedReturnPositionCalls();
            onComplete.run();
        }
    }

    /**
     * For each traced call whose line number falls in the just-stepped window,
     * evaluate the {@code resultVariable} if one exists.
     *
     * <p>Four capture strategies, chosen by the return type metadata that
     * {@link MethodTracer} pre-computed:
     * <ol>
     *   <li>Primitives / String → single expression eval + formatting.</li>
     *   <li>Enums → {@code resultVar.name()} to get a clean constant name,
     *       then emit as {@code Type.NAME}.</li>
     *   <li>POJOs → evaluate each field as {@code resultVar.fieldName} and
     *       store a list of {@link CapturedField}s for the generator to turn
     *       into a {@code new + setters} block.</li>
     *   <li>Lists → read {@code resultVar.size()} then recursively capture
     *       each {@code resultVar.get(i)} as a literal/POJO element.</li>
     * </ol>
     *
     * <p>All evaluations go through {@link #evaluateWithRetry} so that the
     * "Collecting data…" placeholder IntelliJ returns when the value isn't
     * ready yet automatically retries once after a short wait.
     */
    private void captureReturnValues(int previousLine, int currentLine) {
        XStackFrame frame = session.getCurrentStackFrame();
        if (frame == null) return;
        XDebuggerEvaluator evaluator = frame.getEvaluator();
        if (evaluator == null) return;

        for (TracedCall call : calls) {
            int callLine = call.getLineNumber();
            if (callLine < 0) continue;

            // Was this call just stepped over?
            if (callLine <= previousLine || callLine > currentLine) continue;
            if ("void".equals(call.getReturnType())) continue;

            CaptureSpec spec = CaptureSpec.from(call);

            String resultVar = call.getResultVariable();
            if (resultVar != null) {
                // Register the object identity before capturing, so nested fields
                // can reference back to it (e.g. author.books[0] → this book).
                registerObjectIdentity(evaluator, resultVar, resultVar);
                applyCapture(call, captureExpression(evaluator, resultVar, spec));
                continue;
            }

            // No local variable held the return value. Try the backtrace expressions
            // (e.g. target.getX() / target.isX() inferred from a setter pattern).
            for (String backtraceExpr : call.getBacktraceExpressions()) {
                registerObjectIdentity(evaluator, backtraceExpr, backtraceExpr);
                CaptureResult result = captureExpression(evaluator, backtraceExpr, spec);
                if (result != null) {
                    applyCapture(call, result);
                    break;
                }
            }
        }
    }

    /**
     * Captures the value of a return expression at a {@code return} statement
     * line. Uses the unshifted current line (we're paused AT the return, before
     * stepping out). Only fires when the tracer recorded a side-effect-free
     * return expression for this line.
     */
    private void captureMethodReturnIfApplicable(int currentLine) {
        if (capturedMethodReturnValue != null
                || !capturedMethodReturnFields.isEmpty()
                || !capturedMethodReturnListElements.isEmpty()) {
            return; // already captured at an earlier return statement
        }
        String returnExpr = returnAnalysis.returnPoints().get(currentLine);
        if (returnExpr == null) return;

        stateService.setTracingStatus("Capturing method return value…");

        XStackFrame frame = session.getCurrentStackFrame();
        if (frame == null) return;
        XDebuggerEvaluator evaluator = frame.getEvaluator();
        if (evaluator == null) return;

        CaptureResult result = captureExpression(evaluator, returnExpr, CaptureSpec.from(returnAnalysis));
        if (result == null) return;

        if (result.literal != null) {
            capturedMethodReturnValue = result.literal;
        } else if (!result.fields.isEmpty()) {
            capturedMethodReturnFields = result.fields;
            capturedMethodReturnRuntimeType = result.runtimeType;
        } else if (!result.listElements.isEmpty()) {
            capturedMethodReturnListElements = result.listElements;
        }
    }

    /**
     * Checks if the current line is a {@code throw} statement. If so, records
     * the exception type. The stepper will leave the method on the next stepOver.
     */
    private void detectThrowIfApplicable(int currentLine) {
        if (thrownExceptionType != null) return; // already detected
        String exType = returnAnalysis.throwPoints().get(currentLine);
        if (exType != null) {
            thrownExceptionType = exType;
            stateService.setTracingStatus("Exception detected: " + exType);
        }
    }

    /**
     * Capture ladder shared by per-call resultVar capture, backtrace capture,
     * and method-return capture:
     * <ol>
     *   <li>List — when {@code spec.isList()}, read {@code expression.size()}
     *       then recurse on {@code expression.get(i)} for each element. Tried
     *       first because list references would fail the cheap eval anyway.</li>
     *   <li>Cheap eval — wins for primitives, boxed types, and String.</li>
     *   <li>Enum — {@code expression.name()} formatted as {@code Type.NAME}.</li>
     *   <li>POJO — one eval per pre-computed field signature.</li>
     * </ol>
     * Returns {@code null} when nothing was captured (so a fallback can be tried).
     *
     * <p>Instance method (not static) because the list path needs access to the
     * runtime field-signature cache for subclass dispatch.
     */
    @Nullable
    private CaptureResult captureExpression(@NotNull XDebuggerEvaluator evaluator,
                                            @NotNull String expression,
                                            @NotNull CaptureSpec spec) {
        if (spec.isList()) {
            List<CapturedListElement> elements = captureListElements(evaluator, expression, spec);
            return elements != null ? CaptureResult.list(elements) : null;
        }

        String simple = evaluateWithRetry(evaluator, expression);
        if (simple != null) return CaptureResult.literal(simple);

        if (spec.isEnum()) {
            String enumLiteral = evaluateEnumLiteralWithRetry(evaluator, expression, spec.declaredType());
            return enumLiteral != null ? CaptureResult.literal(enumLiteral) : null;
        }

        // Check if this is an opaque JDK type before trying POJO decomposition.
        // Prevents decomposing BigDecimal.intVal, Long.value, etc.
        String rawFqn = evaluateRuntimeFqn(evaluator, expression);
        String runtimeFqn = rawFqn != null ? deproxyFqn(rawFqn) : null;
        if (runtimeFqn != null && OPAQUE_TYPES.contains(runtimeFqn)) {
            String opaqueVal = captureOpaqueType(evaluator, expression, runtimeFqn);
            return opaqueVal != null ? CaptureResult.literal(opaqueVal) : null;
        }

        // Try declared-type field signatures first.
        CaptureResult pojoResult = capturePojoFields(evaluator, expression, spec.fieldSignatures());
        if (pojoResult != null) {
            // If the runtime type differs from the declared type (e.g. declared
            // as generic "S" but runtime is "BookOrder"), attach the runtime name
            // so the generator can emit the correct type in the test.
            if (runtimeFqn != null) {
                String runtimeSimple = simpleNameOf(runtimeFqn);
                String declaredSimple = spec.declaredType() != null
                        ? simpleNameOf(spec.declaredType()) : null;
                if (!runtimeSimple.equals(declaredSimple)) {
                    return CaptureResult.compositeRuntime(pojoResult.fields, runtimeSimple);
                }
            }
            return pojoResult;
        }

        // Fallback: runtime-class dispatch. The declared type may be wrong
        // (e.g. Optional when the variable holds Book after .orElseThrow()),
        // generic (S extends T), or simply have no field signatures.
        if (runtimeFqn != null) {
            List<ReturnFieldSignature> runtimeSigs = resolveRuntimeFieldSignatures(runtimeFqn);
            if (!runtimeSigs.isEmpty()) {
                CaptureResult runtimeResult = capturePojoFields(evaluator, expression, runtimeSigs);
                if (runtimeResult != null && !runtimeResult.fields.isEmpty()) {
                    return CaptureResult.compositeRuntime(runtimeResult.fields, simpleNameOf(runtimeFqn));
                }
            }
        }
        return null;
    }

    /**
     * Captures the elements of a {@code List<E>} expression by reading
     * {@code expression.size()} and then dispatching each {@code expression.get(i)}
     * through {@link #captureSingleListElement}. Returns {@code null} when the
     * size couldn't be read (i.e. the expression didn't resolve to a list);
     * returns an empty list when the runtime list itself was empty.
     */
    @Nullable
    private List<CapturedListElement> captureListElements(@NotNull XDebuggerEvaluator evaluator,
                                                          @NotNull String expression,
                                                          @NotNull CaptureSpec spec) {
        String sizeStr = evaluateWithRetry(evaluator, expression + ".size()");
        if (sizeStr == null) return null;

        int size;
        try {
            size = Integer.parseInt(sizeStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (size == 0) return Collections.emptyList();

        int captureCount = Math.min(size, MAX_LIST_ELEMENTS);
        CaptureSpec elementSpec = spec.asElement();
        List<CapturedListElement> elements = new ArrayList<>(captureCount);

        for (int i = 0; i < captureCount; i++) {
            stateService.setTracingStatus("Capturing list element " + (i + 1) + "/" + captureCount + "…");
            CapturedListElement captured = captureSingleListElement(
                    evaluator, expression + ".get(" + i + ")", elementSpec);
            if (captured != null) elements.add(captured);
        }
        return elements;
    }

    /**
     * Captures a single list element with full subclass dispatch:
     * <ol>
     *   <li>Cheap eval — primitives, String, enums; the {@link ParameterEvaluator}
     *       formatting handles all three so we don't need any class info.</li>
     *   <li>Runtime FQN lookup — read {@code element.getClass().getName()} so we
     *       know what the element <em>actually</em> is, regardless of how the
     *       list was declared.</li>
     *   <li>PSI dispatch — resolve that FQN to a {@link PsiClass} via
     *       {@link JavaPsiFacade} (in a read action) and collect its instance
     *       fields. Cached per FQN so a 50-element {@code List<Animal>} of
     *       Cats only does the lookup once.</li>
     *   <li>Runtime-class capture — re-run per-field capture with the runtime
     *       signatures. The result carries the runtime type so the generator
     *       can emit {@code new Cat()} instead of {@code new Animal()}.</li>
     *   <li>Declared-type fallback — only used when PSI couldn't resolve the
     *       runtime FQN (e.g. JDK or third-party classes outside any source root).
     *       Captures whatever fields the declared element type knows about.</li>
     *   <li>Unknown marker — last resort, surfaces a {@code TODO} comment with
     *       the runtime simple name so the user sees what was lost.</li>
     * </ol>
     */
    @Nullable
    private CapturedListElement captureSingleListElement(@NotNull XDebuggerEvaluator evaluator,
                                                          @NotNull String elementExpr,
                                                          @NotNull CaptureSpec elementSpec) {
        // 1. Cheap eval — wins for primitives, String, enum literals.
        String simple = evaluateWithRetry(evaluator, elementExpr);
        if (simple != null) return CapturedListElement.ofLiteral(simple);

        // 2. Runtime FQN — needed for both subclass dispatch and the fallback marker.
        String rawElemFqn = evaluateRuntimeFqn(evaluator, elementExpr);
        String runtimeFqn = rawElemFqn != null ? deproxyFqn(rawElemFqn) : null;
        String runtimeSimple = runtimeFqn != null ? simpleNameOf(runtimeFqn) : null;

        // 3 + 4. Try the runtime class's field signatures (cached per FQN).
        if (runtimeFqn != null) {
            stateService.setTracingStatus("Resolving " + simpleNameOf(runtimeFqn) + "…");
            List<ReturnFieldSignature> runtimeSigs = resolveRuntimeFieldSignatures(runtimeFqn);
            if (!runtimeSigs.isEmpty()) {
                boolean proxy = rawElemFqn != null && isProxyFqn(rawElemFqn);
                CaptureResult res = capturePojoFields(evaluator, elementExpr, runtimeSigs, proxy);
                if (res != null && !res.fields.isEmpty()) {
                    return CapturedListElement.ofRuntimeFields(runtimeSimple, res.fields);
                }
            }
        }

        // 5. Declared-type fallback — useful when the runtime class isn't visible
        //    via PSI (third-party / JDK types) but the declared type has fields.
        if (!elementSpec.fieldSignatures().isEmpty()) {
            CaptureResult res = capturePojoFields(evaluator, elementExpr, elementSpec.fieldSignatures());
            if (res != null && !res.fields.isEmpty()) {
                return CapturedListElement.ofFields(res.fields);
            }
        }

        // 6. Unknown marker — at least surface the runtime type if we have it.
        return runtimeSimple != null ? CapturedListElement.ofUnknown(runtimeSimple) : null;
    }

    /**
     * Evaluates each field in {@code sigs} as {@code expression.fieldName} and
     * bundles them into a {@link CaptureResult}. Returns {@code null} when no
     * field came back with a value (so the caller knows to try a fallback).
     *
     * <p>Delegates to {@link #capturePojoFieldsRecursive} with depth 0.
     */
    @Nullable
    private CaptureResult capturePojoFields(@NotNull XDebuggerEvaluator evaluator,
                                            @NotNull String expression,
                                            @NotNull List<ReturnFieldSignature> sigs) {
        return capturePojoFieldsRecursive(evaluator, expression, sigs, 0, expression, false);
    }

    /** Overload that allows caller to signal the expression is a proxy object. */
    @Nullable
    private CaptureResult capturePojoFields(@NotNull XDebuggerEvaluator evaluator,
                                            @NotNull String expression,
                                            @NotNull List<ReturnFieldSignature> sigs,
                                            boolean useGetters) {
        return capturePojoFieldsRecursive(evaluator, expression, sigs, 0, expression, useGetters);
    }

    /**
     * Recursive POJO field capture with identity-based cycle detection.
     *
     * <p>When a field evaluates to an object reference (null from formatForCode),
     * this method:
     * <ol>
     *   <li>Reads {@code System.identityHashCode(expr.field)} for the identity.</li>
     *   <li>If the identity is already in {@link #objectIdentityRegistry} →
     *       emits a {@link CapturedField#ofReference} pointing to the existing variable.</li>
     *   <li>If not seen and {@code depth < MAX_NESTING_DEPTH} → resolves the
     *       runtime class, recurses into its fields, stores as
     *       {@link CapturedField#ofNested}.</li>
     *   <li>For list-typed fields → reads element identities and emits
     *       {@link CapturedField#ofListReferences} for known objects.</li>
     * </ol>
     *
     * @param varPath the variable path for the object being captured (e.g. "findByIdResult"),
     *                used to build nested variable names. Null at the top level (the caller
     *                registers the identity before calling).
     */
    @Nullable
    private CaptureResult capturePojoFieldsRecursive(@NotNull XDebuggerEvaluator evaluator,
                                                      @NotNull String expression,
                                                      @NotNull List<ReturnFieldSignature> sigs,
                                                      int depth,
                                                      @Nullable String varPath,
                                                      boolean useGetters) {
        if (sigs.isEmpty()) return null;
        List<CapturedField> captured = new ArrayList<>(sigs.size());
        boolean anyValue = false;

        for (ReturnFieldSignature sig : sigs) {
            // For Hibernate/CGLIB proxies, direct field access returns null
            // because the proxy's inherited fields are uninitialized — use
            // getter-based evaluation instead (the proxy intercepts getters).
            String fieldExpr = useGetters
                    ? expression + "." + sig.getterName() + "()"
                    : expression + "." + sig.fieldName();
            String val = evaluateWithRetry(evaluator, fieldExpr);

            if (val != null) {
                // Literal field — primitives, String, enum, etc.
                anyValue = true;
                captured.add(new CapturedField(sig.fieldName(), sig.fieldType(), val));
                continue;
            }

            // Field evaluated to null from formatForCode — it's an object reference.
            // First check: is this an opaque JDK type whose internals we should NOT
            // decompose? (e.g. Long, BigDecimal, LocalDateTime, String)
            // If so, try .toString() and format as a type-aware literal.
            String rawFieldFqn = evaluateRuntimeFqn(evaluator, fieldExpr);
            String runtimeFqn = rawFieldFqn != null ? deproxyFqn(rawFieldFqn) : null;
            if (runtimeFqn != null && OPAQUE_TYPES.contains(runtimeFqn)) {
                String opaqueVal = captureOpaqueType(evaluator, fieldExpr, runtimeFqn);
                if (opaqueVal != null) {
                    anyValue = true;
                    captured.add(new CapturedField(sig.fieldName(), sig.fieldType(), opaqueVal));
                } else {
                    captured.add(new CapturedField(sig.fieldName(), sig.fieldType(), null));
                }
                continue;
            }

            // Try identity-based recursion if we haven't hit the depth limit.
            if (depth >= MAX_NESTING_DEPTH) {
                captured.add(new CapturedField(sig.fieldName(), sig.fieldType(), null));
                continue;
            }

            // Check if this field is a list (try .size())
            String sizeStr = evaluateWithRetry(evaluator, fieldExpr + ".size()");
            if (sizeStr != null) {
                // It's a list — check element identities for back-references
                CapturedField listField = captureListFieldReferences(evaluator, fieldExpr, sig, sizeStr);
                if (listField != null) {
                    anyValue = true;
                    captured.add(listField);
                } else {
                    captured.add(new CapturedField(sig.fieldName(), sig.fieldType(), null));
                }
                continue;
            }

            // Scalar object field — check identity
            String identity = evaluateIdentity(evaluator, fieldExpr);
            if (identity != null) {
                String existingVar = objectIdentityRegistry.get(identity);
                if (existingVar != null) {
                    // Back-reference to an already-captured object
                    anyValue = true;
                    captured.add(CapturedField.ofReference(sig.fieldName(), sig.fieldType(), existingVar));
                    continue;
                }

                // New object — resolve runtime class and recurse
                boolean fieldIsProxy = rawFieldFqn != null && isProxyFqn(rawFieldFqn);
                if (runtimeFqn != null) {
                    String runtimeSimple = simpleNameOf(runtimeFqn);
                    List<ReturnFieldSignature> nestedSigs = resolveRuntimeFieldSignatures(runtimeFqn);
                    if (!nestedSigs.isEmpty()) {
                        // Build the nested variable name
                        String nestedVarPath = (varPath != null ? varPath : "obj")
                                + Character.toUpperCase(sig.fieldName().charAt(0))
                                + sig.fieldName().substring(1);
                        // Register identity BEFORE recursing (so children can reference it)
                        objectIdentityRegistry.put(identity, nestedVarPath);

                        CaptureResult nested = capturePojoFieldsRecursive(
                                evaluator, fieldExpr, nestedSigs, depth + 1, nestedVarPath, fieldIsProxy);
                        if (nested != null && !nested.fields.isEmpty()) {
                            anyValue = true;
                            captured.add(CapturedField.ofNested(
                                    sig.fieldName(), sig.fieldType(), runtimeSimple, nested.fields));
                            continue;
                        }
                    }
                }
            }

            // Fallback — couldn't recurse
            captured.add(new CapturedField(sig.fieldName(), sig.fieldType(), null));
        }
        return anyValue ? CaptureResult.composite(captured) : null;
    }

    /**
     * For a list-typed field, reads element identities and returns a
     * {@link CapturedField#ofListReferences} for any elements that are
     * already-known objects (back-references). Returns {@code null} if
     * no elements are known.
     */
    @Nullable
    private CapturedField captureListFieldReferences(@NotNull XDebuggerEvaluator evaluator,
                                                      @NotNull String listExpr,
                                                      @NotNull ReturnFieldSignature sig,
                                                      @NotNull String sizeStr) {
        int size;
        try {
            size = Integer.parseInt(sizeStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (size == 0) return null;

        int checkCount = Math.min(size, MAX_LIST_ELEMENTS);
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < checkCount; i++) {
            String elemIdentity = evaluateIdentity(evaluator, listExpr + ".get(" + i + ")");
            if (elemIdentity != null) {
                String existingVar = objectIdentityRegistry.get(elemIdentity);
                if (existingVar != null) {
                    refs.add(existingVar);
                }
            }
        }
        return refs.isEmpty() ? null : CapturedField.ofListReferences(sig.fieldName(), sig.fieldType(), refs);
    }

    /**
     * Captures a JDK value type via {@code .toString()} and formats it as a
     * Java-source literal appropriate for the type. Returns {@code null} on failure.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code java.lang.Long} → {@code 3L}</li>
     *   <li>{@code java.math.BigDecimal} → {@code new BigDecimal("9.99")}</li>
     *   <li>{@code java.time.LocalDateTime} → {@code LocalDateTime.parse("2026-04-09T19:58:03")}</li>
     *   <li>{@code java.lang.String} → {@code "orwell@abc.com"}</li>
     * </ul>
     */
    @Nullable
    private static String captureOpaqueType(@NotNull XDebuggerEvaluator evaluator,
                                             @NotNull String expression,
                                             @NotNull String runtimeFqn) {
        // For boxed primitives, prefer the unboxing expression for a clean literal
        String unboxed = switch (runtimeFqn) {
            case "java.lang.Long"      -> evaluateWithRetry(evaluator, expression + ".longValue()");
            case "java.lang.Integer"   -> evaluateWithRetry(evaluator, expression + ".intValue()");
            case "java.lang.Short"     -> evaluateWithRetry(evaluator, expression + ".shortValue()");
            case "java.lang.Byte"      -> evaluateWithRetry(evaluator, expression + ".byteValue()");
            case "java.lang.Float"     -> evaluateWithRetry(evaluator, expression + ".floatValue()");
            case "java.lang.Double"    -> evaluateWithRetry(evaluator, expression + ".doubleValue()");
            case "java.lang.Boolean"   -> evaluateWithRetry(evaluator, expression + ".booleanValue()");
            case "java.lang.Character" -> evaluateWithRetry(evaluator, expression + ".charValue()");
            default -> null;
        };
        if (unboxed != null) return unboxed;

        // For String, evaluate .toString() which gives us the clean string content
        if ("java.lang.String".equals(runtimeFqn)) {
            String s = evaluateWithRetry(evaluator, expression + ".toString()");
            return s; // evaluateWithRetry already formats strings with quotes
        }

        // For everything else, use .toString() and wrap with the appropriate factory
        String toStr = evaluateWithRetry(evaluator, expression + ".toString()");
        if (toStr == null) return null;

        // Strip surrounding quotes if present — we'll re-wrap
        String raw = toStr;
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }

        return switch (runtimeFqn) {
            case "java.math.BigDecimal"  -> "new BigDecimal(\"" + raw + "\")";
            case "java.math.BigInteger"  -> "new BigInteger(\"" + raw + "\")";
            case "java.time.LocalDate"   -> "LocalDate.parse(\"" + raw + "\")";
            case "java.time.LocalTime"   -> "LocalTime.parse(\"" + raw + "\")";
            case "java.time.LocalDateTime" -> "LocalDateTime.parse(\"" + raw + "\")";
            case "java.time.ZonedDateTime" -> "ZonedDateTime.parse(\"" + raw + "\")";
            case "java.time.OffsetDateTime" -> "OffsetDateTime.parse(\"" + raw + "\")";
            case "java.time.Instant"     -> "Instant.parse(\"" + raw + "\")";
            case "java.time.Duration"    -> "Duration.parse(\"" + raw + "\")";
            case "java.time.Period"      -> "Period.parse(\"" + raw + "\")";
            case "java.util.UUID"        -> "UUID.fromString(\"" + raw + "\")";
            default                      -> "\"" + raw + "\" /* " + simpleNameOf(runtimeFqn) + " */";
        };
    }

    /**
     * Evaluates {@code System.identityHashCode(expression)} to get a stable
     * identity for cycle detection. Returns the string form of the hash code,
     * or {@code null} on failure.
     */
    @Nullable
    private static String evaluateIdentity(@NotNull XDebuggerEvaluator evaluator,
                                            @NotNull String expression) {
        return evaluateWithRetry(evaluator, "System.identityHashCode(" + expression + ")");
    }

    /**
     * Registers an object's identity in the registry so nested captures can
     * detect back-references. No-op if the identity can't be evaluated.
     */
    private void registerObjectIdentity(@NotNull XDebuggerEvaluator evaluator,
                                         @NotNull String expression,
                                         @NotNull String varPath) {
        String identity = evaluateIdentity(evaluator, expression);
        if (identity != null) {
            objectIdentityRegistry.putIfAbsent(identity, varPath);
        }
    }

    /**
     * Looks up the field signatures of a runtime FQN via PSI, caching the result
     * for the lifetime of this stepper. The PSI lookup happens inside a read
     * action so it's safe to call from the pooled stepper thread.
     *
     * <p>An empty list is cached as a sentinel for "PSI couldn't find this class"
     * (e.g. JDK types, classes from a jar that isn't on the project's source
     * roots) so we don't repeatedly hammer {@code findClass} for the same miss.
     */
    @NotNull
    private List<ReturnFieldSignature> resolveRuntimeFieldSignatures(@NotNull String fqn) {
        return runtimeFieldSignatureCache.computeIfAbsent(fqn, qn -> {
            Project project = session.getProject();
            return ApplicationManager.getApplication().runReadAction(
                    (Computable<List<ReturnFieldSignature>>) () -> {
                        PsiClass cls = JavaPsiFacade.getInstance(project)
                                .findClass(qn, GlobalSearchScope.allScope(project));
                        return cls != null
                                ? MethodTracer.collectInstanceFieldSignatures(cls)
                                : Collections.emptyList();
                    });
        });
    }

    /**
     * Reads {@code expression.getClass().getName()} via the debugger and strips
     * the surrounding quotes that {@link ParameterEvaluator#formatForCode} adds
     * for String values. Returns the FQN with {@code $} separators preserved
     * (so inner-class lookups work) or {@code null} on failure.
     */
    @Nullable
    private static String evaluateRuntimeFqn(@NotNull XDebuggerEvaluator evaluator,
                                              @NotNull String expression) {
        String quoted = evaluateWithRetry(evaluator, expression + ".getClass().getName()");
        if (quoted == null) return null;

        String unquoted = quoted;
        if (unquoted.length() >= 2 && unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        return unquoted.isEmpty() ? null : unquoted;
    }

    /** Returns true when the FQN looks like a runtime-generated proxy class. */
    private static boolean isProxyFqn(@NotNull String fqn) {
        return fqn.contains("$HibernateProxy$") || fqn.contains("$$EnhancerByCGLIB$$")
                || fqn.contains("$$SpringCGLIB$$") || fqn.contains("$ByteBuddy$");
    }

    /**
     * Strips Hibernate / CGLIB / ByteBuddy proxy suffixes from a runtime FQN
     * so that e.g. {@code com.example.Author$HibernateProxy$s5YYfc73} becomes
     * {@code com.example.Author}. This lets PSI resolve the real class's fields
     * instead of the synthetic proxy internals.
     */
    @NotNull
    private static String deproxyFqn(@NotNull String fqn) {
        // Hibernate proxies: Foo$HibernateProxy$xxx
        int idx = fqn.indexOf("$HibernateProxy$");
        if (idx > 0) return fqn.substring(0, idx);

        // CGLIB proxies: Foo$$EnhancerByCGLIB$$xxx
        idx = fqn.indexOf("$$EnhancerByCGLIB$$");
        if (idx > 0) return fqn.substring(0, idx);

        // Spring CGLIB: Foo$$SpringCGLIB$$xxx
        idx = fqn.indexOf("$$SpringCGLIB$$");
        if (idx > 0) return fqn.substring(0, idx);

        // ByteBuddy: Foo$ByteBuddy$xxx
        idx = fqn.indexOf("$ByteBuddy$");
        if (idx > 0) return fqn.substring(0, idx);

        return fqn;
    }

    /**
     * Extracts the simple class name from a JVM FQN. Handles inner classes by
     * keeping only the part after the last {@code $} so {@code com.foo.Outer$Inner}
     * becomes {@code Inner}. The result is what the generator emits in
     * {@code new Foo()} expressions, so it must be a valid Java identifier.
     */
    @NotNull
    private static String simpleNameOf(@NotNull String fqn) {
        int dot = fqn.lastIndexOf('.');
        String afterDot = dot < 0 ? fqn : fqn.substring(dot + 1);
        int dollar = afterDot.lastIndexOf('$');
        return dollar < 0 ? afterDot : afterDot.substring(dollar + 1);
    }

    /**
     * For dependency calls in return position (e.g. {@code return repo.save(order)}),
     * propagate whatever the stepper captured as the method's return value to
     * the dependency call's mock return slot. This makes the generator emit
     * {@code thenReturn(capturedValue)} instead of {@code thenReturn(null / TODO)}.
     */
    private void feedReturnPositionCalls() {
        for (TracedCall call : calls) {
            if (!call.isInReturnPosition()) continue;
            if (call.getCapturedReturnValue() != null || call.hasCapturedReturnFields()
                    || call.hasCapturedReturnListElements()) continue; // already captured

            if (capturedMethodReturnValue != null) {
                call.setCapturedReturnValue(capturedMethodReturnValue);
            } else if (!capturedMethodReturnFields.isEmpty()) {
                call.setCapturedReturnFields(capturedMethodReturnFields);
                // Propagate the actual type so the generator emits e.g. "BookOrder"
                // instead of a generic "S". Prefer the runtime type from capture;
                // fall back to the method's declared return type when the call's own
                // return type differs (common for generic repo methods like save(S)).
                String effectiveType = capturedMethodReturnRuntimeType;
                if (effectiveType == null && returnAnalysis.returnType() != null
                        && !returnAnalysis.returnType().equals(call.getReturnType())) {
                    effectiveType = returnAnalysis.returnType();
                }
                if (effectiveType != null) {
                    call.setCapturedReturnRuntimeType(effectiveType);
                }
            } else if (!capturedMethodReturnListElements.isEmpty()) {
                call.setCapturedReturnListElements(capturedMethodReturnListElements);
            }
        }
    }

    /** Stores a {@link CaptureResult} on the call, mapping each shape to the right slot. */
    private static void applyCapture(@NotNull TracedCall call, @Nullable CaptureResult result) {
        if (result == null) return;
        if (result.literal != null) {
            call.setCapturedReturnValue(result.literal);
        } else if (!result.fields.isEmpty()) {
            call.setCapturedReturnFields(result.fields);
            if (result.runtimeType != null) {
                call.setCapturedReturnRuntimeType(result.runtimeType);
            }
        } else if (!result.listElements.isEmpty()) {
            call.setCapturedReturnListElements(result.listElements);
        }
    }

    /** Sum-type for {@link #captureExpression}: literal, single POJO, or list of elements. */
    private static final class CaptureResult {
        final String literal;
        final List<CapturedField> fields;
        final List<CapturedListElement> listElements;
        /** Runtime simple name when resolved via runtime-class dispatch (e.g. "Book"). */
        final String runtimeType;

        private CaptureResult(String literal, List<CapturedField> fields,
                              List<CapturedListElement> listElements, String runtimeType) {
            this.literal = literal;
            this.fields = fields;
            this.listElements = listElements;
            this.runtimeType = runtimeType;
        }

        static CaptureResult literal(String l)              { return new CaptureResult(l, Collections.emptyList(), Collections.emptyList(), null); }
        static CaptureResult composite(List<CapturedField> f) { return new CaptureResult(null, f, Collections.emptyList(), null); }
        static CaptureResult compositeRuntime(List<CapturedField> f, String rt) { return new CaptureResult(null, f, Collections.emptyList(), rt); }
        static CaptureResult list(List<CapturedListElement> e) { return new CaptureResult(null, Collections.emptyList(), e, null); }
    }

    /**
     * Bundle of return-type metadata used to drive {@link #captureExpression}.
     * Lets us produce a uniform spec from either a {@link TracedCall} (for
     * dependency-call captures) or a {@link ReturnAnalysis} (for the method's
     * own return) without dragging seven parameters through every call site.
     */
    private record CaptureSpec(
            String declaredType,
            boolean isEnum,
            List<ReturnFieldSignature> fieldSignatures,
            boolean isList,
            String elementType,
            boolean elementIsEnum,
            List<ReturnFieldSignature> elementSignatures) {

        /** Spec used when recursing into a single element of a list — list flags cleared. */
        CaptureSpec asElement() {
            return new CaptureSpec(elementType, elementIsEnum, elementSignatures,
                    false, null, false, Collections.emptyList());
        }

        static CaptureSpec from(@NotNull TracedCall c) {
            return new CaptureSpec(c.getReturnType(), c.isReturnTypeEnum(), c.getReturnFieldSignatures(),
                    c.isReturnList(), c.getReturnElementType(), c.isReturnElementEnum(), c.getReturnElementSignatures());
        }

        static CaptureSpec from(@NotNull ReturnAnalysis a) {
            return new CaptureSpec(a.returnType(), a.isEnum(), a.fieldSignatures(),
                    a.isList(), a.elementType(), a.elementIsEnum(), a.elementFieldSignatures());
        }
    }

    /**
     * Evaluates {@code expression} via the debugger and retries once after a
     * short pause when IntelliJ returns its "Collecting data…" placeholder
     * (which means the JDI request is still in flight). Returns {@code null}
     * on hard failure or when the placeholder is still present after the retry.
     */
    @Nullable
    private static String evaluateWithRetry(@NotNull XDebuggerEvaluator evaluator,
                                            @NotNull String expression) {
        String value = ParameterEvaluator.evaluateAndFormat(evaluator, expression);
        if (!isCollectingPlaceholder(value)) return value;

        sleepQuietly(COLLECTING_RETRY_MS);
        value = ParameterEvaluator.evaluateAndFormat(evaluator, expression);
        return isCollectingPlaceholder(value) ? null : value;
    }

    /**
     * Same retry semantics as {@link #evaluateWithRetry}, but produces an enum
     * literal of the form {@code ShortType.CONSTANT} from {@code resultVar.name()}.
     */
    @Nullable
    private static String evaluateEnumLiteralWithRetry(@NotNull XDebuggerEvaluator evaluator,
                                                       @NotNull String resultVar,
                                                       @NotNull String declaredType) {
        String nameLiteral = evaluateWithRetry(evaluator, resultVar + ".name()");
        if (nameLiteral == null) return null;

        // evaluateAndFormat wraps Strings in quotes — peel them off.
        String constantName = nameLiteral;
        if (constantName.length() >= 2 && constantName.startsWith("\"") && constantName.endsWith("\"")) {
            constantName = constantName.substring(1, constantName.length() - 1);
        }
        if (constantName.isEmpty()) return null;

        String shortType = declaredType.contains(".")
                ? declaredType.substring(declaredType.lastIndexOf('.') + 1)
                : declaredType;
        return shortType + "." + constantName;
    }

    private static boolean isCollectingPlaceholder(@Nullable String value) {
        if (value == null) return false;
        for (String marker : COLLECTING_PLACEHOLDERS) {
            if (value.contains(marker)) return true;
        }
        return false;
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private int currentLine() {
        XSourcePosition pos = session.getCurrentPosition();
        return pos != null ? pos.getLine() : -1;
    }
}
