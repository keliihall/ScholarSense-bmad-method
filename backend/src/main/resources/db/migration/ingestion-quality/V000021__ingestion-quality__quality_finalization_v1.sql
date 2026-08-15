-- Story 2.5c forward/expand migration. Every worker mutation uses lease-generation
-- fencing; no observation row or terminal result is written after a stale fence.
-- Frozen predecessors:
-- V15 sha256:21a66478ce29bf71838f4375c7162f5bbd390d5db60661981c5acfa03c419edb
-- V16 sha256:a720f004d30962b4b7bff2e8e734e1ee211f06866e8c3e02ec5c5d36b6eb791c
-- V20 sha256:f24b6e516351eecdcdc036dc309afe57edc661b5b869b5186640c6ff29b4615e

-- The predecessor accepted only open tasks. Add a terminal state without changing
-- task identity, workItemKey or generation.
alter table ingestion_quality.iq_quality_recovery_task_history
    drop constraint if exists iq_quality_recovery_task_history_status_check;
alter table ingestion_quality.iq_quality_recovery_task_current
    drop constraint if exists iq_quality_recovery_task_current_status_check;
alter table ingestion_quality.iq_quality_recovery_task_history
    add column closed_at timestamptz,
    add column closure_reason varchar(64),
    add column owner_result_digest char(71),
    add constraint iq_quality_recovery_task_history_status_v21_ck
        check (status in ('open','closed')),
    add constraint iq_quality_recovery_task_history_close_v21_ck check (
        (status='open' and closed_at is null and closure_reason is null
            and owner_result_digest is null)
        or (status='closed' and closed_at is not null
            and closure_reason in ('RECOVERY_FINALIZED')
            and owner_result_digest ~ '^sha256:[0-9a-f]{64}$'));

-- Closed-task read successor. V16 functions remain byte-for-byte and open-only.
create function ingestion_quality.iq_find_quality_recovery_task_ids_v2(
    requested_source_id varchar,requested_status varchar,
    requested_after_occurred_at timestamptz,requested_after_task_id uuid,
    requested_limit integer)
returns table(task_id uuid)
language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_online');
    if requested_source_id is null
       or requested_source_id!~'^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$'
       or requested_status is not null
          and requested_status not in ('open','closed')
       or requested_limit not between 1 and 101
       or ((requested_after_occurred_at is null)<>(requested_after_task_id is null)) then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_RECOVERY_TASK_QUERY_INVALID';
    end if;
    return query
    select current_fact.task_id
      from ingestion_quality.iq_quality_recovery_task_current current_fact
     where current_fact.source_id=requested_source_id
       and (requested_status is null or current_fact.status=requested_status)
       and (requested_after_occurred_at is null or
            (current_fact.occurred_at,current_fact.task_id)<
            (requested_after_occurred_at,requested_after_task_id))
     order by current_fact.occurred_at desc,current_fact.task_id desc
     limit requested_limit;
end
$$;

create function ingestion_quality.iq_find_quality_recovery_task_page_v2(
    requested_task_ids uuid[])
returns table(
    task_id uuid,episode_id uuid,episode_generation bigint,source_id varchar,
    dependency_id varchar,owner_ref varchar,priority varchar,due_at timestamptz,
    status varchar,watermark_utf8 bytea,trigger jsonb,current_evidence jsonb,
    aggregate_version bigint,occurred_at timestamptz,closed_at timestamptz,
    closure_reason varchar,owner_result_digest character,
    delivery_target varchar,delivery_status varchar,delivery_attempt bigint,
    next_attempt_at timestamptz,route_sequence bigint,trace_id character)
language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_online');
    if coalesce(cardinality(requested_task_ids),0) not between 1 and 101
       or (select count(distinct value) from unnest(requested_task_ids) value)
          <> cardinality(requested_task_ids) then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_RECOVERY_TASK_PAGE_INVALID';
    end if;
    return query
    select history.task_id,history.episode_id,history.episode_generation,
           history.source_id,history.dependency_id,history.owner_ref,history.priority,
           history.due_at,history.status,history.watermark_utf8,history.trigger,
           jsonb_build_object(
             'qualityGateVersion',history.current_evidence->>'qualityGateVersion',
             'qmdpVersion',history.current_evidence->>'qmdpVersion',
             'qshmVersion',history.current_evidence->>'qshmVersion'),
           history.aggregate_version,current_fact.occurred_at,history.closed_at,
           history.closure_reason,history.owner_result_digest,
           delivery.target,delivery.status,delivery.attempt,delivery.next_attempt_at,
           delivery.route_sequence,episode_history.trace_id
      from unnest(requested_task_ids) with ordinality request(id,ordinal)
      join ingestion_quality.iq_quality_recovery_task_current current_fact
        on current_fact.task_id=request.id
      join ingestion_quality.iq_quality_recovery_task_history history
        on history.task_id=current_fact.task_id
       and history.aggregate_version=current_fact.aggregate_version
      join ingestion_quality.iq_quality_task_delivery delivery
        on delivery.task_id=current_fact.task_id
      join ingestion_quality.iq_quality_fuse_episode_current episode_current
        on episode_current.episode_id=current_fact.episode_id
      join ingestion_quality.iq_quality_fuse_episode_history episode_history
        on episode_history.episode_id=episode_current.episode_id
       and episode_history.aggregate_version=episode_current.aggregate_version
     order by request.ordinal;
end
$$;
alter table ingestion_quality.iq_quality_recovery_task_current
    add column closed_at timestamptz,
    add column closure_reason varchar(64),
    add column owner_result_digest char(71),
    add constraint iq_quality_recovery_task_current_status_v21_ck
        check (status in ('open','closed')),
    add constraint iq_quality_recovery_task_current_close_v21_ck check (
        (status='open' and closed_at is null and closure_reason is null
            and owner_result_digest is null)
        or (status='closed' and closed_at is not null
            and closure_reason in ('RECOVERY_FINALIZED')
            and owner_result_digest ~ '^sha256:[0-9a-f]{64}$'));

alter table ingestion_quality.iq_quality_recovery_outbox
    drop constraint if exists iq_quality_recovery_outbox_shape_ck;
alter table ingestion_quality.iq_quality_recovery_outbox
    add constraint iq_quality_recovery_outbox_shape_v21_ck check (
      event_type in (
        'scholarsense.ingestion-quality.quality-eligibility.recovering.v1',
        'scholarsense.ingestion-quality.recovery-lease.confirmation.v1',
        'scholarsense.ingestion-quality.quality-finalization-lease.confirmation.v1')
      and jsonb_typeof(payload)='object' and octet_length(payload::text)<=65536
      and payload_digest ~ '^sha256:[0-9a-f]{64}$'
      and (claimed_until is null or status='pending'));

-- Identity-access remains the sole owner and runtime writer for D4 evidence.
-- This coordinated expand step only strengthens its delivered approval/lease
-- constraints and separates the fresh recovering->eligible approval identity.
alter table identity_access.ia_high_risk_approval_history
    add constraint ia_high_risk_approval_history_final_pair_v21_ck check (
      (aggregate->>'currentState'='fused'
        and aggregate->>'targetState'='recovering'
        and not aggregate ? 'observationDecisionDigest'
        and not aggregate ? 'memberSetDigest'
        and not aggregate ? 'watermarksDigest')
      or (aggregate->>'currentState'='recovering'
        and aggregate->>'targetState'='eligible'
        and aggregate->>'observationDecisionDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'memberSetDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'watermarksDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'qualityRecoveryPolicyVersion'='QRP-1.0.0'
        and aggregate->>'qualityRecoveryPolicyDigest' ~ '^sha256:[0-9a-f]{64}$'));
alter table identity_access.ia_high_risk_approval_current
    add constraint ia_high_risk_approval_current_final_pair_v21_ck check (
      (aggregate->>'currentState'='fused'
        and aggregate->>'targetState'='recovering'
        and not aggregate ? 'observationDecisionDigest'
        and not aggregate ? 'memberSetDigest'
        and not aggregate ? 'watermarksDigest')
      or (aggregate->>'currentState'='recovering'
        and aggregate->>'targetState'='eligible'
        and aggregate->>'observationDecisionDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'memberSetDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'watermarksDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'qualityRecoveryPolicyVersion'='QRP-1.0.0'
        and aggregate->>'qualityRecoveryPolicyDigest' ~ '^sha256:[0-9a-f]{64}$'));
drop index if exists identity_access.ia_high_risk_approval_active_object_uk;
create unique index ia_high_risk_approval_active_object_v21_uk
    on identity_access.ia_high_risk_approval_current(
        action_type,object_ref_digest,object_version,maker_principal_digest,
        (aggregate->>'currentState'),(aggregate->>'targetState'))
    where status in ('pending','approved');
alter table identity_access.ia_high_risk_execution_lease_history
    add constraint ia_high_risk_lease_history_final_pair_v21_ck check (
      (aggregate->>'currentState'='fused'
        and aggregate->>'targetState'='recovering'
        and not aggregate ? 'observationDecisionDigest')
      or (aggregate->>'currentState'='recovering'
        and aggregate->>'targetState'='eligible'
        and aggregate->>'observationDecisionDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'memberSetDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'watermarksDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'qualityRecoveryPolicyVersion'='QRP-1.0.0'
        and aggregate->>'qualityRecoveryPolicyDigest' ~ '^sha256:[0-9a-f]{64}$'));
alter table identity_access.ia_high_risk_execution_lease_current
    add constraint ia_high_risk_lease_current_final_pair_v21_ck check (
      (aggregate->>'currentState'='fused'
        and aggregate->>'targetState'='recovering'
        and not aggregate ? 'observationDecisionDigest')
      or (aggregate->>'currentState'='recovering'
        and aggregate->>'targetState'='eligible'
        and aggregate->>'observationDecisionDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'memberSetDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'watermarksDigest' ~ '^sha256:[0-9a-f]{64}$'
        and aggregate->>'qualityRecoveryPolicyVersion'='QRP-1.0.0'
        and aggregate->>'qualityRecoveryPolicyDigest' ~ '^sha256:[0-9a-f]{64}$'));

alter table ingestion_quality.iq_quality_eligibility_history
    add constraint iq_quality_eligibility_history_reason_v21_ck check (reason_code in (
        'ALL_REQUIRED_ELIGIBLE','REQUIRED_MEMBER_FUSED','REQUIRED_MEMBER_RECOVERING',
        'REQUIRED_MEMBER_MISSING','REQUIRED_MEMBER_VERSION_GAP','ANY_OF_UNSATISFIED',
        'THRESHOLD_UNSATISFIED','BOOTSTRAP_FUSED','FUSE_LATCHED','RECOVERY_LATCHED',
        'RECOVERY_RELAPSED','RECOVERY_COMMAND_ACCEPTED',
        'RECOVERY_VALIDATION_APPROVED','RECOVERY_FINALIZED'));
alter table ingestion_quality.iq_quality_eligibility_current
    add constraint iq_quality_eligibility_current_reason_v21_ck check (reason_code in (
        'ALL_REQUIRED_ELIGIBLE','REQUIRED_MEMBER_FUSED','REQUIRED_MEMBER_RECOVERING',
        'REQUIRED_MEMBER_MISSING','REQUIRED_MEMBER_VERSION_GAP','ANY_OF_UNSATISFIED',
        'THRESHOLD_UNSATISFIED','BOOTSTRAP_FUSED','FUSE_LATCHED','RECOVERY_LATCHED',
        'RECOVERY_RELAPSED','RECOVERY_COMMAND_ACCEPTED',
        'RECOVERY_VALIDATION_APPROVED','RECOVERY_FINALIZED'));

