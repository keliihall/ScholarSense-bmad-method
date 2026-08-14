package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityEventTransactionPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilitySnapshotLookupPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseWorkloadAuthorizationGuard;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

class Story24QualityEligibilityJdbcBoundaryContractTest {
    private static final Path OUTBOUND = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound");
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/ingestion-quality/"
                    + "V000015__ingestion-quality__quality_eligibility_v1.sql");

    @Test
    void exactSnapshotLookupAndAtomicEventTransactionAreSeparateClosedPorts() throws Exception {
        Class<?> lookup = Class.forName(
                "cn.edu.suda.scholarsense.ingestionquality.adapters.outbound."
                        + "JdbcQualityEligibilitySnapshotLookupStore");
        Class<?> transaction = Class.forName(
                "cn.edu.suda.scholarsense.ingestionquality.adapters.outbound."
                        + "JdbcQualityEligibilityEventTransactionAdapter");

        assertEquals(Set.of(QualityEligibilitySnapshotLookupPort.class), Set.of(lookup.getInterfaces()));
        assertEquals(Set.of(QualityEligibilityEventTransactionPort.class),
                Set.of(transaction.getInterfaces()));
        assertNotNull(lookup.getConstructor(JdbcTemplate.class));
        assertNotNull(transaction.getConstructor(
                JdbcTemplate.class, TransactionOperations.class, ObjectMapper.class,
                QualityFuseWorkloadAuthorizationGuard.class, TrustedTimeSource.class));
    }

    @Test
    void adaptersUseOnlyFixedSearchPathOwnerFunctionsAndOneOuterTransaction() throws Exception {
        String lookup = Files.readString(OUTBOUND.resolve(
                "JdbcQualityEligibilitySnapshotLookupStore.java"));
        String transaction = Files.readString(OUTBOUND.resolve(
                "JdbcQualityEligibilityEventTransactionAdapter.java"));

        assertTrue(lookup.contains("ingestion_quality.iq_find_quality_snapshot_evidence_v2("));
        assertTrue(transaction.contains(
                "ingestion_quality.iq_load_quality_eligibility_processing_state_v2("));
        assertTrue(transaction.contains(
                "ingestion_quality.iq_accept_quality_eligibility_event_v2("));
        assertTrue(transaction.contains("transactions.execute("));
        assertFalse((lookup + transaction).matches(
                "(?is).*\\b(?:insert\\s+into|update|delete\\s+from)\\s+ingestion_quality\\..*"));
    }

    @Test
    void processingStateIsReadOnlyAfterAffectedRuleVersionsAreLockedInStableOrder()
            throws Exception {
        String migration = Files.readString(MIGRATION);
        int loaderStart = migration.indexOf(
                "create function ingestion_quality.iq_load_quality_eligibility_processing_state(");
        int loaderEnd = migration.indexOf(
                "create function ingestion_quality.iq_accept_quality_eligibility_event(");
        String loader = migration.substring(loaderStart, loaderEnd);

        int ruleLock = loader.indexOf("quality-eligibility-rule:");
        int stateRead = loader.indexOf("select jsonb_build_object(");
        assertTrue(ruleLock >= 0, "the loader must lock every affected RuleVersion");
        assertTrue(loader.contains("order by member.rule_id,member.rule_version"),
                "overlapping RuleVersions must be locked in deterministic order");
        assertTrue(ruleLock < stateRead,
                "dependency state must be re-read only after RuleVersion locks are held");
    }
}
