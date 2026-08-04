package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogAuditRelayWork;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogStore;
import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.JdbcCatalogTransactionAdapter;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuthorizationDecision;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogContractViolation;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogView;
import cn.edu.suda.scholarsense.ingestionquality.application.DataSourceCatalogService;
import cn.edu.suda.scholarsense.ingestionquality.application.PublishCatalogCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.ValidateCatalogCommand;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuntimeEvidenceClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContract;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL 18.4 evidence, launched by scripts/run_audit_postgresql_tests.sh. */
class DataSourceCatalogPostgreSqlIT {
    private static final UUID CATALOG_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000111");
    private static final UUID RELEASE_ID = UUID.fromString("019fc6b8-9400-7000-8000-000000000112");
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final String TRACE = "00112233445566778899aabbccddeeff";
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private JdbcCatalogStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(required("scholarsense.audit.pg.url"));
        source.setUsername(required("scholarsense.audit.pg.user"));
        source.setPassword(System.getProperty("scholarsense.audit.pg.password", ""));
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new JdbcCatalogStore(jdbc, new ObjectMapper());
        jdbc.execute("""
                truncate table ingestion_quality.iq_local_audit_outbox,
                  ingestion_quality.iq_local_audit_fact,
                  ingestion_quality.iq_catalog_idempotency,
                  ingestion_quality.iq_catalog_current,
                  ingestion_quality.iq_catalog_evidence,
                  ingestion_quality.iq_catalog_validation_attempt,
                  ingestion_quality.iq_dependency_binding,
                  ingestion_quality.iq_source_contract,
                  ingestion_quality.iq_dependency_id_reservation,
                  ingestion_quality.iq_source_id_reservation,
                  ingestion_quality.iq_data_source_catalog cascade
                """);
    }

    @Test
    void exactServerMigrationAndLeastPrivilegeRolesExist() {
        assertEquals("180004", jdbc.queryForObject("select current_setting('server_version_num')", String.class));
        assertEquals(11, jdbc.queryForObject("""
                select count(*) from information_schema.tables
                 where table_schema='ingestion_quality' and table_type='BASE TABLE'
                """, Integer.class));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "select has_schema_privilege('scholarsense_ingestion_quality_online','ingestion_quality','USAGE')",
                Boolean.class)));
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
                "select has_table_privilege('scholarsense_ingestion_quality_relay',"
                        + "'ingestion_quality.iq_data_source_catalog','INSERT')", Boolean.class)));
    }

    @Test
    void publishCommitsCatalogIdempotencyCurrentAndLocalAuditAtomically() {
        transactions.executeWithoutResult(status -> store.save(draft(), 0));
        DataSourceCatalogService service = service(store::append);
        CatalogView publishable = service.validate(new ValidateCatalogCommand(
                CATALOG_ID, 1, "owner-r6", TRACE, NOW.plusSeconds(1)));
        CatalogView published = service.publish(new PublishCatalogCommand(
                CATALOG_ID, publishable.aggregateVersion(), RELEASE_ID, "idem-pg-001",
                "sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64),
                "owner-r6", TRACE, NOW.plusSeconds(2)));

        assertEquals(CatalogStatus.PUBLISHED, published.status());
        assertEquals(CATALOG_ID, store.current().orElseThrow().catalogId());
        assertEquals(1, count("iq_catalog_idempotency"));
        assertEquals(2, count("iq_local_audit_fact"));
        assertEquals(2, count("iq_local_audit_outbox"));
        assertEquals("pending", jdbc.queryForObject("""
                select status from ingestion_quality.iq_local_audit_outbox
                 where event_type='ingestion-quality.local-audit-fact.recorded.v1'
                 order by created_at desc limit 1
                """, String.class));

        var relay = new JdbcCatalogAuditRelayWork(jdbc, transactions, new ObjectMapper());
        assertEquals(2, relay.claimDue(100, NOW.plusSeconds(3), Duration.ofSeconds(60)).size());
    }

    @Test
    void auditFailureRollsBackPublishedStatePointerAndIdempotency() {
        transactions.executeWithoutResult(status -> store.save(draft(), 0));
        DataSourceCatalogService normal = service(store::append);
        normal.validate(new ValidateCatalogCommand(CATALOG_ID, 1, "owner-r6", TRACE, NOW.plusSeconds(1)));
        DataSourceCatalogService failing = service(event -> { throw new IllegalStateException("audit unavailable"); });

        assertThrows(IllegalStateException.class, () -> failing.publish(new PublishCatalogCommand(
                CATALOG_ID, 2, RELEASE_ID, "idem-pg-rollback", "sha256:" + "c".repeat(64),
                "sha256:" + "d".repeat(64), "owner-r6", TRACE, NOW.plusSeconds(2))));
        assertEquals(CatalogStatus.PUBLISHABLE, store.find(CATALOG_ID).orElseThrow().status());
        assertTrue(store.current().isEmpty());
        assertEquals(0, count("iq_catalog_idempotency"));
        assertEquals(1, count("iq_local_audit_fact"));
    }

    @Test
    void stableSourceIdAllowsACompatibleCatalogRevisionButRejectsPurposeReuse() {
        transactions.executeWithoutResult(status -> store.save(draft(), 0));
        UUID nextCatalogId = UUID.fromString("019fc6b8-9400-7000-8000-000000000121");
        DataSourceCatalog compatible = DataSourceCatalog.draft(
                nextCatalogId, "DCC-1.0.0", List.of(new SourceContract(
                        "SRC-P0-CALENDAR-001", "business-calendar-control", "BC-1.1.0", "QG-1.0.0",
                        "evidence+sha256://" + "b".repeat(64), RuntimeEvidenceClaim.TARGET_VERIFIED)),
                List.of(new DependencyBinding("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001",
                        DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF)),
                "sha256:" + "b".repeat(64), NOW.plusSeconds(1));
        transactions.executeWithoutResult(status -> store.save(compatible, 0));
        assertEquals(2, count("iq_data_source_catalog"));
        assertEquals(1, count("iq_source_id_reservation"));

        UUID reusedCatalogId = UUID.fromString("019fc6b8-9400-7000-8000-000000000122");
        DataSourceCatalog reused = DataSourceCatalog.draft(
                reusedCatalogId, "DCC-1.0.0", List.of(new SourceContract(
                        "SRC-P0-CALENDAR-001", "unrelated-purpose", "BC-2.0.0", "QG-1.0.0",
                        "evidence+sha256://" + "c".repeat(64), RuntimeEvidenceClaim.TARGET_VERIFIED)),
                List.of(), "sha256:" + "c".repeat(64), NOW.plusSeconds(2));
        assertThrows(IllegalStateException.class,
                () -> transactions.executeWithoutResult(status -> store.save(reused, 0)));
        assertEquals(2, count("iq_data_source_catalog"));
    }

    private DataSourceCatalogService service(
            cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditPort audit) {
        return new DataSourceCatalogService(store, store, catalog -> List.<CatalogContractViolation>of(),
                (actor, action, catalog, trace) -> CatalogAuthorizationDecision.ALLOW,
                new JdbcCatalogTransactionAdapter(transactions), audit, trace -> {});
    }

    private static DataSourceCatalog draft() {
        return DataSourceCatalog.draft(CATALOG_ID, "DCC-1.0.0", List.of(new SourceContract(
                        "SRC-P0-CALENDAR-001", "business-calendar-control", "BC-1.0.0", "QG-1.0.0",
                        "evidence+sha256://" + "a".repeat(64), RuntimeEvidenceClaim.TARGET_VERIFIED)),
                List.of(new DependencyBinding("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001",
                        DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF)),
                "sha256:" + "a".repeat(64), NOW);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from ingestion_quality." + table, Integer.class);
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }
}
