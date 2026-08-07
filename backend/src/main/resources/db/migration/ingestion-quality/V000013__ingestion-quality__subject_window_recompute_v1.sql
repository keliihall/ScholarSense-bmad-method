create table ingestion_quality.iq_historical_window (
    window_id varchar(128) primary key,
    subject_ref uuid not null,
    start_at timestamptz not null,
    end_at timestamptz not null,
    timezone varchar(64) not null check (timezone = 'Asia/Shanghai'),
    source_versions jsonb not null check (
        jsonb_typeof(source_versions) = 'object' and source_versions <> '{}'::jsonb),
    source_watermarks jsonb not null check (
        jsonb_typeof(source_watermarks) = 'object' and source_watermarks <> '{}'::jsonb),
    mapping_version bigint not null check (
        mapping_version between 1 and 9007199254740991),
    quality_gate_versions jsonb not null check (
        jsonb_typeof(quality_gate_versions) = 'array'
        and jsonb_array_length(quality_gate_versions) > 0),
    rule_id varchar(128) not null,
    rule_version varchar(64) not null,
    scenario_id varchar(128) not null,
    lineage_run_id uuid not null,
    input_digest char(71) not null check (input_digest ~ '^sha256:[0-9a-f]{64}$'),
    latest_actionable_at timestamptz not null,
    created_at timestamptz not null,
    effective_period tstzrange generated always as
        (tstzrange(start_at, end_at, '[)')) stored,
    check (start_at < end_at),
    check (substring(subject_ref::text, 15, 1) = '7'
        and substring(subject_ref::text, 20, 1) in ('8','9','a','b')),
    check (substring(lineage_run_id::text, 15, 1) = '7'
        and substring(lineage_run_id::text, 20, 1) in ('8','9','a','b')),
    constraint iq_historical_window_no_overlap exclude using gist (
        subject_ref with =,
        rule_id with =,
        rule_version with =,
        scenario_id with =,
        effective_period with &&)
);

create table ingestion_quality.iq_subject_mapping_consumer_cursor (
    consumer_id varchar(128) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null default 0 check (
        aggregate_version between 0 and 9007199254740991),
    input_watermark varchar(512),
    lifecycle varchar(32) not null check (
        lifecycle in ('backfilling','paused-gap','active','quarantined','inactive')),
    gap_from_version bigint check (
        gap_from_version is null or gap_from_version between 1 and 9007199254740991),
    reconciliation_passed boolean not null default false,
    reconciled_at timestamptz,
    updated_at timestamptz not null,
    primary key (consumer_id, aggregate_id)
);

create table ingestion_quality.iq_subject_mapping_event_inbox (
    consumer_id varchar(128) not null,
    event_source varchar(256) not null,
    event_id uuid not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (
        aggregate_version between 1 and 9007199254740991),
    occurred_at timestamptz not null,
    correction_lineage_id uuid not null,
    source_id varchar(64) not null,
    affected_student_refs uuid[] not null check (cardinality(affected_student_refs) > 0),
    input_watermark varchar(512) not null,
    consume_mode varchar(16) not null check (consume_mode in ('normal','backfill')),
    outcome varchar(32) not null check (
        outcome in ('APPLIED','BACKFILL_APPLIED','OLD_VERSION')),
    received_at timestamptz not null,
    primary key (consumer_id, event_source, event_id)
);

create table ingestion_quality.iq_subject_mapping_event_quarantine (
    consumer_id varchar(128) not null,
    event_source varchar(256) not null,
    event_id uuid not null,
    aggregate_id uuid,
    aggregate_version bigint,
    reason_code varchar(64) not null,
    quarantined_at timestamptz not null,
    resolved_at timestamptz,
    primary key (consumer_id, event_source, event_id),
    check (aggregate_version is null
        or aggregate_version between 1 and 9007199254740991)
);

