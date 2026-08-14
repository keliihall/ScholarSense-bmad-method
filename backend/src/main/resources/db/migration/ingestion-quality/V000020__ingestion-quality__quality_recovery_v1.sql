do $migration$
begin
    if not exists (select 1 from pg_catalog.pg_roles
                    where rolname='scholarsense_ingestion_quality_recovery_worker') then
        create role scholarsense_ingestion_quality_recovery_worker nologin;
    end if;
end
$migration$;

alter role scholarsense_ingestion_quality_recovery_worker set search_path=pg_catalog;
grant usage on schema ingestion_quality
    to scholarsense_ingestion_quality_recovery_worker;
revoke create on schema ingestion_quality
    from scholarsense_ingestion_quality_recovery_worker;

create table ingestion_quality.iq_quality_recovery_request_history (
    recovery_request_id uuid not null,
    request_version bigint not null check (request_version between 1 and 9007199254740991),
    task_id uuid not null,
    episode_id uuid not null,
    source_id varchar(64) not null,
    dependency_id varchar(64) not null,
    status varchar(24) not null check (status in (
        'requested','validating','validation-succeeded','validation-failed',
        'approval-pending','approval-approved','executed','cancelled')),
    request_digest char(71) not null,
    aggregate jsonb not null,
    occurred_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((occurred_at at time zone 'UTC')+interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    primary key (recovery_request_id,request_version),
    constraint iq_quality_recovery_request_history_uuid_v7_ck check (
        substring(recovery_request_id::text,15,1)='7'
        and substring(recovery_request_id::text,20,1) ~ '^[89ab]$'),
    constraint iq_quality_recovery_request_history_shape_ck check (
        request_digest ~ '^sha256:[0-9a-f]{64}$'
        and jsonb_typeof(aggregate)='object'
        and aggregate->>'recoveryRequestId'=recovery_request_id::text
        and (aggregate->>'requestVersion')::bigint=request_version
        and aggregate->>'status'=status)
);

create table ingestion_quality.iq_quality_recovery_request_current (
    recovery_request_id uuid primary key,
    request_version bigint not null check (request_version between 1 and 9007199254740991),
    idempotency_key_digest char(71) not null unique,
    request_digest char(71) not null,
    task_id uuid not null,
    episode_id uuid not null,
    source_id varchar(64) not null,
    dependency_id varchar(64) not null,
    episode_generation bigint not null check (episode_generation between 1 and 9007199254740991),
    task_version bigint not null check (task_version between 1 and 9007199254740991),
    episode_version bigint not null check (episode_version between 1 and 9007199254740991),
    affected_rule_versions_digest char(71) not null,
    member_set_digest char(71) not null,
    watermarks_digest char(71) not null,
    qrp_version varchar(32) not null,
    qrp_digest char(71) not null,
    authorization_generation bigint not null check (
        authorization_generation between 0 and 9007199254740991),
    status varchar(24) not null,
    aggregate jsonb not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    legal_hold boolean not null default false,
    foreign key (task_id) references
        ingestion_quality.iq_quality_recovery_task_current(task_id),
    foreign key (episode_id) references
        ingestion_quality.iq_quality_fuse_episode_current(episode_id),
    foreign key (recovery_request_id,request_version) references
        ingestion_quality.iq_quality_recovery_request_history(
            recovery_request_id,request_version),
    constraint iq_quality_recovery_request_current_shape_ck check (
        idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'
        and request_digest ~ '^sha256:[0-9a-f]{64}$'
        and affected_rule_versions_digest ~ '^sha256:[0-9a-f]{64}$'
        and member_set_digest ~ '^sha256:[0-9a-f]{64}$'
        and watermarks_digest ~ '^sha256:[0-9a-f]{64}$'
        and qrp_version='QRP-1.0.0'
        and qrp_digest='sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366'
        and status in ('requested','validating','validation-succeeded','validation-failed',
            'approval-pending','approval-approved','executed','cancelled')
        and jsonb_typeof(aggregate)='object')
);
create unique index iq_quality_recovery_request_one_active_task_idx
    on ingestion_quality.iq_quality_recovery_request_current(task_id)
    where status not in ('validation-failed','executed','cancelled');

create table ingestion_quality.iq_quality_recovery_evidence_pack (
    evidence_pack_id uuid primary key,
    recovery_request_id uuid not null,
    validation_job_id uuid not null,
    evidence_pack_digest char(71) not null unique,
    input_digest char(71) not null,
    evidence jsonb not null,
    created_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((created_at at time zone 'UTC')+interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    foreign key (recovery_request_id) references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    constraint iq_quality_recovery_evidence_shape_ck check (
        substring(evidence_pack_id::text,15,1)='7'
        and substring(evidence_pack_id::text,20,1) ~ '^[89ab]$'
        and evidence_pack_digest ~ '^sha256:[0-9a-f]{64}$'
        and input_digest ~ '^sha256:[0-9a-f]{64}$'
        and jsonb_typeof(evidence)='object'
        and octet_length(evidence::text)<=262144)
);

create table ingestion_quality.iq_quality_recovery_preview (
    preview_id uuid primary key,
    recovery_request_id uuid not null,
    preview_version bigint not null check (preview_version between 1 and 9007199254740991),
    input_digest char(71) not null,
    preview_digest char(71) not null unique,
    preview jsonb not null,
    generated_at timestamptz not null,
    expires_at timestamptz not null,
    invalidated_at timestamptz,
    retention_due_at timestamptz generated always as (
        ((generated_at at time zone 'UTC')+interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    foreign key (recovery_request_id) references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    unique (recovery_request_id,preview_version),
    constraint iq_quality_recovery_preview_shape_ck check (
        substring(preview_id::text,15,1)='7'
        and substring(preview_id::text,20,1) ~ '^[89ab]$'
        and input_digest ~ '^sha256:[0-9a-f]{64}$'
        and preview_digest ~ '^sha256:[0-9a-f]{64}$'
        and jsonb_typeof(preview)='object'
        and octet_length(preview::text)<=65536
        and expires_at>generated_at
        and (invalidated_at is null or invalidated_at>=generated_at))
);
create unique index iq_quality_recovery_preview_current_idx
    on ingestion_quality.iq_quality_recovery_preview(recovery_request_id)
    where invalidated_at is null;

-- Durable digest-only evidence that the recovery worker actually traversed the
-- bounded historical window set.  These rows are not business outputs and are
-- retained with the recovery request for audit/replay.
create table ingestion_quality.iq_recovery_backfill_window_result (
    recovery_request_id uuid not null,
    window_id varchar(128) not null,
    expected_digest char(71) not null,
    normalized_input_digest char(71) not null,
    recomputed_digest char(71) not null,
    reconstructable boolean not null,
    processed_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((processed_at at time zone 'UTC')+interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    primary key (recovery_request_id,window_id),
    foreign key (recovery_request_id) references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    constraint iq_recovery_backfill_window_result_shape_ck check (
        expected_digest ~ '^sha256:[0-9a-f]{64}$'
        and normalized_input_digest ~ '^sha256:[0-9a-f]{64}$'
        and recomputed_digest ~ '^sha256:[0-9a-f]{64}$')
);

create table ingestion_quality.iq_recovery_backfill_run (
    recovery_request_id uuid primary key,
    start_watermark_digest char(71) not null,
    target_watermark_digest char(71) not null,
    processed_count bigint not null check (
        processed_count between 0 and 9007199254740991),
    summary_digest char(71) not null,
    completed_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((completed_at at time zone 'UTC')+interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    foreign key (recovery_request_id) references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    constraint iq_recovery_backfill_run_shape_ck check (
        start_watermark_digest ~ '^sha256:[0-9a-f]{64}$'
        and target_watermark_digest ~ '^sha256:[0-9a-f]{64}$'
        and summary_digest ~ '^sha256:[0-9a-f]{64}$')
);

create table ingestion_quality.iq_recovery_validation_job (
    job_id uuid primary key,
    job_version bigint not null check (job_version between 1 and 9007199254740991),
    recovery_request_id uuid not null unique,
    idempotency_key_digest char(71) not null unique,
    input_digest char(71) not null,
    binding jsonb not null,
    status varchar(16) not null check (status in (
        'queued','running','succeeded','failed','cancelled')),
    attempt_count integer not null check (attempt_count between 0 and 5),
    lease_generation bigint not null check (lease_generation between 0 and 9007199254740991),
    lease_owner_digest char(71),
    claimed_at timestamptz,
    lease_expires_at timestamptz,
    checkpoint_version bigint not null check (checkpoint_version between 0 and 9007199254740991),
    result_digest char(71),
    error_code varchar(64),
    next_attempt_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    legal_hold boolean not null default false,
    foreign key (recovery_request_id) references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    constraint iq_recovery_validation_job_uuid_v7_ck check (
        substring(job_id::text,15,1)='7'
        and substring(job_id::text,20,1) ~ '^[89ab]$'),
    constraint iq_recovery_validation_job_shape_ck check (
        idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'
        and input_digest ~ '^sha256:[0-9a-f]{64}$'
        and jsonb_typeof(binding)='object'
        and (lease_owner_digest is null or lease_owner_digest ~ '^sha256:[0-9a-f]{64}$')
        and (result_digest is null or result_digest ~ '^sha256:[0-9a-f]{64}$')
        and ((status='running')=(lease_owner_digest is not null
            and claimed_at is not null and lease_expires_at is not null))
        and ((status in ('succeeded','failed','cancelled'))=(completed_at is not null)))
);
create index iq_recovery_validation_job_due_idx
    on ingestion_quality.iq_recovery_validation_job(
        status,next_attempt_at,lease_expires_at,job_id);

create table ingestion_quality.iq_recovery_validation_attempt (
    job_id uuid not null,
    attempt_number integer not null check (attempt_number between 1 and 5),
    lease_generation bigint not null check (lease_generation between 1 and 9007199254740991),
    worker_digest char(71) not null,
    claimed_at timestamptz not null,
    lease_expires_at timestamptz not null,
    released_at timestamptz,
    outcome varchar(32),
    primary key (job_id,attempt_number,lease_generation),
    foreign key (job_id) references ingestion_quality.iq_recovery_validation_job(job_id),
    constraint iq_recovery_validation_attempt_shape_ck check (
        worker_digest ~ '^sha256:[0-9a-f]{64}$'
        and lease_expires_at>claimed_at
        and (released_at is null or released_at>=claimed_at))
);

create table ingestion_quality.iq_recovery_validation_checkpoint (
    job_id uuid not null,
    checkpoint_version bigint not null check (checkpoint_version between 1 and 9007199254740991),
    lease_generation bigint not null check (lease_generation between 1 and 9007199254740991),
    phase varchar(32) not null check (phase in (
        'backfill','full-reconciliation','sample-recompute')),
    phase_completed boolean not null,
    opaque_resume_ref varchar(80) not null,
    cursor_digest char(71) not null,
    partial_summary_digest char(71) not null,
    processed_count bigint not null check (processed_count between 0 and 9007199254740991),
    mismatch_count bigint not null check (mismatch_count between 0 and processed_count),
    recorded_at timestamptz not null,
    primary key (job_id,checkpoint_version),
    foreign key (job_id) references ingestion_quality.iq_recovery_validation_job(job_id),
    constraint iq_recovery_validation_checkpoint_shape_ck check (
        opaque_resume_ref ~ '^resume:v1:[0-9a-f]{64}$'
        and cursor_digest ~ '^sha256:[0-9a-f]{64}$'
        and partial_summary_digest ~ '^sha256:[0-9a-f]{64}$')
);

create table ingestion_quality.iq_recovery_validation_result (
    result_id uuid primary key,
    job_id uuid not null unique,
    job_version bigint not null,
    recovery_request_id uuid not null unique,
    state varchar(16) not null check (state in ('succeeded','failed','cancelled')),
    input_digest char(71) not null,
    qualified boolean not null,
    error_code varchar(64),
    result_digest char(71) not null unique,
    result jsonb not null,
    completed_at timestamptz not null,
    trace_id char(32) not null,
    retention_due_at timestamptz generated always as (
        ((completed_at at time zone 'UTC')+interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    foreign key (job_id) references ingestion_quality.iq_recovery_validation_job(job_id),
    foreign key (recovery_request_id) references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    constraint iq_recovery_validation_result_shape_ck check (
        substring(result_id::text,15,1)='7'
        and substring(result_id::text,20,1) ~ '^[89ab]$'
        and input_digest ~ '^sha256:[0-9a-f]{64}$'
        and result_digest ~ '^sha256:[0-9a-f]{64}$'
        and jsonb_typeof(result)='object'
        and octet_length(result::text)<=65536
        and trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'
        and (not qualified or (state='succeeded' and error_code is null)))
);

create table ingestion_quality.iq_quality_recovery_execution_jti (
    execution_jti uuid primary key,
    lease_id uuid not null unique,
    lease_digest char(71) not null,
    recovery_request_id uuid not null unique,
    owner_commit_id varchar(128) not null unique,
    owner_result_digest char(71) not null,
    outbox_event_id uuid not null unique,
    committed_at timestamptz not null,
    authorized_until timestamptz not null,
    confirmed_at timestamptz,
    legal_hold boolean not null default false,
    foreign key (recovery_request_id) references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    constraint iq_quality_recovery_execution_jti_shape_ck check (
        substring(execution_jti::text,15,1)='7'
        and substring(execution_jti::text,20,1) ~ '^[89ab]$'
        and lease_digest ~ '^sha256:[0-9a-f]{64}$'
        and owner_commit_id ~ '^[A-Za-z0-9._:-]{16,128}$'
        and owner_result_digest ~ '^sha256:[0-9a-f]{64}$'
        and committed_at<authorized_until
        and (confirmed_at is null or confirmed_at>=committed_at))
);

create table ingestion_quality.iq_quality_recovery_idempotency (
    idempotency_key_digest char(71) primary key,
    command_digest char(71) not null,
    replay_input_digest char(71) not null,
    recovery_request_id uuid not null,
    execution_jti uuid,
    response jsonb not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint iq_quality_recovery_idempotency_shape_ck check (
        idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'
        and command_digest ~ '^sha256:[0-9a-f]{64}$'
        and replay_input_digest ~ '^sha256:[0-9a-f]{64}$'
        and jsonb_typeof(response)='object'
        and expires_at=created_at+interval '90 days')
);

create table ingestion_quality.iq_quality_recovery_audit (
    audit_id uuid primary key,
    recovery_request_id uuid not null,
    action varchar(96) not null,
    outcome varchar(32) not null,
    object_version bigint not null check (object_version between 1 and 9007199254740991),
    evidence_digest char(71) not null,
    trace_id char(32) not null,
    occurred_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((occurred_at at time zone 'UTC')+interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    constraint iq_quality_recovery_audit_shape_ck check (
        evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        and trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$')
);

create table ingestion_quality.iq_quality_recovery_outbox (
    event_id uuid primary key,
    recovery_request_id uuid not null,
    event_type varchar(160) not null,
    payload jsonb not null,
    payload_digest char(71) not null,
    status varchar(16) not null check (status in ('pending','delivered','failed')),
    attempts bigint not null default 0 check (attempts between 0 and 9007199254740991),
    available_at timestamptz not null,
    claimed_until timestamptz,
    last_error_code varchar(96),
    created_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((created_at at time zone 'UTC')+interval '90 days') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    unique (recovery_request_id,event_type),
    constraint iq_quality_recovery_outbox_shape_ck check (
        event_type in ('scholarsense.ingestion-quality.quality-eligibility.recovering.v1',
            'scholarsense.ingestion-quality.recovery-lease.confirmation.v1')
        and jsonb_typeof(payload)='object' and octet_length(payload::text)<=65536
        and payload_digest ~ '^sha256:[0-9a-f]{64}$'
        and (claimed_until is null or status='pending'))
);
create index iq_quality_recovery_outbox_due_idx
    on ingestion_quality.iq_quality_recovery_outbox(status,available_at,event_id);

create function ingestion_quality.iq_require_recovery_workload(requested_role name)
returns void language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    if requested_role not in ('scholarsense_ingestion_quality_online',
            'scholarsense_ingestion_quality_recovery_worker',
            'scholarsense_ingestion_quality_retention_executor')
       or not pg_catalog.pg_has_role(session_user,requested_role,'USAGE') then
        raise exception using errcode='insufficient_privilege',
            message='INGESTION_QUALITY_RECOVERY_WORKLOAD_FORBIDDEN';
    end if;
end
$$;

-- A recovery-eligible batch must carry the observable result of the enforced
-- assessed-passed -> published transition and must still be the authoritative
-- head of its correction lineage.  Looking only at status='published' would
-- accept a stale predecessor after a correction arrived.
create function ingestion_quality.iq_is_quality_recovery_batch_ready(requested_batch_id uuid)
returns boolean language sql stable security definer set search_path=pg_catalog as $$
select exists (
    select 1
      from ingestion_quality.iq_data_batch batch
      join ingestion_quality.iq_quality_snapshot snapshot
        on snapshot.batch_id=batch.batch_id
     where batch.batch_id=requested_batch_id
       and batch.status='published'
       and batch.aggregate_version=4
       and batch.evaluated_at is not null
       and batch.published_at is not null
       and batch.published_at>=batch.evaluated_at
       and snapshot.assessed_batch_status='quality-passed'
       and snapshot.overall_result='quality-passed'
       and snapshot.aggregate_version=3
       and snapshot.evaluated_at=batch.evaluated_at
       and not exists (
           select 1 from ingestion_quality.iq_data_batch successor
            where successor.supersedes_batch_id=batch.batch_id))
$$;

-- V16 remains byte-identical. The original function is hidden behind a new
-- owner-controlled wrapper that takes the common source lock before any row lock.
alter function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    uuid,varchar,bigint,character,jsonb)
rename to iq_accept_quality_eligibility_event_v16;
revoke all on function ingestion_quality.iq_accept_quality_eligibility_event_v16(
    uuid,varchar,bigint,character,jsonb)
from public,scholarsense_ingestion_quality_eligibility_consumer;

create function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    requested_event_id uuid,requested_source_id varchar,
    requested_source_version bigint,requested_payload_digest character,
    requested_mutation jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_eligibility_consumer');
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-eligibility:'||requested_source_id,0));
    return ingestion_quality.iq_accept_quality_eligibility_event_v16(
        requested_event_id,requested_source_id,requested_source_version,
        requested_payload_digest,requested_mutation);
end
$$;

create function ingestion_quality.iq_submit_quality_recovery_request(
    requested_idempotency_digest character,requested_aggregate jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_task ingestion_quality.iq_quality_recovery_task_current%rowtype;
declare iq_episode ingestion_quality.iq_quality_fuse_episode_current%rowtype;
declare iq_existing ingestion_quality.iq_quality_recovery_idempotency%rowtype;
declare iq_now timestamptz:=statement_timestamp();
declare iq_response jsonb;
declare iq_legal_hold boolean;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    if requested_idempotency_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_aggregate->>'actionType'<>'quality-fuse.recover'
       or requested_aggregate->>'currentState'<>'fused'
       or requested_aggregate->>'targetState'<>'recovering'
       or requested_aggregate->>'status'<>'requested'
       or requested_aggregate->>'idempotencyInputDigest' !~ '^sha256:[0-9a-f]{64}$'
       or (requested_aggregate->>'requestVersion')::bigint<>1
       or requested_aggregate->>'qrpVersion'<>'QRP-1.0.0'
       or requested_aggregate->>'qrpDigest'<>
          'sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366' then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_RECOVERY_REQUEST_INVALID';
    end if;
    -- All quality transitions take the common source lock before any
    -- idempotency or aggregate-row lock, matching the eligibility/fuse order.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-eligibility:'||(requested_aggregate->>'sourceId'),0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-fuse-episode:'||(requested_aggregate->>'sourceId')||'@'||
        (requested_aggregate->>'dependencyId'),0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-recovery-task:'||(requested_aggregate->>'sourceId')||'@'||
        (requested_aggregate->>'dependencyId'),0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-recovery-idempotency:'||requested_idempotency_digest,0));
    select idempotency.* into iq_existing
      from ingestion_quality.iq_quality_recovery_idempotency idempotency
     where idempotency.idempotency_key_digest=requested_idempotency_digest for update;
    if iq_existing.idempotency_key_digest is not null then
        if trim(iq_existing.command_digest)<>
           requested_aggregate->>'idempotencyInputDigest' then
            raise exception using errcode='unique_violation',
                message='INGESTION_QUALITY_RECOVERY_IDEMPOTENCY_CONFLICT';
        end if;
        return iq_existing.response;
    end if;
    select task.* into iq_task
      from ingestion_quality.iq_quality_recovery_task_current task
     where task.task_id=(requested_aggregate->>'taskId')::uuid for update;
    select episode.* into iq_episode
      from ingestion_quality.iq_quality_fuse_episode_current episode
     where episode.episode_id=iq_task.episode_id for update;
    select coalesce(bool_or(legal_hold),false) into iq_legal_hold
      from (
        select history.legal_hold
          from ingestion_quality.iq_quality_recovery_task_history history
         where history.task_id=iq_task.task_id
        union all
        select history.legal_hold
          from ingestion_quality.iq_quality_fuse_episode_history history
         where history.episode_id=iq_episode.episode_id) source_fact;
    if iq_task.task_id is null or iq_task.status<>'open'
       or iq_episode.episode_id is null or not iq_episode.active
       or iq_task.source_id<>requested_aggregate->>'sourceId'
       or iq_task.dependency_id<>requested_aggregate->>'dependencyId'
       or iq_task.aggregate_version<>(requested_aggregate->>'taskVersion')::bigint
       or iq_episode.aggregate_version<>(requested_aggregate->>'episodeVersion')::bigint
       or iq_episode.generation<>(requested_aggregate->>'episodeGeneration')::bigint
       or not exists (select 1
          from ingestion_quality.iq_quality_recovery_task_affected_rule affected
         where affected.task_id=iq_task.task_id)
       or exists (select 1
          from ingestion_quality.iq_quality_recovery_task_affected_rule affected
          left join ingestion_quality.iq_quality_eligibility_current eligibility
            on eligibility.rule_id=affected.rule_id
           and eligibility.rule_version=affected.rule_version
           and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
         where affected.task_id=iq_task.task_id
           and (eligibility.eligibility_id is null or eligibility.status<>'fused')) then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_RECOVERY_VERSION_CONFLICT';
    end if;
    insert into ingestion_quality.iq_quality_recovery_request_history(
        recovery_request_id,request_version,task_id,episode_id,source_id,
        dependency_id,status,request_digest,aggregate,occurred_at,legal_hold) values (
        (requested_aggregate->>'recoveryRequestId')::uuid,1,iq_task.task_id,
        iq_episode.episode_id,iq_task.source_id,iq_task.dependency_id,'requested',
        requested_aggregate->>'requestDigest',requested_aggregate,iq_now,iq_legal_hold);
    insert into ingestion_quality.iq_quality_recovery_request_current values (
        (requested_aggregate->>'recoveryRequestId')::uuid,1,
        requested_idempotency_digest,requested_aggregate->>'requestDigest',
        iq_task.task_id,iq_episode.episode_id,iq_task.source_id,iq_task.dependency_id,
        iq_episode.generation,iq_task.aggregate_version,iq_episode.aggregate_version,
        requested_aggregate->>'affectedRuleVersionsDigest',
        requested_aggregate->>'memberSetDigest',requested_aggregate->>'watermarksDigest',
        requested_aggregate->>'qrpVersion',requested_aggregate->>'qrpDigest',
        (requested_aggregate->>'authorizationGeneration')::bigint,'requested',
        requested_aggregate,iq_now,iq_now,iq_legal_hold);
    iq_response:=jsonb_build_object('recoveryRequestId',
        requested_aggregate->>'recoveryRequestId','requestVersion',1,
        'status','requested','taskId',iq_task.task_id,'traceId',
        requested_aggregate->>'traceId');
    insert into ingestion_quality.iq_quality_recovery_idempotency values (
        requested_idempotency_digest,requested_aggregate->>'idempotencyInputDigest',
        requested_aggregate->>'idempotencyInputDigest',
        (requested_aggregate->>'recoveryRequestId')::uuid,null,iq_response,
        iq_now,iq_now+interval '90 days',iq_legal_hold);
    return iq_response;
end
$$;

-- Application entrypoint: request creation and validation-job creation are one
-- database transaction.  A failed job insert rolls the request back as well.
create function ingestion_quality.iq_submit_quality_recovery_with_validation(
    requested_request_idempotency_digest character,requested_aggregate jsonb,
    requested_job_idempotency_digest character,requested_job_id uuid)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_request_response jsonb;
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_binding jsonb;
declare iq_job jsonb;
declare iq_input_digest text;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    iq_request_response:=ingestion_quality.iq_submit_quality_recovery_request(
        requested_request_idempotency_digest,requested_aggregate);
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=
        (iq_request_response->>'recoveryRequestId')::uuid for update;
    iq_input_digest:='sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
        trim(iq_request.request_digest)||chr(10)||
        trim(iq_request.affected_rule_versions_digest)||chr(10)||
        trim(iq_request.member_set_digest)||chr(10)||
        trim(iq_request.watermarks_digest)||chr(10)||trim(iq_request.qrp_digest)||chr(10)||
        (iq_request.aggregate->>'selectionSeed'),'UTF8')),'hex');
    iq_binding:=jsonb_build_object(
        'recoveryRequestId',iq_request.recovery_request_id,
        'episodeId',iq_request.episode_id,'taskId',iq_request.task_id,
        'inputDigest',iq_input_digest,'qualityRecoveryPolicyVersion',iq_request.qrp_version,
        'qualityRecoveryPolicyDigest',trim(iq_request.qrp_digest),
        'ruleVersionsDigest',trim(iq_request.affected_rule_versions_digest),
        'memberSetDigest',trim(iq_request.member_set_digest),
        'watermarksDigest',trim(iq_request.watermarks_digest),
        'selectionSeed',iq_request.aggregate->>'selectionSeed',
        'traceId',iq_request.aggregate->>'traceId');
    iq_job:=jsonb_build_object('jobId',requested_job_id,'jobVersion',1,
        'recoveryRequestId',iq_request.recovery_request_id,'inputDigest',iq_input_digest,
        'binding',iq_binding,'status','queued','attemptCount',0,'leaseGeneration',0);
    return ingestion_quality.iq_submit_recovery_validation_job(
        requested_job_idempotency_digest,iq_job);
end
$$;

create function ingestion_quality.iq_submit_recovery_validation_job(
    requested_idempotency_digest character,requested_job jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_value jsonb;
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_existing ingestion_quality.iq_recovery_validation_job%rowtype;
declare iq_inserted boolean;
declare iq_now timestamptz:=statement_timestamp();
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    if requested_idempotency_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_job->>'status'<>'queued'
       or (requested_job->>'jobVersion')::bigint<>1
       or (requested_job->>'attemptCount')::integer<>0
       or (requested_job->>'leaseGeneration')::bigint<>0 then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_RECOVERY_JOB_INVALID';
    end if;
    select job.* into iq_existing
      from ingestion_quality.iq_recovery_validation_job job
     where job.idempotency_key_digest=requested_idempotency_digest;
    if iq_existing.job_id is not null then
        if trim(iq_existing.input_digest)<>requested_job->>'inputDigest'
           or iq_existing.recovery_request_id<>
              (requested_job->>'recoveryRequestId')::uuid then
            raise exception using errcode='unique_violation',
                message='INGESTION_QUALITY_RECOVERY_IDEMPOTENCY_CONFLICT';
        end if;
        return iq_existing.binding||jsonb_build_object(
            'jobId',iq_existing.job_id,'jobVersion',iq_existing.job_version,
            'status',iq_existing.status,'attemptCount',iq_existing.attempt_count,
            'leaseGeneration',iq_existing.lease_generation);
    end if;
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=(requested_job->>'recoveryRequestId')::uuid
     for update;
    if iq_request.status<>'requested'
       or requested_job->>'inputDigest'<>requested_job->'binding'->>'inputDigest'
       or requested_job->'binding'->>'recoveryRequestId'<>
          iq_request.recovery_request_id::text then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_RECOVERY_JOB_BINDING_DRIFT';
    end if;
    insert into ingestion_quality.iq_recovery_validation_job(
        job_id,job_version,recovery_request_id,idempotency_key_digest,input_digest,
        binding,status,attempt_count,lease_generation,lease_owner_digest,claimed_at,
        lease_expires_at,checkpoint_version,result_digest,error_code,next_attempt_at,
        created_at,updated_at,completed_at,legal_hold) values (
        (requested_job->>'jobId')::uuid,1,
        (requested_job->>'recoveryRequestId')::uuid,requested_idempotency_digest,
        requested_job->>'inputDigest',requested_job->'binding','queued',0,0,
        null,null,null,0,null,null,null,iq_now,iq_now,null,iq_request.legal_hold)
    on conflict (idempotency_key_digest) do nothing returning true into iq_inserted;
    select binding||jsonb_build_object('jobId',job_id,'jobVersion',job_version,
        'status',status,'attemptCount',attempt_count,'leaseGeneration',lease_generation)
      into iq_value from ingestion_quality.iq_recovery_validation_job
     where idempotency_key_digest=requested_idempotency_digest;
    if iq_value->>'inputDigest'<>requested_job->>'inputDigest' then
        raise exception using errcode='unique_violation',
            message='INGESTION_QUALITY_RECOVERY_IDEMPOTENCY_CONFLICT';
    end if;
    if coalesce(iq_inserted,false) then
        insert into ingestion_quality.iq_quality_recovery_request_history(
            recovery_request_id,request_version,task_id,episode_id,source_id,
            dependency_id,status,request_digest,aggregate,occurred_at,legal_hold)
        values (iq_request.recovery_request_id,iq_request.request_version+1,
            iq_request.task_id,iq_request.episode_id,iq_request.source_id,
            iq_request.dependency_id,'validating',iq_request.request_digest,
            iq_request.aggregate||jsonb_build_object(
                'requestVersion',iq_request.request_version+1,'status','validating'),
            iq_now,iq_request.legal_hold);
        update ingestion_quality.iq_quality_recovery_request_current set
            request_version=request_version+1,status='validating',
            aggregate=aggregate||jsonb_build_object(
                'requestVersion',request_version+1,'status','validating'),updated_at=iq_now
         where recovery_request_id=iq_request.recovery_request_id;
    end if;
    return iq_value;
end
$$;

create function ingestion_quality.iq_claim_recovery_validation_job(
    requested_job_id uuid,requested_worker_digest character,
    requested_now timestamptz,requested_lease_seconds integer)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_job ingestion_quality.iq_recovery_validation_job%rowtype;
declare iq_attempt integer;
declare iq_generation bigint;
declare iq_expiry timestamptz;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    if requested_worker_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_lease_seconds not between 1 and 300 then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_RECOVERY_CLAIM_INVALID';
    end if;
    select job.* into iq_job from ingestion_quality.iq_recovery_validation_job job
     where job.job_id=requested_job_id for update;
    if iq_job.job_id is null
       or not (iq_job.status='queued' and coalesce(iq_job.next_attempt_at,requested_now)<=requested_now
          or iq_job.status='running' and iq_job.lease_expires_at<=requested_now)
       or iq_job.attempt_count>=5 then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_RECOVERY_CLAIM_CONFLICT';
    end if;
    iq_attempt:=case when iq_job.status='queued' and iq_job.attempt_count>0
            and iq_job.error_code is null then iq_job.attempt_count
        else iq_job.attempt_count+1 end;
    iq_generation:=iq_job.lease_generation+1;
    iq_expiry:=requested_now+pg_catalog.make_interval(secs=>requested_lease_seconds);
    update ingestion_quality.iq_recovery_validation_job set
        job_version=job_version+1,status='running',attempt_count=iq_attempt,
        lease_generation=iq_generation,lease_owner_digest=requested_worker_digest,
        claimed_at=requested_now,lease_expires_at=iq_expiry,error_code=null,
        next_attempt_at=null,updated_at=requested_now
     where job_id=requested_job_id;
    insert into ingestion_quality.iq_recovery_validation_attempt values (
        requested_job_id,iq_attempt,iq_generation,requested_worker_digest,
        requested_now,iq_expiry,null,null);
    return jsonb_build_object('jobId',requested_job_id,
        'attemptNumber',iq_attempt,'leaseGeneration',iq_generation,
        'leaseOwnerDigest',trim(requested_worker_digest),'claimedAt',requested_now,
        'leaseExpiresAt',iq_expiry,'checkpointVersion',iq_job.checkpoint_version,
        'checkpoint',case when iq_job.checkpoint_version=0 then null else (
          select jsonb_build_object(
            'checkpointVersion',checkpoint.checkpoint_version,
            'phase',checkpoint.phase,'phaseCompleted',checkpoint.phase_completed,
            'opaqueResumeRef',checkpoint.opaque_resume_ref,
            'cursorDigest',trim(checkpoint.cursor_digest),
            'partialSummaryDigest',trim(checkpoint.partial_summary_digest),
            'processedCount',checkpoint.processed_count,
            'mismatchCount',checkpoint.mismatch_count)
          from ingestion_quality.iq_recovery_validation_checkpoint checkpoint
         where checkpoint.job_id=requested_job_id
           and checkpoint.checkpoint_version=iq_job.checkpoint_version) end);
end
$$;

create function ingestion_quality.iq_checkpoint_recovery_validation_job(
    requested_job_id uuid,requested_generation bigint,
    expected_checkpoint_version bigint,requested_checkpoint jsonb,
    requested_now timestamptz)
returns boolean language plpgsql security definer set search_path=pg_catalog as $$
declare iq_job ingestion_quality.iq_recovery_validation_job%rowtype;
declare iq_previous ingestion_quality.iq_recovery_validation_checkpoint%rowtype;
declare iq_phase_order integer;
declare iq_previous_order integer;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    select job.* into iq_job from ingestion_quality.iq_recovery_validation_job job
     where job.job_id=requested_job_id for update;
    if iq_job.status<>'running' or iq_job.lease_generation<>requested_generation
       or iq_job.checkpoint_version<>expected_checkpoint_version
       or requested_now>=iq_job.lease_expires_at
       or (requested_checkpoint->>'checkpointVersion')::bigint<>
          expected_checkpoint_version+1 then return false; end if;
    select checkpoint.* into iq_previous
      from ingestion_quality.iq_recovery_validation_checkpoint checkpoint
     where checkpoint.job_id=requested_job_id
     order by checkpoint_version desc limit 1;
    iq_phase_order:=case requested_checkpoint->>'phase'
        when 'backfill' then 0 when 'full-reconciliation' then 1
        when 'sample-recompute' then 2 else -1 end;
    iq_previous_order:=case iq_previous.phase
        when 'backfill' then 0 when 'full-reconciliation' then 1
        when 'sample-recompute' then 2 else -1 end;
    if iq_phase_order<0 or (iq_previous.job_id is null and iq_phase_order<>0)
       or (iq_previous.job_id is not null and not (
          iq_phase_order=iq_previous_order and not iq_previous.phase_completed
          or iq_phase_order=iq_previous_order+1 and iq_previous.phase_completed)) then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_RECOVERY_CHECKPOINT_INVALID';
    end if;
    insert into ingestion_quality.iq_recovery_validation_checkpoint values (
        requested_job_id,expected_checkpoint_version+1,requested_generation,
        requested_checkpoint->>'phase',(requested_checkpoint->>'phaseCompleted')::boolean,
        requested_checkpoint->>'opaqueResumeRef',requested_checkpoint->>'cursorDigest',
        requested_checkpoint->>'partialSummaryDigest',
        (requested_checkpoint->>'processedCount')::bigint,
        (requested_checkpoint->>'mismatchCount')::bigint,requested_now);
    update ingestion_quality.iq_recovery_validation_job set
        checkpoint_version=expected_checkpoint_version+1,
        job_version=job_version+1,updated_at=requested_now
     where job_id=requested_job_id;
    return true;
end
$$;

-- Builds one bounded, PII-free executable QRP projection from owner-local immutable facts.
-- Historical subject/window identifiers never leave this function.
create function ingestion_quality.iq_build_quality_recovery_readiness_evidence(
    requested_recovery_request_id uuid,requested_now timestamptz)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_task ingestion_quality.iq_quality_recovery_task_current%rowtype;
declare iq_episode ingestion_quality.iq_quality_fuse_episode_current%rowtype;
declare iq_cursor ingestion_quality.iq_quality_dependency_cursor%rowtype;
declare iq_source_class varchar;
declare iq_required_batches integer;
declare iq_observation interval;
declare iq_actual_batches bigint:=0;
declare iq_batch_offset integer;
declare iq_batch_rows bigint;
declare iq_batch_material text;
declare iq_sequence_material text:='';
declare iq_sequence_digest text;
declare iq_rule_digest text;
declare iq_member_digest text;
declare iq_watermark_digest text;
declare iq_current_binding_digest text;
declare iq_eligibility_bindings jsonb;
declare iq_snapshot_ids_digest text;
declare iq_snapshot_hashes_digest text;
declare iq_metric_results_digest text;
declare iq_source_schema_version text;
declare iq_source_schema_digest text;
declare iq_dependency_version text;
declare iq_dependency_version_count bigint;
declare iq_dependency_digest text;
declare iq_source_watermark text;
declare iq_dependency_watermark text;
declare iq_required_members_eligible boolean:=false;
declare iq_all_metrics_passed boolean:=false;
declare iq_lkg_at timestamptz;
declare iq_lkg_watermark_utf8 bytea;
declare iq_backfill_start_at timestamptz;
declare iq_lkg_watermark text;
declare iq_backfill_start_watermark text;
declare iq_backfill_completed_watermark text;
declare iq_backfill_status varchar;
declare iq_backfill_digest text;
declare iq_population bigint:=0;
declare iq_reconstructed bigint:=0;
declare iq_reconciliation_mismatch bigint:=0;
declare iq_impact_expired bigint:=0;
declare iq_impact_potential bigint:=0;
declare iq_impact_expected bigint:=0;
declare iq_missing jsonb:='[]'::jsonb;
declare iq_evidence jsonb;
begin
    if pg_catalog.pg_has_role(session_user,
            'scholarsense_ingestion_quality_recovery_worker','USAGE') then
        perform ingestion_quality.iq_require_recovery_workload(
            'scholarsense_ingestion_quality_recovery_worker');
    else
        perform ingestion_quality.iq_require_recovery_workload(
            'scholarsense_ingestion_quality_online');
    end if;
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=requested_recovery_request_id;
    if iq_request.recovery_request_id is null then return null; end if;
    select task.* into iq_task
      from ingestion_quality.iq_quality_recovery_task_current task
     where task.task_id=iq_request.task_id;
    select episode.* into iq_episode
      from ingestion_quality.iq_quality_fuse_episode_current episode
     where episode.episode_id=iq_request.episode_id;
    if iq_task.task_id is null or iq_task.status<>'open' then
        iq_missing:=iq_missing||'"OPEN_RECOVERY_TASK_MISSING"'::jsonb;
    end if;
    if iq_episode.episode_id is null or not iq_episode.active then
        iq_missing:=iq_missing||'"ACTIVE_EPISODE_MISSING"'::jsonb;
    end if;

    case iq_request.source_id
      when 'SRC-P0-ACCOMMODATION-001' then iq_source_class:='streaming';
      when 'SRC-P0-CARD-001' then iq_source_class:='streaming';
      when 'SRC-P0-CAMPUS-ACCESS-001' then iq_source_class:='streaming';
      when 'SRC-P0-DORM-ACCESS-001' then iq_source_class:='streaming';
      when 'SRC-P0-DEVICE-001' then iq_source_class:='streaming';
      when 'SRC-P0-LEAVE-001' then iq_source_class:='streaming';
      when 'SRC-P0-CALENDAR-001' then iq_source_class:='streaming';
      when 'SRC-P0-TIMETABLE-001' then iq_source_class:='streaming';
      when 'SRC-P1-OFFCAMPUS-001' then iq_source_class:='streaming';
      when 'SRC-P1-NETWORK-001' then iq_source_class:='dailyBatch';
      when 'SRC-P1-ACADEMIC-001' then iq_source_class:='dailyBatch';
      else iq_source_class:=null;
    end case;
    if iq_source_class='dailyBatch' then
        iq_required_batches:=2; iq_observation:=interval '24 hours';
    elsif iq_source_class='streaming' then
        iq_required_batches:=3; iq_observation:=interval '60 minutes';
    else
        iq_required_batches:=3; iq_observation:=interval '60 minutes';
        iq_missing:=iq_missing||'"VERSION_OR_DIGEST_UNKNOWN"'::jsonb;
    end if;

    select 'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
        affected.rule_id||'@'||affected.rule_version,chr(30)
        order by affected.rule_id,affected.rule_version),'UTF8')),'hex')
      into iq_rule_digest
      from ingestion_quality.iq_quality_recovery_task_affected_rule affected
     where affected.task_id=iq_request.task_id;
    with current_member as (
        select eligibility.rule_id,eligibility.rule_version,
               eligibility.eligibility_id,eligibility.aggregate_version,
               eligibility.status eligibility_status,member.*
          from ingestion_quality.iq_quality_recovery_task_affected_rule affected
          join ingestion_quality.iq_quality_eligibility_current eligibility
            on eligibility.rule_id=affected.rule_id
           and eligibility.rule_version=affected.rule_version
           and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
          join ingestion_quality.iq_quality_eligibility_member_history member
            on member.eligibility_id=eligibility.eligibility_id
           and member.aggregate_version=eligibility.aggregate_version
         where affected.task_id=iq_request.task_id
    )
    select 'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
               dependency_id||chr(31)||dependency_version::text||chr(31)||
               requirement||chr(31)||state||chr(31)||failed::text,chr(30)
               order by dependency_id),'UTF8')),'hex'),
           'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
               replace(encode(source_watermark_utf8,'base64'),chr(10),'')||chr(31)||
               replace(encode(dependency_watermark_utf8,'base64'),chr(10),''),chr(30)
               order by dependency_id),'UTF8')),'hex'),
           'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
               snapshot_id::text,chr(30) order by rule_id,rule_version,dependency_id),
               'UTF8')),'hex'),
           'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
               trim(snapshot_immutable_hash),chr(30)
               order by rule_id,rule_version,dependency_id),'UTF8')),'hex'),
           'wm:v1:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
               replace(encode(source_watermark_utf8,'base64'),chr(10),''),chr(30)
               order by rule_id,rule_version,dependency_id),'UTF8')),'hex'),
           'wm:v1:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
               replace(encode(dependency_watermark_utf8,'base64'),chr(10),''),chr(30)
               order by rule_id,rule_version,dependency_id),'UTF8')),'hex'),
           bool_and(requirement='required' and state='eligible'
               and version_continuous and not failed and snapshot_id is not null),
           min(dependency_version) filter (
               where dependency_id=iq_request.dependency_id)::text,
           count(distinct dependency_version) filter (
               where dependency_id=iq_request.dependency_id)
      into iq_member_digest,iq_watermark_digest,
           iq_snapshot_ids_digest,iq_snapshot_hashes_digest,
           iq_source_watermark,iq_dependency_watermark,
           iq_required_members_eligible,iq_dependency_version,iq_dependency_version_count
      from current_member;
    select jsonb_agg(jsonb_build_object(
               'eligibilityId',eligibility.eligibility_id,'ruleId',eligibility.rule_id,
               'ruleVersion',eligibility.rule_version,
               'expectedAggregateVersion',eligibility.aggregate_version)
               order by eligibility.rule_id,eligibility.rule_version)
      into iq_eligibility_bindings
      from ingestion_quality.iq_quality_recovery_task_affected_rule affected
      join ingestion_quality.iq_quality_eligibility_current eligibility
        on eligibility.rule_id=affected.rule_id
       and eligibility.rule_version=affected.rule_version
       and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
     where affected.task_id=iq_request.task_id;
    iq_dependency_digest:='sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
        iq_request.dependency_id||chr(31)||coalesce(iq_dependency_version,'0'),'UTF8')),'hex');

    with current_member as (
        select eligibility.rule_id,eligibility.rule_version,member.*
          from ingestion_quality.iq_quality_recovery_task_affected_rule affected
          join ingestion_quality.iq_quality_eligibility_current eligibility
            on eligibility.rule_id=affected.rule_id
           and eligibility.rule_version=affected.rule_version
           and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
          join ingestion_quality.iq_quality_eligibility_member_history member
            on member.eligibility_id=eligibility.eligibility_id
           and member.aggregate_version=eligibility.aggregate_version
         where affected.task_id=iq_request.task_id
    ), metric_material as (
        select member.rule_id,member.rule_version,member.dependency_id,
               metric.metric_ordinal,metric.metric_id,metric.formula_id,
               metric.result,metric.applicable,metric.numerator,metric.denominator,
               metric.operator,metric.threshold_numerator,metric.threshold_denominator,
               metric.boundary
          from current_member member
          join ingestion_quality.iq_quality_snapshot_metric metric
            on metric.snapshot_id=member.snapshot_id
    )
    select 'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(coalesce(string_agg(
               rule_id||chr(31)||rule_version||chr(31)||dependency_id||chr(31)||
               metric_ordinal::text||chr(31)||metric_id||chr(31)||formula_id||chr(31)||
               result||chr(31)||applicable::text||chr(31)||numerator::text||chr(31)||
               denominator::text||chr(31)||operator||chr(31)||threshold_numerator::text||
               chr(31)||threshold_denominator::text||chr(31)||boundary,chr(30)
               order by rule_id,rule_version,dependency_id,metric_ordinal),''),'UTF8')),'hex')
      into iq_metric_results_digest from metric_material;
    select bool_and(snapshot.quality_gate_version='QG-1.0.0'
               and trim(snapshot.quality_gate_digest)=
                 'sha256:0a6701cc598be754813bdddbe7e0cbff7b65c1e03ca2cd026d63ee2d366a4e3a'
               and snapshot.qmdp_version='QMDP-1.0.0'
               and trim(snapshot.qmdp_digest)=
                 'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8'
               and snapshot.hash_profile_version='QSHM-1.0.0'
               and trim(snapshot.hash_profile_digest)=
                 'sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2'
               and member.qmdp_version='QMDP-1.0.0'
               and trim(member.qmdp_digest)=
                 'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8'
               and member.qshm_version='QSHM-1.0.0'
               and trim(member.qshm_digest)=
                 'sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2'
               and exists (select 1
                    from ingestion_quality.iq_quality_snapshot_metric metric
                   where metric.snapshot_id=snapshot.snapshot_id)
               and not exists (select 1
                    from ingestion_quality.iq_quality_snapshot_metric metric
                   where metric.snapshot_id=snapshot.snapshot_id
                     and metric.applicable and metric.result<>'passed')),
           min(snapshot.source_schema_version) filter (
               where member.source_id=iq_request.source_id),
           min(trim(snapshot.source_schema_digest)) filter (
               where member.source_id=iq_request.source_id),
           count(distinct snapshot.source_schema_version) filter (
               where member.source_id=iq_request.source_id),
           count(distinct trim(snapshot.source_schema_digest)) filter (
               where member.source_id=iq_request.source_id)
      into iq_all_metrics_passed,iq_source_schema_version,iq_source_schema_digest,
           iq_batch_rows,iq_reconstructed
      from ingestion_quality.iq_quality_recovery_task_affected_rule affected
      join ingestion_quality.iq_quality_eligibility_current eligibility
        on eligibility.rule_id=affected.rule_id
       and eligibility.rule_version=affected.rule_version
       and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
      join ingestion_quality.iq_quality_eligibility_member_history member
        on member.eligibility_id=eligibility.eligibility_id
       and member.aggregate_version=eligibility.aggregate_version
      join ingestion_quality.iq_quality_snapshot snapshot
        on snapshot.snapshot_id=member.snapshot_id
     where affected.task_id=iq_request.task_id;
    if iq_source_schema_version is null or iq_source_schema_digest is null
       or iq_batch_rows<>1 or iq_reconstructed<>1 or iq_dependency_version is null
       or iq_dependency_version_count<>1 then
        iq_missing:=iq_missing||'"VERSION_OR_DIGEST_UNKNOWN"'::jsonb;
        iq_all_metrics_passed:=false;
    end if;
    if not coalesce(iq_required_members_eligible,false) then
        iq_missing:=iq_missing||'"REQUIRED_DEPENDENCY_NOT_ELIGIBLE"'::jsonb;
    end if;
    if not coalesce(iq_all_metrics_passed,false) then
        iq_missing:=iq_missing||'"QUALITY_GATE_NOT_PASSED"'::jsonb;
    end if;

    select cursor.* into iq_cursor
      from ingestion_quality.iq_quality_dependency_cursor cursor
     where cursor.source_id=iq_request.source_id;
    if iq_cursor.source_id is not null and iq_cursor.stage='terminal' and not iq_cursor.paused then
        for iq_batch_offset in 0..iq_required_batches-1 loop
            select count(*),min(batch.source_version::text||chr(31)||batch.lineage_id::text||
                   chr(31)||snapshot.snapshot_id::text||chr(31)||trim(snapshot.immutable_hash))
              into iq_batch_rows,iq_batch_material
              from ingestion_quality.iq_data_batch batch
              join ingestion_quality.iq_quality_snapshot snapshot on snapshot.batch_id=batch.batch_id
             where batch.source_id=iq_request.source_id
               and batch.source_version=iq_cursor.source_version-iq_batch_offset
               and ingestion_quality.iq_is_quality_recovery_batch_ready(batch.batch_id)
               and snapshot.quality_gate_version='QG-1.0.0'
               and trim(snapshot.quality_gate_digest)=
                 'sha256:0a6701cc598be754813bdddbe7e0cbff7b65c1e03ca2cd026d63ee2d366a4e3a'
               and snapshot.qmdp_version='QMDP-1.0.0'
               and trim(snapshot.qmdp_digest)=
                 'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8'
               and snapshot.hash_profile_version='QSHM-1.0.0'
               and trim(snapshot.hash_profile_digest)=
                 'sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2';
            exit when iq_batch_rows<>1;
            iq_actual_batches:=iq_actual_batches+1;
            iq_sequence_material:=iq_sequence_material||iq_batch_material||chr(30);
        end loop;
    end if;
    iq_sequence_digest:='sha256:'||encode(pg_catalog.sha256(
        pg_catalog.convert_to(iq_sequence_material,'UTF8')),'hex');
    if iq_actual_batches<iq_required_batches then
        iq_missing:=iq_missing||'"CONSECUTIVE_BATCHES_INSUFFICIENT"'::jsonb;
    end if;

    select snapshot.cutoff_at,snapshot.watermark_utf8
      into iq_lkg_at,iq_lkg_watermark_utf8
      from ingestion_quality.iq_quality_snapshot snapshot
      join ingestion_quality.iq_data_batch batch on batch.batch_id=snapshot.batch_id
     where snapshot.source_id=iq_request.source_id
       and ingestion_quality.iq_is_quality_recovery_batch_ready(batch.batch_id)
       and snapshot.quality_gate_version='QG-1.0.0'
       and trim(snapshot.quality_gate_digest)=
         'sha256:0a6701cc598be754813bdddbe7e0cbff7b65c1e03ca2cd026d63ee2d366a4e3a'
       and snapshot.qmdp_version='QMDP-1.0.0'
       and trim(snapshot.qmdp_digest)=
         'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8'
       and snapshot.hash_profile_version='QSHM-1.0.0'
       and trim(snapshot.hash_profile_digest)=
         'sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2'
       and snapshot.cutoff_at<=requested_now
     order by snapshot.cutoff_at desc,snapshot.snapshot_id desc limit 1;
    iq_backfill_start_at:=greatest(coalesce(iq_lkg_at,requested_now-interval '90 days'),
        requested_now-interval '90 days');
    iq_lkg_watermark:=case when iq_lkg_watermark_utf8 is null then 'unavailable'
      else 'wm:v1:'||encode(pg_catalog.sha256(iq_lkg_watermark_utf8),'hex') end;
    iq_backfill_start_watermark:='instant:'||to_char(iq_backfill_start_at at time zone 'UTC',
        'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
    iq_backfill_completed_watermark:='wm:v1:'||substring(coalesce(
        iq_watermark_digest,'sha256:'||repeat('0',64)) from 8);
    iq_backfill_status:=case when iq_lkg_at is null then 'unavailable' else 'succeeded' end;

    with candidate as (
        select historical.*,
               not exists (
                 select 1 from jsonb_each_text(historical.source_versions) source_version
                  where source_version.value !~ '^[1-9][0-9]{0,15}$'
                     or not exists (
                       select 1 from ingestion_quality.iq_data_batch batch
                       join ingestion_quality.iq_quality_snapshot snapshot
                         on snapshot.batch_id=batch.batch_id
                      where batch.source_id=source_version.key
                        and batch.source_version=source_version.value::bigint
                        and ingestion_quality.iq_is_quality_recovery_batch_ready(batch.batch_id)
                        and snapshot.quality_gate_version='QG-1.0.0'
                        and trim(snapshot.quality_gate_digest)=
                          'sha256:0a6701cc598be754813bdddbe7e0cbff7b65c1e03ca2cd026d63ee2d366a4e3a'
                        and snapshot.qmdp_version='QMDP-1.0.0'
                        and trim(snapshot.qmdp_digest)=
                          'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8'
                        and snapshot.hash_profile_version='QSHM-1.0.0'
                        and trim(snapshot.hash_profile_digest)=
                          'sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2'))
               and historical.quality_gate_versions @> '["QG-1.0.0"]'::jsonb reconstructable
          from ingestion_quality.iq_historical_window historical
         where historical.end_at>iq_backfill_start_at
           and historical.start_at<requested_now
           and exists (select 1
             from ingestion_quality.iq_quality_recovery_task_affected_rule affected
            where affected.task_id=iq_request.task_id
              and affected.rule_id=historical.rule_id
              and affected.rule_version=historical.rule_version)
    )
    select count(*),count(*) filter (where reconstructable),
           count(*) filter (where latest_actionable_at is null
                                  or latest_actionable_at<requested_now),
           count(*) filter (where latest_actionable_at>=requested_now+iq_observation),
           count(*) filter (where latest_actionable_at>=requested_now
                                  and latest_actionable_at<requested_now+iq_observation)
      into iq_population,iq_reconstructed,iq_impact_expired,
           iq_impact_potential,iq_impact_expected from candidate;
    iq_reconciliation_mismatch:=iq_population-iq_reconstructed;
    iq_backfill_digest:='sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
        iq_lkg_watermark||chr(31)||iq_backfill_start_watermark||chr(31)||
        iq_backfill_completed_watermark||chr(31)||iq_population::text||chr(31)||
        iq_backfill_status,'UTF8')),'hex');
    if iq_backfill_status<>'succeeded' then
        iq_missing:=iq_missing||'"BACKFILL_INCOMPLETE"'::jsonb;
    end if;
    if iq_reconciliation_mismatch<>0 then
        iq_missing:=iq_missing||'"RECONCILIATION_MISMATCH"'::jsonb;
    end if;
    if iq_rule_digest is null or iq_member_digest is null or iq_watermark_digest is null
       or iq_rule_digest<>trim(iq_request.affected_rule_versions_digest)
       or iq_member_digest<>trim(iq_request.member_set_digest)
       or iq_watermark_digest<>trim(iq_request.watermarks_digest)
       or iq_task.aggregate_version<>iq_request.task_version
       or iq_episode.aggregate_version<>iq_request.episode_version
       or iq_episode.generation<>iq_request.episode_generation then
        iq_missing:=iq_missing||'"VERSION_OR_DIGEST_UNKNOWN"'::jsonb;
    end if;
    select jsonb_agg(to_jsonb(code) order by code) into iq_missing
      from (select distinct value #>> '{}' code from jsonb_array_elements(iq_missing)) missing;
    iq_missing:=coalesce(iq_missing,'[]'::jsonb);
    iq_current_binding_digest:='sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
        coalesce(iq_rule_digest,'')||chr(31)||coalesce(iq_member_digest,'')||chr(31)||
        coalesce(iq_watermark_digest,'')||chr(31)||coalesce(iq_episode.aggregate_version,0)::text||
        chr(31)||coalesce(iq_task.aggregate_version,0)::text||chr(31)||
        coalesce(iq_eligibility_bindings,'[]'::jsonb)::text,'UTF8')),'hex');
    iq_evidence:=jsonb_build_object(
        'episodeGeneration',coalesce(iq_episode.generation,iq_request.episode_generation),
        'expectedEpisodeVersion',coalesce(iq_episode.aggregate_version,iq_request.episode_version),
        'expectedTaskVersion',coalesce(iq_task.aggregate_version,iq_request.task_version),
        'sourceId',iq_request.source_id,'dependencyId',iq_request.dependency_id,
        'ruleVersionsDigest',coalesce(iq_rule_digest,trim(iq_request.affected_rule_versions_digest)),
        'memberSetDigest',coalesce(iq_member_digest,trim(iq_request.member_set_digest)),
        'watermarksDigest',coalesce(iq_watermark_digest,trim(iq_request.watermarks_digest)),
        'sourceClass',coalesce(iq_source_class,'streaming'),
        'sourceSchemaVersion',coalesce(iq_source_schema_version,'UNKNOWN-0.0.0'),
        'sourceSchemaDigest',coalesce(iq_source_schema_digest,'sha256:'||repeat('0',64)),
        'dependencyVersion',coalesce(iq_dependency_version,'1'),
        'dependencyDigest',iq_dependency_digest,
        'eligibilityBindings',coalesce(iq_eligibility_bindings,'[]'::jsonb),
        'currentBindingDigest',iq_current_binding_digest,
        'qualityEvidence',jsonb_build_object(
          'snapshotIdsDigest',coalesce(iq_snapshot_ids_digest,'sha256:'||repeat('0',64)),
          'snapshotHashesDigest',coalesce(iq_snapshot_hashes_digest,'sha256:'||repeat('0',64)),
          'metricResultsDigest',coalesce(iq_metric_results_digest,'sha256:'||repeat('0',64)),
          'allRequiredMetricsPassed',coalesce(iq_all_metrics_passed,false),
          'sourceWatermark',coalesce(iq_source_watermark,'unavailable'),
          'dependencyWatermark',coalesce(iq_dependency_watermark,'unavailable')),
        'batchEvidence',jsonb_build_object(
          'sequenceEvidenceVersion','QUALITY-RECOVERY-BATCH-SEQUENCE-1.0.0',
          'sequenceEvidenceDigest',iq_sequence_digest,
          'requiredConsecutivePassedBatches',iq_required_batches,
          'actualConsecutivePassedBatches',iq_actual_batches,
          'qualified',iq_actual_batches>=iq_required_batches),
        'backfillEvidence',jsonb_build_object(
          'lastKnownGoodWatermark',iq_lkg_watermark,'trustedNow',requested_now,
          'lookbackDays',90,'backfillStartWatermark',iq_backfill_start_watermark,
          'backfillCompletedWatermark',iq_backfill_completed_watermark,
          'evidenceDigest',iq_backfill_digest,'status',iq_backfill_status),
        'requiredMembersEligible',coalesce(iq_required_members_eligible,false),
        'missingEvidenceCodes',iq_missing,
        'impactAlreadyExpiredCount',iq_impact_expired,
        'impactPotentiallyActionableCount',iq_impact_potential,
        'impactExpectedToExpireCount',iq_impact_expected,
        'trustedStartedAt',requested_now);
    return iq_evidence||jsonb_build_object('evidenceDigest','sha256:'||encode(
        pg_catalog.sha256(pg_catalog.convert_to(iq_evidence::text,'UTF8')),'hex'),
        'populationCount',iq_population,'reconstructedCount',iq_reconstructed,
        'reconciliationMismatchCount',iq_reconciliation_mismatch);
