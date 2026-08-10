package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAuditPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAtomicCommandPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAtomicCommandResult;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchCommandReplayPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchIdempotencyPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchQualityEvaluationService;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchRepository;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchTransactionPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchStagingPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityMeasurementPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRepository;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchIdentity;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Java persistence-port and atomic-command contract for Story 2.3. */
class Story23DataBatchJdbcBoundaryContractTest {
    private static final String APPLICATION_PACKAGE =
            "cn.edu.suda.scholarsense.ingestionquality.application.";
    private static final String OUTBOUND_PACKAGE =
            "cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.";
    private static final Path OUTBOUND_SOURCE = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound");

    @Test
    void hydrationPortsExcludeEveryRawWriteCapability() throws Exception {
        Class<?> batchReads = Class.forName(APPLICATION_PACKAGE + "DataBatchReadPort");
        Class<?> snapshotReads = Class.forName(APPLICATION_PACKAGE + "QualitySnapshotReadPort");

        assertEquals(Optional.class, batchReads.getMethod("find", UUID.class).getReturnType());
        assertEquals(Optional.class,
                batchReads.getMethod("findByIdentity", BatchIdentity.class).getReturnType());
        assertEquals(Optional.class,
                batchReads.getMethod(
                        "latestForBusinessKey", String.class, String.class).getReturnType());
        assertEquals(Optional.class,
                batchReads.getMethod("lineageHead", UUID.class).getReturnType());
        assertEquals(Set.of("find", "findByIdentity", "latestForBusinessKey", "lineageHead"),
                Arrays.stream(batchReads.getDeclaredMethods())
                        .map(Method::getName).collect(Collectors.toSet()));

        assertEquals(Optional.class,
                snapshotReads.getMethod("findByBatchId", UUID.class).getReturnType());
        assertEquals(Set.of("findByBatchId"), Arrays.stream(snapshotReads.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet()));
        assertTrue(batchReads.isAssignableFrom(DataBatchRepository.class));
        assertTrue(snapshotReads.isAssignableFrom(QualitySnapshotRepository.class));

        boolean evaluationUsesOnlyReads = Arrays.stream(
                        DataBatchQualityEvaluationService.class.getConstructors())
                .map(Constructor::getParameterTypes)
                .anyMatch(types -> Arrays.asList(types).contains(batchReads)
                        && Arrays.asList(types).contains(snapshotReads)
                        && !Arrays.asList(types).contains(DataBatchRepository.class)
                        && !Arrays.asList(types).contains(QualitySnapshotRepository.class));
        assertTrue(evaluationUsesOnlyReads,
                "quality evaluation may hydrate batches/snapshots but may not acquire raw writes");
    }

    @Test
    void jdbcStoreImplementsOnlyReadAndBoundedMeasurementPorts() throws Exception {
        Class<?> batchReads = Class.forName(APPLICATION_PACKAGE + "DataBatchReadPort");
        Class<?> snapshotReads = Class.forName(APPLICATION_PACKAGE + "QualitySnapshotReadPort");
        Class<?> store = Class.forName(OUTBOUND_PACKAGE + "JdbcDataBatchStore");

        assertEquals(Set.of(
                        batchReads, snapshotReads, QualityMeasurementPort.class,
                        DataBatchCommandReplayPort.class),
                Set.of(store.getInterfaces()));
        assertFalse(DataBatchRepository.class.isAssignableFrom(store));
        assertFalse(QualitySnapshotRepository.class.isAssignableFrom(store));
        assertFalse(DataBatchIdempotencyPort.class.isAssignableFrom(store));
        assertFalse(DataBatchAuditPort.class.isAssignableFrom(store));
        assertNotNull(store.getConstructor(JdbcTemplate.class, ObjectMapper.class));
    }