create table ingestion_quality.iq_recovery_observation_fact (
    fact_id uuid primary key,
    recovery_id uuid not null references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    generation bigint not null check (generation between 1 and 9007199254740991),
    source_id varchar(64) not null check (
        source_id ~ '^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$'),
    dependency_id varchar(64) not null check (
        dependency_id ~ '^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$'),
    source_version_ordinal bigint not null check (
        source_version_ordinal between 1 and 9007199254740991),
    lineage_revision bigint not null check (
        lineage_revision between 0 and 9007199254740991),
    fact_type varchar(32) not null check (
        fact_type in ('published-pair','verified-quality-failure')),
    batch_id uuid not null,
    snapshot_id uuid not null,
    fact_digest char(71) not null check (fact_digest ~ '^sha256:[0-9a-f]{64}$'),
    watermark_utf8 bytea not null check (
        ingestion_quality.iq_utf8_scalar_count(watermark_utf8) between 1 and 512),
    observed_at timestamptz not null,
    trace_id char(32) not null check (trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'),
    retention_due_at timestamptz generated always as (
        ((observed_at at time zone 'UTC')+interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    unique (recovery_id,generation,source_version_ordinal,lineage_revision,fact_type),
    check (substring(fact_id::text,15,1)='7'
        and substring(fact_id::text,20,1) in ('8','9','a','b'))
);
create index iq_recovery_observation_fact_scope_idx
    on ingestion_quality.iq_recovery_observation_fact(
        recovery_id,generation,source_version_ordinal,lineage_revision);
create index iq_recovery_observation_fact_retention_idx
    on ingestion_quality.iq_recovery_observation_fact(
        legal_hold,retention_due_at,recovery_id,generation);

create table ingestion_quality.iq_recovery_observation_current (
    recovery_id uuid primary key references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    generation bigint not null check (generation between 1 and 9007199254740991),
    source_id varchar(64) not null,
    dependency_id varchar(64) not null,
    source_class varchar(16) not null check (source_class in ('streaming','daily-batch')),
    policy_version varchar(64) not null check (policy_version='QRP-1.0.0'),
    policy_digest char(71) not null check (policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    member_set_digest char(71) not null check (member_set_digest ~ '^sha256:[0-9a-f]{64}$'),
    watermarks_digest char(71) not null check (watermarks_digest ~ '^sha256:[0-9a-f]{64}$'),
    recovering_started_at timestamptz not null,
    last_source_version_ordinal bigint not null default 0 check (
        last_source_version_ordinal between 0 and 9007199254740991),
    last_lineage_revision bigint not null default 0 check (
        last_lineage_revision between 0 and 9007199254740991),
    consecutive_passed_batches integer not null default 0 check (
        consecutive_passed_batches between 0 and 1000000),
    watermark_utf8 bytea,
    status varchar(32) not null check (
        status in ('observing','relapsed','ready','policy-drift','finalized')),
    aggregate_version bigint not null default 0 check (
        aggregate_version between 0 and 9007199254740991),
    finalization_state varchar(24) not null default 'not-requested' check (
        finalization_state in ('not-requested','approval-pending','approval-approved',
            'approval-rejected','cancelled','finalizing','executed')),
    final_request_digest char(71),
    final_preview_digest char(71),
    final_approval_id uuid,
    final_approval_version bigint,
    final_approval_receipt_digest char(71),
    final_checker_set_digest char(71),
    final_owner_binding_set_digest char(71),
    final_checker_person_set_digest char(71),
    final_authorization_context_digest char(71),
    final_authentication_state_digest char(71),
    final_authorization_generation bigint,
    final_maker_principal_digest char(71),
    final_trace_id char(32),
    updated_at timestamptz not null,
    check ((last_source_version_ordinal=0)=(watermark_utf8 is null)),
    check ((last_source_version_ordinal=0)=(aggregate_version=0)),
    check ((finalization_state='not-requested')=(final_approval_id is null)),
    check (finalization_state='not-requested' or (
        final_request_digest ~ '^sha256:[0-9a-f]{64}$'
        and final_preview_digest ~ '^sha256:[0-9a-f]{64}$'
        and final_approval_id is not null
        and final_approval_version between 1 and 9007199254740991
        and final_checker_set_digest ~ '^sha256:[0-9a-f]{64}$'
        and final_owner_binding_set_digest ~ '^sha256:[0-9a-f]{64}$'
        and final_checker_person_set_digest ~ '^sha256:[0-9a-f]{64}$'
        and final_authorization_context_digest ~ '^sha256:[0-9a-f]{64}$'
        and final_authentication_state_digest ~ '^sha256:[0-9a-f]{64}$'
        and final_authorization_generation between 0 and 9007199254740991
        and final_maker_principal_digest ~ '^sha256:[0-9a-f]{64}$'
        and final_trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$')),
    check ((finalization_state in ('approval-approved','approval-rejected','cancelled',
            'finalizing','executed'))
        =(final_approval_receipt_digest is not null)),
    check (final_approval_receipt_digest is null
        or final_approval_receipt_digest ~ '^sha256:[0-9a-f]{64}$')
);
create index iq_recovery_observation_current_source_idx
    on ingestion_quality.iq_recovery_observation_current(
        source_id,dependency_id,generation,status);
create unique index iq_recovery_observation_active_source_generation_uk
    on ingestion_quality.iq_recovery_observation_current(
        source_id,dependency_id,generation)
    where status in ('observing','ready');

create table ingestion_quality.iq_recovery_observation_decision (
    decision_id uuid primary key,
    recovery_id uuid not null references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    generation bigint not null check (generation between 1 and 9007199254740991),
    aggregate_version bigint not null check (
        aggregate_version between 1 and 9007199254740991),
    status varchar(32) not null check (status in (
        'not-ready','ready','relapsed','dependency-unavailable','policy-drift','finalized')),
    reason_code varchar(64) not null check (reason_code in (
        'NO_DATA','CONSECUTIVE_BATCHES_INSUFFICIENT','DURATION_INCOMPLETE',
        'SEQUENCE_GAP','POISONED_PAIR','EVIDENCE_UNKNOWN','TECHNICAL_UNAVAILABLE',
        'VERIFIED_QUALITY_FAILURE','CURRENT_FACT_DRIFT','READY','RECOVERY_FINALIZED')),
    consecutive_passed_batches integer not null check (
        consecutive_passed_batches between 0 and 1000000),
    required_passed_batches integer not null check (required_passed_batches in (2,3)),
    observed_duration_micros bigint not null check (observed_duration_micros>=0),
    required_duration_micros bigint not null check (
        required_duration_micros in (3600000000,86400000000)),
    final_watermark_utf8 bytea,
    policy_digest char(71) not null check (policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    member_set_digest char(71) not null check (member_set_digest ~ '^sha256:[0-9a-f]{64}$'),
    watermarks_digest char(71) not null check (watermarks_digest ~ '^sha256:[0-9a-f]{64}$'),
    decision_digest char(71) not null check (decision_digest ~ '^sha256:[0-9a-f]{64}$'),
    decided_at timestamptz not null,
    trace_id char(32) not null check (trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'),
    retention_due_at timestamptz generated always as (
        ((decided_at at time zone 'UTC')+interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    unique (recovery_id,generation,aggregate_version),
    unique (recovery_id,generation,decision_digest),
    check ((status='relapsed')=(reason_code='VERIFIED_QUALITY_FAILURE')),
    check ((status='ready')=(reason_code='READY')),
    check ((status='finalized')=(reason_code='RECOVERY_FINALIZED'))
);
create index iq_recovery_observation_decision_retention_idx
    on ingestion_quality.iq_recovery_observation_decision(
        legal_hold,retention_due_at,recovery_id,generation);

create table ingestion_quality.iq_recovery_observation_job (
    job_id uuid primary key,
    recovery_id uuid not null unique references
        ingestion_quality.iq_quality_recovery_request_current(recovery_request_id),
    generation bigint not null check (generation between 1 and 9007199254740991),
    status varchar(16) not null check (
        status in ('scheduled','running','completed','failed','cancelled')),
    attempt_count integer not null default 0 check (attempt_count between 0 and 8),
    next_attempt_at timestamptz not null,
    lease_owner_digest char(71),
    lease_until timestamptz,
    lease_generation bigint not null default 0 check (
        lease_generation between 0 and 9007199254740991),
    last_error_code varchar(64),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    legal_hold boolean not null default false,
    check ((status='running')=(lease_owner_digest is not null and lease_until is not null)),
    check ((lease_owner_digest is null)=(lease_until is null)),
    check (substring(job_id::text,15,1)='7'
        and substring(job_id::text,20,1) in ('8','9','a','b'))
);
create index iq_recovery_observation_job_due_idx
    on ingestion_quality.iq_recovery_observation_job(
        status,next_attempt_at,job_id);

create table ingestion_quality.iq_recovery_observation_attempt (
    job_id uuid not null references ingestion_quality.iq_recovery_observation_job(job_id),
    lease_generation bigint not null,
    attempt_number integer not null check (attempt_number between 1 and 8),
    worker_digest char(71) not null check (worker_digest ~ '^sha256:[0-9a-f]{64}$'),
    started_at timestamptz not null,
    finished_at timestamptz,
    outcome varchar(32) check (outcome in (
        'completed','retry-scheduled','yielded','failed','fenced')),
    error_code varchar(64),
    retention_due_at timestamptz generated always as (
        ((started_at at time zone 'UTC')+interval '90 days') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    primary key (job_id,lease_generation),
    check ((finished_at is null)=(outcome is null))
);
create index iq_recovery_observation_attempt_retention_idx
    on ingestion_quality.iq_recovery_observation_attempt(
        legal_hold,retention_due_at,job_id,lease_generation);

create table ingestion_quality.iq_recovery_final_idempotency (
    idempotency_key_digest char(71) primary key check (
        idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'),
    client_command_digest char(71) not null check (
        client_command_digest ~ '^sha256:[0-9a-f]{64}$'),
    command_digest char(71) not null check (command_digest ~ '^sha256:[0-9a-f]{64}$'),
    recovery_id uuid not null,
    final_observation_watermark_utf8 bytea not null,
    response jsonb not null check (jsonb_typeof(response)='object'),
    created_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    check (expires_at=created_at+interval '90 days')
);
create index iq_recovery_final_idempotency_retention_idx
    on ingestion_quality.iq_recovery_final_idempotency(
        legal_hold,expires_at,recovery_id);
create unique index iq_recovery_final_idempotency_watermark_uk
    on ingestion_quality.iq_recovery_final_idempotency(
        recovery_id,final_observation_watermark_utf8);

create table ingestion_quality.iq_recovery_final_execution_jti (
    execution_jti uuid primary key,
    lease_id uuid not null,
    recovery_id uuid not null,
    owner_commit_id varchar(256) not null unique,
    owner_result_digest char(71) not null check (
        owner_result_digest ~ '^sha256:[0-9a-f]{64}$'),
    outbox_event_id uuid not null unique,
    consumed_at timestamptz not null,
    confirmed_at timestamptz,
    authorized_until timestamptz not null,
    expires_at timestamptz generated always as (
        ((consumed_at at time zone 'UTC')+interval '90 days') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    check (consumed_at<authorized_until),
    check (confirmed_at is null or confirmed_at>=consumed_at),
    check (substring(execution_jti::text,15,1)='7'
        and substring(execution_jti::text,20,1) in ('8','9','a','b'))
);
create index iq_recovery_final_execution_jti_retention_idx
    on ingestion_quality.iq_recovery_final_execution_jti(
        legal_hold,expires_at,recovery_id);

create table ingestion_quality.iq_recovery_window_outcome (
    recovery_id uuid not null,
    generation bigint not null,
    rule_id varchar(64) not null,
    rule_version varchar(64) not null,
    window_id varchar(128) not null,
    watermark_utf8 bytea not null,
    latest_actionable_at timestamptz not null,
    recovery_completed_at timestamptz not null,
    outcome varchar(32) not null check (
        outcome in ('eligible-for-handoff','history-only')),
    decision_reason varchar(64) not null check (decision_reason in (
        'COMPLETED_AT_OR_BEFORE_LATEST_ACTIONABLE',
        'COMPLETED_AFTER_LATEST_ACTIONABLE')),
    decision_digest char(71) not null check (
        decision_digest ~ '^sha256:[0-9a-f]{64}$'),
    occurred_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((occurred_at at time zone 'UTC')+interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    primary key (recovery_id,generation,rule_id,rule_version,window_id),
    check ((recovery_completed_at<=latest_actionable_at)
        =(outcome='eligible-for-handoff'))
);
create index iq_recovery_window_outcome_retention_idx
    on ingestion_quality.iq_recovery_window_outcome(
        legal_hold,retention_due_at,recovery_id,generation);

create function ingestion_quality.iq_guard_recovery_observation_history()
returns trigger language plpgsql security definer set search_path=pg_catalog as $$
begin
    if tg_op='UPDATE' then
        if tg_table_name='iq_recovery_observation_attempt'
           and pg_catalog.pg_has_role(session_user,
             'scholarsense_ingestion_quality_recovery_worker','USAGE')
           and old.finished_at is null and old.outcome is null and old.error_code is null
           and new.finished_at is not null and new.outcome is not null then
            return new;
        end if;
        raise exception using errcode='object_not_in_prerequisite_state',
            message='INGESTION_QUALITY_OBSERVATION_HISTORY_IMMUTABLE';
    end if;
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_retention_executor');
    return old;
end
$$;
create trigger iq_recovery_observation_fact_guard
before update or delete on ingestion_quality.iq_recovery_observation_fact
for each row execute function ingestion_quality.iq_guard_recovery_observation_history();
create trigger iq_recovery_observation_decision_guard
before update or delete on ingestion_quality.iq_recovery_observation_decision
for each row execute function ingestion_quality.iq_guard_recovery_observation_history();
create trigger iq_recovery_observation_attempt_guard
before update or delete on ingestion_quality.iq_recovery_observation_attempt
for each row execute function ingestion_quality.iq_guard_recovery_observation_history();
create trigger iq_recovery_window_outcome_guard
before update or delete on ingestion_quality.iq_recovery_window_outcome
for each row execute function ingestion_quality.iq_guard_recovery_observation_history();

create function ingestion_quality.iq_guard_quality_recovery_task_reopen()
returns trigger language plpgsql set search_path=pg_catalog as $$
begin
    if tg_op='UPDATE' and old.status='closed' and new.status<>'closed' then
        raise exception using errcode='object_not_in_prerequisite_state',
            message='INGESTION_QUALITY_TASK_REOPEN_FORBIDDEN';
    end if;
    return new;
end
$$;
create trigger iq_quality_recovery_task_reopen_guard
before update on ingestion_quality.iq_quality_recovery_task_current
for each row execute function ingestion_quality.iq_guard_quality_recovery_task_reopen();

create function ingestion_quality.iq_start_recovery_observation(requested jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_task ingestion_quality.iq_quality_recovery_task_current%rowtype;
declare iq_episode ingestion_quality.iq_quality_fuse_episode_current%rowtype;
declare iq_existing ingestion_quality.iq_recovery_observation_current%rowtype;
declare iq_now timestamptz:=statement_timestamp();
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    if requested->>'policyVersion'<>'QRP-1.0.0'
       or requested->>'policyDigest' !~ '^sha256:[0-9a-f]{64}$'
       or requested->>'memberSetDigest' !~ '^sha256:[0-9a-f]{64}$'
       or requested->>'watermarksDigest' !~ '^sha256:[0-9a-f]{64}$'
       or requested->>'sourceClass' not in ('streaming','daily-batch') then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_OBSERVATION_INVALID';
    end if;
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=(requested->>'recoveryId')::uuid;
    if iq_request.recovery_request_id is null then
        raise exception using errcode='no_data_found',
            message='INGESTION_QUALITY_RECOVERY_NOT_FOUND';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-eligibility:'||iq_request.source_id,0));
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=iq_request.recovery_request_id for update;
    select task.* into iq_task
      from ingestion_quality.iq_quality_recovery_task_current task
     where task.task_id=iq_request.task_id for update;
    select episode.* into iq_episode
      from ingestion_quality.iq_quality_fuse_episode_current episode
     where episode.episode_id=iq_request.episode_id for update;
    select observation.* into iq_existing
      from ingestion_quality.iq_recovery_observation_current observation
     where observation.recovery_id=iq_request.recovery_request_id for update;
    if iq_existing.recovery_id is not null then
        if iq_existing.generation<>(requested->>'generation')::bigint
           or iq_existing.source_class<>requested->>'sourceClass'
           or iq_existing.policy_version<>requested->>'policyVersion'
           or trim(iq_existing.policy_digest)<>requested->>'policyDigest'
           or trim(iq_existing.member_set_digest)<>requested->>'memberSetDigest'
           or trim(iq_existing.watermarks_digest)<>requested->>'watermarksDigest' then
            raise exception using errcode='unique_violation',
                message='INGESTION_QUALITY_OBSERVATION_IDEMPOTENCY_CONFLICT';
        end if;
        return jsonb_build_object('recoveryId',iq_existing.recovery_id,
            'jobId',(select job_id from ingestion_quality.iq_recovery_observation_job
                where recovery_id=iq_existing.recovery_id),
            'status',iq_existing.status,'replayed',true);
    end if;
    if iq_request.status<>'executed' or iq_task.status<>'open' or not iq_episode.active
       or iq_episode.generation<>(requested->>'generation')::bigint
       or iq_task.episode_generation<>iq_episode.generation
       or iq_request.qrp_version<>requested->>'policyVersion'
       or trim(iq_request.qrp_digest)<>requested->>'policyDigest'
       or trim(iq_request.member_set_digest)<>requested->>'memberSetDigest'
       or trim(iq_request.watermarks_digest)<>requested->>'watermarksDigest'
       or exists (select 1
          from ingestion_quality.iq_quality_recovery_task_affected_rule affected
          left join ingestion_quality.iq_quality_eligibility_current eligibility
            on eligibility.rule_id=affected.rule_id
           and eligibility.rule_version=affected.rule_version
           and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
         where affected.task_id=iq_task.task_id
           and (eligibility.eligibility_id is null
                or eligibility.status<>'recovering')) then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_MIXED_GENERATION';
    end if;
    insert into ingestion_quality.iq_recovery_observation_current(
        recovery_id,generation,source_id,dependency_id,source_class,
        policy_version,policy_digest,member_set_digest,watermarks_digest,
        recovering_started_at,status,updated_at)
    values (iq_request.recovery_request_id,iq_episode.generation,iq_request.source_id,
        iq_request.dependency_id,requested->>'sourceClass','QRP-1.0.0',
        requested->>'policyDigest',requested->>'memberSetDigest',
        requested->>'watermarksDigest',iq_request.updated_at,
        'observing',iq_now);
    insert into ingestion_quality.iq_recovery_observation_job(
        job_id,recovery_id,generation,status,next_attempt_at,created_at,updated_at)
    values ((requested->>'jobId')::uuid,iq_request.recovery_request_id,
        iq_episode.generation,'scheduled',iq_now,iq_now,iq_now);
    return jsonb_build_object('recoveryId',iq_request.recovery_request_id,
        'jobId',requested->>'jobId','status','observing','replayed',false);
end
$$;

-- Preserve V16/V20 bytes by wrapping the stable public function names.
alter function ingestion_quality.iq_execute_quality_recovery(character,jsonb)
rename to iq_execute_quality_recovery_v20;
revoke all on function ingestion_quality.iq_execute_quality_recovery_v20(character,jsonb)
from public,scholarsense_ingestion_quality_online;

create function ingestion_quality.iq_execute_quality_recovery(
    requested_idempotency_digest character,requested_command jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_result jsonb;
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_source_class varchar;
declare iq_job_id uuid;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    iq_result:=ingestion_quality.iq_execute_quality_recovery_v20(
        requested_idempotency_digest,requested_command);
    if iq_result->>'state'<>'recovering' then return iq_result; end if;
    select request.* into strict iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=(iq_result->>'recoveryRequestId')::uuid;
    select case evidence.evidence->>'sourceClass'
      when 'streaming' then 'streaming'
      when 'dailyBatch' then 'daily-batch' else null end
      into iq_source_class
      from ingestion_quality.iq_quality_recovery_evidence_pack evidence
     where evidence.recovery_request_id=iq_request.recovery_request_id;
    if iq_source_class is null then
        raise exception using errcode='data_exception',
            message='INGESTION_QUALITY_SOURCE_CLASS_BINDING_INVALID';
    end if;
    iq_job_id:=ingestion_quality.iq_quality_uuid_v7_derive(
        (iq_result->>'executionJti')::uuid,'recovery-observation-job');
    perform ingestion_quality.iq_start_recovery_observation(jsonb_build_object(
      'recoveryId',iq_request.recovery_request_id,'jobId',iq_job_id,
      'generation',iq_request.episode_generation,'sourceClass',iq_source_class,
      'policyVersion',iq_request.qrp_version,'policyDigest',trim(iq_request.qrp_digest),
      'memberSetDigest',trim(iq_request.member_set_digest),
      'watermarksDigest',trim(iq_request.watermarks_digest)));
    return iq_result||jsonb_build_object('observationJobId',iq_job_id,
      'observationStatus','observing');
end
$$;

alter function ingestion_quality.iq_load_quality_eligibility_processing_state_v2(
    uuid,varchar) rename to iq_load_quality_eligibility_processing_state_v20;
revoke all on function ingestion_quality.iq_load_quality_eligibility_processing_state_v20(
    uuid,varchar) from public,scholarsense_ingestion_quality_eligibility_consumer;

create function ingestion_quality.iq_load_quality_eligibility_processing_state_v2(
    requested_event_id uuid,requested_source_id varchar)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_base jsonb;
begin
    iq_base:=ingestion_quality.iq_load_quality_eligibility_processing_state_v20(
        requested_event_id,requested_source_id);
    return iq_base||jsonb_build_object('recoveryObservations',coalesce((
      select jsonb_object_agg(observation.source_id||'@'||observation.dependency_id,
        jsonb_build_object(
          'recoveryId',observation.recovery_id,'generation',observation.generation,
          'sourceId',observation.source_id,'dependencyId',observation.dependency_id,
          'recoveringStartedAt',observation.recovering_started_at,
          'lastSourceVersionOrdinal',observation.last_source_version_ordinal,
          'lastLineageRevision',observation.last_lineage_revision,
          'consecutivePassedBatches',observation.consecutive_passed_batches,
          'watermark',case when observation.watermark_utf8 is null then null
            else convert_from(observation.watermark_utf8,'UTF8') end,
          'lastObservedAt',observation.updated_at,'status',observation.status,
          'aggregateVersion',observation.aggregate_version))
      from ingestion_quality.iq_recovery_observation_current observation
      join ingestion_quality.iq_quality_fuse_episode_current episode
        on episode.source_id=observation.source_id
       and episode.dependency_id=observation.dependency_id
       and episode.generation=observation.generation
     where observation.source_id=requested_source_id and episode.active
       and observation.status in ('observing','ready')),
      '{}'::jsonb));
end
$$;

alter function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    uuid,varchar,bigint,character,jsonb)
rename to iq_accept_quality_eligibility_event_v20;
revoke all on function ingestion_quality.iq_accept_quality_eligibility_event_v20(
    uuid,varchar,bigint,character,jsonb)
from public,scholarsense_ingestion_quality_eligibility_consumer;

create function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    requested_event_id uuid,requested_source_id varchar,
    requested_source_version bigint,requested_payload_digest character,
    requested_mutation jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_result jsonb;
declare iq_progress jsonb:=requested_mutation->'recoveryObservationProgress';
declare iq_current ingestion_quality.iq_recovery_observation_current%rowtype;
declare iq_snapshot jsonb:=requested_mutation->'snapshotEvidence';
declare iq_now timestamptz:=statement_timestamp();
declare iq_fact_type varchar;
begin
    iq_result:=ingestion_quality.iq_accept_quality_eligibility_event_v20(
        requested_event_id,requested_source_id,requested_source_version,
        requested_payload_digest,requested_mutation-'recoveryObservationProgress');
    if iq_progress is null or iq_progress='null'::jsonb
       or iq_result->>'outcome'<>'applied' then return iq_result; end if;
    select observation.* into iq_current
      from ingestion_quality.iq_recovery_observation_current observation
     where observation.recovery_id=(iq_progress->>'recoveryId')::uuid for update;
    if iq_current.recovery_id is null
       or iq_current.source_id<>iq_progress->>'sourceId'
       or iq_current.dependency_id<>iq_progress->>'dependencyId'
       or iq_current.generation<>(iq_progress->>'generation')::bigint
       or iq_current.aggregate_version+1<>(iq_progress->>'aggregateVersion')::bigint
       or iq_current.status not in ('observing','ready')
       or (iq_current.last_source_version_ordinal<>0 and not (
            iq_current.last_source_version_ordinal+1=
              (iq_progress->>'lastSourceVersionOrdinal')::bigint
            or (iq_current.last_source_version_ordinal=
                  (iq_progress->>'lastSourceVersionOrdinal')::bigint
                and (iq_progress->>'lastLineageRevision')::bigint>
                  iq_current.last_lineage_revision))) then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_OBSERVATION_FENCE_STALE';
    end if;
    iq_fact_type:=case iq_progress->>'status'
        when 'relapsed' then 'verified-quality-failure' else 'published-pair' end;
    insert into ingestion_quality.iq_recovery_observation_fact(
        fact_id,recovery_id,generation,source_id,dependency_id,
        source_version_ordinal,lineage_revision,fact_type,batch_id,snapshot_id,
        fact_digest,watermark_utf8,observed_at,trace_id,legal_hold)
    values (requested_event_id,iq_current.recovery_id,iq_current.generation,
        iq_current.source_id,iq_current.dependency_id,
        (iq_progress->>'lastSourceVersionOrdinal')::bigint,
        (iq_progress->>'lastLineageRevision')::bigint,iq_fact_type,
        (requested_mutation#>>'{cursor,batchId}')::uuid,
        (iq_snapshot->>'snapshotId')::uuid,
        'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
          ingestion_quality.iq_json_canonical(iq_progress),'UTF8')),'hex'),
        pg_catalog.convert_to(iq_progress->>'watermark','UTF8'),
        (iq_progress->>'lastObservedAt')::timestamptz,
        requested_mutation->>'traceId',iq_current.recovery_id in (
          select recovery_request_id
            from ingestion_quality.iq_quality_recovery_request_current
           where legal_hold));
    update ingestion_quality.iq_recovery_observation_current set
        last_source_version_ordinal=(iq_progress->>'lastSourceVersionOrdinal')::bigint,
        last_lineage_revision=(iq_progress->>'lastLineageRevision')::bigint,
        consecutive_passed_batches=(iq_progress->>'consecutivePassedBatches')::integer,
        watermark_utf8=pg_catalog.convert_to(iq_progress->>'watermark','UTF8'),
        status=iq_progress->>'status',
        aggregate_version=(iq_progress->>'aggregateVersion')::bigint,
        updated_at=(iq_progress->>'lastObservedAt')::timestamptz
      where recovery_id=iq_current.recovery_id
        and aggregate_version=iq_current.aggregate_version;
    if not found then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_OBSERVATION_FENCE_STALE';
    end if;
    if iq_current.status='ready' and iq_progress->>'status'='observing' then
        update ingestion_quality.iq_recovery_observation_job set
            status='scheduled',attempt_count=0,next_attempt_at=
              (iq_progress->>'lastObservedAt')::timestamptz,
            lease_owner_digest=null,lease_until=null,last_error_code=null,
            updated_at=(iq_progress->>'lastObservedAt')::timestamptz
         where recovery_id=iq_current.recovery_id and status='completed';
    end if;
    return iq_result;
end
$$;

create function ingestion_quality.iq_find_claimable_recovery_observation_jobs(
    requested_limit integer,requested_now timestamptz)
returns table(job_id uuid) language plpgsql security definer set search_path=pg_catalog as $$
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    if requested_limit not between 1 and 100 then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_OBSERVATION_LIMIT_INVALID';
    end if;
    return query select job.job_id
      from ingestion_quality.iq_recovery_observation_job job
     where job.attempt_count<8 and (
           (job.status='scheduled' and job.next_attempt_at<=requested_now)
        or (job.status='running' and job.lease_until<=requested_now))
     order by job.next_attempt_at,job.job_id limit requested_limit;
end
$$;

create function ingestion_quality.iq_claim_recovery_observation_job(
    requested_job_id uuid,requested_worker_digest character,
    requested_now timestamptz,requested_lease_seconds integer)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_job ingestion_quality.iq_recovery_observation_job%rowtype;
declare iq_observation ingestion_quality.iq_recovery_observation_current%rowtype;
declare iq_attempt integer;
declare iq_all_recovering boolean;
declare iq_evidence_pack jsonb;
declare iq_published_fact_count bigint;
declare iq_snapshot_count bigint;
declare iq_freshness_passed_count bigint;
declare iq_freshness_failed_count bigint;
declare iq_current_readiness jsonb;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    if requested_worker_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_lease_seconds not between 1 and 300 then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_OBSERVATION_CLAIM_INVALID';
    end if;
    update ingestion_quality.iq_recovery_observation_job set
        status='running',attempt_count=attempt_count+1,
        lease_owner_digest=requested_worker_digest,
        lease_until=requested_now+make_interval(secs=>requested_lease_seconds),
        lease_generation=lease_generation+1,updated_at=requested_now
     where job_id=requested_job_id and attempt_count<8
       and ((status='scheduled' and next_attempt_at<=requested_now)
         or (status='running' and lease_until<=requested_now))
    returning * into iq_job;
    if iq_job.job_id is null then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_OBSERVATION_FENCE_STALE';
    end if;
    iq_attempt:=iq_job.attempt_count;
    insert into ingestion_quality.iq_recovery_observation_attempt(
        job_id,lease_generation,attempt_number,worker_digest,started_at,legal_hold)
    values (iq_job.job_id,iq_job.lease_generation,iq_attempt,
        requested_worker_digest,requested_now,iq_job.legal_hold);
    select observation.* into strict iq_observation
      from ingestion_quality.iq_recovery_observation_current observation
     where observation.recovery_id=iq_job.recovery_id;
    select not exists (select 1
        from ingestion_quality.iq_quality_recovery_task_affected_rule affected
        join ingestion_quality.iq_quality_recovery_request_current request
          on request.task_id=affected.task_id
        left join ingestion_quality.iq_quality_eligibility_current eligibility
          on eligibility.rule_id=affected.rule_id
         and eligibility.rule_version=affected.rule_version
         and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
       where request.recovery_request_id=iq_observation.recovery_id
         and (eligibility.eligibility_id is null or eligibility.status<>'recovering'))
      into iq_all_recovering;
    select evidence.evidence into iq_evidence_pack
      from ingestion_quality.iq_quality_recovery_evidence_pack evidence
     where evidence.recovery_request_id=iq_observation.recovery_id
     order by evidence.created_at desc,evidence.evidence_pack_id desc limit 1;
    iq_current_readiness:=
      ingestion_quality.iq_build_quality_recovery_readiness_evidence(
        iq_observation.recovery_id,requested_now);
    select count(*) filter (where fact.fact_type='published-pair'),
           count(snapshot.snapshot_id) filter (where fact.fact_type='published-pair'),
           count(*) filter (where fact.fact_type='published-pair'
             and metric.applicable and metric.result='passed'),
           count(*) filter (where fact.fact_type='published-pair'
             and metric.applicable and metric.result='failed')
      into iq_published_fact_count,iq_snapshot_count,
           iq_freshness_passed_count,iq_freshness_failed_count
      from ingestion_quality.iq_recovery_observation_fact fact
      left join ingestion_quality.iq_quality_snapshot snapshot
        on snapshot.snapshot_id=fact.snapshot_id
       and snapshot.batch_id=fact.batch_id
       and snapshot.source_id=iq_observation.source_id
       and snapshot.overall_result='quality-passed'
      left join ingestion_quality.iq_quality_snapshot_metric metric
        on metric.snapshot_id=snapshot.snapshot_id
       and metric.metric_id='FRESHNESS_WITHIN_SLO_BP'
     where fact.recovery_id=iq_observation.recovery_id;
    return jsonb_build_object('jobId',iq_job.job_id,
      'leaseGeneration',iq_job.lease_generation,'attemptNumber',iq_attempt,
      'recoveryId',iq_observation.recovery_id,'generation',iq_observation.generation,
      'sourceId',iq_observation.source_id,'dependencyId',iq_observation.dependency_id,
      'sourceClass',iq_observation.source_class,
      'recoveringStartedAt',iq_observation.recovering_started_at,
      'policyVersion',iq_observation.policy_version,
      'policyDigest',trim(iq_observation.policy_digest),
      'memberSetDigest',trim(iq_observation.member_set_digest),
      'watermarksDigest',trim(iq_observation.watermarks_digest),
      'currentFence',jsonb_build_object(
        'policyVersion','QRP-1.0.0',
        'policyDigest','sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366',
        'memberSetDigest',iq_current_readiness->>'memberSetDigest',
        'watermarksDigest',iq_current_readiness->>'watermarksDigest'),
      'allAffectedEligibilitiesRecovering',iq_all_recovering,
      'evidence',jsonb_build_object(
        'requiredMembers',case when iq_all_recovering then 'passed'
          else 'verified-failed' end,
        'reconciliation',case
          when iq_evidence_pack is null then 'unknown'
          when iq_evidence_pack#>>'{reconciliationEvidence,expectedCount}' !~ '^[0-9]+$'
            or iq_evidence_pack#>>'{reconciliationEvidence,actualCount}' !~ '^[0-9]+$'
            or iq_evidence_pack#>>'{reconciliationEvidence,mismatchCount}' !~ '^[0-9]+$'
            then 'unknown'
          when iq_evidence_pack#>>'{reconciliationEvidence,coverage}'='full'
            and (iq_evidence_pack#>>'{reconciliationEvidence,expectedCount}')::bigint=
                (iq_evidence_pack#>>'{reconciliationEvidence,actualCount}')::bigint
            and (iq_evidence_pack#>>'{reconciliationEvidence,mismatchCount}')::bigint=0
            then 'passed'
          else 'verified-failed' end,
        'sample',case
          when iq_evidence_pack is null then 'unknown'
          when iq_evidence_pack#>>'{sampleEvidence,providerAvailability}'='unavailable'
            then 'unavailable'
          when iq_evidence_pack#>>'{sampleEvidence,populationCount}' !~ '^[0-9]+$'
            or iq_evidence_pack#>>'{sampleEvidence,selectedCount}' !~ '^[0-9]+$'
            or iq_evidence_pack#>>'{sampleEvidence,mismatchCount}' !~ '^[0-9]+$'
            then 'unknown'
          when iq_evidence_pack#>>'{sampleEvidence,providerAvailability}'='available'
            and (iq_evidence_pack#>>'{sampleEvidence,mismatchCount}')::bigint=0
            and (iq_evidence_pack#>>'{sampleEvidence,selectedCount}')::bigint=
              least((iq_evidence_pack#>>'{sampleEvidence,populationCount}')::bigint,100)
            then 'passed'
          else 'verified-failed' end,
        'sloFreshness',case
          when iq_published_fact_count=0 then 'unknown'
          when iq_snapshot_count<>iq_published_fact_count
            or iq_freshness_passed_count+iq_freshness_failed_count<>
               iq_published_fact_count then 'unknown'
          when iq_freshness_failed_count>0 then 'verified-failed'
          else 'passed' end),
      'facts',coalesce((select jsonb_agg(jsonb_build_object(
        'eventId',policy_fact.fact_id,'batchId',policy_fact.batch_id,
        'snapshotId',policy_fact.snapshot_id,
        'sourceVersionOrdinal',policy_fact.source_version_ordinal,
        'lineageRevision',policy_fact.lineage_revision,
        'factType',policy_fact.fact_type,
        'factDigest',trim(policy_fact.fact_digest),
        'watermark',convert_from(policy_fact.watermark_utf8,'UTF8'),
        'observedAt',policy_fact.observed_at)
          order by policy_fact.source_version_ordinal,
            policy_fact.lineage_revision,policy_fact.observed_at)
        from (
          (select distinct on (fact.source_version_ordinal) fact.*
             from ingestion_quality.iq_recovery_observation_fact fact
            where fact.recovery_id=iq_observation.recovery_id
              and fact.fact_type='published-pair'
            order by fact.source_version_ordinal desc,
              fact.lineage_revision desc,fact.observed_at desc
            limit 3)
          union all
          (select fact.*
             from ingestion_quality.iq_recovery_observation_fact fact
            where fact.recovery_id=iq_observation.recovery_id
              and fact.fact_type='verified-quality-failure'
            order by fact.source_version_ordinal desc,
              fact.lineage_revision desc,fact.observed_at desc
            limit 1)
        ) policy_fact),'[]'::jsonb));
end
$$;

create function ingestion_quality.iq_is_recovery_observation_lease_current(
    requested_job_id uuid,requested_lease_generation bigint,requested_now timestamptz)
returns boolean language plpgsql security definer set search_path=pg_catalog as $$
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    return exists (select 1 from ingestion_quality.iq_recovery_observation_job job
      where job.job_id=requested_job_id and job.status='running'
        and job.lease_generation=requested_lease_generation
        and requested_now<job.lease_until);
end
$$;

create function ingestion_quality.iq_finalize_recovery_observation_job(
    requested_job_id uuid,requested_lease_generation bigint,
    requested_decision jsonb,requested_now timestamptz)
returns boolean language plpgsql security definer set search_path=pg_catalog as $$
declare iq_job ingestion_quality.iq_recovery_observation_job%rowtype;
declare iq_observation ingestion_quality.iq_recovery_observation_current%rowtype;
declare iq_source_id varchar;
declare iq_current_readiness jsonb;
declare iq_has_verified_failure boolean;
declare iq_all_recovering boolean;
declare iq_policy_drift boolean;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    select observation.source_id into iq_source_id
      from ingestion_quality.iq_recovery_observation_job job
      join ingestion_quality.iq_recovery_observation_current observation
        on observation.recovery_id=job.recovery_id
     where job.job_id=requested_job_id;
    if iq_source_id is null then return false; end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-eligibility:'||iq_source_id,0));
    select job.* into iq_job from ingestion_quality.iq_recovery_observation_job job
     where job.job_id=requested_job_id for update;
    if iq_job.job_id is null or iq_job.status<>'running'
       or iq_job.lease_generation<>requested_lease_generation
       or requested_now>=iq_job.lease_until then return false; end if;
    select observation.* into strict iq_observation
      from ingestion_quality.iq_recovery_observation_current observation
     where observation.recovery_id=iq_job.recovery_id for update;
    iq_current_readiness:=
      ingestion_quality.iq_build_quality_recovery_readiness_evidence(
        iq_observation.recovery_id,requested_now);
    select exists (select 1
        from ingestion_quality.iq_recovery_observation_fact fact
       where fact.recovery_id=iq_observation.recovery_id
         and fact.generation=iq_observation.generation
         and fact.fact_type='verified-quality-failure')
      into iq_has_verified_failure;
    select not exists (select 1
        from ingestion_quality.iq_quality_recovery_task_affected_rule affected
        join ingestion_quality.iq_quality_recovery_request_current request
          on request.task_id=affected.task_id
        left join ingestion_quality.iq_quality_eligibility_current eligibility
          on eligibility.rule_id=affected.rule_id
         and eligibility.rule_version=affected.rule_version
         and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
       where request.recovery_request_id=iq_observation.recovery_id
         and (eligibility.eligibility_id is null
           or eligibility.status<>'recovering')) into iq_all_recovering;
    iq_policy_drift:=not iq_all_recovering
      or iq_observation.policy_version<>'QRP-1.0.0'
      or trim(iq_observation.policy_digest)<>
        'sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366'
      or (iq_current_readiness is not null and (
           iq_current_readiness->>'memberSetDigest' is distinct from
             trim(iq_observation.member_set_digest)
        or iq_current_readiness->>'watermarksDigest' is distinct from
             trim(iq_observation.watermarks_digest)));
    if iq_observation.status<>'observing'
       or requested_decision->>'status' not in ('ready','relapsed','policy-drift')
       or (requested_decision->>'status'='policy-drift' and not iq_policy_drift)
       or (requested_decision->>'status'<>'policy-drift' and (
            not iq_all_recovering
         or iq_current_readiness is null
         or iq_current_readiness->>'memberSetDigest'<>
              trim(iq_observation.member_set_digest)
         or iq_current_readiness->>'watermarksDigest'<>
              trim(iq_observation.watermarks_digest)))
       or (requested_decision->>'status'='ready' and iq_has_verified_failure)
       or (requested_decision->>'status'='relapsed' and not iq_has_verified_failure) then
        return false;
    end if;
    insert into ingestion_quality.iq_recovery_observation_decision(
        decision_id,recovery_id,generation,aggregate_version,status,reason_code,
        consecutive_passed_batches,required_passed_batches,
        observed_duration_micros,required_duration_micros,final_watermark_utf8,
        policy_digest,member_set_digest,watermarks_digest,decision_digest,
        decided_at,trace_id,legal_hold)
    values (ingestion_quality.iq_quality_uuid_v7_derive(
          iq_job.job_id,'recovery-observation-decision:'||
            (iq_observation.aggregate_version+1)),
        iq_observation.recovery_id,
        iq_observation.generation,iq_observation.aggregate_version+1,
        requested_decision->>'status',requested_decision->>'reasonCode',
        (requested_decision->>'consecutivePassedBatches')::integer,
        (requested_decision->>'requiredPassedBatches')::integer,
        (requested_decision->>'observedDurationMicros')::bigint,
        (requested_decision->>'requiredDurationMicros')::bigint,
        case when requested_decision->>'finalWatermark' is null then null else
          convert_to(requested_decision->>'finalWatermark','UTF8') end,
        iq_observation.policy_digest,iq_observation.member_set_digest,
        iq_observation.watermarks_digest,requested_decision->>'decisionDigest',
        requested_now,requested_decision->>'traceId',iq_job.legal_hold);
    update ingestion_quality.iq_recovery_observation_current set
        status=requested_decision->>'status',
        aggregate_version=aggregate_version+1,updated_at=requested_now
     where recovery_id=iq_observation.recovery_id
       and aggregate_version=iq_observation.aggregate_version;
    update ingestion_quality.iq_recovery_observation_attempt set
        finished_at=requested_now,outcome='completed'
     where job_id=iq_job.job_id and lease_generation=requested_lease_generation;
    update ingestion_quality.iq_recovery_observation_job set
        status='completed',lease_owner_digest=null,lease_until=null,
        updated_at=requested_now
     where job_id=iq_job.job_id and lease_generation=requested_lease_generation;
    return found;
end
$$;

create function ingestion_quality.iq_release_recovery_observation_job(
    requested_job_id uuid,requested_lease_generation bigint,
    requested_outcome varchar,requested_next_attempt_at timestamptz,
    requested_now timestamptz)
returns boolean language plpgsql security definer set search_path=pg_catalog as $$
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_recovery_worker');
    if requested_outcome not in ('retry-scheduled','yielded','failed') then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_OBSERVATION_RELEASE_INVALID';
    end if;
    update ingestion_quality.iq_recovery_observation_job set status=case
          when requested_outcome='failed'
            or (requested_outcome='retry-scheduled' and attempt_count>=8)
            then 'failed' else 'scheduled' end,
        attempt_count=case when requested_outcome='yielded'
          then greatest(0,attempt_count-1) else attempt_count end,
        next_attempt_at=coalesce(requested_next_attempt_at,requested_now),
        lease_owner_digest=null,lease_until=null,
        last_error_code=case when requested_outcome='retry-scheduled'
          then 'DEPENDENCY_UNAVAILABLE' when requested_outcome='failed'
          then 'POLICY_DRIFT' else null end,updated_at=requested_now
     where job_id=requested_job_id and status='running'
       and lease_generation=requested_lease_generation
       and requested_now<lease_until;
    if not found then return false; end if;
    update ingestion_quality.iq_recovery_observation_attempt set
        finished_at=requested_now,outcome=requested_outcome,
        error_code=case when requested_outcome='retry-scheduled'
          then 'DEPENDENCY_UNAVAILABLE' when requested_outcome='failed'
          then 'POLICY_DRIFT' else null end
     where job_id=requested_job_id and lease_generation=requested_lease_generation;
    return true;
end
$$;

create function ingestion_quality.iq_quality_finalization_preview_digest(
    requested_recovery_id uuid)
returns character language sql stable security definer set search_path=pg_catalog as $$
select 'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
  ingestion_quality.iq_json_canonical(jsonb_build_object(
    'schemaVersion','QUALITY-RECOVERY-FINAL-PREVIEW-1.0.0',
    'recoveryId',observation.recovery_id,
    'generation',observation.generation,
    'observationDecisionDigest',trim(decision.decision_digest),
    'policyVersion',observation.policy_version,
    'policyDigest',trim(observation.policy_digest),
    'memberSetDigest',trim(observation.member_set_digest),
    'watermarksDigest',trim(observation.watermarks_digest),
    'finalObservationWatermark',convert_from(observation.watermark_utf8,'UTF8'),
    'affectedRules',coalesce((select jsonb_agg(jsonb_build_object(
      'ruleId',affected.rule_id,'ruleVersion',affected.rule_version,
      'eligibilityVersion',eligibility.aggregate_version)
      order by affected.rule_id,affected.rule_version)
      from ingestion_quality.iq_quality_recovery_request_current request
      join ingestion_quality.iq_quality_recovery_task_affected_rule affected
        on affected.task_id=request.task_id
      join ingestion_quality.iq_quality_eligibility_current eligibility
        on eligibility.rule_id=affected.rule_id
       and eligibility.rule_version=affected.rule_version
       and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
      where request.recovery_request_id=observation.recovery_id),'[]'::jsonb),
    'windows',coalesce((select jsonb_agg(jsonb_build_object(
      'windowId',historical.window_id,'ruleId',historical.rule_id,
      'ruleVersion',historical.rule_version,
      'latestActionableAt',historical.latest_actionable_at)
      order by historical.rule_id,historical.rule_version,historical.window_id)
      from ingestion_quality.iq_historical_window historical
      join ingestion_quality.iq_quality_recovery_request_current request
        on request.recovery_request_id=observation.recovery_id
      join ingestion_quality.iq_quality_recovery_task_affected_rule affected
        on affected.task_id=request.task_id and affected.rule_id=historical.rule_id
       and affected.rule_version=historical.rule_version),'[]'::jsonb))),
  'UTF8')),'hex')
from ingestion_quality.iq_recovery_observation_current observation
join lateral (select value.*
  from ingestion_quality.iq_recovery_observation_decision value
  where value.recovery_id=observation.recovery_id and value.status='ready'
  order by value.aggregate_version desc limit 1) decision on true
where observation.recovery_id=requested_recovery_id and observation.status='ready'
$$;

create function ingestion_quality.iq_load_recovery_observation_view(requested_task_id uuid)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare iq_value jsonb;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    select jsonb_build_object(
      'recoveryId',observation.recovery_id,'recoveryVersion',request.request_version,
      'taskId',task.task_id,
      'taskVersion',task.aggregate_version,'generation',observation.generation,
      'sourceClass',observation.source_class,'policyVersion',observation.policy_version,
      'policyDigest',trim(observation.policy_digest),'status',observation.status,
      'finalizationState',observation.finalization_state,
      'approvalId',observation.final_approval_id,
      'approvalVersion',observation.final_approval_version,
      'consecutivePassedBatches',observation.consecutive_passed_batches,
      'requiredPassedBatches',case observation.source_class
        when 'streaming' then 3 else 2 end,
      'observedDurationMicros',greatest(0,floor(extract(epoch from
        (observation.updated_at-observation.recovering_started_at))*1000000))::bigint,
      'requiredDurationMicros',case observation.source_class
        when 'streaming' then 3600000000::bigint else 86400000000::bigint end,
      'observationDuration',case observation.source_class
        when 'streaming' then 'PT60M' else 'P1D' end,
      'watermark',case when observation.watermark_utf8 is null then null
        else convert_from(observation.watermark_utf8,'UTF8') end,
      'recoveringStartedAt',observation.recovering_started_at,
      'lastObservedAt',observation.updated_at,
      'latestActionableAt',(select min(historical.latest_actionable_at)
        from ingestion_quality.iq_historical_window historical
        join ingestion_quality.iq_quality_recovery_task_affected_rule affected
          on affected.task_id=task.task_id and affected.rule_id=historical.rule_id
         and affected.rule_version=historical.rule_version),
      'failedMembers',coalesce((select jsonb_agg(failed.dependency_id
          order by failed.dependency_id) from (select distinct member.dependency_id
          from ingestion_quality.iq_quality_recovery_task_affected_rule affected
          join ingestion_quality.iq_quality_eligibility_current eligibility
            on eligibility.rule_id=affected.rule_id
           and eligibility.rule_version=affected.rule_version
           and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
          join ingestion_quality.iq_quality_eligibility_member_history member
            on member.eligibility_id=eligibility.eligibility_id
           and member.aggregate_version=eligibility.aggregate_version
         where affected.task_id=task.task_id and member.failed) failed),'[]'::jsonb),
      'failureReasonCode',(select decision.reason_code
        from ingestion_quality.iq_recovery_observation_decision decision
        where decision.recovery_id=observation.recovery_id
          and decision.status in ('relapsed','policy-drift','dependency-unavailable')
        order by decision.aggregate_version desc limit 1),
      'eligibilityStatus',case
        when observation.status='finalized' then 'eligible'
        when observation.status='relapsed' then 'fused' else 'recovering' end,
      'taskStatus',task.status,'taskClosedAt',task.closed_at,
      'ownerResultDigest',trim(task.owner_result_digest),
      'deliveryStatus',delivery.status,'deliveryAttempt',delivery.attempt,
      'deliveryNextAttemptAt',delivery.next_attempt_at,
      'eligibleForHandoffWindowCount',(select count(*)
        from ingestion_quality.iq_recovery_window_outcome outcome
        where outcome.recovery_id=observation.recovery_id
          and outcome.outcome='eligible-for-handoff'),
      'historyOnlyWindowCount',(select count(*)
        from ingestion_quality.iq_recovery_window_outcome outcome
        where outcome.recovery_id=observation.recovery_id
          and outcome.outcome='history-only'),
      'traceId',coalesce((select trim(decision.trace_id)
        from ingestion_quality.iq_recovery_observation_decision decision
        where decision.recovery_id=observation.recovery_id
        order by decision.aggregate_version desc limit 1),request.aggregate->>'traceId'))
      into iq_value
      from ingestion_quality.iq_quality_recovery_task_current task
      join ingestion_quality.iq_quality_recovery_request_current request
        on request.task_id=task.task_id
      join ingestion_quality.iq_recovery_observation_current observation
        on observation.recovery_id=request.recovery_request_id
      join ingestion_quality.iq_quality_task_delivery delivery
        on delivery.task_id=task.task_id and delivery.target='public-task-platform'
     where task.task_id=requested_task_id
     order by observation.updated_at desc,observation.recovery_id desc limit 1;
    if iq_value is null then return null; end if;
    return iq_value;
end
$$;

create function ingestion_quality.iq_load_quality_finalization_context(
    requested_recovery_id uuid)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_value jsonb;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    select jsonb_build_object(
      'recoveryId',request.recovery_request_id,
      'recoveryVersion',request.request_version,
      'taskId',task.task_id,'taskVersion',task.aggregate_version,
      'episodeId',episode.episode_id,'episodeVersion',episode.aggregate_version,
      'generation',episode.generation,'sourceId',request.source_id,
      'dependencyId',request.dependency_id,'taskStatus',task.status,
      'episodeActive',episode.active,'observationStatus',observation.status,
      'observationVersion',observation.aggregate_version,
      'observationDecisionDigest',trim(decision.decision_digest),
      'finalObservationWatermark',convert_from(observation.watermark_utf8,'UTF8'),
      'policyVersion',observation.policy_version,
      'policyDigest',trim(observation.policy_digest),
      'memberSetDigest',trim(observation.member_set_digest),
      'watermarksDigest',trim(observation.watermarks_digest),
      'finalPreviewDigest',coalesce(trim(observation.final_preview_digest),
        ingestion_quality.iq_quality_finalization_preview_digest(request.recovery_request_id)),
      'finalizationState',observation.finalization_state,
      'approvalId',observation.final_approval_id,
      'approvalVersion',observation.final_approval_version,
      'approvalReceiptDigest',trim(observation.final_approval_receipt_digest),
      'checkerSetDigest',trim(observation.final_checker_set_digest),
      'ownerBindingSetDigest',trim(observation.final_owner_binding_set_digest),
      'checkerPersonSetDigest',trim(observation.final_checker_person_set_digest),
      'authorizationContextDigest',coalesce(
        trim(observation.final_authorization_context_digest),
        request.aggregate->>'authorizationContextDigest'),
      'authenticationStateDigest',coalesce(
        trim(observation.final_authentication_state_digest),
        request.aggregate->>'authenticationStateDigest'),
      'authorizationGeneration',coalesce(
        observation.final_authorization_generation,request.authorization_generation),
      'makerPrincipalDigest',trim(observation.final_maker_principal_digest),
      'requestDigest',trim(observation.final_request_digest),
      'scopeDigest',request.aggregate->>'scopeDigest',
      'impactScopeDigest',request.aggregate->>'impactScopeDigest',
      'affectedRules',coalesce((select jsonb_agg(jsonb_build_object(
        'ruleId',affected.rule_id,'ruleVersion',affected.rule_version,
        'ruleVersionDigest','sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
          affected.rule_id||'@'||affected.rule_version,'UTF8')),'hex'))
        order by affected.rule_id,affected.rule_version)
        from ingestion_quality.iq_quality_recovery_task_affected_rule affected
        where affected.task_id=task.task_id),'[]'::jsonb),
      'traceId',coalesce(observation.final_trace_id,request.aggregate->>'traceId'))
      into iq_value
      from ingestion_quality.iq_quality_recovery_request_current request
      join ingestion_quality.iq_quality_recovery_task_current task
        on task.task_id=request.task_id
      join ingestion_quality.iq_quality_fuse_episode_current episode
        on episode.episode_id=request.episode_id
      join ingestion_quality.iq_recovery_observation_current observation
        on observation.recovery_id=request.recovery_request_id
      join lateral (select value.*
        from ingestion_quality.iq_recovery_observation_decision value
        where value.recovery_id=observation.recovery_id
          and value.status='ready'
        order by value.aggregate_version desc limit 1) decision on true
     where request.recovery_request_id=requested_recovery_id;
    if iq_value is null then
        return null;
    end if;
    return iq_value;
end
$$;

-- A caller of the ingestion-quality online API must not be able to manufacture
-- an approval/receipt binding.  This identity-owned, boolean-only capability
-- exposes no identity table and verifies the exact persisted D4 aggregate.
create function identity_access.ia_verify_quality_finalization_approval(
    requested_binding jsonb)
returns boolean language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    if not pg_catalog.pg_has_role(session_user,
            'scholarsense_ingestion_quality_online','USAGE')
       or pg_catalog.jsonb_typeof(requested_binding)<>'object' then
        raise exception using errcode='insufficient_privilege',
            message='IDENTITY_QUALITY_FINALIZATION_APPROVAL_INVALID';
    end if;
    if not exists (select 1
      from identity_access.ia_high_risk_approval_current approval
      left join identity_access.ia_high_risk_approval_receipt receipt
        on receipt.approval_id=approval.approval_id
       and receipt.approval_version=approval.approval_version
     where approval.approval_id=(requested_binding->>'approvalId')::uuid
       and approval.approval_version=(requested_binding->>'approvalVersion')::bigint
       and approval.status=requested_binding->>'approvalStatus'
       and trim(approval.request_digest)=requested_binding->>'requestDigest'
       and approval.action_type='quality-fuse.recover'
       and approval.object_type='RECOVERY_TASK'
       and trim(approval.object_ref_digest)=requested_binding->>'objectRefDigest'
       and approval.object_version=(requested_binding->>'objectVersion')::bigint
       and trim(approval.maker_principal_digest)=
            requested_binding->>'makerPrincipalDigest'
       and approval.authorization_generation=
            (requested_binding->>'authorizationGeneration')::bigint
       and approval.aggregate->>'requestDigest'=requested_binding->>'requestDigest'
       and approval.aggregate->>'actionType'='quality-fuse.recover'
       and approval.aggregate->>'makerPrincipalDigest'=
            requested_binding->>'makerPrincipalDigest'
       and approval.aggregate->>'authorizationContextDigest'=
            requested_binding->>'authorizationContextDigest'
       and approval.aggregate->>'authenticationStateDigest'=
            requested_binding->>'authenticationStateDigest'
       and approval.aggregate->>'objectType'='RECOVERY_TASK'
       and approval.aggregate->>'objectRefDigest'=requested_binding->>'objectRefDigest'
       and (approval.aggregate->>'objectVersion')::bigint=
            (requested_binding->>'objectVersion')::bigint
       and approval.aggregate->>'scopeDigest'=requested_binding->>'scopeDigest'
       and approval.aggregate->>'impactScopeDigest'=
            requested_binding->>'impactScopeDigest'
       and approval.aggregate->>'dataSensitivity'='highly-sensitive-deidentified'
       and approval.aggregate->>'currentState'='recovering'
       and approval.aggregate->>'targetState'='eligible'
       and approval.aggregate->>'reasonCode'='RECOVERY_FINALIZATION'
       and approval.aggregate->>'matrixVersion'='HRAM-1.0.0'
       and approval.aggregate->>'matrixDigest'=
            'sha256:96142286aa1da5633e77a8c435df37102744a61b573eae189ad44fe1e53251ec'
       and approval.aggregate->>'policyVersion'='HRAP-1.0.0'
       and approval.aggregate->>'policyDigest'=
            'sha256:1db5201136807c3bcf8fa62186414b7c79c062f595920012edd0001c77804900'
       and approval.aggregate->>'roleFieldPolicyVersion'='RFP-1.0.0'
       and approval.aggregate->>'roleFieldPolicyDigest'=
            'sha256:84191d8b844b31ef91fb051d34d6731b80981d6d193e42b0f8d19f73caa9ed25'
       and approval.aggregate->>'previewDigest'=requested_binding->>'finalPreviewDigest'
       and approval.aggregate->>'checkerSetDigest'=
            requested_binding->>'checkerSetDigest'
       and (approval.aggregate->>'authorizationGeneration')::bigint=
            (requested_binding->>'authorizationGeneration')::bigint
       and approval.aggregate->>'traceId'=requested_binding->>'traceId'
       and approval.aggregate->>'observationDecisionDigest'=
            requested_binding->>'observationDecisionDigest'
       and approval.aggregate->>'memberSetDigest'=requested_binding->>'memberSetDigest'
       and approval.aggregate->>'watermarksDigest'=requested_binding->>'watermarksDigest'
       and approval.aggregate->>'qualityRecoveryPolicyVersion'=
            requested_binding->>'policyVersion'
       and approval.aggregate->>'qualityRecoveryPolicyDigest'=
            requested_binding->>'policyDigest'
       and (approval.status not in ('pending','approved')
            or statement_timestamp()<approval.expires_at)
       and ((approval.status='pending'
             and requested_binding->>'approvalReceiptDigest' is null
             and receipt.approval_id is null)
         or (approval.status in ('approved','rejected','cancelled')
             and trim(receipt.receipt_digest)=
                  requested_binding->>'approvalReceiptDigest'
             and receipt.status=approval.status
             and receipt.receipt->>'approvalId'=approval.approval_id::text
             and (receipt.receipt->>'approvalVersion')::bigint=approval.approval_version
             and receipt.receipt->>'requestDigest'=trim(approval.request_digest)
             and receipt.receipt->>'status'=approval.status
             and receipt.receipt->>'receiptDigest'=trim(receipt.receipt_digest)
             and receipt.receipt->>'traceId'=approval.aggregate->>'traceId'))
     limit 1) then
        raise exception using errcode='serialization_failure',
            message='IDENTITY_QUALITY_FINALIZATION_APPROVAL_INVALID';
    end if;
    return true;
end
$$;

-- Execution trusts the identity-owned current lease and its approved receipt,
-- not lease fields echoed by the online ingestion caller.
create function identity_access.ia_verify_quality_finalization_lease(
    requested_binding jsonb,requested_now timestamptz)
returns boolean language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    if not pg_catalog.pg_has_role(session_user,
            'scholarsense_ingestion_quality_online','USAGE')
       or pg_catalog.jsonb_typeof(requested_binding)<>'object'
       or requested_now is null then
        raise exception using errcode='insufficient_privilege',
            message='IDENTITY_QUALITY_FINALIZATION_LEASE_INVALID';
    end if;
    if not exists (select 1
      from identity_access.ia_high_risk_execution_lease_current lease
      join identity_access.ia_high_risk_approval_current approval
        on approval.approval_id=lease.approval_id
      join identity_access.ia_high_risk_approval_receipt receipt
        on receipt.approval_id=approval.approval_id
       and receipt.approval_version=approval.approval_version
     where lease.lease_id=(requested_binding->>'leaseId')::uuid
       and lease.lease_version=(requested_binding->>'leaseVersion')::bigint
       and trim(lease.lease_digest)=requested_binding->>'leaseDigest'
       and lease.execution_jti=(requested_binding->>'executionJti')::uuid
       and lease.state='reserved'
       and lease.authorized_until=(requested_binding->>'authorizedUntil')::timestamptz
       and requested_now<lease.authorized_until
       and approval.approval_id=(requested_binding->>'approvalId')::uuid
       and approval.approval_version=(requested_binding->>'approvalVersion')::bigint
       and approval.status='approved'
       and trim(approval.request_digest)=requested_binding->>'requestDigest'
       and trim(receipt.receipt_digest)=requested_binding->>'approvalReceiptDigest'
       and receipt.status='approved'
       and receipt.receipt->>'requestDigest'=trim(approval.request_digest)
       and receipt.receipt->>'receiptDigest'=trim(receipt.receipt_digest)
       and lease.aggregate->>'leaseId'=lease.lease_id::text
       and (lease.aggregate->>'leaseVersion')::bigint=lease.lease_version
       and lease.aggregate->>'leaseDigest'=trim(lease.lease_digest)
       and lease.aggregate->>'executionJti'=lease.execution_jti::text
       and lease.aggregate->>'state'='reserved'
       and (lease.aggregate->>'authorizedUntil')::timestamptz=lease.authorized_until
       and lease.aggregate->>'audience'='ingestion-quality'
       and lease.aggregate->>'approvalId'=approval.approval_id::text
       and (lease.aggregate->>'approvalVersion')::bigint=approval.approval_version
       and lease.aggregate->>'approvalReceiptDigest'=trim(receipt.receipt_digest)
       and lease.aggregate->>'requestDigest'=trim(approval.request_digest)
       and lease.aggregate->>'actionType'='quality-fuse.recover'
       and lease.aggregate->>'objectType'='RECOVERY_TASK'
       and lease.aggregate->>'objectRefDigest'=requested_binding->>'objectRefDigest'
       and (lease.aggregate->>'objectVersion')::bigint=
            (requested_binding->>'objectVersion')::bigint
       and lease.aggregate->>'scopeDigest'=requested_binding->>'scopeDigest'
       and lease.aggregate->>'impactScopeDigest'=requested_binding->>'impactScopeDigest'
       and lease.aggregate->>'currentState'='recovering'
       and lease.aggregate->>'targetState'='eligible'
       and lease.aggregate->>'previewDigest'=requested_binding->>'finalPreviewDigest'
       and lease.aggregate->>'checkerSetDigest'=requested_binding->>'checkerSetDigest'
       and lease.aggregate->>'authorizationContextDigest'=
            requested_binding->>'authorizationContextDigest'
       and lease.aggregate->>'authenticationStateDigest'=
            requested_binding->>'authenticationStateDigest'
       and (lease.aggregate->>'authorizationGeneration')::bigint=
            (requested_binding->>'authorizationGeneration')::bigint
       and lease.aggregate->>'traceId'=requested_binding->>'authorityTraceId'
       and lease.aggregate->>'observationDecisionDigest'=
            requested_binding->>'observationDecisionDigest'
       and lease.aggregate->>'memberSetDigest'=requested_binding->>'memberSetDigest'
       and lease.aggregate->>'watermarksDigest'=requested_binding->>'watermarksDigest'
       and lease.aggregate->>'qualityRecoveryPolicyVersion'=
            requested_binding->>'policyVersion'
       and lease.aggregate->>'qualityRecoveryPolicyDigest'=
            requested_binding->>'policyDigest'
       and lease.aggregate->>'requestDigest'=approval.aggregate->>'requestDigest'
       and lease.aggregate->>'objectRefDigest'=approval.aggregate->>'objectRefDigest'
       and lease.aggregate->>'previewDigest'=approval.aggregate->>'previewDigest'
       and lease.aggregate->>'observationDecisionDigest'=
            approval.aggregate->>'observationDecisionDigest'
       and lease.aggregate->>'memberSetDigest'=approval.aggregate->>'memberSetDigest'
       and lease.aggregate->>'watermarksDigest'=approval.aggregate->>'watermarksDigest'
       and lease.aggregate->>'qualityRecoveryPolicyDigest'=
            approval.aggregate->>'qualityRecoveryPolicyDigest'
     limit 1) then
        raise exception using errcode='serialization_failure',
            message='IDENTITY_QUALITY_FINALIZATION_LEASE_INVALID';
    end if;
    return true;
end
$$;

create function ingestion_quality.iq_bind_quality_finalization_approval(
    requested_binding jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_task ingestion_quality.iq_quality_recovery_task_current%rowtype;
declare iq_observation ingestion_quality.iq_recovery_observation_current%rowtype;
declare iq_state varchar;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=(requested_binding->>'recoveryId')::uuid;
    if iq_request.recovery_request_id is null then
        raise exception using errcode='no_data_found',
            message='INGESTION_QUALITY_FINALIZATION_NOT_FOUND';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-eligibility:'||iq_request.source_id,0));
    select observation.* into strict iq_observation
      from ingestion_quality.iq_recovery_observation_current observation
     where observation.recovery_id=iq_request.recovery_request_id for update;
    select task.* into strict iq_task
      from ingestion_quality.iq_quality_recovery_task_current task
     where task.task_id=iq_request.task_id for share;
    iq_state:=case requested_binding->>'approvalStatus'
      when 'pending' then 'approval-pending'
      when 'approved' then 'approval-approved'
      when 'rejected' then 'approval-rejected'
      when 'cancelled' then 'cancelled'
      else null end;
    if iq_state is null or iq_observation.status<>'ready'
       or trim(iq_observation.policy_digest)<>requested_binding->>'policyDigest'
       or trim(iq_observation.member_set_digest)<>requested_binding->>'memberSetDigest'
       or trim(iq_observation.watermarks_digest)<>requested_binding->>'watermarksDigest'
       or ingestion_quality.iq_quality_finalization_preview_digest(
            iq_observation.recovery_id)<>requested_binding->>'finalPreviewDigest'
       or not exists (select 1
          from ingestion_quality.iq_recovery_observation_decision decision
         where decision.recovery_id=iq_observation.recovery_id
           and decision.status='ready'
           and trim(decision.decision_digest)=
               requested_binding->>'observationDecisionDigest') then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_FINALIZATION_BINDING_DRIFT';
    end if;
    if iq_observation.final_approval_id is not null
       and (iq_observation.final_approval_id<>(requested_binding->>'approvalId')::uuid
         or trim(iq_observation.final_request_digest)<>
            requested_binding->>'requestDigest')
       and not (iq_observation.finalization_state in ('approval-rejected','cancelled')
            and iq_state='approval-pending') then
        raise exception using errcode='unique_violation',
            message='INGESTION_QUALITY_FINALIZATION_APPROVAL_CONFLICT';
    end if;
    perform identity_access.ia_verify_quality_finalization_approval(
      requested_binding||jsonb_build_object(
        'objectRefDigest','sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
          iq_task.task_id::text,'UTF8')),'hex'),
        'objectVersion',iq_task.aggregate_version,
        'scopeDigest',iq_request.aggregate->>'scopeDigest',
        'impactScopeDigest',iq_request.aggregate->>'impactScopeDigest',
        'policyVersion',iq_observation.policy_version));
    update ingestion_quality.iq_recovery_observation_current set
      finalization_state=iq_state,
      final_request_digest=requested_binding->>'requestDigest',
      final_preview_digest=requested_binding->>'finalPreviewDigest',
      final_approval_id=(requested_binding->>'approvalId')::uuid,
      final_approval_version=(requested_binding->>'approvalVersion')::bigint,
      final_approval_receipt_digest=requested_binding->>'approvalReceiptDigest',
      final_checker_set_digest=requested_binding->>'checkerSetDigest',
      final_owner_binding_set_digest=requested_binding->>'ownerBindingSetDigest',
      final_checker_person_set_digest=requested_binding->>'checkerPersonSetDigest',
      final_authorization_context_digest=requested_binding->>'authorizationContextDigest',
      final_authentication_state_digest=requested_binding->>'authenticationStateDigest',
      final_authorization_generation=(requested_binding->>'authorizationGeneration')::bigint,
      final_maker_principal_digest=requested_binding->>'makerPrincipalDigest',
      final_trace_id=requested_binding->>'traceId',updated_at=statement_timestamp()
     where recovery_id=iq_observation.recovery_id;
    return ingestion_quality.iq_load_quality_finalization_context(
        iq_observation.recovery_id);
end
$$;

create function ingestion_quality.iq_find_quality_finalization_replay(
    requested_idempotency_digest character,
    requested_client_command_digest character,
    requested_recovery_id uuid,
    requested_final_observation_watermark text)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_existing ingestion_quality.iq_recovery_final_idempotency%rowtype;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    if requested_idempotency_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_client_command_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_final_observation_watermark is null then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_FINALIZATION_COMMAND_INVALID';
    end if;
    select item.* into iq_existing
      from ingestion_quality.iq_recovery_final_idempotency item
     where item.idempotency_key_digest=requested_idempotency_digest;
    if iq_existing.idempotency_key_digest is not null then
        if trim(iq_existing.client_command_digest)<>requested_client_command_digest
           or iq_existing.recovery_id<>requested_recovery_id
           or iq_existing.final_observation_watermark_utf8<>
                pg_catalog.convert_to(requested_final_observation_watermark,'UTF8') then
            raise exception using errcode='unique_violation',
                message='INGESTION_QUALITY_FINALIZATION_IDEMPOTENCY_CONFLICT';
        end if;
        return iq_existing.response;
    end if;
    select item.* into iq_existing
      from ingestion_quality.iq_recovery_final_idempotency item
     where item.recovery_id=requested_recovery_id
       and item.final_observation_watermark_utf8=
           pg_catalog.convert_to(requested_final_observation_watermark,'UTF8');
    return iq_existing.response;
end
$$;

create function ingestion_quality.iq_execute_quality_finalization(
    requested_idempotency_digest character,requested_command jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare iq_request ingestion_quality.iq_quality_recovery_request_current%rowtype;
declare iq_task ingestion_quality.iq_quality_recovery_task_current%rowtype;
declare iq_episode ingestion_quality.iq_quality_fuse_episode_current%rowtype;
declare iq_observation ingestion_quality.iq_recovery_observation_current%rowtype;
declare iq_decision ingestion_quality.iq_recovery_observation_decision%rowtype;
declare iq_existing ingestion_quality.iq_recovery_final_idempotency%rowtype;
declare iq_task_history ingestion_quality.iq_quality_recovery_task_history%rowtype;
declare iq_episode_history ingestion_quality.iq_quality_fuse_episode_history%rowtype;
declare iq_eligibility record;
declare iq_now timestamptz:=statement_timestamp();
declare iq_window_values jsonb;
declare iq_window_digest char(71);
declare iq_owner_digest char(71);
declare iq_response jsonb;
declare iq_payload jsonb;
declare iq_payload_utf8 bytea;
declare iq_event_id uuid;
declare iq_task_event_id uuid;
declare iq_confirmation_event_id uuid;
declare iq_root_event_id uuid:=(requested_command->>'rootEventId')::uuid;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_online');
    if requested_idempotency_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_command->>'clientCommandDigest' !~ '^sha256:[0-9a-f]{64}$'
       or requested_command->>'commandDigest' !~ '^sha256:[0-9a-f]{64}$'
       or requested_command->>'commandDigest'<>'sha256:'||encode(pg_catalog.sha256(
          pg_catalog.convert_to(ingestion_quality.iq_json_canonical(
            requested_command-'commandDigest'),'UTF8')),'hex')
       or requested_command->>'finalObservationWatermark' is null
       or requested_command->>'leaseState'<>'reserved'
       or requested_command->>'leaseIssuer'<>'identity-access'
       or requested_command->>'leaseAudience'<>'ingestion-quality'
       or requested_command->>'traceId' !~ '^(?!0{32}$)[0-9a-f]{32}$' then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_FINALIZATION_COMMAND_INVALID';
    end if;
    select request.* into iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=(requested_command->>'recoveryId')::uuid;
    if iq_request.recovery_request_id is null then
        raise exception using errcode='no_data_found',
            message='INGESTION_QUALITY_FINALIZATION_NOT_FOUND';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-eligibility:'||iq_request.source_id,0));
    select item.* into iq_existing
      from ingestion_quality.iq_recovery_final_idempotency item
     where item.idempotency_key_digest=requested_idempotency_digest for update;
    if iq_existing.idempotency_key_digest is not null then
        if trim(iq_existing.client_command_digest)<>
                requested_command->>'clientCommandDigest'
           or trim(iq_existing.command_digest)<>requested_command->>'commandDigest' then
            raise exception using errcode='unique_violation',
                message='INGESTION_QUALITY_FINALIZATION_IDEMPOTENCY_CONFLICT';
        end if;
        return iq_existing.response;
    end if;
    select item.* into iq_existing
      from ingestion_quality.iq_recovery_final_idempotency item
     where item.recovery_id=iq_request.recovery_request_id
       and item.final_observation_watermark_utf8=
           pg_catalog.convert_to(requested_command->>'finalObservationWatermark','UTF8')
     for update;
    if iq_existing.idempotency_key_digest is not null then return iq_existing.response; end if;

    select request.* into strict iq_request
      from ingestion_quality.iq_quality_recovery_request_current request
     where request.recovery_request_id=iq_request.recovery_request_id for update;
    perform 1 from ingestion_quality.iq_quality_recovery_task_affected_rule affected
      join ingestion_quality.iq_quality_eligibility_current eligibility
        on eligibility.rule_id=affected.rule_id
       and eligibility.rule_version=affected.rule_version
       and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
     where affected.task_id=iq_request.task_id
     order by eligibility.eligibility_id for update of eligibility;
    select episode.* into strict iq_episode
      from ingestion_quality.iq_quality_fuse_episode_current episode
     where episode.episode_id=iq_request.episode_id for update;
    select task.* into strict iq_task
      from ingestion_quality.iq_quality_recovery_task_current task
     where task.task_id=iq_request.task_id for update;
    select observation.* into strict iq_observation
      from ingestion_quality.iq_recovery_observation_current observation
     where observation.recovery_id=iq_request.recovery_request_id for update;
    select decision.* into strict iq_decision
      from ingestion_quality.iq_recovery_observation_decision decision
     where decision.recovery_id=iq_request.recovery_request_id and decision.status='ready'
     order by decision.aggregate_version desc limit 1 for update;

    if iq_request.request_version<>(requested_command->>'expectedRecoveryVersion')::bigint
       or iq_task.aggregate_version<>(requested_command->>'expectedTaskVersion')::bigint
       or iq_episode.aggregate_version<>(requested_command->>'expectedEpisodeVersion')::bigint
       or iq_task.status<>'open' or not iq_episode.active
       or iq_task.episode_generation<>iq_episode.generation
       or iq_observation.generation<>iq_episode.generation
       or iq_observation.status<>'ready'
       or iq_observation.finalization_state<>'approval-approved'
       or iq_observation.final_approval_id<>(requested_command->>'approvalId')::uuid
       or iq_observation.final_approval_version<>(requested_command->>'approvalVersion')::bigint
       or trim(iq_observation.final_approval_receipt_digest)<>
            requested_command->>'approvalReceiptDigest'
       or trim(iq_observation.final_request_digest)<>requested_command->>'requestDigest'
       or trim(iq_observation.final_preview_digest)<>requested_command->>'finalPreviewDigest'
       or trim(iq_decision.decision_digest)<>
            requested_command->>'observationDecisionDigest'
       or trim(iq_observation.policy_digest)<>requested_command->>'policyDigest'
       or trim(iq_observation.member_set_digest)<>requested_command->>'memberSetDigest'
       or trim(iq_observation.watermarks_digest)<>requested_command->>'watermarksDigest'
       or convert_from(iq_observation.watermark_utf8,'UTF8')<>
            requested_command->>'finalObservationWatermark'
       or iq_observation.final_authorization_generation<>
            (requested_command->>'authorizationGeneration')::bigint
       or (requested_command->>'authorizedUntil')::timestamptz<=iq_now
       or exists (select 1
          from ingestion_quality.iq_quality_recovery_task_affected_rule affected
          left join ingestion_quality.iq_quality_eligibility_current eligibility
            on eligibility.rule_id=affected.rule_id
           and eligibility.rule_version=affected.rule_version
           and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
         where affected.task_id=iq_task.task_id
           and (eligibility.eligibility_id is null or eligibility.status<>'recovering'))
       or exists (select 1 from ingestion_quality.iq_recovery_final_execution_jti execution
         where execution.execution_jti=(requested_command->>'executionJti')::uuid) then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_FINALIZATION_BINDING_DRIFT';
    end if;

    perform identity_access.ia_verify_quality_finalization_lease(
      requested_command||jsonb_build_object(
        'objectRefDigest','sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
          iq_task.task_id::text,'UTF8')),'hex'),
        'objectVersion',iq_task.aggregate_version,
        'scopeDigest',iq_request.aggregate->>'scopeDigest',
        'impactScopeDigest',iq_request.aggregate->>'impactScopeDigest',
        'checkerSetDigest',trim(iq_observation.final_checker_set_digest),
        'authorizationContextDigest',
          trim(iq_observation.final_authorization_context_digest),
        'authenticationStateDigest',
          trim(iq_observation.final_authentication_state_digest),
        'authorityTraceId',iq_observation.final_trace_id,
        'policyVersion',iq_observation.policy_version),iq_now);

    select coalesce(jsonb_agg(jsonb_build_object(
      'windowId',historical.window_id,'ruleId',historical.rule_id,
      'ruleVersion',historical.rule_version,'latestActionableAt',historical.latest_actionable_at,
      'outcome',case when iq_now<=historical.latest_actionable_at
        then 'eligible-for-handoff' else 'history-only' end)
      order by historical.rule_id,historical.rule_version,historical.window_id),'[]'::jsonb)
      into iq_window_values
      from ingestion_quality.iq_historical_window historical
      join ingestion_quality.iq_quality_recovery_task_affected_rule affected
        on affected.task_id=iq_task.task_id and affected.rule_id=historical.rule_id
       and affected.rule_version=historical.rule_version;
    iq_window_digest:='sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
      ingestion_quality.iq_json_canonical(iq_window_values),'UTF8')),'hex');
    iq_owner_digest:='sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
      ingestion_quality.iq_json_canonical(jsonb_build_object(
        'schemaVersion','RECOVERY-FINALIZATION-OWNER-RESULT-1.0.0',
        'recoveryId',iq_request.recovery_request_id,'generation',iq_episode.generation,
        'taskId',iq_task.task_id,'taskVersion',iq_task.aggregate_version+1,
        'recoveryCompletedAt',iq_now,'windowOutcomesDigest',trim(iq_window_digest),
        'finalObservationWatermark',requested_command->>'finalObservationWatermark')),
      'UTF8')),'hex');

    for iq_eligibility in select eligibility.*
      from ingestion_quality.iq_quality_recovery_task_affected_rule affected
      join ingestion_quality.iq_quality_eligibility_current eligibility
        on eligibility.rule_id=affected.rule_id
       and eligibility.rule_version=affected.rule_version
       and eligibility.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
     where affected.task_id=iq_task.task_id order by eligibility.eligibility_id
    loop
      insert into ingestion_quality.iq_quality_eligibility_history(
        eligibility_id,rule_id,rule_version,registry_version,registry_digest,
        catalog_version,catalog_digest,rule_catalog_version,rule_catalog_digest,
        aggregate_version,status,reason_code,composition_operator,threshold,
        effective_at,occurred_at,trace_id,producer,retention_due_at,legal_hold)
      select history.eligibility_id,history.rule_id,history.rule_version,
        history.registry_version,history.registry_digest,history.catalog_version,
        history.catalog_digest,history.rule_catalog_version,history.rule_catalog_digest,
        history.aggregate_version+1,'eligible','RECOVERY_FINALIZED',
        history.composition_operator,history.threshold,iq_now,iq_now,
        requested_command->>'traceId','ingestion-quality',iq_now+interval '2 years',
        history.legal_hold
      from ingestion_quality.iq_quality_eligibility_history history
      where history.eligibility_id=iq_eligibility.eligibility_id
        and history.aggregate_version=iq_eligibility.aggregate_version;
      insert into ingestion_quality.iq_quality_eligibility_member_history
      select member.eligibility_id,member.aggregate_version+1,member.member_ordinal,
        member.source_id,member.source_version,member.dependency_id,
        member.dependency_version,member.requirement,'eligible',
        member.version_continuous,member.source_watermark_utf8,
        member.dependency_watermark_utf8,member.snapshot_id,
        member.snapshot_immutable_hash,member.qmdp_version,member.qmdp_digest,
        member.qshm_version,member.qshm_digest,member.lineage_id,false
      from ingestion_quality.iq_quality_eligibility_member_history member
      where member.eligibility_id=iq_eligibility.eligibility_id
        and member.aggregate_version=iq_eligibility.aggregate_version;
      update ingestion_quality.iq_quality_eligibility_current set
        aggregate_version=aggregate_version+1,status='eligible',
        reason_code='RECOVERY_FINALIZED',effective_at=iq_now,occurred_at=iq_now
       where eligibility_id=iq_eligibility.eligibility_id
         and aggregate_version=iq_eligibility.aggregate_version;
      if not found then raise exception using errcode='serialization_failure',
        message='INGESTION_QUALITY_FINALIZATION_BINDING_DRIFT'; end if;
      iq_event_id:=ingestion_quality.iq_quality_uuid_v7_derive(iq_root_event_id,
        'final-eligibility:'||iq_eligibility.eligibility_id::text);
      iq_payload:=jsonb_build_object('specversion','1.0','id',iq_event_id,
        'source','https://scholarsense.suda.edu.cn/ingestion-quality',
        'type','scholarsense.ingestion-quality.quality-eligibility.changed.v1',
        'subject','quality-eligibility/'||iq_eligibility.eligibility_id::text,
        'time',iq_now,'datacontenttype','application/json','data',jsonb_build_object(
          'eventId',iq_event_id,'aggregateId',iq_eligibility.eligibility_id,
          'aggregateType','QualityEligibility',
          'aggregateVersion',iq_eligibility.aggregate_version+1,
          'routeSequence',iq_eligibility.aggregate_version+1,
          'eventType','quality-eligibility.eligible',
          'contractVersion','PIC-1.2.0','schemaVersion','QUALITY-ELIGIBILITY-EVENT-1.0.0',
          'occurredAt',iq_now,'traceId',requested_command->>'traceId',
          'producer','ingestion-quality','runtimeEvidenceClaim','none',
          'recoveryId',iq_request.recovery_request_id,'generation',iq_episode.generation,
          'priorState','recovering','targetState','eligible','taskId',iq_task.task_id,
          'taskStatus','closed','ownerResultDigest',trim(iq_owner_digest)));
      iq_payload_utf8:=pg_catalog.convert_to(iq_payload::text,'UTF8');
      insert into ingestion_quality.iq_quality_eligibility_outbox values (
        iq_event_id,iq_eligibility.eligibility_id,iq_eligibility.aggregate_version+1,
        'scholarsense.ingestion-quality.quality-eligibility.changed.v1',
        'QUALITY-ELIGIBILITY-EVENT-1.0.0',iq_payload_utf8,
        encode(pg_catalog.sha256(iq_payload_utf8),'hex'),'pending',0,iq_now,
        null,null,null,iq_now);
      insert into ingestion_quality.iq_quality_eligibility_audit(
        audit_id,eligibility_id,aggregate_version,action,outcome,trace_id,
        occurred_at,payload_digest,expires_at)
      values (ingestion_quality.iq_quality_uuid_v7_derive(iq_root_event_id,
          'final-audit:'||iq_eligibility.eligibility_id::text),
        iq_eligibility.eligibility_id,iq_eligibility.aggregate_version+1,
        'quality-recovery-finalized','accepted',requested_command->>'traceId',iq_now,
        trim(iq_owner_digest),iq_now+interval '3 years');
    end loop;

    select history.* into strict iq_episode_history
      from ingestion_quality.iq_quality_fuse_episode_history history
     where history.episode_id=iq_episode.episode_id
       and history.aggregate_version=iq_episode.aggregate_version;
    insert into ingestion_quality.iq_quality_fuse_episode_history(
      episode_id,generation,aggregate_version,source_id,dependency_id,
      work_item_key_version,status,trigger_event_id,trigger_batch_id,
      trigger_snapshot_id,trigger_snapshot_hash,trigger_reason_code,
      dependency_version,evidence,transitions,watermark_utf8,occurred_at,
      trace_id,legal_hold)
    values (iq_episode.episode_id,iq_episode.generation,iq_episode.aggregate_version+1,
      iq_episode.source_id,iq_episode.dependency_id,iq_episode.work_item_key_version,
      'closed',iq_episode_history.trigger_event_id,iq_episode_history.trigger_batch_id,
      iq_episode_history.trigger_snapshot_id,iq_episode_history.trigger_snapshot_hash,
      iq_episode_history.trigger_reason_code,iq_episode_history.dependency_version,
      iq_episode_history.evidence,iq_episode_history.transitions||jsonb_build_array(
        jsonb_build_object('from','recovering','to','eligible','occurredAt',iq_now,
          'reasonCode','RECOVERY_FINALIZED')),
      iq_observation.watermark_utf8,iq_now,requested_command->>'traceId',
      iq_episode_history.legal_hold);
    update ingestion_quality.iq_quality_fuse_episode_current set
      aggregate_version=aggregate_version+1,active=false,updated_at=iq_now
     where episode_id=iq_episode.episode_id
       and aggregate_version=iq_episode.aggregate_version;

    select history.* into strict iq_task_history
      from ingestion_quality.iq_quality_recovery_task_history history
     where history.task_id=iq_task.task_id
       and history.aggregate_version=iq_task.aggregate_version;
    insert into ingestion_quality.iq_quality_recovery_task_history(
      task_id,aggregate_version,episode_id,episode_generation,work_item_key,
      work_item_key_version,source_id,dependency_id,status,priority,due_at,owner_ref,
      trigger,current_evidence,watermark_utf8,occurred_at,legal_hold,
      closed_at,closure_reason,owner_result_digest)
    values (iq_task.task_id,iq_task.aggregate_version+1,iq_task.episode_id,
      iq_task.episode_generation,iq_task.work_item_key,iq_task.work_item_key_version,
      iq_task.source_id,iq_task.dependency_id,'closed',iq_task.priority,iq_task.due_at,
      iq_task.owner_ref,iq_task_history.trigger,iq_task_history.current_evidence,
      iq_observation.watermark_utf8,iq_now,iq_task_history.legal_hold,iq_now,
      'RECOVERY_FINALIZED',iq_owner_digest);
    update ingestion_quality.iq_quality_recovery_task_current set
      aggregate_version=aggregate_version+1,status='closed',occurred_at=iq_now,
      updated_at=iq_now,closed_at=iq_now,closure_reason='RECOVERY_FINALIZED',
      owner_result_digest=iq_owner_digest
     where task_id=iq_task.task_id and aggregate_version=iq_task.aggregate_version;

    insert into ingestion_quality.iq_recovery_window_outcome(
      recovery_id,generation,rule_id,rule_version,window_id,watermark_utf8,
      latest_actionable_at,recovery_completed_at,outcome,decision_reason,
      decision_digest,occurred_at,legal_hold)
    select iq_request.recovery_request_id,iq_episode.generation,historical.rule_id,
      historical.rule_version,historical.window_id,iq_observation.watermark_utf8,
      historical.latest_actionable_at,iq_now,
      case when iq_now<=historical.latest_actionable_at
        then 'eligible-for-handoff' else 'history-only' end,
      case when iq_now<=historical.latest_actionable_at
        then 'COMPLETED_AT_OR_BEFORE_LATEST_ACTIONABLE'
        else 'COMPLETED_AFTER_LATEST_ACTIONABLE' end,
      'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
        iq_request.recovery_request_id::text||'\n'||historical.window_id||'\n'||iq_now::text,
        'UTF8')),'hex'),iq_now,iq_request.legal_hold
    from ingestion_quality.iq_historical_window historical
    join ingestion_quality.iq_quality_recovery_task_affected_rule affected
      on affected.task_id=iq_task.task_id and affected.rule_id=historical.rule_id
     and affected.rule_version=historical.rule_version;

    insert into ingestion_quality.iq_recovery_observation_decision(
      decision_id,recovery_id,generation,aggregate_version,status,reason_code,
      consecutive_passed_batches,required_passed_batches,observed_duration_micros,
      required_duration_micros,final_watermark_utf8,policy_digest,member_set_digest,
      watermarks_digest,decision_digest,decided_at,trace_id,legal_hold)
    values (ingestion_quality.iq_quality_uuid_v7_derive(iq_root_event_id,'final-decision'),
      iq_request.recovery_request_id,iq_episode.generation,
      iq_observation.aggregate_version+1,'finalized','RECOVERY_FINALIZED',
      iq_decision.consecutive_passed_batches,iq_decision.required_passed_batches,
      iq_decision.observed_duration_micros,iq_decision.required_duration_micros,
      iq_observation.watermark_utf8,iq_observation.policy_digest,
      iq_observation.member_set_digest,iq_observation.watermarks_digest,
      iq_owner_digest,iq_now,requested_command->>'traceId',iq_request.legal_hold);
    update ingestion_quality.iq_recovery_observation_current set
      status='finalized',aggregate_version=aggregate_version+1,
      finalization_state='executed',updated_at=iq_now
     where recovery_id=iq_request.recovery_request_id
       and aggregate_version=iq_observation.aggregate_version;

    iq_task_event_id:=ingestion_quality.iq_quality_uuid_v7_derive(
      iq_root_event_id,'final-task-close');
    iq_payload:=jsonb_build_object('specversion','1.0','id',iq_task_event_id,
      'source','scholarsense/ingestion-quality',
      'type','scholarsense.ingestion-quality.quality-recovery-task.changed.v1',
      'subject','quality-recovery-task/'||iq_task.task_id::text,'time',iq_now,
      'datacontenttype','application/json','data',jsonb_build_object(
        'eventId',iq_task_event_id,'taskId',iq_task.task_id,
        'episodeId',iq_episode.episode_id,'workItemKey',iq_task.work_item_key,
        'operation','close','routeSequence',iq_task.aggregate_version+1,
        'sourceAggregateVersion',iq_task.aggregate_version+1,
        'eventType','quality-recovery-task.closed','aggregateId',iq_task.task_id,
        'aggregateVersion',iq_task.aggregate_version+1,
        'recoveryId',iq_request.recovery_request_id,'generation',iq_episode.generation,
        'priorState','recovering','targetState','eligible','taskStatus','closed',
        'ownerResultDigest',trim(iq_owner_digest),'occurredAt',iq_now,
        'traceId',requested_command->>'traceId',
        'contractVersion','PIC-1.2.0','task',jsonb_build_object(
          'taskId',iq_task.task_id,'workItemKey',iq_task.work_item_key,
          'episodeId',iq_episode.episode_id,'episodeGeneration',iq_episode.generation,
          'sourceId',iq_task.source_id,'dependencyId',iq_task.dependency_id,
          'ownerRef',iq_task.owner_ref,'priority',iq_task.priority,'dueAt',iq_task.due_at,
          'trigger',iq_task_history.trigger,
          'affectedRules',(select coalesce(jsonb_agg(
            affected.rule_id||'@'||affected.rule_version
            order by affected.rule_id,affected.rule_version),'[]'::jsonb)
            from ingestion_quality.iq_quality_recovery_task_affected_rule affected
            where affected.task_id=iq_task.task_id),
          'currentEvidence',jsonb_build_object(
            'qualityGateVersion',iq_task_history.current_evidence->>'qualityGateVersion',
            'qmdpVersion',iq_task_history.current_evidence->>'qmdpVersion',
            'qshmVersion',iq_task_history.current_evidence->>'qshmVersion'),
          'status','closed','closedAt',iq_now,'closureReason','RECOVERY_FINALIZED',
          'ownerResultDigest',trim(iq_owner_digest),
          'watermark',requested_command->>'finalObservationWatermark',
          'aggregateVersion',iq_task.aggregate_version+1,
          'createdAt',iq_task.occurred_at,'updatedAt',iq_now),
        'runtimeEvidenceClaim','none'));
    iq_payload_utf8:=pg_catalog.convert_to(iq_payload::text,'UTF8');
    insert into ingestion_quality.iq_quality_task_outbox(
      event_id,task_id,route_sequence,event_type,schema_version,payload_utf8,
      payload_digest,status,attempts,available_at,claimed_at,lease_generation,
      last_error_code,created_at,legal_hold) values (
      iq_task_event_id,iq_task.task_id,iq_task.aggregate_version+1,
      'scholarsense.ingestion-quality.quality-recovery-task.changed.v1',
      'QUALITY-RECOVERY-TASK-EVENT-1.0.0',iq_payload_utf8,
      encode(pg_catalog.sha256(iq_payload_utf8),'hex'),'pending',0,iq_now,null,0,null,
      iq_now,iq_request.legal_hold);
    insert into ingestion_quality.iq_quality_task_delivery(
      task_id,target,route_sequence,status,attempt,receipt_id,last_error_code,
      next_attempt_at,lease_owner,lease_generation,lease_expires_at,updated_at)
    values (iq_task.task_id,'public-task-platform',iq_task.aggregate_version+1,
      'pending',0,null,null,null,null,0,null,iq_now)
    on conflict (task_id,target) do update set
      route_sequence=excluded.route_sequence,status='pending',attempt=0,
      receipt_id=null,last_error_code=null,next_attempt_at=null,lease_owner=null,
      lease_generation=ingestion_quality.iq_quality_task_delivery.lease_generation+1,
      lease_expires_at=null,updated_at=excluded.updated_at
    where ingestion_quality.iq_quality_task_delivery.route_sequence+1=
      excluded.route_sequence;
    if not found then raise exception using errcode='serialization_failure',
      message='INGESTION_QUALITY_TASK_ROUTE_SEQUENCE_CONFLICT'; end if;

    iq_confirmation_event_id:=ingestion_quality.iq_quality_uuid_v7_derive(
      iq_root_event_id,'final-confirmation');
    insert into ingestion_quality.iq_recovery_final_execution_jti(
      execution_jti,lease_id,recovery_id,owner_commit_id,owner_result_digest,
      outbox_event_id,consumed_at,authorized_until,legal_hold)
    values ((requested_command->>'executionJti')::uuid,
      (requested_command->>'leaseId')::uuid,iq_request.recovery_request_id,
      'iq-finalization:'||(requested_command->>'executionJti'),iq_owner_digest,
      iq_confirmation_event_id,iq_now,
      (requested_command->>'authorizedUntil')::timestamptz,iq_request.legal_hold);
    iq_payload:=jsonb_build_object('recoveryRequestId',iq_request.recovery_request_id,
      'leaseId',requested_command->>'leaseId',
      'leaseVersion',(requested_command->>'leaseVersion')::bigint,
      'leaseDigest',requested_command->>'leaseDigest',
      'executionJti',requested_command->>'executionJti',
      'requestDigest',requested_command->>'requestDigest',
      'ownerCommitId','iq-finalization:'||(requested_command->>'executionJti'),
      'ownerCommittedAt',iq_now,'ownerResultDigest',trim(iq_owner_digest),
      'outboxEventId',iq_confirmation_event_id,'traceId',requested_command->>'traceId');
    insert into ingestion_quality.iq_quality_recovery_outbox(
      event_id,recovery_request_id,event_type,payload,payload_digest,status,attempts,
      available_at,claimed_until,last_error_code,created_at,legal_hold)
    values (iq_confirmation_event_id,iq_request.recovery_request_id,
      'scholarsense.ingestion-quality.quality-finalization-lease.confirmation.v1',
      iq_payload,'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(
        ingestion_quality.iq_json_canonical(iq_payload),'UTF8')),'hex'),
      'pending',0,iq_now,null,null,iq_now,iq_request.legal_hold);

    perform ingestion_quality.iq_assert_quality_finalization_terminal(
      iq_request.recovery_request_id,iq_episode.generation);
    iq_response:=jsonb_build_object('recoveryId',iq_request.recovery_request_id,
      'generation',iq_episode.generation,'eligibilityStatus','eligible',
      'episodeStatus','closed','taskId',iq_task.task_id,'taskStatus','closed',
      'taskVersion',iq_task.aggregate_version+1,'recoveryCompletedAt',iq_now,
      'windowOutcomesDigest',trim(iq_window_digest),
      'ownerResultDigest',trim(iq_owner_digest),'deliveryStatus','pending',
      'executionJti',requested_command->>'executionJti',
      'confirmationOutboxEventId',iq_confirmation_event_id,
      'traceId',requested_command->>'traceId');
    insert into ingestion_quality.iq_recovery_final_idempotency(
      idempotency_key_digest,client_command_digest,command_digest,recovery_id,
      final_observation_watermark_utf8,response,created_at,expires_at,legal_hold)
    values (requested_idempotency_digest,requested_command->>'clientCommandDigest',
      requested_command->>'commandDigest',
      iq_request.recovery_request_id,
      pg_catalog.convert_to(requested_command->>'finalObservationWatermark','UTF8'),
      iq_response,iq_now,iq_now+interval '90 days',iq_request.legal_hold);
    return iq_response;