create table ingestion_quality.iq_mapping_recompute_request (
    request_id uuid primary key,
    correction_lineage_id uuid not null,
    owner_source_id varchar(64) not null check (
        owner_source_id ~ '^SRC-P[01]-[A-Z-]+-[0-9]{3}$'),
    job_count integer not null check (job_count >= 0),
    history_only_window_count integer not null check (
        history_only_window_count >= 0),
    planned_at timestamptz not null,
    trace_id char(32) not null check (trace_id ~ '^[0-9a-f]{32}$'),
    object_version bigint not null default 1 check (
        object_version between 1 and 9007199254740991),
    check (substring(request_id::text, 15, 1) = '7'
        and substring(request_id::text, 20, 1) in ('8','9','a','b')),
    check (substring(correction_lineage_id::text, 15, 1) = '7'
        and substring(correction_lineage_id::text, 20, 1) in ('8','9','a','b'))
);

create table ingestion_quality.iq_mapping_recompute_job (
    job_id uuid primary key,
    correction_lineage_id uuid not null,
    owner_source_id varchar(64) not null check (
        owner_source_id ~ '^SRC-P[01]-[A-Z-]+-[0-9]{3}$'),
    student_ref uuid not null,
    rule_id varchar(128) not null,
    rule_version varchar(64) not null,
    scenario_id varchar(128) not null,
    window_id varchar(128) not null,
    input_watermarks_digest char(71) not null check (
        input_watermarks_digest ~ '^sha256:[0-9a-f]{64}$'),
    latest_actionable_at timestamptz not null,
    status varchar(16) not null check (
        status in ('queued','running','succeeded','failed','cancelled')),
    attempt_no integer not null default 0 check (attempt_no >= 0),
    fencing_token bigint not null default 0 check (fencing_token >= 0),
    lease_owner varchar(128),
    lease_until timestamptz,
    checkpoint_sequence bigint not null default 0 check (checkpoint_sequence >= 0),
    object_version bigint not null default 1 check (
        object_version between 1 and 9007199254740991),
    queued_at timestamptz not null,
    started_at timestamptz,
    completed_at timestamptz,
    result_code varchar(64),
    failure_code varchar(128) check (
        failure_code is null
        or failure_code ~ '^INGESTION_QUALITY_[A-Z0-9_]{2,100}$'),
    history_corrected boolean not null default false,
    business_publication_created boolean not null default false,
    trace_id char(32) not null check (trace_id ~ '^[0-9a-f]{32}$'),
    constraint iq_mapping_recompute_job_identity_uk unique (
        correction_lineage_id, student_ref, rule_id, rule_version,
        scenario_id, window_id, input_watermarks_digest),
    check ((status = 'running') = (lease_owner is not null and lease_until is not null)),
    check ((status = 'failed') = (failure_code is not null)),
    check ((status = 'succeeded') = (result_code is not null)),
    check (substring(job_id::text, 15, 1) = '7'
        and substring(job_id::text, 20, 1) in ('8','9','a','b')),
    check (substring(correction_lineage_id::text, 15, 1) = '7'
        and substring(correction_lineage_id::text, 20, 1) in ('8','9','a','b')),
    check (substring(student_ref::text, 15, 1) = '7'
        and substring(student_ref::text, 20, 1) in ('8','9','a','b'))
);

create table ingestion_quality.iq_mapping_recompute_result (
    job_id uuid primary key references ingestion_quality.iq_mapping_recompute_job(job_id),
    status varchar(16) not null check (status = 'succeeded'),
    result_code varchar(64) not null check (
        result_code in ('RECOMPUTED','EXPIRED_HISTORY_ONLY','NO_ACTIONABLE_WINDOW')),
    history_corrected boolean not null check (history_corrected),
    business_publication_created boolean not null,
    completed_at timestamptz not null,
    trace_id char(32) not null check (trace_id ~ '^[0-9a-f]{32}$')
);

create table ingestion_quality.iq_mapping_recompute_outbox (
    event_id uuid primary key,
    job_id uuid not null unique references ingestion_quality.iq_mapping_recompute_result(job_id),
    event_type varchar(128) not null default
        'cn.edu.suda.scholarsense.mapping-recompute.result.v1',
    schema_version varchar(64) not null default 'MAPPING-RECOMPUTE-RESULT-1.0.0',
    payload jsonb not null check (jsonb_typeof(payload) = 'object'),
    status varchar(16) not null default 'pending' check (
        status in ('pending','retrying','delivered','failed')),
    attempts bigint not null default 0 check (attempts >= 0),
    available_at timestamptz not null,
    claimed_until timestamptz,
    delivered_at timestamptz,
    last_error_code varchar(128),
    created_at timestamptz not null,
    check (substring(event_id::text, 15, 1) = '7'
        and substring(event_id::text, 20, 1) in ('8','9','a','b'))
);

