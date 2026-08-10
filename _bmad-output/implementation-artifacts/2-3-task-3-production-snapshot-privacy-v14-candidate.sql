-- Story 2.3 Task 3 production snapshot privacy boundary.
-- Review trace only: do not execute as a standalone migration.
-- Integrated into V000014 only after the outbox work was explicitly released.

-- Insert after iq_qmdp_ordered_definitions, before production staging commands.
create function ingestion_quality.iq_require_production_watermark(
    requested_source_id varchar,
    requested_watermark_utf8 bytea)
returns void
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
declare
    iq_value text;
    iq_prefix text;
    iq_date_text text;
    iq_date date;
    iq_year integer;
begin
    if requested_source_id is null
       or requested_watermark_utf8 is null
       or ingestion_quality.iq_utf8_scalar_count(requested_watermark_utf8)
            not between 1 and 512
       or position(decode('00', 'hex') in requested_watermark_utf8) > 0 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    begin
        iq_value := convert_from(requested_watermark_utf8, 'UTF8');
    exception
        when character_not_in_repertoire then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end;
    if iq_value ~ '^sha256:[0-9a-f]{64}$' then
        return;
    end if;
    iq_prefix := lower(requested_source_id) || '@';
    if not starts_with(iq_value, iq_prefix) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    iq_date_text := substring(iq_value from char_length(iq_prefix) + 1);
    if iq_date_text !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    iq_year := substring(iq_date_text from 1 for 4)::integer;
    if iq_year not between 1 and 9999 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    begin
        iq_date := make_date(
            iq_year,
            substring(iq_date_text from 6 for 2)::integer,
            substring(iq_date_text from 9 for 2)::integer);
    exception
        when datetime_field_overflow then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end;
    if to_char(iq_date, 'YYYY-MM-DD') <> iq_date_text then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
end
$$;

create function ingestion_quality.iq_require_production_impact_scope(
    requested_source_id varchar,
    requested_scope_code_utf8 bytea)
returns void
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
declare
    iq_value text;
begin
    if requested_source_id is null
       or requested_scope_code_utf8 is null
       or ingestion_quality.iq_utf8_scalar_count(requested_scope_code_utf8)
            not between 1 and 64
       or position(decode('00', 'hex') in requested_scope_code_utf8) > 0 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_IMPACT_SCOPE_INVALID';
    end if;
    begin
        iq_value := convert_from(requested_scope_code_utf8, 'UTF8');
    exception
        when character_not_in_repertoire then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_IMPACT_SCOPE_INVALID';
    end;
    if iq_value !~ '^[A-Z][A-Z0-9_]{1,63}$'
       or not exists (
         select 1
           from ingestion_quality.iq_qmdp_ordered_definitions(requested_source_id) expected
          where expected.definition ->> 'metricId' = iq_value) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_IMPACT_SCOPE_INVALID';
    end if;
end
$$;

-- In iq_record_batch_quality_impact_scope, immediately after the locked batch
-- state check and before the cardinality check/insert:
--
--     perform ingestion_quality.iq_require_production_impact_scope(
--         iq_batch.source_id, requested_scope_code_utf8);

-- In iq_seal_data_batch, immediately after the locked batch evidence check and
-- before contract/QMDP validation or the first batch update:
--
--     perform ingestion_quality.iq_require_production_watermark(
--         iq_batch.source_id, requested_watermark_utf8);

-- Add both names to the existing owner/revoke dynamic function list. Do not add
-- either helper to a workload EXECUTE grant:
--
--     'iq_require_production_watermark',
--     'iq_require_production_impact_scope',