end
$$;

create function ingestion_quality.iq_finalize_recovery_validation_job(
    requested_job_id uuid,requested_generation bigint,
    requested_result jsonb,requested_now timestamptz)
returns boolean language plpgsql security definer set search_path=pg_catalog as $$
declare iq_job ingestion_quality.iq_recovery_validation_job%rowtype;
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_state varchar:=requested_result->>'state';
declare iq_request_state varchar;
declare iq_evidence_digest text;
declare iq_preview_digest text;
declare iq_current_readiness jsonb;
declare iq_evidence jsonb;
declare iq_preview jsonb;
declare iq_preview_object jsonb;
declare iq_rule_windows jsonb;
declare iq_observation interval;
declare iq_observation_text varchar;
declare iq_source_class varchar;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    select job.* into iq_job from ingestion_quality.iq_recovery_validation_job job
     where job.job_id=requested_job_id for update;
    if iq_job.status<>'running' or iq_job.lease_generation<>requested_generation
       or requested_now>=iq_job.lease_expires_at
       or requested_result->>'jobId'<>requested_job_id::text
       or requested_result->>'schemaVersion'<>'QUALITY-RECOVERY-VALIDATION-RESULT-1.1.0'
       or requested_result->>'inputDigest'<>trim(iq_job.input_digest)
       or iq_state not in ('succeeded','failed','cancelled') then return false; end if;
    iq_current_readiness:=ingestion_quality.iq_build_quality_recovery_readiness_evidence(
        iq_job.recovery_request_id,
        (requested_result#>>'{readinessEvidence,trustedStartedAt}')::timestamptz);
    if iq_current_readiness is null
       or iq_current_readiness->>'evidenceDigest'<>
          requested_result#>>'{readinessEvidence,evidenceDigest}'
       or (requested_result->>'qualified')::boolean<>
          (jsonb_array_length(requested_result#>'{readinessEvidence,missingEvidenceCodes}')=0
           and (requested_result#>>'{readinessEvidence,qualityEvidence,allRequiredMetricsPassed}')::boolean
           and (requested_result#>>'{readinessEvidence,batchEvidence,qualified}')::boolean
           and requested_result#>>'{readinessEvidence,backfillEvidence,status}'='succeeded'
           and (requested_result#>>'{readinessEvidence,requiredMembersEligible}')::boolean
           and (requested_result->>'reconciliationExpectedCount')::bigint=
               (requested_result->>'reconciliationActualCount')::bigint
           and (requested_result->>'reconciliationMismatchCount')::bigint=0
           and (requested_result->>'selectedCount')::bigint>=least(
               (requested_result->>'populationCount')::bigint,100)
           and (requested_result->>'mismatchCount')::bigint=0) then return false; end if;
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=iq_job.recovery_request_id for update;
    insert into ingestion_quality.iq_recovery_validation_result(
        result_id,job_id,job_version,recovery_request_id,state,input_digest,
        qualified,error_code,result_digest,result,completed_at,trace_id,legal_hold) values (
        (requested_result->>'resultId')::uuid,requested_job_id,iq_job.job_version,
        iq_job.recovery_request_id,iq_state,requested_result->>'inputDigest',
        (requested_result->>'qualified')::boolean,requested_result->>'errorCode',
        requested_result->>'resultDigest',requested_result,requested_now,
        requested_result->>'traceId',iq_request.legal_hold);
    update ingestion_quality.iq_recovery_validation_job set
        job_version=job_version+1,status=iq_state,result_digest=requested_result->>'resultDigest',
        error_code=requested_result->>'errorCode',lease_owner_digest=null,
        claimed_at=null,lease_expires_at=null,next_attempt_at=null,
        updated_at=requested_now,completed_at=requested_now
     where job_id=requested_job_id;
    update ingestion_quality.iq_recovery_validation_attempt set
        released_at=requested_now,outcome=iq_state
     where job_id=requested_job_id and lease_generation=requested_generation;
    iq_request_state:=case when iq_state='succeeded'
                                and (requested_result->>'qualified')::boolean
                           then 'validation-succeeded'
                           else 'validation-failed' end;
    insert into ingestion_quality.iq_quality_recovery_request_history(
        recovery_request_id,request_version,task_id,episode_id,source_id,
        dependency_id,status,request_digest,aggregate,occurred_at,legal_hold)
    values (iq_request.recovery_request_id,iq_request.request_version+1,
        iq_request.task_id,iq_request.episode_id,iq_request.source_id,
        iq_request.dependency_id,iq_request_state,iq_request.request_digest,
        iq_request.aggregate||jsonb_build_object(
            'requestVersion',iq_request.request_version+1,'status',iq_request_state),
        requested_now,iq_request.legal_hold);
    update ingestion_quality.iq_quality_recovery_request_current set
        request_version=request_version+1,status=iq_request_state,
        aggregate=aggregate||jsonb_build_object(
            'requestVersion',request_version+1,'status',iq_request_state),
        updated_at=requested_now
     where recovery_request_id=iq_job.recovery_request_id;
    if iq_state='succeeded' and (requested_result->>'qualified')::boolean then
        iq_source_class:=requested_result#>>'{readinessEvidence,sourceClass}';
        if iq_source_class='dailyBatch' then
            iq_observation:=interval '1 day'; iq_observation_text:='P1D';
        elsif iq_source_class='streaming' then
            iq_observation:=interval '60 minutes'; iq_observation_text:='PT60M';
        else
            return false;
        end if;
        select jsonb_build_object(
            'recoveryRequestId',iq_request.recovery_request_id,
            'eligibilityId',binding->>'eligibilityId',
            'episodeId',iq_request.episode_id,'taskId',iq_request.task_id,
            'episodeGeneration',(requested_result#>>'{readinessEvidence,episodeGeneration}')::bigint,
            'sourceId',iq_request.source_id,'dependencyId',iq_request.dependency_id,
            'expectedEligibilityVersion',(binding->>'expectedAggregateVersion')::bigint,
            'expectedEpisodeVersion',(requested_result#>>'{readinessEvidence,expectedEpisodeVersion}')::bigint,
            'expectedTaskVersion',(requested_result#>>'{readinessEvidence,expectedTaskVersion}')::bigint,
            'ruleVersionsDigest',requested_result#>>'{readinessEvidence,ruleVersionsDigest}',
            'memberSetDigest',requested_result#>>'{readinessEvidence,memberSetDigest}',
            'watermarksDigest',requested_result#>>'{readinessEvidence,watermarksDigest}')
          into iq_preview_object
          from jsonb_array_elements(
            requested_result#>'{readinessEvidence,eligibilityBindings}') binding
         order by binding->>'ruleId',binding->>'ruleVersion' limit 1;
        select jsonb_agg(jsonb_build_object(
            'ruleVersion',historical.rule_id||'@'||historical.rule_version,
            'windowDigest','sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
              historical.window_id,'UTF8')),'hex'),
            'latestActionableAt',historical.latest_actionable_at,
            'category',case
              when historical.latest_actionable_at<requested_now
                then 'already-expired-history-only'
              when historical.latest_actionable_at>=requested_now+iq_observation
                then 'currently-potentially-actionable'
              else 'expected-to-expire-before-observation-completes' end)
            order by historical.rule_id,historical.rule_version,
              historical.latest_actionable_at,historical.window_id)
          into iq_rule_windows
          from ingestion_quality.iq_historical_window historical
         where exists (select 1
           from ingestion_quality.iq_quality_recovery_task_affected_rule affected
          where affected.task_id=iq_request.task_id
            and affected.rule_id=historical.rule_id
            and affected.rule_version=historical.rule_version)
           and historical.end_at>requested_now-interval '90 days'
           and historical.start_at<requested_now;
        if iq_preview_object is null or iq_rule_windows is null
           or jsonb_array_length(iq_rule_windows)=0
           or jsonb_array_length(iq_rule_windows)>10000 then return false; end if;
        iq_preview:=jsonb_build_object(
            'schemaVersion','QUALITY-RECOVERY-IMPACT-PREVIEW-1.0.0',
            'previewId',requested_result->>'resultId','previewVersion',1,
            'object',iq_preview_object,
            'qualityRecoveryPolicyVersion',requested_result->>'qualityRecoveryPolicyVersion',
            'qualityRecoveryPolicyDigest',requested_result->>'qualityRecoveryPolicyDigest',
            'sourceClass',iq_source_class,'targetState','recovering',
            'trustedServerNow',requested_now,
            'observationDuration',iq_observation_text,
            'expectedObservationCompletesAt',requested_now+iq_observation,
            'ruleVersionWindows',iq_rule_windows,'authorizesExecution',false,
            'finalActionabilityOwnerStory','2.5c',
            'driftHandling','invalidate-and-require-explicit-repreview',
            'generatedAt',requested_now,'traceId',requested_result->>'traceId',
            'canonicalizationProfile','SCHOLARSENSE-CANONICAL-JSON-1.0.0',
            'runtimeEvidenceClaim','installed-and-verified');
        iq_preview_digest:='sha256:'||encode(pg_catalog.sha256(
            pg_catalog.convert_to(iq_preview::text,'UTF8')),'hex');
        iq_preview:=iq_preview||jsonb_build_object('previewDigest',iq_preview_digest);
        iq_evidence:=jsonb_build_object(
            'schemaVersion','QUALITY-RECOVERY-EVIDENCE-PACK-1.1.0',
            'evidencePackId',requested_result->>'resultId','evidencePackVersion',1,
            'object',jsonb_build_object(
              'recoveryRequestId',iq_request.recovery_request_id,
              'episodeId',iq_request.episode_id,'taskId',iq_request.task_id,
              'episodeGeneration',(requested_result#>>'{readinessEvidence,episodeGeneration}')::bigint,
              'sourceId',iq_request.source_id,'dependencyId',iq_request.dependency_id,
              'expectedEpisodeVersion',(requested_result#>>'{readinessEvidence,expectedEpisodeVersion}')::bigint,
              'expectedTaskVersion',(requested_result#>>'{readinessEvidence,expectedTaskVersion}')::bigint,
              'ruleVersionsDigest',requested_result#>>'{readinessEvidence,ruleVersionsDigest}',
              'memberSetDigest',requested_result#>>'{readinessEvidence,memberSetDigest}',
              'watermarksDigest',requested_result#>>'{readinessEvidence,watermarksDigest}',
              'eligibilityBindings',requested_result#>'{readinessEvidence,eligibilityBindings}',
              'currentBindingDigest',requested_result#>>'{readinessEvidence,currentBindingDigest}'),
            'policy',jsonb_build_object(
              'qualityRecoveryPolicyVersion','QRP-1.0.0',
              'qualityRecoveryPolicyDigest',requested_result->>'qualityRecoveryPolicyDigest',
              'highRiskActionPolicyVersion','HRAP-1.0.0',
              'highRiskActionPolicyDigest','sha256:1db5201136807c3bcf8fa62186414b7c79c062f595920012edd0001c77804900',
              'highRiskActionMatrixVersion','HRAM-1.0.0',
              'highRiskActionMatrixDigest','sha256:96142286aa1da5633e77a8c435df37102744a61b573eae189ad44fe1e53251ec',
              'roleFieldPolicyVersion','RFP-1.0.0',
              'roleFieldPolicyDigest','sha256:84191d8b844b31ef91fb051d34d6731b80981d6d193e42b0f8d19f73caa9ed25',
              'dataContractCatalogVersion','DCC-1.1.0',
              'dataContractCatalogDigest','sha256:aeb19962071e2144a85bb2e07fd124eff0d69edf93f7202e821204616a60f219',
              'qualityGateVersion','QG-1.0.0',
              'qualityGateDigest','sha256:0a6701cc598be754813bdddbe7e0cbff7b65c1e03ca2cd026d63ee2d366a4e3a',
              'qualityMetricDecisionProfileVersion','QMDP-1.0.0',
              'qualityMetricDecisionProfileDigest','sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8',
              'qualitySnapshotHashProfileVersion','QSHM-1.0.0',
              'qualitySnapshotHashProfileDigest','sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2',
              'ruleDependencyRegistryVersion','RULE-DEPENDENCY-REGISTRY-1.0.0',
              'ruleDependencyRegistryDigest','sha256:cd1915107c0c2657a430c2d5ba0abd420d9bd8d06f6cdcaed2a570c71ce6655a',
              'ruleCatalogVersion','RC-1.0.0',
              'ruleCatalogDigest','sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a'),
            'sourceClass',requested_result#>>'{readinessEvidence,sourceClass}',
            'sourceSchemaVersion',requested_result#>>'{readinessEvidence,sourceSchemaVersion}',
            'sourceSchemaDigest',requested_result#>>'{readinessEvidence,sourceSchemaDigest}',
            'dependencyVersion',requested_result#>>'{readinessEvidence,dependencyVersion}',
            'dependencyDigest',requested_result#>>'{readinessEvidence,dependencyDigest}',
            'qualityEvidence',requested_result#>'{readinessEvidence,qualityEvidence}',
            'batchEvidence',requested_result#>'{readinessEvidence,batchEvidence}',
            'backfillEvidence',requested_result#>'{readinessEvidence,backfillEvidence}',
            'reconciliationEvidence',jsonb_build_object(
              'summaryVersion','QUALITY-RECOVERY-RECONCILIATION-SUMMARY-1.0.0',
              'coverage','full',
              'expectedCount',(requested_result->>'reconciliationExpectedCount')::bigint,
              'actualCount',(requested_result->>'reconciliationActualCount')::bigint,
              'mismatchCount',(requested_result->>'reconciliationMismatchCount')::bigint,
              'summaryDigest',requested_result->>'reconciliationSummaryDigest',
              'completedAt',requested_result->>'completedAt'),
            'sampleEvidence',jsonb_build_object(
              'summaryVersion',requested_result->>'sampleSummaryVersion',
              'providerVersion','RECOVERY-SAMPLE-PROVIDER-1.0.0',
              'providerAvailability','available',
              'selectionSeed',iq_job.binding->>'selectionSeed',
              'populationCount',(requested_result->>'populationCount')::bigint,
              'selectedCount',(requested_result->>'selectedCount')::bigint,
              'strata',requested_result->'strata',
              'strataSummaryDigest',requested_result->>'strataSummaryDigest',
              'expectedDigest',requested_result->>'expectedDigest',
              'actualDigest',requested_result->>'actualDigest',
              'mismatchCount',(requested_result->>'mismatchCount')::bigint,
              'completedAt',requested_result->>'completedAt'),
            'previewDigest',iq_preview_digest,'readinessDecision','ready-for-d4',
            'missingEvidenceCodes',requested_result#>'{readinessEvidence,missingEvidenceCodes}',
            'trustedStartedAt',requested_result#>>'{readinessEvidence,trustedStartedAt}',
            'trustedCompletedAt',requested_now,
            'validationResultDigest',requested_result->>'resultDigest',
            'traceId',requested_result->>'traceId',
            'canonicalizationProfile','SCHOLARSENSE-CANONICAL-JSON-1.0.0',
            'runtimeEvidenceClaim','installed-and-verified');
        iq_evidence_digest:='sha256:'||encode(pg_catalog.sha256(
            pg_catalog.convert_to(iq_evidence::text,'UTF8')),'hex');
        iq_evidence:=iq_evidence||jsonb_build_object(
            'evidencePackDigest',iq_evidence_digest);
        insert into ingestion_quality.iq_quality_recovery_evidence_pack(
            evidence_pack_id,recovery_request_id,validation_job_id,evidence_pack_digest,
            input_digest,evidence,created_at,legal_hold) values (
            (requested_result->>'resultId')::uuid,iq_job.recovery_request_id,
            requested_job_id,iq_evidence_digest,requested_result->>'inputDigest',
            iq_evidence,requested_now,iq_request.legal_hold);
        insert into ingestion_quality.iq_quality_recovery_preview(
            preview_id,recovery_request_id,preview_version,input_digest,preview_digest,
            preview,generated_at,expires_at,invalidated_at,legal_hold) values (
            (requested_result->>'resultId')::uuid,iq_job.recovery_request_id,1,
            requested_result->>'inputDigest',iq_preview_digest,
            iq_preview,
            requested_now,requested_now+interval '15 minutes',null,iq_request.legal_hold);
    end if;
    return true;
end
$$;

create function ingestion_quality.iq_execute_recovery_backfill(
    requested_recovery_request_id uuid,requested_episode_id uuid,requested_task_id uuid,
    requested_rule_versions_digest character,requested_member_set_digest character,
    requested_start_watermark_digest character,requested_target_watermark_digest character,
    requested_trace_id character)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_processed bigint;
declare iq_summary char(71);
declare iq_completed_at timestamptz:=statement_timestamp();
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=requested_recovery_request_id for share;
    if iq_request.recovery_request_id is null
       or iq_request.episode_id<>requested_episode_id
       or iq_request.task_id<>requested_task_id
       or trim(iq_request.affected_rule_versions_digest)<>
          trim(requested_rule_versions_digest)
       or trim(iq_request.member_set_digest)<>trim(requested_member_set_digest)
       or requested_start_watermark_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_target_watermark_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_trace_id !~ '^(?!0{32}$)[0-9a-f]{32}$' then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_RECOVERY_BACKFILL_BINDING_INVALID';
    end if;
    insert into ingestion_quality.iq_recovery_backfill_window_result(
        recovery_request_id,window_id,expected_digest,normalized_input_digest,
        recomputed_digest,reconstructable,processed_at,legal_hold)
    select requested_recovery_request_id,historical.window_id,
        trim(historical.input_digest),normalized_input_digest,
        'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
          'RECOVERY-NORMALIZED-WINDOW-1.0.0'||chr(10)||
          normalized_input_digest,'UTF8')),'hex'),
        not exists (
          select 1 from jsonb_each_text(historical.source_versions) source_version
           where source_version.value !~ '^[1-9][0-9]{0,15}$'
              or not exists (
                select 1 from ingestion_quality.iq_data_batch batch
                join ingestion_quality.iq_quality_snapshot snapshot
                  on snapshot.batch_id=batch.batch_id
               where batch.source_id=source_version.key
                 and batch.source_version=source_version.value::bigint
                 and ingestion_quality.iq_is_quality_recovery_batch_ready(batch.batch_id)
                 and snapshot.quality_gate_version='QG-1.0.0'
                 and trim(snapshot.quality_gate_digest)=
                   'sha256:0a6701cc598be754813bdddbe7e0cbff7b65c1e03ca2cd026d63ee2d366a4e3a'
                 and snapshot.qmdp_version='QMDP-1.0.0'
                 and trim(snapshot.qmdp_digest)=
                   'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8'
                 and snapshot.hash_profile_version='QSHM-1.0.0'
                 and trim(snapshot.hash_profile_digest)=
                   'sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2'))
          and historical.quality_gate_versions @> '["QG-1.0.0"]'::jsonb,
        iq_completed_at,iq_request.legal_hold
      from ingestion_quality.iq_historical_window historical
      cross join lateral (select 'sha256:'||encode(pg_catalog.sha256(
        pg_catalog.convert_to(
          ingestion_quality.iq_json_canonical(historical.source_versions)||chr(31)||
          ingestion_quality.iq_json_canonical(historical.source_watermarks)||chr(31)||
          historical.mapping_version::text||chr(31)||
          ingestion_quality.iq_json_canonical(historical.quality_gate_versions)||chr(31)||
          historical.rule_id||
          chr(31)||historical.rule_version||chr(31)||historical.scenario_id,'UTF8')),'hex')
        normalized_input_digest) material
     where historical.end_at>greatest(iq_completed_at-interval '90 days',
            coalesce((select max(snapshot.cutoff_at)
              from ingestion_quality.iq_quality_snapshot snapshot
              join ingestion_quality.iq_data_batch batch on batch.batch_id=snapshot.batch_id
             where snapshot.source_id=iq_request.source_id
               and ingestion_quality.iq_is_quality_recovery_batch_ready(batch.batch_id)
               and snapshot.cutoff_at<=iq_completed_at),
              iq_completed_at-interval '90 days'))
       and historical.start_at<iq_completed_at
       and exists (select 1
         from ingestion_quality.iq_quality_recovery_task_affected_rule affected
        where affected.task_id=iq_request.task_id
          and affected.rule_id=historical.rule_id
          and affected.rule_version=historical.rule_version)
    on conflict (recovery_request_id,window_id) do nothing;
    select count(*) into iq_processed
      from ingestion_quality.iq_recovery_backfill_window_result result
     where result.recovery_request_id=requested_recovery_request_id;
    iq_summary:='sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
        requested_recovery_request_id::text||chr(31)||
        trim(requested_start_watermark_digest)||chr(31)||
        trim(requested_target_watermark_digest)||chr(31)||iq_processed::text||chr(31)||
        coalesce((select string_agg(window_id||chr(31)||trim(expected_digest)||chr(31)||
          trim(normalized_input_digest)||chr(31)||trim(recomputed_digest)||chr(31)||
          reconstructable::text,chr(30) order by window_id)
          from ingestion_quality.iq_recovery_backfill_window_result
         where recovery_request_id=requested_recovery_request_id),''),'UTF8')),'hex');
    insert into ingestion_quality.iq_recovery_backfill_run(
        recovery_request_id,start_watermark_digest,target_watermark_digest,
        processed_count,summary_digest,completed_at,legal_hold) values(
        requested_recovery_request_id,requested_start_watermark_digest,
        requested_target_watermark_digest,iq_processed,iq_summary,iq_completed_at,
        iq_request.legal_hold)
    on conflict (recovery_request_id) do nothing;
    select run.processed_count,run.summary_digest,run.completed_at
      into iq_processed,iq_summary,iq_completed_at
      from ingestion_quality.iq_recovery_backfill_run run
     where run.recovery_request_id=requested_recovery_request_id;
    return jsonb_build_object(
        'startWatermarkDigest',trim(requested_start_watermark_digest),
        'targetWatermarkDigest',trim(requested_target_watermark_digest),
        'processedCount',iq_processed,'summaryDigest',trim(iq_summary),
        'completedAt',iq_completed_at);
end
$$;

create function ingestion_quality.iq_execute_recovery_full_reconciliation(
    requested_recovery_request_id uuid,requested_episode_id uuid,requested_task_id uuid,
    requested_rule_versions_digest character,requested_member_set_digest character,
    requested_watermarks_digest character,requested_backfill_summary_digest character,
    requested_trace_id character)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_run ingestion_quality.iq_recovery_backfill_run%rowtype;
declare iq_expected bigint;
declare iq_actual bigint;
declare iq_mismatch bigint;
declare iq_summary char(71);
declare iq_completed_at timestamptz:=statement_timestamp();
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=requested_recovery_request_id;
    select run.* into iq_run from ingestion_quality.iq_recovery_backfill_run run
     where run.recovery_request_id=requested_recovery_request_id;
    if iq_request.recovery_request_id is null or iq_run.recovery_request_id is null
       or iq_request.episode_id<>requested_episode_id
       or iq_request.task_id<>requested_task_id
       or trim(iq_request.affected_rule_versions_digest)<>
          trim(requested_rule_versions_digest)
       or trim(iq_request.member_set_digest)<>trim(requested_member_set_digest)
       or trim(iq_request.watermarks_digest)<>trim(requested_watermarks_digest)
       or trim(iq_run.summary_digest)<>trim(requested_backfill_summary_digest)
       or requested_trace_id !~ '^(?!0{32}$)[0-9a-f]{32}$' then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_RECOVERY_RECONCILIATION_BINDING_INVALID';
    end if;
    select iq_run.processed_count,count(*),count(*) filter (
        where not result.reconstructable
           or trim(result.expected_digest)<>trim(result.recomputed_digest))
      into iq_expected,iq_actual,iq_mismatch
      from ingestion_quality.iq_recovery_backfill_window_result result
     where result.recovery_request_id=requested_recovery_request_id
     group by iq_run.processed_count;
    iq_expected:=coalesce(iq_expected,0); iq_actual:=coalesce(iq_actual,0);
    iq_mismatch:=coalesce(iq_mismatch,0)+abs(iq_expected-iq_actual);
    iq_summary:='sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
        requested_recovery_request_id::text||chr(31)||trim(iq_run.summary_digest)||chr(31)||
        iq_expected::text||chr(31)||iq_actual::text||chr(31)||iq_mismatch::text,
        'UTF8')),'hex');
    return jsonb_build_object('expectedCount',iq_expected,'actualCount',iq_actual,
        'mismatchCount',iq_mismatch,'summaryDigest',trim(iq_summary),
        'completedAt',iq_completed_at);
end
$$;

create function ingestion_quality.iq_load_recovery_validation_execution_context(
    requested_job_id uuid,requested_now timestamptz)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare iq_job ingestion_quality.iq_recovery_validation_job%rowtype;
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_readiness jsonb;
declare iq_population bigint;
declare iq_reconstructed bigint;
declare iq_selected jsonb;
declare iq_start_digest text;
declare iq_target_digest text;
declare iq_backfill_digest text;
declare iq_reconciliation_digest text;
declare iq_selection_ref text;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    select job.* into iq_job from ingestion_quality.iq_recovery_validation_job job
     where job.job_id=requested_job_id;
    if iq_job.job_id is null or iq_job.status<>'running'
       or requested_now>=iq_job.lease_expires_at then return null; end if;
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=iq_job.recovery_request_id;
    iq_readiness:=ingestion_quality.iq_build_quality_recovery_readiness_evidence(
        iq_request.recovery_request_id,requested_now);
    if iq_readiness is null then return null; end if;
    iq_population:=(iq_readiness->>'populationCount')::bigint;
    iq_reconstructed:=(iq_readiness->>'reconstructedCount')::bigint;
    with last_good as (
        select snapshot.cutoff_at
          from ingestion_quality.iq_quality_snapshot snapshot
          join ingestion_quality.iq_data_batch batch on batch.batch_id=snapshot.batch_id
         where snapshot.source_id=iq_request.source_id
           and ingestion_quality.iq_is_quality_recovery_batch_ready(batch.batch_id)
           and snapshot.cutoff_at<=requested_now
         order by snapshot.cutoff_at desc,snapshot.snapshot_id desc limit 1
    ), candidate as (
        select historical.window_id,historical.rule_id,historical.rule_version,
               trim(historical.input_digest) expected_digest,
               not exists (
                 select 1 from jsonb_each_text(historical.source_versions) source_version
                  where source_version.value !~ '^[1-9][0-9]{0,15}$'
                     or not exists (
                       select 1 from ingestion_quality.iq_data_batch batch
                       join ingestion_quality.iq_quality_snapshot snapshot
                         on snapshot.batch_id=batch.batch_id
                      where batch.source_id=source_version.key
                        and batch.source_version=source_version.value::bigint
                        and ingestion_quality.iq_is_quality_recovery_batch_ready(batch.batch_id)
                        and snapshot.quality_gate_version='QG-1.0.0'
                        and trim(snapshot.quality_gate_digest)=
                          'sha256:0a6701cc598be754813bdddbe7e0cbff7b65c1e03ca2cd026d63ee2d366a4e3a'
                        and snapshot.qmdp_version='QMDP-1.0.0'
                        and trim(snapshot.qmdp_digest)=
                          'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8'
                        and snapshot.hash_profile_version='QSHM-1.0.0'
                        and trim(snapshot.hash_profile_digest)=
                          'sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2'))
               and historical.quality_gate_versions @> '["QG-1.0.0"]'::jsonb reconstructable
          from ingestion_quality.iq_historical_window historical
         where historical.end_at>greatest(coalesce((select cutoff_at from last_good),
                    requested_now-interval '90 days'),requested_now-interval '90 days')
           and historical.start_at<requested_now
           and exists (select 1
             from ingestion_quality.iq_quality_recovery_task_affected_rule affected
            where affected.task_id=iq_request.task_id
              and affected.rule_id=historical.rule_id
              and affected.rule_version=historical.rule_version)
    ), stratum_ranked as (
        select candidate.*,
          'RULE_'||regexp_replace(upper(rule_id),'[^A-Z0-9]','_','g')||'_V'||
            regexp_replace(rule_version,'[^0-9]','_','g') stratum_code,
          row_number() over (partition by rule_id,rule_version order by pg_catalog.sha256(
            pg_catalog.convert_to(trim(iq_job.binding->>'selectionSeed')||window_id,'UTF8')))
            stratum_rank
          from candidate
    ), ranked as (
        select stratum_ranked.*,row_number() over (order by stratum_rank,stratum_code,
            pg_catalog.sha256(pg_catalog.convert_to(
              trim(iq_job.binding->>'selectionSeed')||window_id,'UTF8'))) selection_rank
          from stratum_ranked
    ), strata_base as (
        select ranked.stratum_code,count(*) population_count,
               coalesce(jsonb_agg(jsonb_build_object(
                   'selectionRankDigest','sha256:'||encode(pg_catalog.sha256(
                     pg_catalog.convert_to(trim(iq_job.binding->>'selectionSeed')||
                       ranked.window_id,
                     'UTF8')),'hex'),
                   'expectedDigest',ranked.expected_digest,
                   'normalizedInputDigest',coalesce(result.normalized_input_digest,
                     'sha256:'||repeat('0',64)))
                   order by ranked.selection_rank)
                   filter (where ranked.selection_rank<=100),'[]'::jsonb)
                   selected_windows
          from ranked left join ingestion_quality.iq_recovery_backfill_window_result result
            on result.recovery_request_id=iq_job.recovery_request_id
           and result.window_id=ranked.window_id
         group by ranked.stratum_code
    )
    select coalesce(jsonb_agg(jsonb_build_object(
        'code',stratum_code,'populationCount',population_count,
        'selectedWindows',selected_windows) order by stratum_code),
        jsonb_build_array(jsonb_build_object(
          'code','NO_WINDOWS','populationCount',0,'selectedWindows','[]'::jsonb)))
      into iq_selected from strata_base;
    iq_start_digest:=iq_readiness#>>'{backfillEvidence,evidenceDigest}';
    iq_target_digest:=trim(iq_job.binding->>'watermarksDigest');
    iq_backfill_digest:=iq_readiness#>>'{backfillEvidence,evidenceDigest}';
    iq_reconciliation_digest:='sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
        iq_population::text||chr(31)||iq_reconstructed::text||chr(31)||
        (iq_population-iq_reconstructed)::text,'UTF8')),'hex');
    iq_selection_ref:='oswref:v1:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
        trim(iq_job.input_digest)||chr(31)||'sealed-selection','UTF8')),'hex');
    return jsonb_build_object(
        'jobId',iq_job.job_id,'jobVersion',iq_job.job_version,
        'recoveryRequestId',iq_job.recovery_request_id,
        'episodeId',iq_request.episode_id,'taskId',iq_request.task_id,
        'inputDigest',trim(iq_job.input_digest),'binding',iq_job.binding,
        'sourceId',iq_request.source_id,
        'backfillStartWatermarkDigest',iq_start_digest,
        'backfillTargetWatermarkDigest',iq_target_digest,
        'backfillProcessedCount',iq_population,'backfillSummaryDigest',iq_backfill_digest,
        'reconciliationExpectedCount',iq_population,
        'reconciliationActualCount',iq_reconstructed,
        'reconciliationMismatchCount',iq_population-iq_reconstructed,
        'reconciliationSummaryDigest',iq_reconciliation_digest,
        'opaqueSubjectWindowSelectionRef',iq_selection_ref,
        'strata',iq_selected,'readinessEvidence',iq_readiness-
            'populationCount'-'reconstructedCount'-'reconciliationMismatchCount');
