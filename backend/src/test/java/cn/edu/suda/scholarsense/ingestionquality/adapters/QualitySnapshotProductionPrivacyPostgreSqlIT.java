package cn.edu.suda.scholarsense.ingestionquality.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** PostgreSQL contract for production-only watermark and impact-scope privacy. */
class QualitySnapshotProductionPrivacyPostgreSqlIT {
    private static final String WATERMARK_HELPER = "iq_require_production_watermark";
    private static final String IMPACT_HELPER = "iq_require_production_impact_scope";

    @Test
    void internalValidatorsAreOwnerOnlyAndRunBeforeProductionPersistence() {
        JdbcTemplate jdbc = admin();

        assertEquals(2, jdbc.queryForObject("""
                select count(*)
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname = any (?::text[])
                   and procedure.proconfig @> array['search_path=pg_catalog']
                   and procedure.proowner=(
                     select role_record.oid from pg_catalog.pg_roles role_record
                      where role_record.rolname=
                        'scholarsense_ingestion_quality_batch_owner')
                   and not exists (
                     select 1
                       from pg_catalog.aclexplode(coalesce(
                              procedure.proacl,
                              pg_catalog.acldefault('f', procedure.proowner))) privilege
                      where privilege.grantee=0
                        and privilege.privilege_type='EXECUTE')
                """, Integer.class,
                (Object) new String[] {WATERMARK_HELPER, IMPACT_HELPER}));
        for (String role : new String[] {
            "scholarsense_ingestion_quality_online",
            "scholarsense_ingestion_quality_quality_worker",
            "scholarsense_ingestion_quality_relay",
            "scholarsense_ingestion_quality_retention_executor",
            "scholarsense_ingestion_quality_consumer_registry_authority"
        }) {
            assertFalse(canExecute(jdbc, role, WATERMARK_HELPER), role + " watermark helper");
            assertFalse(canExecute(jdbc, role, IMPACT_HELPER), role + " impact helper");
        }

        String watermark = functionDefinition(jdbc, WATERMARK_HELPER);
        assertTrue(watermark.contains("requested_source_id"));
        assertTrue(watermark.contains("requested_watermark_utf8"));
        assertTrue(watermark.contains("sha256:"));
        assertTrue(watermark.contains("lower(requested_source_id)"));
        assertTrue(watermark.contains("yyyy-mm-dd") || watermark.contains("make_date"),
                "the date suffix must be a real strict calendar date, not regex-only text");
        assertTrue(watermark.contains("character_not_in_repertoire"),
                "invalid UTF-8 must map to the seal stable code instead of leaking SQLSTATE 22021");

        String impact = functionDefinition(jdbc, IMPACT_HELPER);
        assertTrue(impact.contains("iq_qmdp_ordered_definitions(requested_source_id)"));
        assertTrue(impact.contains("definition ->> 'metricid'")
                        || impact.contains("definition #>> '{metricid}'"),
                "impact code must be an exact embedded QMDP metricId");
        assertFalse(impact.contains("definition ->> 'category'"),
                "QMDP never approved category values as impactScopeCodes");
        assertTrue(impact.contains("character_not_in_repertoire"),
                "invalid UTF-8 must map to the impact stable code instead of leaking SQLSTATE 22021");

        String seal = functionDefinition(jdbc, "iq_seal_data_batch");
        int watermarkCheck = seal.indexOf(WATERMARK_HELPER);
        int batchWrite = seal.indexOf("update ingestion_quality.iq_data_batch");
        assertTrue(watermarkCheck >= 0 && watermarkCheck < batchWrite,
                "unsafe watermark bytes must fail before the first batch persistence update");

        String record = functionDefinition(jdbc, "iq_record_batch_quality_impact_scope");
        int impactCheck = record.indexOf(IMPACT_HELPER);
        int impactWrite = record.indexOf(
                "insert into ingestion_quality.iq_batch_quality_impact_scope");
        assertTrue(impactCheck >= 0 && impactCheck < impactWrite,
                "free-text impact scope must fail before insert/conflict handling");

        assertFalse(functionDefinition(jdbc, "iq_qshm_canonical")
                        .contains("iq_require_production_"),
                "generic QSHM canonicalization must retain its approved Unicode semantics");
    }