end
$$;

-- Invoked by the existing retention scheduler successor. Active observation/jobs are kept.
create function ingestion_quality.iq_cleanup_quality_finalization_expired(
    requested_now timestamptz)
returns bigint language plpgsql security definer set search_path=pg_catalog as $$
declare iq_count bigint:=0; declare iq_deleted bigint;
begin
    perform ingestion_quality.iq_require_recovery_workload(
        'scholarsense_ingestion_quality_retention_executor');
    delete from ingestion_quality.iq_recovery_final_idempotency item
     where not item.legal_hold and item.expires_at<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_final_execution_jti item
     where not item.legal_hold and item.expires_at<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_observation_attempt attempt
     where not attempt.legal_hold and attempt.retention_due_at<=requested_now
       and not exists (select 1 from ingestion_quality.iq_recovery_observation_job job
         where job.job_id=attempt.job_id and job.status in ('scheduled','running'));
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_window_outcome outcome
     where not outcome.legal_hold and outcome.retention_due_at<=requested_now;
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_observation_decision decision
     where not decision.legal_hold and decision.retention_due_at<=requested_now
       and not exists (select 1
         from ingestion_quality.iq_recovery_observation_current current_decision
        where current_decision.recovery_id=decision.recovery_id
          and current_decision.status='ready');
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    delete from ingestion_quality.iq_recovery_observation_fact fact
     where not fact.legal_hold and fact.retention_due_at<=requested_now
       and not exists (select 1 from ingestion_quality.iq_recovery_observation_current current_fact
         where current_fact.recovery_id=fact.recovery_id
           and current_fact.status in ('observing','ready'));
    get diagnostics iq_deleted=row_count; iq_count:=iq_count+iq_deleted;
    return iq_count;