create index iq_historical_window_subject_idx
    on ingestion_quality.iq_historical_window(subject_ref, end_at desc, window_id);
create index iq_subject_mapping_inbox_aggregate_idx
    on ingestion_quality.iq_subject_mapping_event_inbox(
        consumer_id, aggregate_id, aggregate_version);
create index iq_subject_mapping_quarantine_unresolved_idx
    on ingestion_quality.iq_subject_mapping_event_quarantine(
        consumer_id, aggregate_id, quarantined_at) where resolved_at is null;
create index iq_mapping_recompute_request_lineage_idx
    on ingestion_quality.iq_mapping_recompute_request(
        correction_lineage_id, request_id);
create index iq_mapping_recompute_job_claim_idx
    on ingestion_quality.iq_mapping_recompute_job(status, lease_until, queued_at, job_id);
create index iq_mapping_recompute_outbox_due_idx
    on ingestion_quality.iq_mapping_recompute_outbox(status, available_at, event_id);

create function ingestion_quality.iq_reject_immutable_subject_history()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    raise exception using
        errcode = 'check_violation',
        message = 'INGESTION_QUALITY_SUBJECT_HISTORY_IMMUTABLE';
end
$$;

create trigger iq_historical_window_update_immutable
before update on ingestion_quality.iq_historical_window
for each row execute function ingestion_quality.iq_reject_immutable_subject_history();
create trigger iq_historical_window_delete_immutable
before delete on ingestion_quality.iq_historical_window
for each row execute function ingestion_quality.iq_reject_immutable_subject_history();
create trigger iq_subject_mapping_event_inbox_update_immutable
before update on ingestion_quality.iq_subject_mapping_event_inbox
for each row execute function ingestion_quality.iq_reject_immutable_subject_history();
create trigger iq_subject_mapping_event_inbox_delete_immutable
before delete on ingestion_quality.iq_subject_mapping_event_inbox
for each row execute function ingestion_quality.iq_reject_immutable_subject_history();
create trigger iq_mapping_recompute_request_update_immutable
before update on ingestion_quality.iq_mapping_recompute_request
for each row execute function ingestion_quality.iq_reject_immutable_subject_history();
create trigger iq_mapping_recompute_request_delete_immutable
before delete on ingestion_quality.iq_mapping_recompute_request
for each row execute function ingestion_quality.iq_reject_immutable_subject_history();
create trigger iq_mapping_recompute_result_update_immutable
before update on ingestion_quality.iq_mapping_recompute_result
for each row execute function ingestion_quality.iq_reject_immutable_subject_history();
create trigger iq_mapping_recompute_result_delete_immutable
before delete on ingestion_quality.iq_mapping_recompute_result
for each row execute function ingestion_quality.iq_reject_immutable_subject_history();

create function ingestion_quality.iq_record_historical_window(
    requested_window_id varchar,
    requested_subject_ref uuid,
    requested_start_at timestamptz,
    requested_end_at timestamptz,
    requested_timezone varchar,
    requested_source_versions jsonb,
    requested_source_watermarks jsonb,
    requested_mapping_version bigint,
    requested_quality_gate_versions jsonb,
    requested_rule_id varchar,
    requested_rule_version varchar,
    requested_scenario_id varchar,
    requested_lineage_run_id uuid,
    requested_input_digest char(71),
    requested_latest_actionable_at timestamptz,
    requested_created_at timestamptz)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    insert into ingestion_quality.iq_historical_window
      (window_id, subject_ref, start_at, end_at, timezone,
       source_versions, source_watermarks, mapping_version, quality_gate_versions,
       rule_id, rule_version, scenario_id, lineage_run_id, input_digest,
       latest_actionable_at, created_at)
    values
      (requested_window_id, requested_subject_ref, requested_start_at, requested_end_at,
       requested_timezone, requested_source_versions, requested_source_watermarks,
       requested_mapping_version, requested_quality_gate_versions, requested_rule_id,
       requested_rule_version, requested_scenario_id, requested_lineage_run_id,
       requested_input_digest, requested_latest_actionable_at, requested_created_at);
    return true;