end
$$;

create function ingestion_quality.iq_find_claimable_recovery_validation_jobs(
    requested_limit integer,requested_now timestamptz)
returns table(job_id uuid,job_version bigint,recovery_request_id uuid,
    input_digest character,status varchar,next_attempt_at timestamptz,
    lease_expires_at timestamptz)
language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    if requested_limit not between 1 and 100 then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_RECOVERY_LIMIT_INVALID';
    end if;
    return query
    select job.job_id,job.job_version,job.recovery_request_id,job.input_digest,
        job.status,job.next_attempt_at,job.lease_expires_at
      from ingestion_quality.iq_recovery_validation_job job
     where (job.status='queued' and coalesce(job.next_attempt_at,requested_now)<=requested_now)
        or (job.status='running' and job.lease_expires_at<=requested_now)
     order by coalesce(job.next_attempt_at,job.lease_expires_at,job.created_at),job.job_id
     limit requested_limit;
end
$$;

create function ingestion_quality.iq_is_recovery_validation_lease_current(
    requested_job_id uuid,requested_generation bigint,requested_now timestamptz)
returns boolean language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    return exists (select 1 from ingestion_quality.iq_recovery_validation_job job
        where job.job_id=requested_job_id and job.status='running'
          and job.lease_generation=requested_generation
          and requested_now<job.lease_expires_at);
