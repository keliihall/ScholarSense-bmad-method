-- Additive successor: preserve the V1 enqueue function and legacy rows while new
-- producers persist the complete W3C parent used by a durable worker.

alter table ingestion_quality.iq_mapping_recompute_job
    add column traceparent varchar(55),
    add constraint iq_mapping_recompute_job_traceparent_ck check (
        traceparent is null
        or (traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-(00|01)$'
            and substring(traceparent from 4 for 32) !~ '^0{32}$'
            and substring(traceparent from 37 for 16) !~ '^0{16}$'
            and substring(traceparent from 4 for 32) = trace_id));

create function ingestion_quality.iq_enqueue_mapping_recompute_v2(
    requested_job_id uuid,
    requested_correction_lineage_id uuid,
    requested_owner_source_id varchar,
    requested_student_ref uuid,
    requested_rule_id varchar,
    requested_rule_version varchar,
    requested_scenario_id varchar,
    requested_window_id varchar,
    requested_input_watermarks_digest char(71),
    requested_latest_actionable_at timestamptz,
    requested_server_now timestamptz,
    requested_trace_id char(32),
    requested_traceparent varchar)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_job_id uuid;
begin
    if requested_latest_actionable_at is null
       or requested_server_now >= requested_latest_actionable_at then
        return null;
    end if;
    if requested_traceparent is null
       or requested_traceparent !~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-(00|01)$'
       or substring(requested_traceparent from 4 for 32) <> requested_trace_id
       or substring(requested_traceparent from 4 for 32) ~ '^0{32}$'
       or substring(requested_traceparent from 37 for 16) ~ '^0{16}$' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_TRACEPARENT_INVALID';
    end if;
    insert into ingestion_quality.iq_mapping_recompute_job
      (job_id, correction_lineage_id, owner_source_id, student_ref, rule_id, rule_version,
       scenario_id, window_id, input_watermarks_digest, latest_actionable_at,
       status, queued_at, trace_id, traceparent)
    values
      (requested_job_id, requested_correction_lineage_id, requested_owner_source_id,
       requested_student_ref, requested_rule_id, requested_rule_version,
       requested_scenario_id, requested_window_id, requested_input_watermarks_digest,
       requested_latest_actionable_at, 'queued', requested_server_now,
       requested_trace_id, requested_traceparent)
    on conflict on constraint iq_mapping_recompute_job_identity_uk do nothing;
    select job_id
      into iq_job_id
      from ingestion_quality.iq_mapping_recompute_job
     where correction_lineage_id = requested_correction_lineage_id
       and student_ref = requested_student_ref
       and rule_id = requested_rule_id
       and rule_version = requested_rule_version
       and scenario_id = requested_scenario_id
       and window_id = requested_window_id
       and input_watermarks_digest = requested_input_watermarks_digest;
    return iq_job_id;
end
$$;

revoke all on function ingestion_quality.iq_enqueue_mapping_recompute_v2(
    uuid, uuid, varchar, uuid, varchar, varchar, varchar, varchar, char,
    timestamptz, timestamptz, char, varchar)
    from public;

alter function ingestion_quality.iq_enqueue_mapping_recompute_v2(
    uuid, uuid, varchar, uuid, varchar, varchar, varchar, varchar, char,
    timestamptz, timestamptz, char, varchar)
    owner to scholarsense_ingestion_quality_batch_owner;

grant execute on function ingestion_quality.iq_enqueue_mapping_recompute_v2(
    uuid, uuid, varchar, uuid, varchar, varchar, varchar, varchar, char,
    timestamptz, timestamptz, char, varchar)
    to scholarsense_ingestion_quality_online;
