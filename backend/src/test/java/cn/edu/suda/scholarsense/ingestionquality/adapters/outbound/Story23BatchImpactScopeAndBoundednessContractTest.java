package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Impact-scope staging and no-job bounded persistence contract for Story 2.3. */
class Story23BatchImpactScopeAndBoundednessContractTest {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/ingestion-quality/"
                    + "V000014__ingestion-quality__data_batch_quality_snapshot_v1.sql");
    private static final Path POLICY = REPOSITORY.resolve(
            "contracts/ingestion-quality/batch-quality/"
                    + "executable-quality-policy-1.0.0.json");
    private static final Path STORE = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/"
                    + "adapters/outbound/JdbcDataBatchStore.java");
    private static final Path QUERY_STORE = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/"
                    + "adapters/outbound/JdbcQualitySnapshotQueryStore.java");

    @Test
    void receivingImpactScopeUsesAnExactRawKeyAndAWorkerOnlyBoundedCommand()
            throws Exception {
        String migration = migration();
        String table = section(
                migration,
                "create table ingestion_quality.iq_batch_quality_impact_scope (",
                "create table ingestion_quality.iq_batch_idempotency (");

        assertTrue(table.contains(
                "batch_id uuid not null references ingestion_quality.iq_data_batch(batch_id)"));
        assertTrue(table.contains("scope_code_utf8 bytea not null"),
                "scope identity must preserve exact UTF-8 bytes, including U+0000");
        assertTrue(table.contains("recorded_at timestamptz not null"));
        assertTrue(table.contains("sealed_at timestamptz"));
        assertFalse(table.contains("sealed_at timestamptz not null"),
                "receiving scopes are not frozen until seal");
        assertTrue(table.contains("primary key (batch_id, scope_code_utf8)"),
                "the raw UTF-8 scope value, not a digest or ordinal, is its identity");
        assertTrue(Pattern.compile(
                        "iq_utf8_scalar_count\\(scope_code_utf8\\)\\s+between\\s+1\\s+and\\s+64",
                        Pattern.DOTALL)
                .matcher(table).find(), "scope codes are 1..64 Unicode scalar values");

        String record = section(
                migration,
                "create function ingestion_quality.iq_record_batch_quality_impact_scope(",
                "create function ingestion_quality.iq_seal_data_batch(");
        assertTrue(record.contains("security definer"));
        assertTrue(record.contains("set search_path = pg_catalog"));
        assertTrue(record.contains("iq_require_exclusive_workload("));
        assertTrue(record.contains("scholarsense_ingestion_quality_quality_worker"));
        assertTrue(record.contains("for update"));
        assertTrue(record.contains("status <> 'receiving'")
                        || record.contains("status = 'receiving'"),
                "only receiving batches accept impact scopes");
        assertTrue(record.contains(
                "on conflict (batch_id, scope_code_utf8) do nothing"),
                "same raw scope must take the replay path");
        assertTrue(record.contains("requested_recorded_at"));
        assertTrue(record.contains("ingestion_quality_impact_scope_conflict"),
                "same key with different evidence must fail closed");
        assertTrue(record.contains("ingestion_quality_impact_scope_limit_exceeded"));
        assertTrue(Pattern.compile("count\\(\\*\\)\\s*>=\\s*64", Pattern.DOTALL)
                        .matcher(record).find(),
                "the 65th distinct scope must fail before insertion");
    }

    @Test
    void sealFreezesScopesAndEvaluationCopiesOnlyTheFrozenCanonicalOrder()
            throws Exception {
        String migration = migration();
        String seal = section(
                migration,
                "create function ingestion_quality.iq_seal_data_batch(",
                "create function ingestion_quality.iq_commit_batch_quality_evaluation(");
        assertTrue(seal.contains("update ingestion_quality.iq_batch_quality_impact_scope"));
        assertTrue(seal.contains("set sealed_at = requested_sealed_at"));
        assertTrue(seal.contains("sealed_at is null"));

        String commit = section(
                migration,
                "create function ingestion_quality.iq_commit_batch_quality_evaluation(",
                "create function ingestion_quality.iq_publish_data_batch(");
        String signature = commit.substring(0, commit.indexOf("returns boolean"));
        assertFalse(signature.contains("requested_impact_scope"),
                "evaluation may not receive caller-authored impact JSON");
        assertFalse(commit.contains("jsonb_array_elements_text"),
                "impact scopes must come from sealed rows, never caller JSON");
        assertTrue(commit.contains(
                "from ingestion_quality.iq_batch_quality_impact_scope"));
        assertTrue(Pattern.compile(
                        "sealed_at\\s*=\\s*iq_batch\\.sealed_at", Pattern.DOTALL)
                .matcher(commit).find(), "evaluation copies only the sealed scope set");
        assertTrue(Pattern.compile(
                        "order\\s+by\\s+[a-z_][a-z0-9_]*\\.scope_code_utf8",
                        Pattern.DOTALL)
                .matcher(commit).find(),
                "valid UTF-8 byte order is the frozen Unicode scalar/code-point order");
    }

    @Test
    void qmdpFixesEverySourceFormulaSetToAtMostFourteenRowsAndTwoOperands()
            throws Exception {
        Map<String, Integer> expectedCounts = qmdpMeasurementCounts();
        assertEquals(17, expectedCounts.size());
        expectedCounts.forEach((source, count) -> assertTrue(
                count >= 11 && count <= 14, source + " -> " + count));

        String migration = migration();
        String measurements = section(
                migration,
                "create table ingestion_quality.iq_batch_quality_measurement (",
                "create table ingestion_quality.iq_batch_quality_operand (");
        assertTrue(Pattern.compile(
                        "formula_ordinal\\s+integer\\s+not null\\s+check\\s*"
                                + "\\(formula_ordinal\\s+between\\s+0\\s+and\\s+13\\)",
                        Pattern.DOTALL)
                .matcher(measurements).find());
        assertTrue(measurements.contains("unique (batch_id, formula_ordinal)"));

        String record = section(
                migration,
                "create function ingestion_quality.iq_record_batch_quality_measurement(",
                "create function ingestion_quality.iq_record_batch_quality_impact_scope(");
        assertTrue(Pattern.compile(
                        "count\\(\\*\\)\\s+from\\s+jsonb_object_keys"
                                + "\\(requested_operands\\).*?>\\s*2",
                        Pattern.DOTALL)
                .matcher(record).find(),
                "one formula may persist at most two operands without relying on an "
                        + "unsupported jsonb_object_length helper");

        String seal = section(
                migration,
                "create function ingestion_quality.iq_seal_data_batch(",
                "create function ingestion_quality.iq_commit_batch_quality_evaluation(");
        assertTrue(seal.contains(
                "from ingestion_quality.iq_qmdp_ordered_definitions(iq_batch.source_id)"));
        assertTrue(seal.contains("iq_measurement_count <> iq_expected_measurement_count"));

        String sourceLookup = section(
                migration,
                "create function ingestion_quality.iq_qmdp_source(",
                "create function ingestion_quality.iq_qmdp_ordered_definitions(");
        assertTrue(sourceLookup.contains("if iq_count <> 1"));
        assertTrue(sourceLookup.contains("ingestion_quality_qmdp_source_invalid"),
                "an unknown source must fail closed before seal cardinality is evaluated");

        String projection = section(
                migration,
                "create function ingestion_quality.iq_qmdp_ordered_definitions(",
                "create function ingestion_quality.iq_expected_sealed_contract()");
        assertTrue(projection.contains("iq_qmdp_source(requested_source_id)"));
        assertEquals(17, expectedCounts.size());
        assertTrue(expectedCounts.values().stream().allMatch(count -> count >= 11 && count <= 14));
        assertEquals(219, expectedCounts.values().stream().mapToInt(Integer::intValue).sum(),
                "the embedded QMDP projection must preserve all approved source formulas");
    }

    @Test
    void factCardinalityIsAccumulatedOnceAndNeverRescannedAtPublish() throws Exception {
        String migration = migration();
        String batches = section(
                migration,
                "create table ingestion_quality.iq_data_batch (",
                "create unique index iq_data_batch_root_uk");
        assertTrue(Pattern.compile(
                        "normalized_fact_count\\s+bigint\\s+not null\\s+default\\s+0",
                        Pattern.DOTALL)
                .matcher(batches).find());
        assertTrue(batches.contains("normalized_fact_schema_version varchar"));
        assertTrue(batches.contains("normalized_fact_schema_digest char(71)"));
        assertTrue(Pattern.compile(
                        "normalized_fact_count\\s+between\\s+0\\s+and\\s+9007199254740991",
                        Pattern.DOTALL)
                .matcher(batches).find());

        String guard = section(
                migration,
                "create function ingestion_quality.iq_guard_data_batch_state()",
                "create trigger iq_data_batch_state_guard");
        assertTrue(Pattern.compile(
                        "old\\.status\\s*=\\s*'receiving'.*new\\.status\\s*=\\s*'receiving'",
                        Pattern.DOTALL)
                .matcher(guard).find());
        assertTrue(guard.contains(
                "new.normalized_fact_count = old.normalized_fact_count + 1"));
        assertTrue(guard.contains("normalized_fact_schema_version")
                        && guard.contains("normalized_fact_schema_digest"),
                "the only receiving-to-receiving mutation is the bounded fact accumulator");

        String append = section(
                migration,
                "create function ingestion_quality.iq_append_normalized_fact(",
                "create function ingestion_quality.iq_record_batch_quality_measurement(");
        assertTrue(append.contains(
                "normalized_fact_count = normalized_fact_count + 1"));
        assertTrue(append.contains("row_count"),
                "same-same fact replay must not increment the accumulator twice");

        String seal = section(
                migration,
                "create function ingestion_quality.iq_seal_data_batch(",
                "create function ingestion_quality.iq_commit_batch_quality_evaluation(");
        assertTrue(seal.contains("iq_batch.normalized_fact_count"));
        assertTrue(seal.contains("iq_batch.normalized_fact_schema_version"));
        assertTrue(seal.contains("iq_batch.normalized_fact_schema_digest"));
        assertFalse(seal.contains("iq_normalized_fact"),
                "seal must use only the O(1) fact accumulator, including schema evidence");

        String publish = section(
                migration,
                "create function ingestion_quality.iq_publish_data_batch(",
                "create function ingestion_quality.iq_execute_quality_snapshot_retention(");
        assertFalse(publish.contains("iq_normalized_fact"),
                "publish is one batch-state CAS; the view exposes facts atomically");
    }

    @Test
    void snapshotPanelUsesBoundedMaterializedKeysetsAndNeverScansFacts() throws Exception {
        String migration = migration();
        String indexes = section(
                migration,
                "create index iq_quality_snapshot_source_idx",
                "create table ingestion_quality.iq_quality_snapshot_metric (");
        assertTrue(indexes.contains(
                "iq_quality_snapshot_assessed_page_idx"));
        assertTrue(indexes.contains(
                "iq_quality_snapshot_result_page_idx"));
        assertTrue(indexes.contains(
                "iq_quality_snapshot_source_result_page_idx"));
        assertTrue(indexes.contains(
                "iq_quality_snapshot_token_digest_idx"),
                "owner resolution must use a direct indexed token digest, not hash every row");
        assertTrue(indexes.contains(
                "source_id, evaluated_at desc, snapshot_id desc"));

        String reads = section(
                migration,
                "create function ingestion_quality.iq_find_assessed_quality_snapshot_ids(",
                "create function ingestion_quality.iq_resolve_quality_snapshot_source(");
        assertTrue(reads.contains("requested_sort_direction = 'asc'"));
        assertTrue(reads.contains("requested_sort_direction = 'desc'"));
        assertTrue(reads.contains(
                "case when requested_sort_direction = 'asc' then snapshot.evaluated_at end asc"));
        assertTrue(reads.contains("limit requested_limit"));
        String resolver = section(
                migration,
                "create function ingestion_quality.iq_resolve_quality_snapshot_source(",
                "do $$");
        assertTrue(resolver.contains(
                "snapshot.snapshot_token_digest = requested_snapshot_token_digest"));
        assertFalse(resolver.contains(
                "sha256(convert_to(snapshot.snapshot_id::text"),
                "owner resolution must never perform a computed full-table digest scan");
        for (String forbidden : new String[] {
                "iq_normalized_fact", "iq_batch_quality_measurement",
                "iq_batch_quality_operand", "iq_published_normalized_fact"
        }) {
            assertFalse(reads.contains(forbidden),
                    "the panel may read only materialized snapshot tables: " + forbidden);
        }
    }

    @Test
    void snapshotListHydratesItsBoundedPageWithConstantBulkQueries() throws Exception {
        String source = Files.readString(QUERY_STORE);
        String list = section(source, "public List<QualitySnapshot> findAssessed(",
                "public Optional<QualitySnapshot> findById(");

        assertFalse(list.contains("findById("),
                "a page must not issue three detail queries for every snapshot id");
        assertTrue(list.contains("iq_find_assessed_quality_snapshot_page(?::uuid[])"));
        assertTrue(list.contains("iq_find_assessed_quality_snapshot_page_metrics(?::uuid[])"));
        assertTrue(list.contains(
                "iq_find_assessed_quality_snapshot_page_impact_scopes(?::uuid[])"));
    }

    @Test
    void measurementHydrationReadsTheFrozenScopeSetInCanonicalOrder() throws Exception {
        String source = Files.readString(STORE);
        int start = source.indexOf("public QualityMeasurement measure(");
        int end = source.indexOf("private List<DataBatch> batches(", start);
        assertTrue(start >= 0 && end > start);
        String measure = source.substring(start, end);

        assertTrue(measure.contains("ingestion_quality.iq_batch_quality_impact_scope"));
        assertTrue(measure.contains("impact.sealed_at=?")
                        || measure.contains("scope.sealed_at=?"),
                "hydration must bind impact scopes to the batch seal instant");
        assertTrue(Pattern.compile("order by\\s+(?:impact|scope)\\.scope_code_utf8")
                .matcher(measure).find());
        assertTrue(measure.contains("measured, impactScopes"));
        assertFalse(measure.contains("measured, List.of()"));
    }

    private static Map<String, Integer> qmdpMeasurementCounts() throws Exception {
        JsonNode policy = new ObjectMapper().readTree(Files.readAllBytes(POLICY));
        int commonCount = policy.required("commonMetrics").size();
        assertEquals(12, commonCount);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode source : policy.required("sources")) {
            int count = source.required("applicableCommonMetricIds").size()
                    + source.required("sourceGates").size();
            counts.put(source.required("sourceId").asText(), count);
        }
        return counts;
    }

    private static String migration() throws Exception {
        return Files.readString(MIGRATION).toLowerCase(Locale.ROOT);
    }

    private static String section(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        assertTrue(start >= 0, "missing SQL contract: " + startToken);
        int end = source.indexOf(endToken, start + startToken.length());
        assertTrue(end > start, "missing SQL contract boundary: " + endToken);
        return source.substring(start, end);
    }
}