end
$$;

create function ingestion_quality.iq_release_recovery_validation_job(
    requested_job_id uuid,requested_generation bigint,requested_operation varchar,
    requested_error_code varchar,requested_next_attempt_at timestamptz,
    requested_now timestamptz)
returns boolean language plpgsql security definer set search_path=pg_catalog as $$
declare iq_job ingestion_quality.iq_recovery_validation_job%rowtype;
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_status varchar;
declare iq_request_status varchar;
declare iq_terminal boolean;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    select job.* into iq_job from ingestion_quality.iq_recovery_validation_job job
     where job.job_id=requested_job_id for update;
    if iq_job.status<>'running' or iq_job.lease_generation<>requested_generation
       or requested_now>=iq_job.lease_expires_at then return false; end if;
    if requested_operation='retry' then
        if requested_error_code is null or requested_next_attempt_at<=requested_now
           or iq_job.attempt_count>=5 then return false; end if;
        iq_status:='queued'; iq_terminal:=false;
    elsif requested_operation='yield' then
        if requested_error_code is not null or requested_next_attempt_at<=requested_now then
            return false;
        end if;
        iq_status:='queued'; iq_terminal:=false;
    elsif requested_operation='fail' then
        if requested_error_code is null then return false; end if;
        iq_status:='failed'; iq_terminal:=true;
    elsif requested_operation='cancel' then
        iq_status:='cancelled'; iq_terminal:=true;
    else
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_RECOVERY_RELEASE_INVALID';
    end if;
    update ingestion_quality.iq_recovery_validation_job set
        job_version=job_version+1,status=iq_status,
        lease_generation=case when iq_terminal then lease_generation+1 else lease_generation end,
        lease_owner_digest=null,claimed_at=null,lease_expires_at=null,
        error_code=case when requested_operation='yield' then null
                        when requested_operation='cancel' then 'CANCELLED'
                        else requested_error_code end,
        next_attempt_at=case when iq_terminal then null else requested_next_attempt_at end,
        updated_at=requested_now,completed_at=case when iq_terminal then requested_now else null end
     where job_id=requested_job_id;
    update ingestion_quality.iq_recovery_validation_attempt set
        released_at=requested_now,outcome=requested_operation
     where job_id=requested_job_id and lease_generation=requested_generation;
    if iq_terminal then
        select request.* into iq_request
          from ingestion_quality.iq_quality_recovery_request_current request
         where request.recovery_request_id=iq_job.recovery_request_id for update;
        iq_request_status:=case when requested_operation='cancel' then 'cancelled'
                                else 'validation-failed' end;
        insert into ingestion_quality.iq_quality_recovery_request_history(
            recovery_request_id,request_version,task_id,episode_id,source_id,
            dependency_id,status,request_digest,aggregate,occurred_at,legal_hold)
        values (iq_request.recovery_request_id,iq_request.request_version+1,
            iq_request.task_id,iq_request.episode_id,iq_request.source_id,
            iq_request.dependency_id,iq_request_status,iq_request.request_digest,
            iq_request.aggregate||jsonb_build_object(
                'requestVersion',iq_request.request_version+1,'status',iq_request_status),
            requested_now,iq_request.legal_hold);
        update ingestion_quality.iq_quality_recovery_request_current set
            request_version=request_version+1,status=iq_request_status,
            aggregate=aggregate||jsonb_build_object(
                'requestVersion',request_version+1,'status',iq_request_status),
            updated_at=requested_now
         where recovery_request_id=iq_request.recovery_request_id;
    end if;
    return true;