end
$$;

create function ingestion_quality.iq_accept_subject_mapping_event(
    requested_consumer_id varchar,
    requested_event_source varchar,
    requested_event_id uuid,
    requested_aggregate_id uuid,
    requested_aggregate_version bigint,
    requested_occurred_at timestamptz,
    requested_correction_lineage_id uuid,
    requested_source_id varchar,
    requested_affected_student_refs uuid[],
    requested_input_watermark varchar,
    requested_mode varchar,
    requested_schema_valid boolean,
    requested_received_at timestamptz)
returns varchar
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_current_version bigint;
    iq_outcome varchar;
begin
    if exists (
        select 1 from ingestion_quality.iq_subject_mapping_event_inbox
         where consumer_id = requested_consumer_id
           and event_source = requested_event_source
           and event_id = requested_event_id)
       or exists (
        select 1 from ingestion_quality.iq_subject_mapping_event_quarantine
         where consumer_id = requested_consumer_id
           and event_source = requested_event_source
           and event_id = requested_event_id) then
        return 'DUPLICATE';
    end if;

    insert into ingestion_quality.iq_subject_mapping_consumer_cursor
      (consumer_id, aggregate_id, aggregate_version, lifecycle,
       reconciliation_passed, updated_at)
    values
      (requested_consumer_id, requested_aggregate_id, 0, 'backfilling',
       false, requested_received_at)
    on conflict (consumer_id, aggregate_id) do nothing;

    select aggregate_version
      into iq_current_version
      from ingestion_quality.iq_subject_mapping_consumer_cursor
     where consumer_id = requested_consumer_id
       and aggregate_id = requested_aggregate_id
     for update;

    if not requested_schema_valid
       or requested_event_source <> 'urn:scholarsense:subject-registry'
       or requested_aggregate_version < 1
       or requested_mode not in ('normal','backfill')
       or cardinality(requested_affected_student_refs) < 1 then
        insert into ingestion_quality.iq_subject_mapping_event_quarantine
          (consumer_id, event_source, event_id, aggregate_id, aggregate_version,
           reason_code, quarantined_at)
        values
          (requested_consumer_id, requested_event_source, requested_event_id,
           requested_aggregate_id, requested_aggregate_version,
           'SUBJECT_MAPPING_EVENT_SCHEMA_INVALID', requested_received_at);
        update ingestion_quality.iq_subject_mapping_consumer_cursor
           set lifecycle = 'quarantined', reconciliation_passed = false,
               updated_at = requested_received_at
         where consumer_id = requested_consumer_id
           and aggregate_id = requested_aggregate_id;
        return 'POISON_QUARANTINED';
    end if;

    if requested_aggregate_version <= iq_current_version then
        insert into ingestion_quality.iq_subject_mapping_event_inbox
          (consumer_id, event_source, event_id, aggregate_id, aggregate_version,
           occurred_at, correction_lineage_id, source_id, affected_student_refs,
           input_watermark, consume_mode, outcome, received_at)
        values
          (requested_consumer_id, requested_event_source, requested_event_id,
           requested_aggregate_id, requested_aggregate_version, requested_occurred_at,
           requested_correction_lineage_id, requested_source_id,
           requested_affected_student_refs, requested_input_watermark,
           requested_mode, 'OLD_VERSION', requested_received_at);
        return 'OLD_VERSION';
    end if;

    if requested_aggregate_version <> iq_current_version + 1 then
        update ingestion_quality.iq_subject_mapping_consumer_cursor
           set lifecycle = 'paused-gap', gap_from_version = iq_current_version + 1,
               reconciliation_passed = false, updated_at = requested_received_at
         where consumer_id = requested_consumer_id
           and aggregate_id = requested_aggregate_id;
        return 'GAP_PAUSED';
    end if;

    iq_outcome := case when requested_mode = 'backfill'
        then 'BACKFILL_APPLIED' else 'APPLIED' end;
    insert into ingestion_quality.iq_subject_mapping_event_inbox
      (consumer_id, event_source, event_id, aggregate_id, aggregate_version,
       occurred_at, correction_lineage_id, source_id, affected_student_refs,
       input_watermark, consume_mode, outcome, received_at)
    values
      (requested_consumer_id, requested_event_source, requested_event_id,
       requested_aggregate_id, requested_aggregate_version, requested_occurred_at,
       requested_correction_lineage_id, requested_source_id,
       requested_affected_student_refs, requested_input_watermark,
       requested_mode, iq_outcome, requested_received_at);
    update ingestion_quality.iq_subject_mapping_consumer_cursor
       set aggregate_version = requested_aggregate_version,
           input_watermark = requested_input_watermark,
           lifecycle = 'backfilling', gap_from_version = null,
           reconciliation_passed = false, reconciled_at = null,
           updated_at = requested_received_at
     where consumer_id = requested_consumer_id
       and aggregate_id = requested_aggregate_id;
    return iq_outcome;