    @Test
    void watermarkValidatorRejectsSubjectTextAndAcceptsOnlyTwoCanonicalForms() {
        JdbcTemplate jdbc = admin();
        for (String rejected : new String[] {
            "student-20260001",
            "student name and class",
            "学生王某某20260001",
            "src-p0-card-001@2026-08-10\0student-20260001",
            "SRC-P0-CARD-001@2026-08-10",
            "src-p0-student-001@2026-08-10",
            "src-p0-card-001@2026-02-30",
            "sha256:" + "A".repeat(64)
        }) {
            assertDatabaseFailure(
                    "INGESTION_QUALITY_SEAL_EVIDENCE_INVALID",
                    rejected,
                    () -> invoke(jdbc, WATERMARK_HELPER, "SRC-P0-CARD-001", rejected));
        }
        assertStableEncodingFailure(
                "INGESTION_QUALITY_SEAL_EVIDENCE_INVALID",
                () -> invoke(jdbc, WATERMARK_HELPER, "SRC-P0-CARD-001",
                        new byte[] {(byte) 0xc0, (byte) 0xaf}));

        invoke(jdbc, WATERMARK_HELPER,
                "SRC-P0-CARD-001", "src-p0-card-001@2026-08-10");
        invoke(jdbc, WATERMARK_HELPER,
                "SRC-P0-CARD-001", "sha256:" + "b".repeat(64));
    }

    @Test
    void impactValidatorUsesExactSourceApplicableQmdpMetricIds() {
        JdbcTemplate jdbc = admin();
        for (String rejected : new String[] {
            "student-20260001",
            "student name and class",
            "学生王某某20260001",
            "PRIMARY_KEY_COMPLETENESS_BP\0student-20260001",
            "PRIMARY_KEY",
            "PRIVACY",
            "P0_SUBJECT_MAPPING_BP"
        }) {
            assertDatabaseFailure(
                    "INGESTION_QUALITY_IMPACT_SCOPE_INVALID",
                    rejected,
                    () -> invoke(jdbc, IMPACT_HELPER, "SRC-P0-CALENDAR-001", rejected));
        }
        assertStableEncodingFailure(
                "INGESTION_QUALITY_IMPACT_SCOPE_INVALID",
                () -> invoke(jdbc, IMPACT_HELPER, "SRC-P0-CALENDAR-001",
                        new byte[] {(byte) 0xc0, (byte) 0xaf}));

        invoke(jdbc, IMPACT_HELPER,
                "SRC-P0-CALENDAR-001", "PRIMARY_KEY_COMPLETENESS_BP");
        invoke(jdbc, IMPACT_HELPER,
                "SRC-P0-CALENDAR-001", "SOURCE_CONTINUITY_GATE");
    }

    private static void invoke(
            JdbcTemplate jdbc, String function, String sourceId, String value) {
        invoke(jdbc, function, sourceId, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void invoke(
            JdbcTemplate jdbc, String function, String sourceId, byte[] value) {
        jdbc.queryForList(
                "select ingestion_quality." + function + "(?, ?)",
                sourceId, value);
    }

    private static void assertDatabaseFailure(
            String expectedCode, String forbiddenEcho, Runnable command) {
        DataAccessException failure = assertThrows(DataAccessException.class, command::run);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertTrue(message.contains(expectedCode), message);
        assertFalse(message.contains(forbiddenEcho),
                "stable rejection must not echo the rejected subject/free-text value");
    }

    private static void assertStableEncodingFailure(String expectedCode, Runnable command) {
        DataAccessException failure = assertThrows(DataAccessException.class, command::run);
        String message = failure.getMostSpecificCause().getMessage();
        assertNotNull(message);
        assertEquals("ERROR: " + expectedCode, message.lines().findFirst().orElseThrow());
        assertFalse(message.toLowerCase().contains("invalid byte sequence"), message);
        assertFalse(message.toLowerCase().contains("0xc0"), message);
    }

    private static String functionDefinition(JdbcTemplate jdbc, String function) {
        String definition = jdbc.queryForObject("""
                select lower(pg_catalog.pg_get_functiondef(procedure.oid))
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname=?
                """, String.class, function);
        assertNotNull(definition, function);
        return definition;
    }

    private static boolean canExecute(JdbcTemplate jdbc, String role, String function) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*)=1
                   and bool_and(pg_catalog.has_function_privilege(
                         ?, procedure.oid, 'EXECUTE'))
                  from pg_catalog.pg_proc procedure
                  join pg_catalog.pg_namespace namespace
                    on namespace.oid=procedure.pronamespace
                 where namespace.nspname='ingestion_quality'
                   and procedure.proname=?
                """, Boolean.class, role, function));
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(dataSource(required("scholarsense.audit.pg.user")));
    }

    private static DataSource dataSource(String username) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(required("scholarsense.audit.pg.url"));
        source.setUsername(username);
        source.setPassword(System.getProperty("scholarsense.audit.pg.password", ""));
        return source;
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " required");
        return value;
    }
}
