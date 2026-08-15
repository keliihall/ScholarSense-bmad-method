-- Reader-first additive event cutover. The frozen V1 validators are retained
-- under explicit names; their published identities become dual-read wrappers.

alter function ingestion_quality.iq_validate_batch_business_payload(
    uuid, uuid, uuid, uuid, bigint, varchar, varchar, char,
    timestamptz, bytea, char)
    rename to iq_validate_batch_business_payload_v1;

alter function ingestion_quality.iq_validate_batch_published_payload(
    uuid, uuid, uuid, uuid, bigint, varchar, varchar, char,
    timestamptz, bytea, char)
    rename to iq_validate_batch_published_payload_v1;

create function ingestion_quality.iq_data_batch_event_v2_as_v1(
    requested_payload_utf8 bytea,
    requested_payload_digest char(64),
    requested_trace_id char(32),
    requested_v2_type varchar,
    requested_v2_schema varchar,
    requested_v1_type varchar,
    requested_v1_schema varchar)
returns bytea
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_payload_text text;
    iq_payload jsonb;
    iq_traceparent varchar;
begin
    if requested_payload_utf8 is null
       or octet_length(requested_payload_utf8) not between 2 and 65536
       or requested_payload_digest is null
       or requested_payload_digest !~ '^[0-9a-f]{64}$'
       or encode(sha256(requested_payload_utf8), 'hex') <> requested_payload_digest then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    begin
        iq_payload_text := convert_from(requested_payload_utf8, 'UTF8');
        if (iq_payload_text is json object with unique keys) is not true then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
        end if;
        iq_payload := iq_payload_text::jsonb;
        if convert_to(ingestion_quality.iq_json_canonical(iq_payload), 'UTF8')
                <> requested_payload_utf8 then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_NOT_CANONICAL';
        end if;
    exception when others then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end;
    iq_traceparent := iq_payload ->> 'traceparent';
    if iq_payload ->> 'type' <> requested_v2_type
       or iq_payload #>> '{data,schemaVersion}' <> requested_v2_schema
       or iq_payload #>> '{data,traceId}' <> requested_trace_id
       or iq_traceparent !~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-(00|01)$'
       or substring(iq_traceparent from 4 for 32) <> requested_trace_id
       or substring(iq_traceparent from 4 for 32) ~ '^0{32}$'
       or substring(iq_traceparent from 37 for 16) ~ '^0{16}$' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    iq_payload := jsonb_set(iq_payload, '{type}', to_jsonb(requested_v1_type));
    iq_payload := jsonb_set(
        iq_payload, '{data,schemaVersion}', to_jsonb(requested_v1_schema));
    iq_payload := jsonb_set(
        iq_payload,
        '{traceparent}',
        to_jsonb('00-' || requested_trace_id || '-' || (case
          when substring(requested_trace_id from 1 for 16) ~ '^0{16}$'
            then substring(requested_trace_id from 17 for 16)
          else substring(requested_trace_id from 1 for 16)
        end) || '-01'));
    return convert_to(ingestion_quality.iq_json_canonical(iq_payload), 'UTF8');
end
$$;