end
$$;

create function ingestion_quality.iq_reconcile_subject_mapping_consumer(
    requested_consumer_id varchar,
    requested_aggregate_id uuid,
    requested_authoritative_version bigint,
    requested_server_now timestamptz)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_current_version bigint;
begin
    select aggregate_version
      into iq_current_version
      from ingestion_quality.iq_subject_mapping_consumer_cursor
     where consumer_id = requested_consumer_id
       and aggregate_id = requested_aggregate_id
     for update;
    if iq_current_version is null
       or iq_current_version <> requested_authoritative_version
       or exists (
          select 1 from ingestion_quality.iq_subject_mapping_event_quarantine
           where consumer_id = requested_consumer_id
             and aggregate_id = requested_aggregate_id
             and resolved_at is null) then
        update ingestion_quality.iq_subject_mapping_consumer_cursor
           set reconciliation_passed = false, updated_at = requested_server_now
         where consumer_id = requested_consumer_id
           and aggregate_id = requested_aggregate_id;
        return false;
    end if;
    update ingestion_quality.iq_subject_mapping_consumer_cursor
       set lifecycle = 'active', reconciliation_passed = true,
           reconciled_at = requested_server_now, updated_at = requested_server_now
     where consumer_id = requested_consumer_id
       and aggregate_id = requested_aggregate_id;
    return true;
end
$$;

create function ingestion_quality.iq_enqueue_mapping_recompute(
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
    requested_trace_id char(32))
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
    insert into ingestion_quality.iq_mapping_recompute_job
      (job_id, correction_lineage_id, owner_source_id, student_ref, rule_id, rule_version,
       scenario_id, window_id, input_watermarks_digest, latest_actionable_at,
       status, queued_at, trace_id)
    values
      (requested_job_id, requested_correction_lineage_id, requested_owner_source_id,
       requested_student_ref,
       requested_rule_id, requested_rule_version, requested_scenario_id,
       requested_window_id, requested_input_watermarks_digest,
       requested_latest_actionable_at, 'queued', requested_server_now,
       requested_trace_id)
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

create function ingestion_quality.iq_record_mapping_recompute_plan(
    requested_request_id uuid,
    requested_correction_lineage_id uuid,
    requested_owner_source_id varchar,
    requested_job_count integer,
    requested_history_only_window_count integer,
    requested_planned_at timestamptz,
    requested_trace_id char(32))
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_exact_match boolean;
begin
    if requested_job_count < 0 or requested_history_only_window_count < 0 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RECOMPUTE_PLAN_INVALID';
    end if;
    insert into ingestion_quality.iq_mapping_recompute_request
      (request_id, correction_lineage_id, owner_source_id, job_count,
       history_only_window_count, planned_at, trace_id)
    values
      (requested_request_id, requested_correction_lineage_id,
       requested_owner_source_id, requested_job_count,
       requested_history_only_window_count, requested_planned_at,
       requested_trace_id)
    on conflict (request_id) do nothing;
    select correction_lineage_id = requested_correction_lineage_id
           and owner_source_id = requested_owner_source_id
           and job_count = requested_job_count
           and history_only_window_count = requested_history_only_window_count
           and planned_at = requested_planned_at
           and trace_id = requested_trace_id
      into iq_exact_match
      from ingestion_quality.iq_mapping_recompute_request
     where request_id = requested_request_id;
    if not coalesce(iq_exact_match, false) then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_RECOMPUTE_PLAN_IDEMPOTENCY_MISMATCH';
    end if;
    return true;
