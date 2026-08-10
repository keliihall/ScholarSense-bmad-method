package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import cn.edu.suda.scholarsense.ingestionquality.application.VerifiedQualityContract;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL 18.4 QSHM byte/hash parity evidence. */
class QualitySnapshotCanonicalPostgreSqlIT {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path GOLDENS = REPOSITORY.resolve(
            "contracts/ingestion-quality/batch-quality/fixtures/valid/"
                    + "quality-snapshot-hash-vectors-1.0.0.json");
    private static final Path POLICY = REPOSITORY.resolve(
            "contracts/ingestion-quality/batch-quality/executable-quality-policy-1.0.0.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final VerifiedQualityContract CONTRACT =
            new FrozenExecutableQualityPolicyLoader(REPOSITORY).loadVerified();
    private static final QualitySnapshotCanonicalizer CANONICALIZER =
            new QualitySnapshotCanonicalizer(CONTRACT.policy(), CONTRACT.hashProfile());

    @Test
    void allControlledGoldensHaveJavaPostgreSqlCanonicalByteAndHashParity() throws Exception {
        JsonNode vectors = JSON.readTree(Files.readAllBytes(GOLDENS));
        assertEquals(3, vectors.required("cases").size());

        for (String databaseUrl : databaseUrls()) {
            try (Connection connection = connection(databaseUrl)) {
                assertEquals("180004", scalar(
                        connection, "select current_setting('server_version_num')"));
                installSqlFixture(connection);
                for (JsonNode golden : iterable(vectors.required("cases"))) {
                    QualitySnapshot snapshot = snapshotFromGolden(golden);
                    byte[] expected = HexFormat.of().parseHex(
                            golden.required("expectedCanonicalUtf8Hex").asText());
                    byte[] javaBytes = CANONICALIZER.canonicalUtf8(
                            CANONICALIZER.materialize(snapshot));
                    String javaHash = CANONICALIZER.immutableHash(snapshot);
                    SqlResult sql = sqlResult(connection, golden.required("snapshot"));

                    assertArrayEquals(expected, javaBytes, golden.required("caseId").asText());
                    assertEquals(golden.required("expectedImmutableHash").asText(), javaHash);
                    assertArrayEquals(
                            expected, sql.canonicalUtf8(), golden.required("caseId").asText());
                    assertEquals(
                            golden.required("expectedImmutableHash").asText(),
                            sql.immutableHash());
                    assertArrayEquals(
                            javaBytes, sql.canonicalUtf8(), golden.required("caseId").asText());
                    assertEquals(
                            javaHash, sql.immutableHash(), golden.required("caseId").asText());
                }
            }
        }
    }

    @Test
    void criticalIncludedExcludedTimeNullAndUnicodeMutationsStayInParity() throws Exception {
        JsonNode vectors = JSON.readTree(Files.readAllBytes(GOLDENS));
        ObjectNode baselineNode = (ObjectNode) stream(vectors.required("cases"))
                .filter(value -> value.required("caseId").asText()
                        .equals("root-policy-order-golden"))
                .findFirst().orElseThrow().required("snapshot");

        for (String databaseUrl : databaseUrls()) {
            try (Connection connection = connection(databaseUrl)) {
                installSqlFixture(connection);
                Parity baseline = parity(connection, baselineNode);
                String baselineCanonical = new String(
                        baseline.javaCanonicalUtf8(), StandardCharsets.UTF_8);
                assertTrue(baselineCanonical.contains("\"supersedesSnapshotId\":null"));
                assertTrue(baselineCanonical.contains("\"reasonCode\":null"));
                assertTrue(baselineCanonical.contains("\"valueBasisPoints\":null"));
                assertTrue(baselineCanonical.contains(
                        "\"cutoffAt\":\"2026-08-09T00:00:00.000000Z\""));
                assertTrue(baselineCanonical.contains("\"sourceOwnerRef\":\"校历数据 owner\""));
                assertTrue(baselineCanonical.contains("\"impactScopeCodes\":"
                        + "[\"CONTINUITY\",\"PRIMARY_KEY\",\"\uE000\",\"\uD800\uDC00\"]"));

                ObjectNode excluded = baselineNode.deepCopy();
                excluded.put("snapshotId", "019fe66d-7c00-7000-8000-000000000099");
                excluded.put("evaluatedAt", "2026-08-09T02:03:00.123457Z");
                excluded.put("traceId", "fedcba9876543210fedcba9876543210");
                excluded.put("aggregateVersion", 99);
                excluded.put("immutableHash", "sha256:" + "f".repeat(64));
                Parity excludedResult = parity(connection, excluded);
                assertArrayEquals(baseline.javaCanonicalUtf8(), excludedResult.javaCanonicalUtf8());
                assertEquals(baseline.javaHash(), excludedResult.javaHash());

                ObjectNode included = baselineNode.deepCopy();
                included.put("watermark", "included-field-mutation");
                Parity includedResult = parity(connection, included);
                assertNotEquals(baseline.javaHash(), includedResult.javaHash());

                ObjectNode adjacentMicrosecond = baselineNode.deepCopy();
                adjacentMicrosecond.put("cutoffAt", "2026-08-09T00:00:00.000001Z");
                Parity microsecondResult = parity(connection, adjacentMicrosecond);
                assertTrue(new String(microsecondResult.sqlCanonicalUtf8(), StandardCharsets.UTF_8)
                        .contains("\"cutoffAt\":\"2026-08-09T00:00:00.000001Z\""));
                assertNotEquals(baseline.javaHash(), microsecondResult.javaHash());
            }
        }
    }

    @Test
    void nulScalarsAndSqlShapeAndSafeIntegerBoundariesAreEnforced() throws Exception {
        JsonNode vectors = JSON.readTree(Files.readAllBytes(GOLDENS));
        ObjectNode baseline = (ObjectNode) stream(vectors.required("cases"))
                .filter(value -> value.required("caseId").asText()
                        .equals("root-policy-order-golden"))
                .findFirst().orElseThrow().required("snapshot");

        ObjectNode nulScalars = baseline.deepCopy();
        nulScalars.put("watermark", "\u0000");
        ArrayNode impacts = JSON.createArrayNode();
        impacts.add("PRIMARY_KEY");
        impacts.add("\uD800\uDC00");
        impacts.add("\u0000");
        impacts.add("CONTINUITY");
        impacts.add("\uE000");
        nulScalars.set("impactScopeCodes", impacts);
        ObjectNode literalBackslashU0000 = nulScalars.deepCopy();
        literalBackslashU0000.put("watermark", "\\u0000");

        ObjectNode duplicateNulImpacts = baseline.deepCopy();
        ArrayNode duplicateImpacts = JSON.createArrayNode();
        duplicateImpacts.add("\u0000");
        duplicateImpacts.add("CONTINUITY");
        duplicateImpacts.add("\u0000");
        duplicateNulImpacts.set("impactScopeCodes", duplicateImpacts);

        ObjectNode maximumSafe = baseline.deepCopy();
        ((ObjectNode) maximumSafe.required("metricResults").required(0))
                .put("numerator", 9_007_199_254_740_991L);
        ObjectNode aboveMaximumSafe = baseline.deepCopy();
        ((ObjectNode) aboveMaximumSafe.required("metricResults").required(0))
                .put("numerator", new BigInteger("9007199254740992"));
        ObjectNode negative = baseline.deepCopy();
        ((ObjectNode) negative.required("metricResults").required(0)).put("numerator", -1);
        ObjectNode missing = baseline.deepCopy();
        missing.remove("watermark");
        ObjectNode extra = baseline.deepCopy();
        extra.put("unexpectedTopLevel", "must-not-enter-material");
        ObjectNode loneSurrogate = baseline.deepCopy();
        loneSurrogate.put("watermark", Character.toString((char) 0xD800));
        assertThrows(IllegalArgumentException.class, () -> sqlTransport(loneSurrogate));

        for (String databaseUrl : databaseUrls()) {
            try (Connection connection = connection(databaseUrl)) {
                installSqlFixture(connection);

                Parity nulParity = parity(connection, nulScalars);
                String canonical = new String(nulParity.sqlCanonicalUtf8(), StandardCharsets.UTF_8);
                assertTrue(canonical.contains("\"watermark\":\"\\u0000\""));
                assertTrue(canonical.contains("\"impactScopeCodes\":[\"\\u0000\","
                        + "\"CONTINUITY\",\"PRIMARY_KEY\",\"\uE000\",\"\uD800\uDC00\"]"));

                Parity literalParity = parity(connection, literalBackslashU0000);
                String literalCanonical = new String(
                        literalParity.sqlCanonicalUtf8(), StandardCharsets.UTF_8);
                assertTrue(literalCanonical.contains("\"watermark\":\"\\\\u0000\""));
                assertTrue(!Arrays.equals(
                        nulParity.javaCanonicalUtf8(), literalParity.javaCanonicalUtf8()));
                assertTrue(!Arrays.equals(
                        nulParity.sqlCanonicalUtf8(), literalParity.sqlCanonicalUtf8()));
                assertNotEquals(nulParity.javaHash(), literalParity.javaHash());
                assertNotEquals(nulParity.sqlHash(), literalParity.sqlHash());

                assertThrows(IngestionQualityException.class,
                        () -> snapshotFromNode(duplicateNulImpacts));
                SQLException duplicateFailure = assertThrows(
                        SQLException.class, () -> sqlResult(connection, duplicateNulImpacts));
                assertTrue(duplicateFailure.getMessage()
                        .contains("QSHM impact scope codes must be unique"));

                String maximumCanonical = new String(
                        sqlResult(connection, maximumSafe).canonicalUtf8(), StandardCharsets.UTF_8);
                assertTrue(maximumCanonical.contains("\"numerator\":9007199254740991"));
                assertThrows(SQLException.class, () -> sqlResult(connection, aboveMaximumSafe));
                assertThrows(SQLException.class, () -> sqlResult(connection, negative));
                assertThrows(SQLException.class, () -> sqlResult(connection, missing));
                assertThrows(SQLException.class, () -> sqlResult(connection, extra));
                assertThrows(SQLException.class, () -> scalar(connection,
                        "select pg_temp.qshm_canonical("
                                + "'{\"$utf8\":\"eda080\"}'::jsonb)"));
                assertThrows(SQLException.class, () -> scalar(connection,
                        "select pg_temp.qshm_canonical("
                                + "'{\"$utf8\":\"c080\"}'::jsonb)"));
            }
        }
    }

    private static Parity parity(Connection connection, JsonNode snapshotNode) throws Exception {
        QualitySnapshot snapshot = snapshotFromNode(snapshotNode);
        byte[] javaBytes = CANONICALIZER.canonicalUtf8(CANONICALIZER.materialize(snapshot));
        String javaHash = CANONICALIZER.immutableHash(snapshot);
        SqlResult sql = sqlResult(connection, snapshotNode);
        assertArrayEquals(javaBytes, sql.canonicalUtf8());
        assertEquals(javaHash, sql.immutableHash());
        return new Parity(javaBytes, javaHash, sql.canonicalUtf8(), sql.immutableHash());
    }

    private static void installSqlFixture(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                create or replace function pg_temp.qshm_bytes(input_value jsonb)
                returns bytea
                language plpgsql
                immutable
                strict
                as $function$
                declare
                  key_count integer;
                  hex_value text;
                begin
                  if jsonb_typeof(input_value) is distinct from 'object' then
                    raise exception 'QSHM UTF-8 transport value must be an object';
                  end if;
                  select count(*) into key_count from jsonb_object_keys(input_value);
                  if key_count <> 1
                     or not input_value ? '$utf8'
                     or jsonb_typeof(input_value->'$utf8') is distinct from 'string' then
                    raise exception 'QSHM UTF-8 transport wrapper is not exact';
                  end if;
                  hex_value := input_value->>'$utf8';
                  if hex_value !~ '^([0-9a-f]{2})*$' then
                    raise exception 'QSHM UTF-8 transport payload is not lowercase hex';
                  end if;
                  return decode(hex_value, 'hex');
                end
                $function$;

                create or replace function pg_temp.qshm_utf8_text(input_value jsonb)
                returns text
                language plpgsql
                immutable
                strict
                as $function$
                begin
                  return convert_from(pg_temp.qshm_bytes(input_value), 'UTF8');
                end
                $function$;

                create or replace function pg_temp.qshm_quote_utf8(input_value bytea)
                returns text
                language plpgsql
                immutable
                strict
                as $function$
                declare
                  result_value text := '"';
                  byte_offset integer := 0;
                  byte_count integer := octet_length(input_value);
                  first_byte integer;
                  second_byte integer;
                  third_byte integer;
                  fourth_byte integer;
                  scalar_width integer;
                begin
                  while byte_offset < byte_count loop
                    first_byte := get_byte(input_value, byte_offset);
                    if first_byte < 128 then
                      if first_byte = 34 then
                        result_value := result_value || chr(92) || '"';
                      elsif first_byte = 92 then
                        result_value := result_value || chr(92) || chr(92);
                      elsif first_byte = 8 then
                        result_value := result_value || chr(92) || 'b';
                      elsif first_byte = 9 then
                        result_value := result_value || chr(92) || 't';
                      elsif first_byte = 10 then
                        result_value := result_value || chr(92) || 'n';
                      elsif first_byte = 12 then
                        result_value := result_value || chr(92) || 'f';
                      elsif first_byte = 13 then
                        result_value := result_value || chr(92) || 'r';
                      elsif first_byte < 32 then
                        result_value := result_value || chr(92) || 'u00'
                          || lpad(to_hex(first_byte), 2, '0');
                      else
                        result_value := result_value || chr(first_byte);
                      end if;
                      byte_offset := byte_offset + 1;
                      continue;
                    end if;

                    if first_byte between 194 and 223 then
                      scalar_width := 2;
                    elsif first_byte between 224 and 239 then
                      scalar_width := 3;
                    elsif first_byte between 240 and 244 then
                      scalar_width := 4;
                    else
                      raise exception 'QSHM string contains invalid UTF-8';
                    end if;
                    if byte_offset + scalar_width > byte_count then
                      raise exception 'QSHM string contains truncated UTF-8';
                    end if;

                    second_byte := get_byte(input_value, byte_offset + 1);
                    if second_byte not between 128 and 191 then
                      raise exception 'QSHM string contains invalid UTF-8 continuation';
                    end if;
                    if scalar_width >= 3 then
                      third_byte := get_byte(input_value, byte_offset + 2);
                      if third_byte not between 128 and 191 then
                        raise exception 'QSHM string contains invalid UTF-8 continuation';
                      end if;
                    end if;
                    if scalar_width = 4 then
                      fourth_byte := get_byte(input_value, byte_offset + 3);
                      if fourth_byte not between 128 and 191 then
                        raise exception 'QSHM string contains invalid UTF-8 continuation';
                      end if;
                    end if;
                    if (first_byte = 224 and second_byte < 160)
                       or (first_byte = 237 and second_byte > 159)
                       or (first_byte = 240 and second_byte < 144)
                       or (first_byte = 244 and second_byte > 143) then
                      raise exception 'QSHM string contains a non-scalar or non-shortest UTF-8 value';
                    end if;

                    result_value := result_value || convert_from(
                        substring(input_value from byte_offset + 1 for scalar_width), 'UTF8');
                    byte_offset := byte_offset + scalar_width;
                  end loop;
                  return result_value || '"';
                end
                $function$;

                create or replace function pg_temp.qshm_instant(input_value text)
                returns text
                language plpgsql
                immutable
                strict
                as $function$
                declare
                  parsed_value timestamptz;
                begin
                  if input_value !~
                     '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$' then
                    raise exception 'QSHM instant is not an offset timestamp at microsecond precision';
                  end if;
                  parsed_value := input_value::timestamptz;
                  if extract(year from parsed_value at time zone 'UTC') not between 1 and 9999 then
                    raise exception 'QSHM instant year is outside 0001..9999';
                  end if;
                  return to_char(
                      parsed_value at time zone 'UTC',
                      'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
                end
                $function$;

                create or replace function pg_temp.qshm_canonical(input_value jsonb)
                returns text
                language plpgsql
                immutable
                strict
                as $function$
                declare
                  value_type text := jsonb_typeof(input_value);
                  result_value text;
                  number_value text;
                begin
                  case value_type
                    when 'object' then
                      if input_value ? '$utf8' then
                        return pg_temp.qshm_quote_utf8(pg_temp.qshm_bytes(input_value));
                      end if;
                      select '{' || coalesce(string_agg(
                          to_json(object_key)::text || ':'
                            || pg_temp.qshm_canonical(object_value),
                          ',' order by object_key collate "C"), '') || '}'
                        into result_value
                        from jsonb_each(input_value) entry(object_key, object_value);
                      return result_value;
                    when 'array' then
                      select '[' || coalesce(string_agg(
                          pg_temp.qshm_canonical(array_value),
                          ',' order by array_index), '') || ']'
                        into result_value
                        from jsonb_array_elements(input_value)
                          with ordinality entry(array_value, array_index);
                      return result_value;
                    when 'string' then
                      return pg_temp.qshm_quote_utf8(
                          convert_to(input_value #>> '{}', 'UTF8'));
                    when 'number' then
                      number_value := input_value::text;
                      if number_value !~ '^(0|[1-9][0-9]*)$'
                         or number_value::numeric > 9007199254740991 then
                        raise exception 'QSHM numbers must be non-negative safe integers';
                      end if;
                      return number_value;
                    when 'boolean' then
                      return input_value::text;
                    when 'null' then
                      return 'null';
                    else
                      raise exception 'unsupported QSHM JSON type';
                  end case;
                end
                $function$;

                create or replace function pg_temp.qshm_material(
                    snapshot_value jsonb,
                    policy_value jsonb,
                    domain_tag_value text)
                returns jsonb
                language plpgsql
                immutable
                strict
                as $function$
                declare
                  source_value jsonb;
                  source_count integer;
                  ordered_formula_ids jsonb;
                  ordered_metric_results jsonb;
                  ordered_impact_codes jsonb;
                  expected_metric_count integer;
                  input_metric_count integer;
                  distinct_metric_count integer;
                  matched_metric_count integer;
                  impact_count integer;
                  distinct_impact_count integer;
                  material_value jsonb;
                begin
                  if jsonb_typeof(snapshot_value) is distinct from 'object'
                     or jsonb_typeof(policy_value) is distinct from 'object' then
                    raise exception 'QSHM inputs must be JSON objects';
                  end if;
                  if (select count(*) from jsonb_object_keys(snapshot_value)) <> 30
                     or exists (
                       select 1 from jsonb_object_keys(snapshot_value) snapshot_key
                        where snapshot_key <> all (array[
                          'hashProfileVersion','hashProfileDigest','snapshotId','batchId',
                          'sourceId','assessedBatchStatus','overallResult','observationWindow',
                          'cutoffAt','evaluatedAt','watermark','metricResults',
                          'impactScopeCodes','sourceOwnerRef','approvalRef','effectiveAt',
                          'retentionScheduleVersion','qualityMetricDecisionProfileVersion',
                          'qualityMetricDecisionProfileDigest','qualityGateVersion',
                          'qualityGateDigest','canonicalizationProfile','manifestDigest',
                          'sourceSchemaVersion','sourceSchemaDigest','immutableHash','traceId',
                          'lineageId','supersedesSnapshotId','aggregateVersion']::text[])) then
                    raise exception 'QSHM snapshot field set is not exact';
                  end if;
                  if jsonb_typeof(snapshot_value->'observationWindow') is distinct from 'object'
                     or (select count(*) from jsonb_object_keys(
                           snapshot_value->'observationWindow')) <> 2
                     or exists (
                       select 1 from jsonb_object_keys(
                           snapshot_value->'observationWindow') window_key
                        where window_key <> all (array['startAt','endAt']::text[])) then
                    raise exception 'QSHM observation window field set is not exact';
                  end if;
                  if jsonb_typeof(snapshot_value->'metricResults') is distinct from 'array'
                     or exists (
                       select 1
                         from jsonb_array_elements(snapshot_value->'metricResults') metric(metric_value)
                        where jsonb_typeof(metric_value) is distinct from 'object'
                           or (select count(*) from jsonb_object_keys(metric_value)) <> 14
                           or exists (
                             select 1 from jsonb_object_keys(metric_value) metric_key
                              where metric_key <> all (array[
                                'metricId','formulaId','formulaVersion','result','applicable',
                                'numerator','denominator','valueBasisPoints','unit','operator',
                                'thresholdNumerator','thresholdDenominator','boundary',
                                'reasonCode']::text[]))) then
                    raise exception 'QSHM metric result field set is not exact';
                  end if;
                  if jsonb_typeof(snapshot_value->'impactScopeCodes') is distinct from 'array' then
                    raise exception 'QSHM impact scope codes must be an array';
                  end if;

                  select count(*), min(source_item::text)::jsonb
                    into source_count, source_value
                    from jsonb_array_elements(policy_value->'sources') source(source_item)
                   where source_item->>'sourceId' =
                         pg_temp.qshm_utf8_text(snapshot_value->'sourceId');
                  if source_count <> 1 then
                    raise exception 'QSHM source binding is not unique';
                  end if;

                  with definitions as (
                    select 0 as scope_order, common_index as definition_order,
                           common_value->>'formulaId' as formula_id
                      from jsonb_array_elements(policy_value->'commonMetrics')
                        with ordinality common_metric(common_value, common_index)
                     where exists (
                       select 1
                         from jsonb_array_elements_text(
                              source_value->'applicableCommonMetricIds') applicable(metric_id)
                        where metric_id = common_value->>'metricId')
                    union all
                    select 1, gate_index, gate_value->>'formulaId'
                      from jsonb_array_elements(source_value->'sourceGates')
                        with ordinality source_gate(gate_value, gate_index)
                  )
                  select jsonb_agg(to_jsonb(formula_id)
                                   order by scope_order, definition_order)
                    into ordered_formula_ids
                    from definitions;
                  expected_metric_count := jsonb_array_length(ordered_formula_ids);

                  select count(*), count(distinct
                         pg_temp.qshm_utf8_text(metric_value->'formulaId'))
                    into input_metric_count, distinct_metric_count
                    from jsonb_array_elements(snapshot_value->'metricResults') metric(metric_value);
                  select count(*)
                    into matched_metric_count
                    from jsonb_array_elements(snapshot_value->'metricResults') metric(metric_value)
                   where pg_temp.qshm_utf8_text(metric_value->'formulaId') in (
                     select jsonb_array_elements_text(ordered_formula_ids));
                  if input_metric_count <> expected_metric_count
                     or distinct_metric_count <> expected_metric_count
                     or matched_metric_count <> expected_metric_count then
                    raise exception 'QSHM metric formula cardinality/order binding failed';
                  end if;

                  select jsonb_agg(metric_value order by formula_index)
                    into ordered_metric_results
                    from jsonb_array_elements_text(ordered_formula_ids)
                      with ordinality formula(formula_id, formula_index)
                    join jsonb_array_elements(snapshot_value->'metricResults') metric(metric_value)
                      on pg_temp.qshm_utf8_text(metric_value->'formulaId') = formula_id;

                  select count(*), count(distinct pg_temp.qshm_bytes(impact_value))
                    into impact_count, distinct_impact_count
                    from jsonb_array_elements(snapshot_value->'impactScopeCodes')
                      impact(impact_value);
                  if impact_count <> distinct_impact_count then
                    raise exception 'QSHM impact scope codes must be unique';
                  end if;
                  select coalesce(jsonb_agg(impact_value
                                           order by pg_temp.qshm_bytes(impact_value)), '[]'::jsonb)
                    into ordered_impact_codes
                    from jsonb_array_elements(snapshot_value->'impactScopeCodes')
                      impact(impact_value);

                  material_value := snapshot_value - array[
                    'snapshotId', 'evaluatedAt', 'traceId',
                    'aggregateVersion', 'immutableHash']::text[];
                  material_value := material_value || jsonb_build_object(
                      'domainTag', domain_tag_value,
                      'metricResults', ordered_metric_results,
                      'impactScopeCodes', ordered_impact_codes,
                      'cutoffAt', pg_temp.qshm_instant(
                          pg_temp.qshm_utf8_text(snapshot_value->'cutoffAt')),
                      'effectiveAt', pg_temp.qshm_instant(
                          pg_temp.qshm_utf8_text(snapshot_value->'effectiveAt')),
                      'observationWindow', jsonb_build_object(
                        'startAt', pg_temp.qshm_instant(
                            pg_temp.qshm_utf8_text(
                                snapshot_value#>'{observationWindow,startAt}')),
                        'endAt', pg_temp.qshm_instant(
                            pg_temp.qshm_utf8_text(
                                snapshot_value#>'{observationWindow,endAt}'))));
                  if (select count(*) from jsonb_object_keys(material_value)) <> 26
                     or exists (
                       select 1 from jsonb_object_keys(material_value) material_key
                        where material_key <> all (array[
                          'domainTag','hashProfileVersion','hashProfileDigest','batchId','sourceId',
                          'assessedBatchStatus','overallResult','observationWindow','cutoffAt',
                          'watermark','metricResults','impactScopeCodes','sourceOwnerRef',
                          'approvalRef','effectiveAt','retentionScheduleVersion',
                          'qualityMetricDecisionProfileVersion','qualityMetricDecisionProfileDigest',
                          'qualityGateVersion','qualityGateDigest','canonicalizationProfile',
                          'manifestDigest','sourceSchemaVersion','sourceSchemaDigest','lineageId',
                          'supersedesSnapshotId']::text[])) then
                    raise exception 'QSHM material field set is not exact';
                  end if;
                  return material_value;
                end
                $function$;
                """)) {
            statement.execute();
        }
    }

    private static SqlResult sqlResult(Connection connection, JsonNode snapshot) throws Exception {
        String policy = Files.readString(POLICY);
        try (PreparedStatement statement = connection.prepareStatement("""
                with material(value) as (
                  select pg_temp.qshm_material(?::jsonb, ?::jsonb, ?)
                ), canonical(value) as (
                  select pg_temp.qshm_canonical(value) from material
                )
                select convert_to(value, 'UTF8'),
                       'sha256:' || encode(sha256(convert_to(value, 'UTF8')), 'hex')
                  from canonical
                """)) {
            statement.setString(1, sqlTransport(snapshot).toString());
            statement.setString(2, policy);
            statement.setString(3, CONTRACT.hashProfile().domainTag());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new SqlResult(result.getBytes(1), result.getString(2));
            }
        }
    }

    /**
     * Lossless JDBC transport only: SQL still builds the material, quotes every scalar and hashes
     * the result. Hex-wrapping raw UTF-8 keeps U+0000 out of PostgreSQL text/jsonb values.
     */
    private static JsonNode sqlTransport(JsonNode input) {
        if (input.isTextual()) {
            return JSON.createObjectNode().put(
                    "$utf8",
                    HexFormat.of().formatHex(scalarUtf8(input.asText())));
        }
        if (input.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            input.properties().forEach(entry -> result.set(
                    entry.getKey(), sqlTransport(entry.getValue())));
            return result;
        }
        if (input.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            input.forEach(value -> result.add(sqlTransport(value)));
            return result;
        }
        return input.deepCopy();
    }

    private static byte[] scalarUtf8(String value) {
        for (int offset = 0; offset < value.length(); offset++) {
            char current = value.charAt(offset);
            if (Character.isHighSurrogate(current)) {
                if (++offset >= value.length()
                        || !Character.isLowSurrogate(value.charAt(offset))) {
                    throw new IllegalArgumentException(
                            "QSHM transport accepts Unicode scalar values only");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                        "QSHM transport accepts Unicode scalar values only");
            }
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> databaseUrls() {
        return List.of(
                required("scholarsense.audit.pg.url"),
                required("scholarsense.audit.pg.upgrade-url"));
    }

    private static Connection connection(String databaseUrl) throws Exception {
        return DriverManager.getConnection(
                databaseUrl,
                required("scholarsense.audit.pg.user"),
                System.getProperty("scholarsense.audit.pg.password", ""));
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " is required; use scripts/run_audit_postgresql_tests.sh");
        }
        return value;
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        }
    }

    private static QualitySnapshot snapshotFromGolden(JsonNode golden) {
        return snapshot(
                golden.required("snapshot"), golden.required("expectedImmutableHash").asText());
    }

    private static QualitySnapshot snapshotFromNode(JsonNode node) {
        String storedHash = text(node, "immutableHash");
        return snapshot(node, storedHash.equals("sha256:" + "0".repeat(64))
                ? "sha256:" + "1".repeat(64)
                : storedHash);
    }

    private static QualitySnapshot snapshot(JsonNode node, String immutableHash) {
        JsonNode window = node.required("observationWindow");
        return new QualitySnapshot(
                CONTRACT.hashProfile().domainTag(),
                text(node, "hashProfileVersion"),
                text(node, "hashProfileDigest"),
                uuid(text(node, "batchId")),
                text(node, "sourceId"),
                dataBatchStatus(text(node, "assessedBatchStatus")),
                overallResult(text(node, "overallResult")),
                new BatchObservationWindow(
                        instant(window, "startAt"), instant(window, "endAt")),
                instant(node, "cutoffAt"),
                text(node, "watermark"),
                metricResults(node.required("metricResults")),
                stream(node.required("impactScopeCodes")).map(JsonNode::asText).toList(),
                text(node, "sourceOwnerRef"),
                text(node, "approvalRef"),
                instant(node, "effectiveAt"),
                text(node, "retentionScheduleVersion"),
                text(node, "qualityMetricDecisionProfileVersion"),
                text(node, "qualityMetricDecisionProfileDigest"),
                text(node, "qualityGateVersion"),
                text(node, "qualityGateDigest"),
                text(node, "canonicalizationProfile"),
                text(node, "manifestDigest"),
                text(node, "sourceSchemaVersion"),
                text(node, "sourceSchemaDigest"),
                uuid(text(node, "lineageId")),
                nullableUuid(node.get("supersedesSnapshotId")),
                uuid(text(node, "snapshotId")),
                instant(node, "evaluatedAt"),
                text(node, "traceId"),
                node.required("aggregateVersion").longValue(),
                immutableHash);
    }

    private static List<QualityMetricResult> metricResults(JsonNode array) {
        return stream(array).map(node -> new QualityMetricResult(
                text(node, "metricId"),
                text(node, "formulaId"),
                text(node, "formulaVersion"),
                metricStatus(text(node, "result")),
                node.required("applicable").asBoolean(),
                integer(node, "numerator"),
                integer(node, "denominator"),
                nullableInteger(node.get("valueBasisPoints")),
                QualityMetricUnit.fromWire(text(node, "unit")),
                QualityMetricOperator.fromWire(text(node, "operator")),
                integer(node, "thresholdNumerator"),
                integer(node, "thresholdDenominator"),
                QualityMetricBoundary.fromWire(text(node, "boundary")),
                nullableText(node.get("reasonCode")))).toList();
    }

    private static Iterable<JsonNode> iterable(JsonNode array) {
        return array::iterator;
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }

    private static String text(JsonNode parent, String field) {
        return parent.required(field).asText();
    }

    private static Instant instant(JsonNode parent, String field) {
        return Instant.parse(text(parent, field));
    }

    private static BigInteger integer(JsonNode parent, String field) {
        return parent.required(field).bigIntegerValue();
    }

    private static BigInteger nullableInteger(JsonNode node) {
        return node == null || node.isNull() ? null : node.bigIntegerValue();
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static UUID nullableUuid(JsonNode node) {
        return node == null || node.isNull() ? null : uuid(node.asText());
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static DataBatchStatus dataBatchStatus(String wireValue) {
        return Arrays.stream(DataBatchStatus.values())
                .filter(value -> value.wireValue().equals(wireValue))
                .findFirst().orElseThrow();
    }

    private static QualityOverallResult overallResult(String wireValue) {
        return Arrays.stream(QualityOverallResult.values())
                .filter(value -> value.wireValue().equals(wireValue))
                .findFirst().orElseThrow();
    }

    private static QualityMetricResultStatus metricStatus(String wireValue) {
        return Arrays.stream(QualityMetricResultStatus.values())
                .filter(value -> value.wireValue().equals(wireValue))
                .findFirst().orElseThrow();
    }

    private record SqlResult(byte[] canonicalUtf8, String immutableHash) {}

    private record Parity(
            byte[] javaCanonicalUtf8,
            String javaHash,
            byte[] sqlCanonicalUtf8,
            String sqlHash) {}
}