create function ingestion_quality.iq_validate_batch_business_payload(
    requested_command_id uuid,
    requested_causation_id uuid,
    requested_event_id uuid,
    requested_batch_id uuid,
    requested_aggregate_version bigint,
    requested_event_type varchar,
    requested_schema_version varchar,
    requested_trace_id char(32),
    requested_occurred_at timestamptz,
    requested_payload_utf8 bytea,
    requested_payload_digest char(64))
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_v1_payload bytea;
begin
    if requested_event_type =
         'scholarsense.ingestion-quality.data-batch.quality-assessed.v2'
       and requested_schema_version = 'DATA-BATCH-QUALITY-ASSESSED-2.0.0' then
        iq_v1_payload := ingestion_quality.iq_data_batch_event_v2_as_v1(
            requested_payload_utf8, requested_payload_digest, requested_trace_id,
            requested_event_type, requested_schema_version,
            'scholarsense.ingestion-quality.data-batch.quality-assessed.v1',
            'DATA-BATCH-QUALITY-ASSESSED-1.0.0');
        perform ingestion_quality.iq_validate_batch_business_payload_v1(
            requested_command_id, requested_causation_id, requested_event_id,
            requested_batch_id, requested_aggregate_version,
            'scholarsense.ingestion-quality.data-batch.quality-assessed.v1',
            'DATA-BATCH-QUALITY-ASSESSED-1.0.0', requested_trace_id,
            requested_occurred_at, iq_v1_payload,
            encode(sha256(iq_v1_payload), 'hex'));
        return;
    end if;
    perform ingestion_quality.iq_validate_batch_business_payload_v1(
        requested_command_id, requested_causation_id, requested_event_id,
        requested_batch_id, requested_aggregate_version, requested_event_type,
        requested_schema_version, requested_trace_id, requested_occurred_at,
        requested_payload_utf8, requested_payload_digest);
end
$$;

create function ingestion_quality.iq_validate_batch_published_payload(
    requested_command_id uuid,
    requested_causation_id uuid,
    requested_event_id uuid,
    requested_batch_id uuid,
    requested_aggregate_version bigint,
    requested_event_type varchar,
    requested_schema_version varchar,
    requested_trace_id char(32),
    requested_occurred_at timestamptz,
    requested_payload_utf8 bytea,
    requested_payload_digest char(64))
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_v1_payload bytea;
begin
    if requested_event_type =
         'scholarsense.ingestion-quality.data-batch.published.v2'
       and requested_schema_version = 'DATA-BATCH-PUBLISHED-2.0.0' then
        iq_v1_payload := ingestion_quality.iq_data_batch_event_v2_as_v1(
            requested_payload_utf8, requested_payload_digest, requested_trace_id,
            requested_event_type, requested_schema_version,
            'scholarsense.ingestion-quality.data-batch.published.v1',
            'DATA-BATCH-PUBLISHED-1.0.0');
        perform ingestion_quality.iq_validate_batch_published_payload_v1(
            requested_command_id, requested_causation_id, requested_event_id,
            requested_batch_id, requested_aggregate_version,
            'scholarsense.ingestion-quality.data-batch.published.v1',
            'DATA-BATCH-PUBLISHED-1.0.0', requested_trace_id,
            requested_occurred_at, iq_v1_payload,
            encode(sha256(iq_v1_payload), 'hex'));
        return;
    end if;
    perform ingestion_quality.iq_validate_batch_published_payload_v1(
        requested_command_id, requested_causation_id, requested_event_id,
        requested_batch_id, requested_aggregate_version, requested_event_type,
        requested_schema_version, requested_trace_id, requested_occurred_at,
        requested_payload_utf8, requested_payload_digest);
end
$$;

revoke all on function ingestion_quality.iq_data_batch_event_v2_as_v1(
    bytea, char, char, varchar, varchar, varchar, varchar)
    from public;
revoke all on function ingestion_quality.iq_validate_batch_business_payload(
    uuid, uuid, uuid, uuid, bigint, varchar, varchar, char,
    timestamptz, bytea, char)
    from public;
revoke all on function ingestion_quality.iq_validate_batch_published_payload(
    uuid, uuid, uuid, uuid, bigint, varchar, varchar, char,
    timestamptz, bytea, char)
    from public;

alter function ingestion_quality.iq_data_batch_event_v2_as_v1(
    bytea, char, char, varchar, varchar, varchar, varchar)
    owner to scholarsense_ingestion_quality_batch_owner;
alter function ingestion_quality.iq_validate_batch_business_payload(
    uuid, uuid, uuid, uuid, bigint, varchar, varchar, char,
    timestamptz, bytea, char)
    owner to scholarsense_ingestion_quality_batch_owner;
alter function ingestion_quality.iq_validate_batch_published_payload(
    uuid, uuid, uuid, uuid, bigint, varchar, varchar, char,
    timestamptz, bytea, char)
    owner to scholarsense_ingestion_quality_batch_owner;