end
$$;

-- Cross-table terminal validation is called from the final owner transaction in this
-- migration. Any raw partial terminal state remains rejected.
create function ingestion_quality.iq_assert_quality_finalization_terminal(
    requested_recovery_id uuid,requested_generation bigint)
returns void language plpgsql security definer set search_path=pg_catalog as $$
declare iq_terminal_count integer;
declare iq_affected_count integer;
declare iq_eligible_count integer;
begin
    select count(*) filter (where task.status='closed'),
           count(affected.rule_id),
           count(eligibility.eligibility_id) filter (where eligibility.status='eligible')
      into iq_terminal_count,iq_affected_count,iq_eligible_count
      from ingestion_quality.iq_quality_recovery_request_current request
      join ingestion_quality.iq_quality_recovery_task_current task
        on task.task_id=request.task_id
      join ingestion_quality.iq_quality_fuse_episode_current episode
        on episode.episode_id=request.episode_id
      join ingestion_quality.iq_recovery_observation_current observation
        on observation.recovery_id=request.recovery_request_id
      join ingestion_quality.iq_quality_recovery_task_affected_rule affected
        on affected.task_id=task.task_id
      left join ingestion_quality.iq_quality_eligibility_current eligibility
        on eligibility.rule_id=affected.rule_id
       and eligibility.rule_version=affected.rule_version
     where request.recovery_request_id=requested_recovery_id
       and episode.generation=requested_generation;
    if iq_affected_count=0 then return; end if;
    if exists (select 1
      from ingestion_quality.iq_quality_recovery_request_current request
      join ingestion_quality.iq_quality_recovery_task_current task
        on task.task_id=request.task_id
      join ingestion_quality.iq_quality_fuse_episode_current episode
        on episode.episode_id=request.episode_id
      join ingestion_quality.iq_recovery_observation_current observation
        on observation.recovery_id=request.recovery_request_id
     where request.recovery_request_id=requested_recovery_id
       and episode.generation=requested_generation
       and not (
         (task.status='closed' and not episode.active
          and observation.status='finalized'
          and observation.finalization_state='executed'
          and (iq_eligible_count=iq_affected_count or exists (
            select 1
              from ingestion_quality.iq_quality_fuse_episode_current successor
             where successor.source_id=episode.source_id
               and successor.dependency_id=episode.dependency_id
               and successor.generation>episode.generation
               and successor.active)))
         or
         (task.status='open' and episode.active
          and observation.status<>'finalized'
          and observation.finalization_state<>'executed'
          and iq_eligible_count=0))) then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_TERMINAL_STATE_PARTIAL';
    end if;