    @Test
    void atomicAdapterExposesExactlyTheSevenWorkerCompoundCommands() throws Exception {
        Class<?> adapter = Class.forName(
                OUTBOUND_PACKAGE + "JdbcDataBatchAtomicCommandAdapter");
        Map<String, Method> commands = Arrays.stream(adapter.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .collect(Collectors.toMap(Method::getName, Function.identity()));
        Set<String> expected = Set.of(
                "receive", "appendNormalizedFact", "recordQualityMeasurement",
                "recordQualityImpactScope", "seal", "commitQualityEvaluation", "publish");

        assertEquals(expected, commands.keySet());
        for (String command : expected) {
            Method method = commands.get(command);
            if (Set.of("receive", "seal", "commitQualityEvaluation", "publish")
                    .contains(command)) {
                assertEquals(DataBatchAtomicCommandResult.class, method.getReturnType(), command);
            } else {
                assertEquals(boolean.class, method.getReturnType(), command);
            }
            assertEquals(1, method.getParameterCount(),
                    command + " accepts one immutable application command");
            assertTrue(method.getParameterTypes()[0].isRecord(),
                    command + " command must be an immutable record");
        }
        assertEquals(Set.of(DataBatchAtomicCommandPort.class, DataBatchStagingPort.class),
                Set.of(adapter.getInterfaces()));
        assertNotNull(adapter.getConstructor(
                JdbcTemplate.class, ObjectMapper.class, JdbcDataBatchStore.class,
                AuditTokenizationPort.class));
        assertFalse(DataBatchRepository.class.isAssignableFrom(adapter));
        assertFalse(QualitySnapshotRepository.class.isAssignableFrom(adapter));
        assertFalse(DataBatchIdempotencyPort.class.isAssignableFrom(adapter));
        assertFalse(DataBatchAuditPort.class.isAssignableFrom(adapter));

        String source = Files.readString(OUTBOUND_SOURCE.resolve(
                "JdbcDataBatchAtomicCommandAdapter.java"));
        for (String function : Set.of(
                "iq_receive_data_batch",
                "iq_append_normalized_fact",
                "iq_record_batch_quality_measurement",
                "iq_record_batch_quality_impact_scope",
                "iq_seal_data_batch",
                "iq_commit_batch_quality_evaluation",
                "iq_publish_data_batch")) {
            assertTrue(source.contains("ingestion_quality." + function + "("), function);
        }
        assertFalse(source.matches(
                        "(?is).*\\b(?:insert\\s+into|update|delete\\s+from)\\s+"
                                + "ingestion_quality\\..*"),
                "worker adapter must never bypass the compound SECURITY DEFINER functions");
    }

    @Test
    void compoundFunctionsReplaceTheSplitOuterTransactionBoundary() throws Exception {
        Class<?> service = Class.forName(APPLICATION_PACKAGE + "DataBatchCommandService");
        assertTrue(Arrays.stream(service.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .noneMatch(DataBatchTransactionPort.class::equals));
        String source = Files.readString(Path.of(
                "src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/"
                        + "DataBatchCommandService.java"));
        assertFalse(source.contains("transactions.execute("));
    }

    @Test
    void jdbcOwnerCallsBindEvidenceToTheAtomicCommandTrace() throws Exception {
        String source = Files.readString(OUTBOUND_SOURCE.resolve(
                "JdbcDataBatchAtomicCommandAdapter.java"));

        assertTrue(source.contains(
                "timestamp(batch.receivedAt()), commit.traceId(), commit.scopeDigest()"));
        assertTrue(source.contains(
                "timestamp(batch.sealedAt()),\n                commit.traceId(), commit.scopeDigest()"));
        assertTrue(source.contains(
                "timestamp(snapshot.evaluatedAt()),\n                commit.traceId(), snapshot.immutableHash()"));
        assertTrue(source.contains(
                "timestamp(batch.publishedAt()), commit.traceId(), commit.scopeDigest()"));
        assertFalse(source.contains(
                "timestamp(batch.publishedAt()), batch.traceId()"),
                "publish must not reuse the batch receive provenance trace");
    }

    @Test
    void ownerSqlAllowsIndependentCommandTracesButKeepsSnapshotEvidenceConsistent()
            throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/ingestion-quality/"
                        + "V000014__ingestion-quality__data_batch_quality_snapshot_v1.sql"));
        String evaluate = function(
                migration, "iq_commit_batch_quality_evaluation",
                "iq_validate_batch_published_payload");
        String publish = function(
                migration, "iq_publish_data_batch", "iq_json_uuid_v7");
        String seal = function(
                migration, "iq_seal_data_batch", "iq_qmdp_expected_metrics");

        assertFalse(evaluate.contains(
                "requested_trace_id is distinct from iq_batch.trace_id"),
                "evaluate trace is the command/snapshot trace, not the receive trace");
        assertFalse(publish.contains("select trace_id into iq_authoritative_trace_id"),
                "publish trace is the current command trace, not the receive trace");
        assertTrue(seal.contains("requested_trace_id char(32)"));
        for (String owner : List.of(evaluate, publish, seal)) {
            assertTrue(owner.contains("requested_trace_id"));
            assertTrue(owner.contains("iq_append_batch_audit"));
        }
        assertTrue(evaluate.contains(
                "requested_evaluated_at, requested_trace_id"),
                "the immutable snapshot and evaluate evidence share one command trace");
    }

    @Test
    void evaluationCompoundFunctionWinsAndLocksTheCasBeforeSnapshotInsertion() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/ingestion-quality/"
                        + "V000014__ingestion-quality__data_batch_quality_snapshot_v1.sql"));
        int functionStart = migration.indexOf(
                "create function ingestion_quality.iq_commit_batch_quality_evaluation(");
        int functionEnd = migration.indexOf(
                "create function ingestion_quality.iq_publish_data_batch(", functionStart);
        assertTrue(functionStart >= 0 && functionEnd > functionStart);

        String evaluate = migration.substring(functionStart, functionEnd);
        int lockedRead = evaluate.indexOf("for update;");
        int versionCheck = evaluate.indexOf(
                "iq_batch.aggregate_version <> requested_expected_version");
        int snapshotInsert = evaluate.indexOf(
                "insert into ingestion_quality.iq_quality_snapshot");
        assertTrue(lockedRead >= 0 && versionCheck >= 0 && snapshotInsert >= 0
                        && lockedRead < snapshotInsert && versionCheck < snapshotInsert,
                "the compound function must lock and validate the batch CAS before any "
                        + "immutable snapshot row is inserted");
    }

    private static String function(String migration, String name, String successor) {
        int start = migration.indexOf("create function ingestion_quality." + name + "(");
        int end = migration.indexOf(
                "create function ingestion_quality." + successor + "(", start);
        assertTrue(start >= 0 && end > start, name);
        return migration.substring(start, end);
    }
}