end
$$;

create function ingestion_quality.iq_store_quality_recovery_evidence(
    requested_evidence jsonb,requested_preview jsonb,requested_now timestamptz)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_result ingestion_quality.iq_recovery_validation_result%rowtype;
declare iq_next_preview_version bigint;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=(requested_evidence->>'recoveryRequestId')::uuid
     for update;
    select result.* into iq_result
      from ingestion_quality.iq_recovery_validation_result result
     where result.job_id=(requested_evidence->>'validationJobId')::uuid;
    if iq_request.status<>'validation-succeeded'
       or iq_result.recovery_request_id<>iq_request.recovery_request_id
       or not iq_result.qualified
       or requested_evidence->>'inputDigest'<>trim(iq_result.input_digest)
       or requested_preview->>'inputDigest'<>trim(iq_result.input_digest)
       or requested_preview->>'recoveryRequestId'<>iq_request.recovery_request_id::text
       or (requested_preview->>'generatedAt')::timestamptz<>requested_now
       or (requested_preview->>'expiresAt')::timestamptz<>requested_now+interval '15 minutes' then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_RECOVERY_EVIDENCE_DRIFT';
    end if;
    insert into ingestion_quality.iq_quality_recovery_evidence_pack(
        evidence_pack_id,recovery_request_id,validation_job_id,evidence_pack_digest,
        input_digest,evidence,created_at,legal_hold)
    values ((requested_evidence->>'evidencePackId')::uuid,iq_request.recovery_request_id,
        iq_result.job_id,requested_evidence->>'evidencePackDigest',
        requested_evidence->>'inputDigest',requested_evidence,requested_now,false)
    on conflict (evidence_pack_digest) do nothing;
    update ingestion_quality.iq_quality_recovery_preview
       set invalidated_at=requested_now
     where recovery_request_id=iq_request.recovery_request_id and invalidated_at is null;
    select coalesce(max(preview.preview_version),0)+1 into iq_next_preview_version
      from ingestion_quality.iq_quality_recovery_preview preview
     where preview.recovery_request_id=iq_request.recovery_request_id;
    if (requested_preview->>'previewVersion')::bigint<>iq_next_preview_version then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_RECOVERY_PREVIEW_VERSION_CONFLICT';
    end if;
    insert into ingestion_quality.iq_quality_recovery_preview(
        preview_id,recovery_request_id,preview_version,input_digest,preview_digest,
        preview,generated_at,expires_at,invalidated_at,legal_hold)
    values ((requested_preview->>'previewId')::uuid,iq_request.recovery_request_id,
        iq_next_preview_version,requested_preview->>'inputDigest',
        requested_preview->>'previewDigest',requested_preview,requested_now,
        requested_now+interval '15 minutes',null,false);
    return jsonb_build_object('recoveryRequestId',iq_request.recovery_request_id,
        'evidencePackId',requested_evidence->>'evidencePackId',
        'evidencePackDigest',requested_evidence->>'evidencePackDigest',
        'previewId',requested_preview->>'previewId',
        'previewVersion',iq_next_preview_version,
        'previewDigest',requested_preview->>'previewDigest',
        'expiresAt',requested_now+interval '15 minutes');