end
$$;

-- Deferred cross-table guards make the same invariant apply to privileged raw DML.
-- Each trigger observes the final transaction state, so the owner function can update
-- the four aggregates in its stable order without exposing an intermediate terminal.
create function ingestion_quality.iq_guard_quality_finalization_terminal()
returns trigger language plpgsql security definer set search_path=pg_catalog as $$
declare iq_recovery record;
declare iq_new jsonb:=case when tg_op='DELETE' then '{}'::jsonb else to_jsonb(new) end;
declare iq_old jsonb:=case when tg_op='INSERT' then '{}'::jsonb else to_jsonb(old) end;
begin
    for iq_recovery in
      select distinct request.recovery_request_id,episode.generation
        from ingestion_quality.iq_quality_recovery_request_current request
        join ingestion_quality.iq_quality_fuse_episode_current episode
          on episode.episode_id=request.episode_id
        join ingestion_quality.iq_quality_recovery_task_affected_rule affected
          on affected.task_id=request.task_id
       where request.task_id=coalesce(
                (iq_new->>'task_id')::uuid,(iq_old->>'task_id')::uuid)
          or request.episode_id=coalesce(
                (iq_new->>'episode_id')::uuid,(iq_old->>'episode_id')::uuid)
          or request.recovery_request_id=coalesce(
                (iq_new->>'recovery_id')::uuid,(iq_old->>'recovery_id')::uuid)
          or (affected.rule_id=coalesce(iq_new->>'rule_id',iq_old->>'rule_id')
              and affected.rule_version=coalesce(
                iq_new->>'rule_version',iq_old->>'rule_version'))
    loop
      perform ingestion_quality.iq_assert_quality_finalization_terminal(
        iq_recovery.recovery_request_id,iq_recovery.generation);
    end loop;
    return null;