end
$$;

create function ingestion_quality.iq_claim_mapping_recompute_job(
    requested_job_id uuid,
    requested_worker_id varchar,
    requested_server_now timestamptz,
    requested_lease_until timestamptz)
returns bigint
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_status varchar;
    iq_lease_until timestamptz;
    iq_fencing_token bigint;
begin
    if requested_lease_until <= requested_server_now then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_LEASE_INVALID';
    end if;
    select status, lease_until, fencing_token
      into iq_status, iq_lease_until, iq_fencing_token
      from ingestion_quality.iq_mapping_recompute_job
     where job_id = requested_job_id
     for update;
    if iq_status is null
       or (iq_status <> 'queued'
           and not (iq_status = 'running' and requested_server_now >= iq_lease_until)) then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_JOB_NOT_CLAIMABLE';
    end if;
    if iq_fencing_token = 9223372036854775807 then
        raise exception using
            errcode = 'numeric_value_out_of_range',
            message = 'INGESTION_QUALITY_FENCING_EXHAUSTED';
    end if;
    update ingestion_quality.iq_mapping_recompute_job
       set status = 'running', attempt_no = attempt_no + 1,
           fencing_token = fencing_token + 1, lease_owner = requested_worker_id,
           lease_until = requested_lease_until, started_at = requested_server_now,
           completed_at = null, result_code = null, failure_code = null,
           object_version = object_version + 1
     where job_id = requested_job_id
     returning fencing_token into iq_fencing_token;
    return iq_fencing_token;
end
$$;

create function ingestion_quality.iq_checkpoint_mapping_recompute_job(
    requested_job_id uuid,
    requested_fencing_token bigint,
    requested_checkpoint_sequence bigint,
    requested_server_now timestamptz)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    update ingestion_quality.iq_mapping_recompute_job
       set checkpoint_sequence = requested_checkpoint_sequence,
           object_version = object_version + 1
     where job_id = requested_job_id
       and status = 'running'
       and fencing_token = requested_fencing_token
       and requested_server_now < lease_until
       and requested_checkpoint_sequence > checkpoint_sequence;
    if not found then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_STALE_FENCING_TOKEN';
    end if;
    return true;
end
$$;