end
$$;

create function ingestion_quality.iq_resolve_quality_recovery_task_owner(
    requested_task_digest character)
returns table(task_id uuid,source_id varchar,aggregate_version bigint,status varchar)
language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    return query select task.task_id,task.source_id,task.aggregate_version,task.status
      from ingestion_quality.iq_quality_recovery_task_current task
      join ingestion_quality.iq_quality_fuse_episode_current episode
        on episode.episode_id=task.episode_id and episode.active
     where 'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
            task.task_id::text,'UTF8')),'hex')=requested_task_digest
       and task.status='open';
end
$$;

create function ingestion_quality.iq_load_quality_recovery_command_context(
    requested_task_id uuid)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare iq_task ingestion_quality.iq_quality_recovery_task_current%rowtype;
declare iq_episode ingestion_quality.iq_quality_fuse_episode_current%rowtype;
declare iq_rules jsonb;
declare iq_rule_digest text;
declare iq_member_digest text;
declare iq_watermark_digest text;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    select task.* into iq_task
      from ingestion_quality.iq_quality_recovery_task_current task
     where task.task_id=requested_task_id and task.status='open';
    if iq_task.task_id is null then return null; end if;
    select episode.* into iq_episode
      from ingestion_quality.iq_quality_fuse_episode_current episode
     where episode.episode_id=iq_task.episode_id and episode.active;
    if iq_episode.episode_id is null then return null; end if;
    select jsonb_agg(jsonb_build_object(
        'ruleId',affected.rule_id,'ruleVersion',affected.rule_version,
        'ruleVersionDigest','sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
            affected.rule_id||'@'||affected.rule_version,'UTF8')),'hex'))
        order by affected.rule_id,affected.rule_version),
      'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
        affected.rule_id||'@'||affected.rule_version,chr(30)
        order by affected.rule_id,affected.rule_version),'UTF8')),'hex')
      into iq_rules,iq_rule_digest
      from ingestion_quality.iq_quality_recovery_task_affected_rule affected
     where affected.task_id=iq_task.task_id;
    select 'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
        member.dependency_id||chr(31)||member.dependency_version::text||chr(31)||
        member.requirement||chr(31)||member.state||chr(31)||member.failed::text,
        chr(30) order by member.dependency_id),'UTF8')),'hex'),
      'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
        encode(member.source_watermark_utf8,'base64')||chr(31)||
        encode(member.dependency_watermark_utf8,'base64'),chr(30)
        order by member.dependency_id),'UTF8')),'hex')
      into iq_member_digest,iq_watermark_digest
      from ingestion_quality.iq_quality_recovery_task_affected_rule affected
      join ingestion_quality.iq_quality_eligibility_current eligibility
        on eligibility.rule_id=affected.rule_id
       and eligibility.rule_version=affected.rule_version
       and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
      join ingestion_quality.iq_quality_eligibility_member_history member
        on member.eligibility_id=eligibility.eligibility_id
       and member.aggregate_version=eligibility.aggregate_version
     where affected.task_id=iq_task.task_id;
    if iq_rules is null or iq_member_digest is null or iq_watermark_digest is null then
        return null;
    end if;
    return jsonb_build_object(
        'taskId',iq_task.task_id,'taskVersion',iq_task.aggregate_version,
        'episodeId',iq_episode.episode_id,'episodeVersion',iq_episode.aggregate_version,
        'episodeGeneration',iq_episode.generation,'sourceId',iq_task.source_id,
        'dependencyId',iq_task.dependency_id,'affectedRules',iq_rules,
        'affectedRuleVersionsDigest',iq_rule_digest,
        'memberSetDigest',iq_member_digest,'watermarksDigest',iq_watermark_digest,
        'currentState','fused','targetState','recovering');
end
$$;