end
$$;

create constraint trigger iq_quality_recovery_task_terminal_v21_ct
after insert or update or delete on ingestion_quality.iq_quality_recovery_task_current
deferrable initially deferred for each row
execute function ingestion_quality.iq_guard_quality_finalization_terminal();
create constraint trigger iq_quality_fuse_episode_terminal_v21_ct
after insert or update or delete on ingestion_quality.iq_quality_fuse_episode_current
deferrable initially deferred for each row
execute function ingestion_quality.iq_guard_quality_finalization_terminal();
create constraint trigger iq_recovery_observation_terminal_v21_ct
after insert or update or delete on ingestion_quality.iq_recovery_observation_current
deferrable initially deferred for each row
execute function ingestion_quality.iq_guard_quality_finalization_terminal();
create constraint trigger iq_quality_eligibility_terminal_v21_ct
after insert or update or delete on ingestion_quality.iq_quality_eligibility_current
deferrable initially deferred for each row
execute function ingestion_quality.iq_guard_quality_finalization_terminal();

-- Additive successor of the V20 compensation relay. Both transition phases share
-- the same durable claim/release path; delivery marks the matching JTI confirmed.
create or replace function ingestion_quality.iq_claim_quality_recovery_confirmations(
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
         where outbox.event_type in (
           'scholarsense.ingestion-quality.recovery-lease.confirmation.v1',
           'scholarsense.ingestion-quality.quality-finalization-lease.confirmation.v1')
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
         where outbox.event_type in (
           'scholarsense.ingestion-quality.recovery-lease.confirmation.v1',
           'scholarsense.ingestion-quality.quality-finalization-lease.confirmation.v1')
           and outbox.status='pending' and outbox.available_at<=requested_now
           and (outbox.claimed_until is null or outbox.claimed_until<=requested_now)
         order by outbox.available_at,outbox.event_id
         for update skip locked limit requested_limit), claimed as (
        update ingestion_quality.iq_quality_recovery_outbox outbox set
            attempts=outbox.attempts+1,
            claimed_until=requested_now+interval '60 seconds',last_error_code=null
          from candidate where outbox.event_id=candidate.event_id
        returning outbox.event_id,outbox.payload,outbox.payload_digest,outbox.attempts)
      select claimed.payload||jsonb_build_object(
        'payloadDigest',trim(claimed.payload_digest),'deliveryAttempt',claimed.attempts)
        from claimed order by claimed.event_id;