create function ingestion_quality.iq_complete_mapping_recompute_job(
    requested_job_id uuid,
    requested_fencing_token bigint,
    requested_server_now timestamptz,
    requested_event_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_latest_actionable_at timestamptz;
    iq_trace_id char(32);
    iq_result_code varchar;
    iq_business_publication_created boolean;
    iq_payload jsonb;
begin
    select latest_actionable_at, trace_id
      into iq_latest_actionable_at, iq_trace_id
      from ingestion_quality.iq_mapping_recompute_job
     where job_id = requested_job_id
       and status = 'running'
       and fencing_token = requested_fencing_token
       and requested_server_now < lease_until
     for update;
    if iq_latest_actionable_at is null then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_STALE_FENCING_TOKEN';
    end if;
    iq_business_publication_created := requested_server_now < iq_latest_actionable_at;
    iq_result_code := case when iq_business_publication_created
        then 'RECOMPUTED' else 'EXPIRED_HISTORY_ONLY' end;
    update ingestion_quality.iq_mapping_recompute_job
       set status = 'succeeded', lease_owner = null, lease_until = null,
           completed_at = requested_server_now, result_code = iq_result_code,
           failure_code = null,
           history_corrected = true,
           business_publication_created = iq_business_publication_created,
           object_version = object_version + 1
     where job_id = requested_job_id;
    insert into ingestion_quality.iq_mapping_recompute_result
      (job_id, status, result_code, history_corrected,
       business_publication_created, completed_at, trace_id)
    values
      (requested_job_id, 'succeeded', iq_result_code, true,
       iq_business_publication_created, requested_server_now, iq_trace_id);
    iq_payload := jsonb_build_object(
        'jobId', requested_job_id,
        'status', 'succeeded',
        'resultCode', iq_result_code,
        'historyCorrected', true,
        'businessPublicationCreated', iq_business_publication_created,
        'completedAt', requested_server_now,
        'traceId', iq_trace_id);
    insert into ingestion_quality.iq_mapping_recompute_outbox
      (event_id, job_id, payload, available_at, created_at)
    values
      (requested_event_id, requested_job_id, iq_payload,
       requested_server_now, requested_server_now);
    return iq_payload;
end
$$;

create function ingestion_quality.iq_fail_mapping_recompute_job(
    requested_job_id uuid,
    requested_fencing_token bigint,
    requested_server_now timestamptz,
    requested_failure_code varchar)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    if requested_failure_code is null
       or requested_failure_code !~ '^INGESTION_QUALITY_[A-Z0-9_]{2,100}$' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_FAILURE_CODE_INVALID';
    end if;
    update ingestion_quality.iq_mapping_recompute_job
       set status = 'failed', lease_owner = null, lease_until = null,
           completed_at = requested_server_now, result_code = null,
           failure_code = requested_failure_code,
           history_corrected = false, business_publication_created = false,
           object_version = object_version + 1
     where job_id = requested_job_id
       and status = 'running'
       and fencing_token = requested_fencing_token
       and requested_server_now < lease_until;
    if not found then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_STALE_FENCING_TOKEN';
    end if;
    return true;
end
$$;

create function ingestion_quality.iq_requeue_mapping_recompute_job(
    requested_job_id uuid,
    requested_expected_object_version bigint,
    requested_server_now timestamptz)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    update ingestion_quality.iq_mapping_recompute_job
       set status = 'queued', lease_owner = null, lease_until = null,
           checkpoint_sequence = 0, completed_at = null,
           result_code = null, failure_code = null,
           history_corrected = false, business_publication_created = false,
           queued_at = requested_server_now,
           object_version = object_version + 1
     where job_id = requested_job_id
       and status = 'failed'
       and object_version = requested_expected_object_version;
    if not found then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_JOB_VERSION_CONFLICT';
    end if;
    return true;
end
$$;

create function ingestion_quality.iq_cancel_mapping_recompute_job(
    requested_job_id uuid,
    requested_expected_object_version bigint,
    requested_server_now timestamptz)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    update ingestion_quality.iq_mapping_recompute_job
       set status = 'cancelled', lease_owner = null, lease_until = null,
           fencing_token = fencing_token + 1,
           completed_at = requested_server_now,
           result_code = null, failure_code = null,
           history_corrected = false, business_publication_created = false,
           object_version = object_version + 1
     where job_id = requested_job_id
       and status in ('queued','running')
       and object_version = requested_expected_object_version
       and fencing_token < 9223372036854775807;
    if not found then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_JOB_VERSION_CONFLICT';
    end if;
    return true;
end
$$;

revoke all privileges on ingestion_quality.iq_historical_window,
    ingestion_quality.iq_subject_mapping_consumer_cursor,
    ingestion_quality.iq_subject_mapping_event_inbox,
    ingestion_quality.iq_subject_mapping_event_quarantine,
    ingestion_quality.iq_mapping_recompute_request,
    ingestion_quality.iq_mapping_recompute_job,
    ingestion_quality.iq_mapping_recompute_result,
    ingestion_quality.iq_mapping_recompute_outbox
    from public;
revoke all privileges on ingestion_quality.iq_historical_window,
    ingestion_quality.iq_subject_mapping_consumer_cursor,
    ingestion_quality.iq_subject_mapping_event_inbox,
    ingestion_quality.iq_subject_mapping_event_quarantine,
    ingestion_quality.iq_mapping_recompute_request,
    ingestion_quality.iq_mapping_recompute_job,
    ingestion_quality.iq_mapping_recompute_result,
    ingestion_quality.iq_mapping_recompute_outbox
    from scholarsense_ingestion_quality_online;

revoke all on function ingestion_quality.iq_reject_immutable_subject_history()
    from public;
revoke all on function ingestion_quality.iq_record_historical_window(
    varchar, uuid, timestamptz, timestamptz, varchar, jsonb, jsonb, bigint,
    jsonb, varchar, varchar, varchar, uuid, char, timestamptz, timestamptz)
    from public;
revoke all on function ingestion_quality.iq_accept_subject_mapping_event(
    varchar, varchar, uuid, uuid, bigint, timestamptz, uuid, varchar,
    uuid[], varchar, varchar, boolean, timestamptz)
    from public;
revoke all on function ingestion_quality.iq_reconcile_subject_mapping_consumer(
    varchar, uuid, bigint, timestamptz)
    from public;
revoke all on function ingestion_quality.iq_enqueue_mapping_recompute(
    uuid, uuid, varchar, uuid, varchar, varchar, varchar, varchar, char,
    timestamptz, timestamptz, char)
    from public;
revoke all on function ingestion_quality.iq_record_mapping_recompute_plan(
    uuid, uuid, varchar, integer, integer, timestamptz, char)
    from public;
revoke all on function ingestion_quality.iq_claim_mapping_recompute_job(
    uuid, varchar, timestamptz, timestamptz)
    from public;
revoke all on function ingestion_quality.iq_checkpoint_mapping_recompute_job(
    uuid, bigint, bigint, timestamptz)
    from public;
revoke all on function ingestion_quality.iq_complete_mapping_recompute_job(
    uuid, bigint, timestamptz, uuid)
    from public;
revoke all on function ingestion_quality.iq_fail_mapping_recompute_job(
    uuid, bigint, timestamptz, varchar)
    from public;
revoke all on function ingestion_quality.iq_requeue_mapping_recompute_job(
    uuid, bigint, timestamptz)
    from public;
revoke all on function ingestion_quality.iq_cancel_mapping_recompute_job(
    uuid, bigint, timestamptz)
    from public;

grant execute on function ingestion_quality.iq_record_historical_window(
    varchar, uuid, timestamptz, timestamptz, varchar, jsonb, jsonb, bigint,
    jsonb, varchar, varchar, varchar, uuid, char, timestamptz, timestamptz)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_accept_subject_mapping_event(
    varchar, varchar, uuid, uuid, bigint, timestamptz, uuid, varchar,
    uuid[], varchar, varchar, boolean, timestamptz)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_reconcile_subject_mapping_consumer(
    varchar, uuid, bigint, timestamptz)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_enqueue_mapping_recompute(
    uuid, uuid, varchar, uuid, varchar, varchar, varchar, varchar, char,
    timestamptz, timestamptz, char)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_record_mapping_recompute_plan(
    uuid, uuid, varchar, integer, integer, timestamptz, char)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_claim_mapping_recompute_job(
    uuid, varchar, timestamptz, timestamptz)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_checkpoint_mapping_recompute_job(
    uuid, bigint, bigint, timestamptz)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_complete_mapping_recompute_job(
    uuid, bigint, timestamptz, uuid)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_fail_mapping_recompute_job(
    uuid, bigint, timestamptz, varchar)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_requeue_mapping_recompute_job(
    uuid, bigint, timestamptz)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_cancel_mapping_recompute_job(
    uuid, bigint, timestamptz)
    to scholarsense_ingestion_quality_online;

grant select on ingestion_quality.iq_historical_window,
    ingestion_quality.iq_subject_mapping_consumer_cursor,
    ingestion_quality.iq_mapping_recompute_request,
    ingestion_quality.iq_mapping_recompute_job,
    ingestion_quality.iq_mapping_recompute_result
    to scholarsense_ingestion_quality_online;

grant select on ingestion_quality.iq_mapping_recompute_outbox
    to scholarsense_ingestion_quality_relay;
grant update (status, attempts, available_at, claimed_until,
    delivered_at, last_error_code)
    on ingestion_quality.iq_mapping_recompute_outbox
    to scholarsense_ingestion_quality_relay;