create function ingestion_quality.iq_find_quality_recovery_request(
    requested_request_id uuid)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare iq_value jsonb;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    select jsonb_build_object(
        'recoveryRequestId',request.recovery_request_id,
        'requestVersion',request.request_version,'taskId',request.task_id,
        'episodeId',request.episode_id,'status',request.status,
        'validationJobId',job.job_id,'validationStatus',job.status,
        'validationResultDigest',trim(job.result_digest),
        'previewId',preview.preview_id,'previewVersion',preview.preview_version,
        'previewDigest',trim(preview.preview_digest),'previewExpiresAt',preview.expires_at,
        'previewSummary',case when preview.preview is null or result.result is null
          then null else jsonb_build_object(
            'qualityRecoveryPolicyVersion',preview.preview->>'qualityRecoveryPolicyVersion',
            'requiredConsecutivePassedBatches',
                (result.result#>>'{readinessEvidence,batchEvidence,requiredConsecutivePassedBatches}')::bigint,
            'actualConsecutivePassedBatches',
                (result.result#>>'{readinessEvidence,batchEvidence,actualConsecutivePassedBatches}')::bigint,
            'observationDuration',preview.preview->>'observationDuration',
            'backfillStatus',result.result#>>'{readinessEvidence,backfillEvidence,status}',
            'backfillLookbackDays',(result.result#>>'{readinessEvidence,backfillEvidence,lookbackDays}')::bigint,
            'reconciliationExpectedCount',
                (result.result->>'reconciliationExpectedCount')::bigint,
            'reconciliationActualCount',
                (result.result->>'reconciliationActualCount')::bigint,
            'reconciliationMismatchCount',
                (result.result->>'reconciliationMismatchCount')::bigint,
            'samplePopulationCount',(result.result->>'populationCount')::bigint,
            'sampleSelectedCount',(result.result->>'selectedCount')::bigint,
            'sampleMismatchCount',(result.result->>'mismatchCount')::bigint,
            'sampleStrataCount',jsonb_array_length(result.result->'strata'),
            'impactAlreadyExpiredCount',
                (result.result#>>'{readinessEvidence,impactAlreadyExpiredCount}')::bigint,
            'impactPotentiallyActionableCount',
                (result.result#>>'{readinessEvidence,impactPotentiallyActionableCount}')::bigint,
            'impactExpectedToExpireCount',
                (result.result#>>'{readinessEvidence,impactExpectedToExpireCount}')::bigint,
            'finalActionabilityOwnerStory',preview.preview->>'finalActionabilityOwnerStory') end,
        'approvalId',request.aggregate->>'approvalId',
        'approvalVersion',case when request.aggregate ? 'approvalVersion'
            then (request.aggregate->>'approvalVersion')::bigint else null end,
        'approvalStatus',request.aggregate->>'approvalStatus',
        'traceId',request.aggregate->>'traceId',
        'binding',request.aggregate||coalesce(job.binding,'{}'::jsonb)||jsonb_build_object(
            'inputDigest',trim(job.input_digest),
            'validationResultDigest',trim(result.result_digest),
            'evidencePackDigest',trim(evidence.evidence_pack_digest))) into iq_value
      from ingestion_quality.iq_quality_recovery_request_current request
      left join ingestion_quality.iq_recovery_validation_job job
        on job.recovery_request_id=request.recovery_request_id
      left join ingestion_quality.iq_recovery_validation_result result
        on result.recovery_request_id=request.recovery_request_id
      left join ingestion_quality.iq_quality_recovery_evidence_pack evidence
        on evidence.recovery_request_id=request.recovery_request_id
      left join ingestion_quality.iq_quality_recovery_preview preview
        on preview.recovery_request_id=request.recovery_request_id
       and preview.invalidated_at is null
     where request.recovery_request_id=requested_request_id;
    return iq_value;
end
$$;

create function ingestion_quality.iq_find_quality_recovery_idempotency(
    requested_idempotency_digest character,requested_input_digest character,
    requested_recovery_request_id uuid)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare iq_existing ingestion_quality.iq_quality_recovery_idempotency%rowtype;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    if requested_idempotency_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_input_digest !~ '^sha256:[0-9a-f]{64}$' then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_RECOVERY_IDEMPOTENCY_INVALID';
    end if;
    select idempotency.* into iq_existing
      from ingestion_quality.iq_quality_recovery_idempotency idempotency
     where idempotency.idempotency_key_digest=requested_idempotency_digest;
    if iq_existing.idempotency_key_digest is null then return null; end if;
    if trim(iq_existing.replay_input_digest)<>requested_input_digest
       or iq_existing.recovery_request_id<>requested_recovery_request_id then
        raise exception using errcode='unique_violation',
            message='INGESTION_QUALITY_RECOVERY_IDEMPOTENCY_CONFLICT';
    end if;
    return iq_existing.response;
end
$$;

create function ingestion_quality.iq_bind_quality_recovery_approval(
    requested_idempotency_digest character,requested_input_digest character,
    requested_binding jsonb,requested_now timestamptz)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_existing ingestion_quality.iq_quality_recovery_idempotency%rowtype;
declare iq_status varchar:=requested_binding->>'approvalStatus';
declare iq_next_status varchar;
declare iq_next_aggregate jsonb;
declare iq_response jsonb;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    if requested_idempotency_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_input_digest !~ '^sha256:[0-9a-f]{64}$' then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_RECOVERY_APPROVAL_INVALID';
    end if;
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=(requested_binding->>'recoveryRequestId')::uuid;
    if iq_request.recovery_request_id is null then
        raise exception using errcode='no_data_found',
            message='INGESTION_QUALITY_RECOVERY_NOT_FOUND';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-eligibility:'||iq_request.source_id,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-fuse-episode:'||iq_request.source_id||'@'||iq_request.dependency_id,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-recovery-task:'||iq_request.source_id||'@'||iq_request.dependency_id,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-recovery-idempotency:'||requested_idempotency_digest,0));
    select idempotency.* into iq_existing
      from ingestion_quality.iq_quality_recovery_idempotency idempotency
     where idempotency.idempotency_key_digest=requested_idempotency_digest;
    if iq_existing.idempotency_key_digest is not null then
        if trim(iq_existing.replay_input_digest)<>requested_input_digest
           or iq_existing.recovery_request_id<>
              (requested_binding->>'recoveryRequestId')::uuid then
            raise exception using errcode='unique_violation',
                message='INGESTION_QUALITY_RECOVERY_IDEMPOTENCY_CONFLICT';
        end if;
        return iq_existing.response;
    end if;
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=(requested_binding->>'recoveryRequestId')::uuid
     for update;
    if iq_status not in ('pending','approved','rejected','expired','cancelled')
       or requested_binding->>'actionType'<>'quality-fuse.recover'
       or requested_binding->>'objectType'<>'RECOVERY_TASK'
       or requested_binding->>'objectRefDigest' !~ '^sha256:[0-9a-f]{64}$'
       or requested_binding->>'approvalId' !~
          '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       or (requested_binding->>'approvalVersion')::bigint not between 1 and 9007199254740991
       or (requested_binding->>'authorizationGeneration')::bigint<>
          iq_request.authorization_generation
       or (requested_binding->>'expectedRequestVersion')::bigint<>
          iq_request.request_version
       or requested_binding->>'traceId' !~ '^(?!0{32}$)[0-9a-f]{32}$'
       or requested_binding->>'previewDigest' !~ '^sha256:[0-9a-f]{64}$'
       or requested_binding->>'checkerSetDigest' !~ '^sha256:[0-9a-f]{64}$'
       or requested_binding->>'ownerBindingSetDigest' !~ '^sha256:[0-9a-f]{64}$'
       or requested_binding->>'checkerNaturalPersonBindingSetDigest'
          !~ '^sha256:[0-9a-f]{64}$'
       or not exists (select 1
          from ingestion_quality.iq_quality_recovery_preview preview
         where preview.recovery_request_id=iq_request.recovery_request_id
           and trim(preview.preview_digest)=requested_binding->>'previewDigest'
           and preview.invalidated_at is null and requested_now<preview.expires_at) then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_RECOVERY_APPROVAL_BINDING_DRIFT';
    end if;
    if iq_status='pending' then
        if iq_request.status<>'validation-succeeded'
           or requested_binding->>'approvalReceiptDigest' is not null then
            raise exception using errcode='serialization_failure',
                message='INGESTION_QUALITY_RECOVERY_APPROVAL_STATE_INVALID';
        end if;
        iq_next_status:='approval-pending';
    elsif iq_status='approved' then
        if iq_request.status<>'approval-pending'
           or requested_binding->>'approvalReceiptDigest' !~ '^sha256:[0-9a-f]{64}$' then
            raise exception using errcode='serialization_failure',
                message='INGESTION_QUALITY_RECOVERY_APPROVAL_STATE_INVALID';
        end if;
        iq_next_status:='approval-approved';
    else
        if iq_request.status not in ('approval-pending','approval-approved')
           or requested_binding->>'approvalReceiptDigest' !~ '^sha256:[0-9a-f]{64}$' then
            raise exception using errcode='serialization_failure',
                message='INGESTION_QUALITY_RECOVERY_APPROVAL_STATE_INVALID';
        end if;
        iq_next_status:='cancelled';
    end if;
    iq_next_aggregate:=iq_request.aggregate||jsonb_build_object(
        'requestVersion',iq_request.request_version+1,
        'status',iq_next_status,
        'approvalId',requested_binding->>'approvalId',
        'approvalVersion',(requested_binding->>'approvalVersion')::bigint,
        'approvalStatus',iq_status,
        'approvalReceiptDigest',requested_binding->>'approvalReceiptDigest',
        'checkerSetDigest',requested_binding->>'checkerSetDigest',
        'ownerBindingSetDigest',requested_binding->>'ownerBindingSetDigest',
        'checkerNaturalPersonBindingSetDigest',
          requested_binding->>'checkerNaturalPersonBindingSetDigest',
        'approvalTraceId',requested_binding->>'approvalTraceId',
        'approvalPreviewDigest',requested_binding->>'previewDigest');
    insert into ingestion_quality.iq_quality_recovery_request_history(
        recovery_request_id,request_version,task_id,episode_id,source_id,
        dependency_id,status,request_digest,aggregate,occurred_at,legal_hold)
    values (iq_request.recovery_request_id,iq_request.request_version+1,
        iq_request.task_id,iq_request.episode_id,iq_request.source_id,
        iq_request.dependency_id,iq_next_status,iq_request.request_digest,
        iq_next_aggregate,requested_now,iq_request.legal_hold);
    update ingestion_quality.iq_quality_recovery_request_current set
        request_version=request_version+1,status=iq_next_status,
        aggregate=iq_next_aggregate,updated_at=requested_now
     where recovery_request_id=iq_request.recovery_request_id;
    iq_response:=jsonb_build_object(
        'recoveryRequestId',iq_request.recovery_request_id,
        'requestVersion',iq_request.request_version+1,
        'status',iq_next_status,
        'approvalId',requested_binding->>'approvalId',
        'approvalVersion',(requested_binding->>'approvalVersion')::bigint,
        'approvalStatus',iq_status,
        'traceId',requested_binding->>'traceId');
    insert into ingestion_quality.iq_quality_recovery_idempotency values (
        requested_idempotency_digest,requested_input_digest,requested_input_digest,
        iq_request.recovery_request_id,null,iq_response,
        requested_now,requested_now+interval '90 days',iq_request.legal_hold);
    return iq_response;
end
$$;

create function ingestion_quality.iq_execute_quality_recovery(
    requested_idempotency_digest character,requested_command jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_task ingestion_quality.iq_quality_recovery_task_current%rowtype;
declare iq_episode ingestion_quality.iq_quality_fuse_episode_current%rowtype;
declare iq_existing ingestion_quality.iq_quality_recovery_idempotency%rowtype;
declare iq_rule record;
declare iq_now timestamptz:=statement_timestamp();
declare iq_event_id uuid:=(requested_command->>'outboxEventId')::uuid;
declare iq_confirmation_event_id uuid:=
    (requested_command->>'confirmationOutboxEventId')::uuid;
declare iq_response jsonb;
declare iq_result ingestion_quality.iq_recovery_validation_result%rowtype;
declare iq_current_readiness jsonb;
declare iq_confirmation_payload jsonb;
declare iq_confirmation_payload_digest text;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    if requested_idempotency_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_command->>'idempotencyInputDigest' !~ '^sha256:[0-9a-f]{64}$'
       or requested_command->>'idempotencyReplayDigest' !~ '^sha256:[0-9a-f]{64}$' then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_RECOVERY_EXECUTION_INVALID';
    end if;
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=(requested_command->>'recoveryRequestId')::uuid;
    if iq_request.recovery_request_id is null then
        raise exception using errcode='no_data_found',
            message='INGESTION_QUALITY_RECOVERY_NOT_FOUND';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-eligibility:'||iq_request.source_id,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-fuse-episode:'||iq_request.source_id||'@'||iq_request.dependency_id,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-recovery-task:'||iq_request.source_id||'@'||iq_request.dependency_id,0));
    for iq_rule in select affected.rule_id,affected.rule_version
      from ingestion_quality.iq_quality_recovery_task_affected_rule affected
     where affected.task_id=iq_request.task_id order by affected.rule_id,affected.rule_version
    loop
      perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
          'quality-eligibility-rule:'||iq_rule.rule_id||'@'||iq_rule.rule_version,0));
    end loop;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-recovery-idempotency:'||requested_idempotency_digest,0));
    select idempotency.* into iq_existing
      from ingestion_quality.iq_quality_recovery_idempotency idempotency
     where idempotency.idempotency_key_digest=requested_idempotency_digest for update;
    if iq_existing.idempotency_key_digest is not null then
        if trim(iq_existing.replay_input_digest)<>
           requested_command->>'idempotencyReplayDigest' then
            raise exception using errcode='unique_violation',
                message='INGESTION_QUALITY_RECOVERY_IDEMPOTENCY_CONFLICT';
        end if;
        return iq_existing.response;
    end if;
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=(requested_command->>'recoveryRequestId')::uuid for update;
    select task.* into iq_task from ingestion_quality.iq_quality_recovery_task_current task
     where task.task_id=iq_request.task_id for update;
    select episode.* into iq_episode from ingestion_quality.iq_quality_fuse_episode_current episode
     where episode.episode_id=iq_request.episode_id for update;
    select result.* into iq_result
      from ingestion_quality.iq_recovery_validation_result result
     where result.recovery_request_id=iq_request.recovery_request_id;
    iq_current_readiness:=ingestion_quality.iq_build_quality_recovery_readiness_evidence(
        iq_request.recovery_request_id,
        (iq_result.result#>>'{readinessEvidence,trustedStartedAt}')::timestamptz);
    if iq_request.status<>'approval-approved'
       or iq_task.status<>'open' or not iq_episode.active
       or iq_request.request_version<>(requested_command->>'expectedRequestVersion')::bigint
       or iq_task.aggregate_version<>(requested_command->>'expectedTaskVersion')::bigint
       or iq_episode.aggregate_version<>(requested_command->>'expectedEpisodeVersion')::bigint
       or iq_request.authorization_generation<>
          (requested_command->>'authorizationGeneration')::bigint
       or requested_command->>'actionType'<>'quality-fuse.recover'
       or requested_command->>'leaseState'<>'reserved'
       or requested_command->>'leaseIssuer'<>'identity-access'
       or requested_command->>'leaseAudience'<>'ingestion-quality'
       or iq_now>=(requested_command->>'authorizedUntil')::timestamptz
       or iq_request.aggregate->>'approvalId'<>requested_command->>'approvalId'
       or (iq_request.aggregate->>'approvalVersion')::bigint<>
          (requested_command->>'approvalVersion')::bigint
       or iq_request.aggregate->>'approvalReceiptDigest'<>
          requested_command->>'approvalReceiptDigest'
       or iq_request.aggregate->>'approvalPreviewDigest'<>
          requested_command->>'previewDigest'
       or iq_current_readiness is null
       or iq_current_readiness->>'evidenceDigest'<>
          iq_result.result#>>'{readinessEvidence,evidenceDigest}'
       or jsonb_array_length(iq_current_readiness->'missingEvidenceCodes')<>0
       or iq_current_readiness->>'currentBindingDigest'<>
          iq_result.result#>>'{readinessEvidence,currentBindingDigest}'
       or not exists (select 1 from ingestion_quality.iq_recovery_validation_result result
          where result.recovery_request_id=iq_request.recovery_request_id
            and result.state='succeeded' and result.qualified
            and trim(result.input_digest)=requested_command->>'inputDigest'
            and trim(result.result_digest)=requested_command->>'validationResultDigest')
       or not exists (select 1 from ingestion_quality.iq_quality_recovery_evidence_pack evidence
          where evidence.recovery_request_id=iq_request.recovery_request_id
            and trim(evidence.input_digest)=requested_command->>'inputDigest'
            and trim(evidence.evidence_pack_digest)=requested_command->>'evidencePackDigest')
       or not exists (select 1 from ingestion_quality.iq_quality_recovery_preview preview
          where preview.recovery_request_id=iq_request.recovery_request_id
            and trim(preview.input_digest)=requested_command->>'inputDigest'
            and preview.invalidated_at is null and iq_now<preview.expires_at
            and trim(preview.preview_digest)=requested_command->>'previewDigest') then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_RECOVERY_BINDING_DRIFT';
    end if;
    if exists (select 1
      from ingestion_quality.iq_quality_recovery_task_affected_rule affected
      left join ingestion_quality.iq_quality_eligibility_current eligibility
        on eligibility.rule_id=affected.rule_id
       and eligibility.rule_version=affected.rule_version
       and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
     where affected.task_id=iq_request.task_id
       and (eligibility.eligibility_id is null or eligibility.status<>'fused')) then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_RECOVERY_VERSION_CONFLICT';
    end if;
    for iq_rule in select eligibility.* from
      ingestion_quality.iq_quality_eligibility_current eligibility
     where eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
       and exists (select 1
         from ingestion_quality.iq_quality_recovery_task_affected_rule affected
        where affected.task_id=iq_request.task_id
          and affected.rule_id=eligibility.rule_id
          and affected.rule_version=eligibility.rule_version)
     order by eligibility.rule_id,eligibility.rule_version
    loop
      insert into ingestion_quality.iq_quality_eligibility_history(
        eligibility_id,rule_id,rule_version,registry_version,registry_digest,
        catalog_version,catalog_digest,rule_catalog_version,rule_catalog_digest,
        aggregate_version,status,reason_code,composition_operator,threshold,
        effective_at,occurred_at,trace_id,producer,retention_due_at,legal_hold)
      select history.eligibility_id,history.rule_id,history.rule_version,
        history.registry_version,history.registry_digest,history.catalog_version,
        history.catalog_digest,history.rule_catalog_version,history.rule_catalog_digest,
        history.aggregate_version+1,'recovering','RECOVERY_VALIDATION_APPROVED',
        history.composition_operator,history.threshold,iq_now,iq_now,
        requested_command->>'traceId','ingestion-quality',iq_now+interval '2 years',
        history.legal_hold
      from ingestion_quality.iq_quality_eligibility_history history
      where history.eligibility_id=iq_rule.eligibility_id
        and history.aggregate_version=iq_rule.aggregate_version;
      insert into ingestion_quality.iq_quality_eligibility_member_history
      select member.eligibility_id,member.aggregate_version+1,member.member_ordinal,
        member.source_id,member.source_version,member.dependency_id,
        member.dependency_version,member.requirement,member.state,
        member.version_continuous,member.source_watermark_utf8,
        member.dependency_watermark_utf8,member.snapshot_id,
        member.snapshot_immutable_hash,member.qmdp_version,member.qmdp_digest,
        member.qshm_version,member.qshm_digest,member.lineage_id,member.failed
      from ingestion_quality.iq_quality_eligibility_member_history member
      where member.eligibility_id=iq_rule.eligibility_id
        and member.aggregate_version=iq_rule.aggregate_version;
      update ingestion_quality.iq_quality_eligibility_current set
        aggregate_version=aggregate_version+1,status='recovering',
        reason_code='RECOVERY_VALIDATION_APPROVED',effective_at=iq_now,
        occurred_at=iq_now
      where eligibility_id=iq_rule.eligibility_id
        and aggregate_version=iq_rule.aggregate_version;
    end loop;
    insert into ingestion_quality.iq_quality_recovery_execution_jti values (
        (requested_command->>'executionJti')::uuid,
        (requested_command->>'leaseId')::uuid,requested_command->>'leaseDigest',
        iq_request.recovery_request_id,requested_command->>'ownerCommitId',
        requested_command->>'ownerResultDigest',iq_confirmation_event_id,
        iq_now,
        (requested_command->>'authorizedUntil')::timestamptz,null,iq_request.legal_hold);
    insert into ingestion_quality.iq_quality_recovery_request_history(
        recovery_request_id,request_version,task_id,episode_id,source_id,
        dependency_id,status,request_digest,aggregate,occurred_at,legal_hold)
    values (iq_request.recovery_request_id,iq_request.request_version+1,
        iq_request.task_id,iq_request.episode_id,iq_request.source_id,
        iq_request.dependency_id,'executed',iq_request.request_digest,
        iq_request.aggregate||jsonb_build_object(
            'requestVersion',iq_request.request_version+1,'status','executed'),
        iq_now,iq_request.legal_hold);
    update ingestion_quality.iq_quality_recovery_request_current set
        request_version=request_version+1,status='executed',
        aggregate=aggregate||jsonb_build_object(
            'requestVersion',request_version+1,'status','executed'),updated_at=iq_now
     where recovery_request_id=iq_request.recovery_request_id;
    insert into ingestion_quality.iq_quality_recovery_audit(
        audit_id,recovery_request_id,action,outcome,object_version,evidence_digest,
        trace_id,occurred_at,legal_hold) values (
        (requested_command->>'auditId')::uuid,iq_request.recovery_request_id,
        'quality-fuse.recover','accepted',iq_task.aggregate_version,
        requested_command->>'ownerResultDigest',requested_command->>'traceId',iq_now,
        iq_request.legal_hold);
    iq_confirmation_payload:=requested_command->'confirmationPayload'||jsonb_build_object(
        'leaseVersion',(requested_command->>'leaseVersion')::bigint,
        'ownerCommittedAt',iq_now,
        'outboxEventId',iq_confirmation_event_id);
    iq_confirmation_payload_digest:='sha256:'||encode(pg_catalog.sha256(
        pg_catalog.convert_to(ingestion_quality.iq_json_canonical(
            iq_confirmation_payload),'UTF8')),'hex');
    insert into ingestion_quality.iq_quality_recovery_outbox(
        event_id,recovery_request_id,event_type,payload,payload_digest,status,
        attempts,available_at,claimed_until,last_error_code,created_at,legal_hold) values (
        iq_event_id,iq_request.recovery_request_id,
        'scholarsense.ingestion-quality.quality-eligibility.recovering.v1',
        requested_command->'eventPayload',requested_command->>'eventPayloadDigest',
        'pending',0,iq_now,null,null,iq_now,iq_request.legal_hold);
    insert into ingestion_quality.iq_quality_recovery_outbox(
        event_id,recovery_request_id,event_type,payload,payload_digest,status,
        attempts,available_at,claimed_until,last_error_code,created_at,legal_hold) values (
        iq_confirmation_event_id,iq_request.recovery_request_id,
        'scholarsense.ingestion-quality.recovery-lease.confirmation.v1',
        iq_confirmation_payload,iq_confirmation_payload_digest,
        'pending',0,iq_now,null,null,iq_now,iq_request.legal_hold);
    iq_response:=jsonb_build_object('recoveryRequestId',iq_request.recovery_request_id,
        'taskId',iq_task.task_id,'episodeId',iq_episode.episode_id,
        'state','recovering','transitionApplied',true,
        'executionJti',requested_command->>'executionJti',
        'ownerCommitId',requested_command->>'ownerCommitId',
        'ownerResultDigest',requested_command->>'ownerResultDigest',
        'confirmationOutboxEventId',iq_confirmation_event_id,
        'ownerCommittedAt',iq_now,
        'traceId',requested_command->>'traceId');
    insert into ingestion_quality.iq_quality_recovery_idempotency values (
        requested_idempotency_digest,requested_command->>'idempotencyInputDigest',
        requested_command->>'idempotencyReplayDigest',
        iq_request.recovery_request_id,(requested_command->>'executionJti')::uuid,
        iq_response,iq_now,iq_now+interval '90 days',iq_request.legal_hold);
    return iq_response;
end
$$;

create function ingestion_quality.iq_claim_quality_recovery_confirmations(
    requested_limit integer,requested_now timestamptz)
returns setof jsonb language plpgsql security definer set search_path=pg_catalog as $$
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    if requested_limit not between 1 and 100 then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_RECOVERY_CONFIRMATION_LIMIT_INVALID';
    end if;
    if exists (
        select 1 from ingestion_quality.iq_quality_recovery_outbox outbox
         where outbox.event_type=
           'scholarsense.ingestion-quality.recovery-lease.confirmation.v1'
           and outbox.status='pending' and outbox.available_at<=requested_now
           and (outbox.claimed_until is null or outbox.claimed_until<=requested_now)
           and trim(outbox.payload_digest)<>'sha256:'||encode(pg_catalog.sha256(
             pg_catalog.convert_to(ingestion_quality.iq_json_canonical(
               outbox.payload),'UTF8')),'hex')) then
        raise exception using errcode='data_exception',
            message='INGESTION_QUALITY_RECOVERY_CONFIRMATION_INTEGRITY_INVALID';
    end if;
    return query
      with candidate as (
        select outbox.event_id
          from ingestion_quality.iq_quality_recovery_outbox outbox
         where outbox.event_type=
           'scholarsense.ingestion-quality.recovery-lease.confirmation.v1'
           and outbox.status='pending' and outbox.available_at<=requested_now
           and (outbox.claimed_until is null or outbox.claimed_until<=requested_now)
         order by outbox.available_at,outbox.event_id
         for update skip locked limit requested_limit), claimed as (
        update ingestion_quality.iq_quality_recovery_outbox outbox set
            attempts=outbox.attempts+1,
            claimed_until=requested_now+interval '60 seconds',
            last_error_code=null
          from candidate
         where outbox.event_id=candidate.event_id
        returning outbox.event_id,outbox.payload,outbox.payload_digest,outbox.attempts)
      select claimed.payload||jsonb_build_object(
        'payloadDigest',trim(claimed.payload_digest),
        'deliveryAttempt',claimed.attempts)
        from claimed order by claimed.event_id;
end
$$;

create function ingestion_quality.iq_mark_quality_recovery_confirmation_delivered(
    requested_event_id uuid,requested_payload_digest character,
    requested_now timestamptz)
returns boolean language plpgsql security definer set search_path=pg_catalog as $$
declare iq_current ingestion_quality.iq_quality_recovery_outbox%rowtype;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    select outbox.* into iq_current
      from ingestion_quality.iq_quality_recovery_outbox outbox
     where outbox.event_id=requested_event_id for update;
    if iq_current.event_id is null
       or iq_current.event_type<>
          'scholarsense.ingestion-quality.recovery-lease.confirmation.v1'
       or trim(iq_current.payload_digest)<>requested_payload_digest then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_RECOVERY_CONFIRMATION_BINDING_DRIFT';
    end if;
    if iq_current.status='delivered' then return true; end if;
    if iq_current.status<>'pending' then return false; end if;
    update ingestion_quality.iq_quality_recovery_outbox set
        status='delivered',claimed_until=null,last_error_code=null
     where event_id=requested_event_id;
    update ingestion_quality.iq_quality_recovery_execution_jti set
        confirmed_at=coalesce(confirmed_at,requested_now)
     where outbox_event_id=requested_event_id;
    return true;
end
$$;

create function ingestion_quality.iq_release_quality_recovery_confirmation(
    requested_event_id uuid,requested_payload_digest character,
    requested_error_code varchar,requested_now timestamptz)
returns boolean language plpgsql security definer set search_path=pg_catalog as $$
declare iq_current ingestion_quality.iq_quality_recovery_outbox%rowtype;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    select outbox.* into iq_current
      from ingestion_quality.iq_quality_recovery_outbox outbox
     where outbox.event_id=requested_event_id for update;
    if iq_current.event_id is null
       or trim(iq_current.payload_digest)<>requested_payload_digest
       or requested_error_code !~ '^[A-Z0-9_]{3,96}$' then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_RECOVERY_CONFIRMATION_BINDING_DRIFT';
    end if;
    if iq_current.status='delivered' then return true; end if;
    update ingestion_quality.iq_quality_recovery_outbox set
        status=case when attempts>=8 then 'failed' else 'pending' end,
        available_at=requested_now+
          (least(300,power(2,least(attempts,8))::integer)||' seconds')::interval,
        claimed_until=null,last_error_code=requested_error_code
     where event_id=requested_event_id and status='pending';
    return found;
end
$$;

create function ingestion_quality.iq_cleanup_quality_recovery_expired(requested_now timestamptz)
returns bigint language plpgsql security definer set search_path=pg_catalog as $$
declare iq_count bigint;
declare iq_deleted bigint;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_retention_executor');
    iq_count:=0;
    delete from ingestion_quality.iq_quality_recovery_idempotency
      where not legal_hold and expires_at<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_quality_recovery_outbox
      where not legal_hold and retention_due_at<=requested_now
        and status in ('delivered','failed');
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_quality_recovery_preview
      where not legal_hold and retention_due_at<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_quality_recovery_evidence_pack
      where not legal_hold and retention_due_at<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_validation_result
      where not legal_hold and retention_due_at<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_backfill_window_result
      where not legal_hold and retention_due_at<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_backfill_run
      where not legal_hold and retention_due_at<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_quality_recovery_audit
      where not legal_hold and retention_due_at<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_validation_checkpoint checkpoint
     where exists (select 1 from ingestion_quality.iq_recovery_validation_job job
        where job.job_id=checkpoint.job_id and not job.legal_hold
          and job.completed_at+interval '2 years'<=requested_now);
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_validation_attempt attempt
     where exists (select 1 from ingestion_quality.iq_recovery_validation_job job
        where job.job_id=attempt.job_id and not job.legal_hold
          and job.completed_at+interval '2 years'<=requested_now);
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_validation_job
     where not legal_hold and completed_at+interval '2 years'<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_quality_recovery_execution_jti
     where not legal_hold and committed_at+interval '2 years'<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_quality_recovery_request_current request
     where not request.legal_hold
       and request.status in ('validation-failed','executed','cancelled')
       and request.updated_at+interval '2 years'<=requested_now
       and not exists (select 1 from ingestion_quality.iq_quality_recovery_evidence_pack evidence
            where evidence.recovery_request_id=request.recovery_request_id)
       and not exists (select 1 from ingestion_quality.iq_quality_recovery_preview preview
            where preview.recovery_request_id=request.recovery_request_id)
       and not exists (select 1 from ingestion_quality.iq_recovery_validation_job job
            where job.recovery_request_id=request.recovery_request_id)
       and not exists (select 1 from ingestion_quality.iq_quality_recovery_execution_jti jti
            where jti.recovery_request_id=request.recovery_request_id);
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_quality_recovery_request_history history
     where not history.legal_hold and history.retention_due_at<=requested_now
       and not exists (select 1 from ingestion_quality.iq_quality_recovery_request_current request
            where request.recovery_request_id=history.recovery_request_id);
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    return iq_count;
end
$$;

alter table ingestion_quality.iq_quality_recovery_request_history owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_request_current owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_evidence_pack owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_preview owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_backfill_window_result owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_backfill_run owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_validation_job owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_validation_attempt owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_validation_checkpoint owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_validation_result owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_execution_jti owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_idempotency owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_audit owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_outbox owner to scholarsense_ingestion_quality_batch_owner;

-- Recovery SECURITY DEFINER functions are batch-owner functions. They may read the
-- pre-existing owner-local immutable window facts without exposing those rows to the
-- recovery worker principal.
grant select on ingestion_quality.iq_historical_window
    to scholarsense_ingestion_quality_batch_owner;

revoke all privileges on table
    ingestion_quality.iq_quality_recovery_request_history,
    ingestion_quality.iq_quality_recovery_request_current,
    ingestion_quality.iq_quality_recovery_evidence_pack,
    ingestion_quality.iq_quality_recovery_preview,
    ingestion_quality.iq_recovery_backfill_window_result,
    ingestion_quality.iq_recovery_backfill_run,
    ingestion_quality.iq_recovery_validation_job,
    ingestion_quality.iq_recovery_validation_attempt,
    ingestion_quality.iq_recovery_validation_checkpoint,
    ingestion_quality.iq_recovery_validation_result,
    ingestion_quality.iq_quality_recovery_execution_jti,
    ingestion_quality.iq_quality_recovery_idempotency,
    ingestion_quality.iq_quality_recovery_audit,
    ingestion_quality.iq_quality_recovery_outbox
from public,scholarsense_ingestion_quality_online,
    scholarsense_ingestion_quality_recovery_worker,
    scholarsense_ingestion_quality_retention_executor;

do $migration$
declare iq_function regprocedure;
begin
  for iq_function in select procedure.oid::regprocedure
    from pg_catalog.pg_proc procedure
   where procedure.pronamespace='ingestion_quality'::regnamespace
     and (procedure.proname like 'iq_%quality_recovery%'
       or procedure.proname like 'iq_%recovery_validation%'
       or procedure.proname like 'iq_%recovery_%'
       or procedure.proname='iq_require_recovery_workload')
  loop
    execute format('revoke all on function %s from public',iq_function);
    execute format('alter function %s owner to scholarsense_ingestion_quality_batch_owner',
        iq_function);
  end loop;
end
$migration$;

revoke all on function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    uuid,varchar,bigint,character,jsonb) from public;
alter function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    uuid,varchar,bigint,character,jsonb) owner to scholarsense_ingestion_quality_batch_owner;
grant execute on function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    uuid,varchar,bigint,character,jsonb)
    to scholarsense_ingestion_quality_eligibility_consumer;
grant execute on function ingestion_quality.iq_submit_quality_recovery_request(
    character,jsonb) to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_submit_quality_recovery_with_validation(
    character,jsonb,character,uuid) to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_submit_recovery_validation_job(
    character,jsonb) to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_resolve_quality_recovery_task_owner(character)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_load_quality_recovery_command_context(uuid)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_find_quality_recovery_request(uuid)
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_find_quality_recovery_idempotency(
    character,character,uuid) to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_execute_quality_recovery(character,jsonb)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_claim_quality_recovery_confirmations(
    integer,timestamptz) to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_mark_quality_recovery_confirmation_delivered(
    uuid,character,timestamptz) to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_release_quality_recovery_confirmation(
    uuid,character,varchar,timestamptz) to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_claim_recovery_validation_job(
    uuid,character,timestamptz,integer)
    to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_checkpoint_recovery_validation_job(
    uuid,bigint,bigint,jsonb,timestamptz)
    to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_finalize_recovery_validation_job(
    uuid,bigint,jsonb,timestamptz)
    to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_find_claimable_recovery_validation_jobs(
    integer,timestamptz) to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_is_recovery_validation_lease_current(
    uuid,bigint,timestamptz) to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_release_recovery_validation_job(
    uuid,bigint,varchar,varchar,timestamptz,timestamptz)
    to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_load_recovery_validation_execution_context(
    uuid,timestamptz) to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_execute_recovery_backfill(
    uuid,uuid,uuid,character,character,character,character,character)
    to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_execute_recovery_full_reconciliation(
    uuid,uuid,uuid,character,character,character,character,character)
    to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_build_quality_recovery_readiness_evidence(
    uuid,timestamptz)
    to scholarsense_ingestion_quality_recovery_worker,
       scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_store_quality_recovery_evidence(jsonb,jsonb,timestamptz)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_bind_quality_recovery_approval(
    character,character,jsonb,timestamptz)
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_cleanup_quality_recovery_expired(timestamptz)
    to scholarsense_ingestion_quality_retention_executor;