end
$$;

create or replace function ingestion_quality.iq_mark_quality_recovery_confirmation_delivered(
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
       or iq_current.event_type not in (
          'scholarsense.ingestion-quality.recovery-lease.confirmation.v1',
          'scholarsense.ingestion-quality.quality-finalization-lease.confirmation.v1')
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
    update ingestion_quality.iq_recovery_final_execution_jti set
        confirmed_at=coalesce(confirmed_at,requested_now)
     where outbox_event_id=requested_event_id;
    return true;
end
$$;

alter table ingestion_quality.iq_recovery_observation_fact
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_observation_current
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_observation_decision
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_observation_job
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_observation_attempt
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_final_idempotency
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_final_execution_jti
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_recovery_window_outcome
    owner to scholarsense_ingestion_quality_batch_owner;

revoke all on function identity_access.ia_verify_quality_finalization_approval(jsonb)
from public;
revoke all on function identity_access.ia_verify_quality_finalization_lease(jsonb,timestamptz)
from public;
alter function identity_access.ia_verify_quality_finalization_approval(jsonb)
owner to scholarsense_identity_online;
alter function identity_access.ia_verify_quality_finalization_lease(jsonb,timestamptz)
owner to scholarsense_identity_online;
grant usage on schema identity_access to scholarsense_ingestion_quality_batch_owner;
grant execute on function identity_access.ia_verify_quality_finalization_approval(jsonb)
to scholarsense_ingestion_quality_batch_owner;
grant execute on function identity_access.ia_verify_quality_finalization_lease(jsonb,timestamptz)
to scholarsense_ingestion_quality_batch_owner;

revoke all privileges on table
    ingestion_quality.iq_recovery_observation_fact,
    ingestion_quality.iq_recovery_observation_current,
    ingestion_quality.iq_recovery_observation_decision,
    ingestion_quality.iq_recovery_observation_job,
    ingestion_quality.iq_recovery_observation_attempt,
    ingestion_quality.iq_recovery_final_idempotency,
    ingestion_quality.iq_recovery_final_execution_jti,
    ingestion_quality.iq_recovery_window_outcome
from public,scholarsense_ingestion_quality_online,
    scholarsense_ingestion_quality_recovery_worker,
    scholarsense_ingestion_quality_eligibility_consumer,
    scholarsense_ingestion_quality_retention_executor;

do $migration$
declare iq_function regprocedure;
begin
  for iq_function in select procedure.oid::regprocedure
    from pg_catalog.pg_proc procedure
   where procedure.pronamespace='ingestion_quality'::regnamespace
     and (procedure.proname like 'iq_%recovery_observation%'
       or procedure.proname like 'iq_%quality_finalization%')
  loop
    execute format('revoke all on function %s from public',iq_function);
    execute format('alter function %s owner to scholarsense_ingestion_quality_batch_owner',
        iq_function);
  end loop;
end
$migration$;

alter function ingestion_quality.iq_load_quality_eligibility_processing_state_v2(
    uuid,varchar) owner to scholarsense_ingestion_quality_batch_owner;
alter function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    uuid,varchar,bigint,character,jsonb)
owner to scholarsense_ingestion_quality_batch_owner;
alter function ingestion_quality.iq_assert_quality_finalization_terminal(uuid,bigint)
owner to scholarsense_ingestion_quality_batch_owner;
alter function ingestion_quality.iq_guard_quality_recovery_task_reopen()
owner to scholarsense_ingestion_quality_batch_owner;
alter function ingestion_quality.iq_execute_quality_recovery(character,jsonb)
owner to scholarsense_ingestion_quality_batch_owner;
alter function ingestion_quality.iq_execute_quality_recovery_v20(character,jsonb)
owner to scholarsense_ingestion_quality_batch_owner;
alter function ingestion_quality.iq_find_quality_recovery_task_ids_v2(
    varchar,varchar,timestamptz,uuid,integer)
