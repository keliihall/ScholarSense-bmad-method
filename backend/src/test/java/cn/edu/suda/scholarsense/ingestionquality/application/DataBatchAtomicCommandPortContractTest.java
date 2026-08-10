package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Task 3.4 contract for the single application-owned data-batch commit boundary. */
class DataBatchAtomicCommandPortContractTest {
    private static final String APPLICATION_PACKAGE =
            "cn.edu.suda.scholarsense.ingestionquality.application";
    private static final String ATOMIC_PORT = APPLICATION_PACKAGE + ".DataBatchAtomicCommandPort";
    private static final String STAGING_PORT = APPLICATION_PACKAGE + ".DataBatchStagingPort";
    private static final Path COMMAND_SERVICE = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/"
                    + "DataBatchCommandService.java");
    private static final Path ATOMIC_ADAPTER = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound/"
                    + "JdbcDataBatchAtomicCommandAdapter.java");
    private static final Path QUALITY_WORKER_CONFIGURATION = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/"
                    + "IngestionQualityQualityWorkerConfiguration.java");

    @Test
    void applicationOwnsFourTypedAtomicCommandsAndASeparateStagingPort() {
        Class<?> atomic = requiredClass(ATOMIC_PORT,
                "the application-owned atomic command port is missing");
        Class<?> staging = requiredClass(STAGING_PORT,
                "normalized facts, measurements and impact scopes need a separate staging port");

        assertAll(
                () -> assertTrue(atomic.isInterface(), "DataBatchAtomicCommandPort must be an interface"),
                () -> assertEquals(APPLICATION_PACKAGE, atomic.getPackageName()),
                () -> assertEquals(Set.of(
                                "receive", "seal", "commitQualityEvaluation", "publish"),
                        publicAbstractMethods(atomic).keySet(),
                        "the atomic boundary has exactly the four owner commit operations"),
                () -> assertTrue(staging.isInterface(), "DataBatchStagingPort must be an interface"),
                () -> assertEquals(APPLICATION_PACKAGE, staging.getPackageName()));

        for (Method method : publicAbstractMethods(atomic).values()) {
            assertEquals(1, method.getParameterCount(), method.getName() + " takes one typed command");
            Class<?> command = method.getParameterTypes()[0];
            assertEquals(APPLICATION_PACKAGE, command.getPackageName(),
                    method.getName() + " command must be application-owned");
            assertTrue(command.isRecord(), method.getName() + " command must be an immutable record");
            assertFalse(method.getReturnType().equals(boolean.class),
                    method.getName() + " must not erase accepted/replay into boolean");
            assertTrue(hasAcceptedReplayModel(method.getReturnType()),
                    method.getName() + " result must distinguish ACCEPTED from REPLAY");
        }

        List<String> stagingNames = publicAbstractMethods(staging).keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT)).toList();
        assertAll(
                () -> assertTrue(stagingNames.stream().anyMatch(
                                name -> name.contains("append") && name.contains("fact")),
                        "staging must expose fact append"),
                () -> assertTrue(stagingNames.stream().anyMatch(name -> name.contains("measurement")),
                        "staging must expose quality measurement recording"),
                () -> assertTrue(stagingNames.stream().anyMatch(
                                name -> name.contains("impact") && name.contains("scope")),
                        "staging must expose impact-scope recording"),
                () -> assertFalse(stagingNames.stream().anyMatch(Set.of(
                                "receive", "seal", "commitqualityevaluation", "publish")::contains),
                        "owner commits do not belong on the staging port"));
    }

    @Test
    void evaluationCommitAcceptsOnlyPreparedEvidenceAndTypedCanonicalPayloads() {
        Class<?> atomic = requiredClass(ATOMIC_PORT,
                "the application-owned atomic command port is missing");
        Method commit = publicAbstractMethods(atomic).get("commitQualityEvaluation");
        assertNotNull(commit, "commitQualityEvaluation is required");
        Class<?> command = commit.getParameterTypes()[0];
        List<RecordComponent> components = Arrays.asList(command.getRecordComponents());

        assertAll(
                () -> assertTrue(components.stream().map(RecordComponent::getType)
                                .anyMatch(type -> Set.of(
                                                "PreparedDataBatchAssessment",
                                                "VerifiedQualitySnapshot")
                                        .contains(type.getSimpleName())),
                        "evaluation persistence must carry a non-forgeable prepared assessment"),
                () -> assertTrue(components.stream().map(RecordComponent::getType)
                                .anyMatch(type -> type.getSimpleName().equals("CanonicalOutboxPayload")),
                        "audit/business payload bytes and digests travel as an internally built "
                                + "CanonicalOutboxPayload"),
                () -> assertFalse(components.stream().map(RecordComponent::getName)
                                .map(name -> name.toLowerCase(Locale.ROOT))
                                .anyMatch(Set.of(
                                        "immutablehash", "metrics", "auditpayloadjson",
                                        "auditpayloaddigest", "businesspayloadjson",
                                        "businesspayloaddigest")::contains),
                        "the adapter boundary must not accept forgeable snapshot fields or "
                                + "caller JSON/digests"));

        Method seal = publicAbstractMethods(atomic).get("seal");
        assertNotNull(seal, "seal is required");
        assertTrue(Arrays.stream(seal.getParameterTypes()[0].getRecordComponents())
                        .map(RecordComponent::getType)
                        .anyMatch(type -> type.getSimpleName().equals(
                                "SealedQualityContractEvidence")),
                "seal commit must carry the captured executable-contract evidence");
    }

    @Test
    void atomicCommitContextCarriesTheCurrentAuthorizedCommandTrace() {
        List<RecordComponent> components = Arrays.asList(
                DataBatchAtomicCommitContext.class.getRecordComponents());

        assertEquals(1L, components.stream()
                .filter(component -> component.getName().equals("traceId")
                        && component.getType().equals(String.class))
                .count(),
                "audit, PIC and owner SQL must share the current authorized command trace");
    }

    @Test
    void commandServiceHasOneAtomicWriteDependencyAndNoSplitOwnerWritePorts()
            throws Exception {
        Class<?> atomic = requiredClass(ATOMIC_PORT,
                "the application-owned atomic command port is missing");
        Set<String> forbidden = Set.of(
                DataBatchRepository.class.getName(),
                QualitySnapshotRepository.class.getName(),
                DataBatchAuditPort.class.getName(),
                DataBatchIdempotencyPort.class.getName(),
                DataBatchTransactionPort.class.getName());
        List<Class<?>> constructorTypes = Arrays.stream(DataBatchCommandService.class
                        .getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .toList();
        String source = Files.readString(COMMAND_SERVICE);

        assertAll(
                () -> assertEquals(1, DataBatchCommandService.class.getDeclaredConstructors().length,
                        "there is one production orchestrator constructor"),
                () -> assertTrue(constructorTypes.contains(atomic),
                        "the command service must depend on DataBatchAtomicCommandPort"),
                () -> assertTrue(constructorTypes.stream().noneMatch(
                                type -> forbidden.contains(type.getName())),
                        "the production constructor must not expose split write ports: "
                                + constructorTypes.stream().map(Class::getSimpleName).toList()),
                () -> assertFalse(containsAny(source,
                                "repository.insert(", "repository.save(", "snapshots.insert(",
                                "audit.append(", "idempotency.claim(", "idempotency.complete(",
                                "transactions.execute("),
                        "fresh command paths may write only through the compound atomic port"));
    }

    @Test
    void adapterImplementsApplicationPortsAndDoesNotPublishRawRequestRecords()
            throws Exception {
        Class<?> atomic = requiredClass(ATOMIC_PORT,
                "the application-owned atomic command port is missing");
        Class<?> staging = requiredClass(STAGING_PORT,
                "the application-owned staging port is missing");
        Class<?> adapter = Class.forName(
                "cn.edu.suda.scholarsense.ingestionquality.adapters.outbound."
                        + "JdbcDataBatchAtomicCommandAdapter");
        String source = Files.readString(ATOMIC_ADAPTER);

        assertAll(
                () -> assertTrue(atomic.isAssignableFrom(adapter),
                        "the JDBC compound adapter must implement the application atomic port"),
                () -> assertTrue(staging.isAssignableFrom(adapter),
                        "the JDBC compound adapter must implement the separate staging port"),
                () -> assertTrue(Arrays.stream(adapter.getDeclaredClasses())
                                .noneMatch(type -> type.getSimpleName().endsWith("Request")),
                        "adapter-owned raw request records bypass the application contract"),
                () -> assertFalse(containsAny(source,
                                "auditPayloadJson", "auditPayloadDigest",
                                "businessPayloadJson", "businessPayloadDigest"),
                        "the adapter may serialize typed canonical payloads, not accept caller JSON/digests"));
    }

    @Test
    void freshPathsHaveOneLastOwnerWriteAfterFinalRevalidationAndReplayPrecedenceIsSafe()
            throws Exception {
        String source = Files.readString(COMMAND_SERVICE);

        assertAll(
                () -> assertOneAtomicCall(source,
                        "private DataBatchView receiveInTransaction(",
                        "private DataBatchView sealInTransaction(",
                        "atomic.receive("),
                () -> assertOneAtomicCall(source,
                        "private DataBatchView sealInTransaction(",
                        "private DataBatchView evaluateInTransaction(",
                        "atomic.seal("),
                () -> assertOneAtomicCall(source,
                        "private DataBatchView evaluateInTransaction(",
                        "private DataBatchView publishInTransaction(",
                        "atomic.commitQualityEvaluation("),
                () -> assertOneAtomicCall(source,
                        "private DataBatchView publishInTransaction(",
                        "private DataBatchView execute(",
                        "atomic.publish("));

        String evaluate = section(source,
                "private DataBatchView evaluateInTransaction(",
                "private DataBatchView publishInTransaction(");
        int prepared = evaluate.indexOf("qualityEvaluation.evaluate(");
        int contractRevalidation = evaluate.lastIndexOf("qualityEvaluation.revalidate(");
        int freshTime = evaluate.lastIndexOf("trustedTime()");
        int workloadRevalidation = evaluate.lastIndexOf("revalidateWorkload(");
        int atomicCommit = evaluate.indexOf("atomic.commitQualityEvaluation(");
        assertTrue(ordered(prepared, contractRevalidation, freshTime,
                        workloadRevalidation, atomicCommit),
                "evaluate must prepare snapshot/payloads, revalidate its contract, obtain fresh "
                        + "trusted time, revalidate workload authority, then make its sole atomic call");

        String execute = section(source, "private DataBatchView execute(", "private Optional");
        int mismatch = execute.indexOf("IDEMPOTENCY_MISMATCH");
        int replayAuthorization = execute.indexOf("authorizationCheck.accept(", mismatch);
        int replayResponse = execute.indexOf("return completed.orElseThrow().response()",
                replayAuthorization);
        assertTrue(ordered(mismatch, replayAuthorization, replayResponse),
                "changed-body mismatch must precede object lookup/authorization and an authorized "
                        + "completed replay must precede its response");
    }

    @Test
    void qualityWorkerConfigurationExplicitlyWiresTheCompleteFailClosedGraph()
            throws Exception {
        String source = Files.readString(QUALITY_WORKER_CONFIGURATION);
        Set<String> beanReturnTypes = Arrays.stream(Class.forName(
                        "cn.edu.suda.scholarsense.ingestionquality.adapters."
                                + "IngestionQualityQualityWorkerConfiguration")
                        .getDeclaredMethods())
                .filter(method -> Arrays.stream(method.getAnnotations())
                        .anyMatch(annotation -> annotation.annotationType().getSimpleName()
                                .equals("Bean")))
                .map(method -> method.getReturnType().getSimpleName())
                .collect(Collectors.toSet());

        assertAll(
                () -> assertTrue(beanReturnTypes.contains("JdbcDataBatchStore"),
                        "quality-worker must wire its owner store"),
                () -> assertTrue(beanReturnTypes.contains("JdbcDataBatchAtomicCommandAdapter"),
                        "quality-worker must wire its compound adapter"),
                () -> assertTrue(beanReturnTypes.contains("DataBatchQualityEvaluationService"),
                        "quality-worker must wire evaluation"),
                () -> assertTrue(beanReturnTypes.contains("DataBatchCommandService"),
                        "quality-worker must wire the unique orchestrator"),
                () -> assertTrue(beanReturnTypes.contains("QualityWorkerProviderAdapters"),
                        "the same artifact must implement every target-managed provider port"),
                () -> assertTrue(beanReturnTypes.contains("HttpClient"),
                        "provider calls must use the dedicated mTLS client"),
                () -> assertTrue(beanReturnTypes.contains("TrustedTimeSource"),
                        "the standalone quality-worker must own trusted-time wiring"),
                () -> assertFalse(source.toLowerCase(Locale.ROOT).contains("permitall")
                                || source.toLowerCase(Locale.ROOT).contains("allowall")
                                || source.toLowerCase(Locale.ROOT).contains("alwaysallow"),
                        "missing workload authorization must fail bean creation; no permissive default"));
    }

    private static Map<String, Method> publicAbstractMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .collect(Collectors.toMap(Method::getName, method -> method));
    }

    private static boolean hasAcceptedReplayModel(Class<?> result) {
        if (result.isPrimitive() || result.equals(Boolean.class) || result.equals(Void.class)) {
            return false;
        }
        if (result.isSealed()) {
            Set<String> names = Arrays.stream(result.getPermittedSubclasses())
                    .map(Class::getSimpleName).collect(Collectors.toSet());
            if (names.stream().anyMatch(name -> name.toUpperCase(Locale.ROOT).contains("ACCEPTED"))
                    && names.stream().anyMatch(
                            name -> name.toUpperCase(Locale.ROOT).contains("REPLAY"))) {
                return true;
            }
        }
        return Arrays.stream(result.isRecord() ? result.getRecordComponents()
                        : new RecordComponent[0])
                .map(RecordComponent::getType)
                .filter(Class::isEnum)
                .map(Class::getEnumConstants)
                .filter(constants -> constants != null)
                .anyMatch(constants -> {
                    Set<String> names = Arrays.stream(constants).map(Object::toString)
                            .map(name -> name.toUpperCase(Locale.ROOT))
                            .collect(Collectors.toSet());
                    return names.contains("ACCEPTED") && names.contains("REPLAY");
                });
    }

    private static void assertOneAtomicCall(
            String source, String start, String end, String expectedCall) {
        String command = section(source, start, end);
        assertEquals(1, occurrences(command, expectedCall),
                start + " must make exactly one " + expectedCall + " owner call");
        int call = command.indexOf(expectedCall);
        assertFalse(containsAny(command.substring(Math.max(0, call)),
                        "repository.insert(", "repository.save(", "snapshots.insert(",
                        "audit.append(", "idempotency.complete("),
                start + " must not write after its atomic owner call");
    }

    private static Class<?> requiredClass(String name, String message) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            fail(message + ": " + name);
            throw new AssertionError(missing);
        }
    }

    private static String section(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertTrue(startIndex >= 0 && endIndex > startIndex,
                "missing source section " + start + " .. " + end);
        return source.substring(startIndex, endIndex);
    }

    private static boolean containsAny(String source, String... needles) {
        return Arrays.stream(needles).anyMatch(source::contains);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }

    private static boolean ordered(int... positions) {
        int previous = -1;
        for (int position : positions) {
            if (position < 0 || position <= previous) return false;
            previous = position;
        }
        return true;
    }
}