owner to scholarsense_ingestion_quality_batch_owner;
alter function ingestion_quality.iq_find_quality_recovery_task_page_v2(uuid[])
owner to scholarsense_ingestion_quality_batch_owner;
revoke all on function ingestion_quality.iq_load_quality_eligibility_processing_state_v2(
    uuid,varchar) from public;
revoke all on function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    uuid,varchar,bigint,character,jsonb) from public;
revoke all on function ingestion_quality.iq_assert_quality_finalization_terminal(uuid,bigint)
from public;
revoke all on function ingestion_quality.iq_guard_quality_recovery_task_reopen()
from public;
revoke all on function ingestion_quality.iq_execute_quality_recovery(character,jsonb)
from public;
revoke all on function ingestion_quality.iq_find_quality_recovery_task_ids_v2(
    varchar,varchar,timestamptz,uuid,integer) from public;
revoke all on function ingestion_quality.iq_find_quality_recovery_task_page_v2(uuid[])
from public;

grant execute on function ingestion_quality.iq_load_quality_eligibility_processing_state_v2(
    uuid,varchar) to scholarsense_ingestion_quality_eligibility_consumer;
grant execute on function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    uuid,varchar,bigint,character,jsonb)
to scholarsense_ingestion_quality_eligibility_consumer;
grant execute on function ingestion_quality.iq_start_recovery_observation(jsonb)
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_load_recovery_observation_view(uuid)
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_load_quality_finalization_context(uuid)
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_bind_quality_finalization_approval(jsonb)
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_find_quality_finalization_replay(
    character,character,uuid,text)
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_execute_quality_finalization(character,jsonb)
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_execute_quality_recovery(character,jsonb)
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_find_quality_recovery_task_ids_v2(
    varchar,varchar,timestamptz,uuid,integer)
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_find_quality_recovery_task_page_v2(uuid[])
to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_find_claimable_recovery_observation_jobs(
    integer,timestamptz) to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_claim_recovery_observation_job(
    uuid,character,timestamptz,integer)
to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_is_recovery_observation_lease_current(
    uuid,bigint,timestamptz) to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_finalize_recovery_observation_job(
    uuid,bigint,jsonb,timestamptz) to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_release_recovery_observation_job(
    uuid,bigint,varchar,timestamptz,timestamptz)
to scholarsense_ingestion_quality_recovery_worker;
grant execute on function ingestion_quality.iq_cleanup_quality_finalization_expired(
    timestamptz) to scholarsense_ingestion_quality_retention_executor;
