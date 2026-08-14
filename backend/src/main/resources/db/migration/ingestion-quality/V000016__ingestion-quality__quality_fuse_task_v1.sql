do $$ begin
    create role scholarsense_ingestion_quality_task_relay nologin;
exception when duplicate_object then null;
end $$;
alter role scholarsense_ingestion_quality_task_relay
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
grant usage on schema ingestion_quality to scholarsense_ingestion_quality_task_relay;
revoke create on schema ingestion_quality from scholarsense_ingestion_quality_task_relay;

-- Append-only quality fuse episode facts. The active pointer is owner-local and independent
-- from RuleVersion governance/runtime and public delivery state.
create table ingestion_quality.iq_quality_fuse_episode_history (
    episode_id uuid not null,
    generation bigint not null check (generation between 1 and 9007199254740991),
    aggregate_version bigint not null check (aggregate_version between 1 and 9007199254740991),
    source_id varchar(64) not null check (source_id ~ '^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$'),
    dependency_id varchar(64) not null check (
        dependency_id ~ '^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$'),
    work_item_key_version varchar(32) not null check (
        work_item_key_version ~ '^k[1-9][0-9]*$'),
    status varchar(16) not null check (status in ('active','closed')),
    trigger_event_id uuid not null,
    trigger_batch_id uuid not null,
    trigger_snapshot_id uuid not null,
    trigger_snapshot_hash char(71) not null check (
        trigger_snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
    trigger_reason_code varchar(128) not null check (
        trigger_reason_code in ('REQUIRED_MEMBER_FUSED','THRESHOLD_UNSATISFIED',
            'RECOVERY_RELAPSED')),
    dependency_version bigint not null check (
        dependency_version between 1 and 9007199254740991),
    evidence jsonb not null check (jsonb_typeof(evidence)='object'),
    transitions jsonb not null check (jsonb_typeof(transitions)='array'),
    watermark_utf8 bytea not null check (
        ingestion_quality.iq_utf8_scalar_count(watermark_utf8) between 1 and 512),
    occurred_at timestamptz not null,
    trace_id char(32) not null check (trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'),
    retention_due_at timestamptz generated always as (
        ((occurred_at at time zone 'UTC') + interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    primary key (episode_id,aggregate_version),
    unique (source_id,dependency_id,generation,aggregate_version),
    check (substring(episode_id::text,15,1)='7'
        and substring(episode_id::text,20,1) in ('8','9','a','b'))
);
create index iq_quality_fuse_episode_history_scope_idx
    on ingestion_quality.iq_quality_fuse_episode_history(
        source_id,dependency_id,generation,aggregate_version desc);
create index iq_quality_fuse_episode_history_retention_idx
    on ingestion_quality.iq_quality_fuse_episode_history(
        legal_hold,retention_due_at,episode_id,aggregate_version);

create table ingestion_quality.iq_quality_fuse_episode_current (
    episode_id uuid primary key,
    source_id varchar(64) not null,
    dependency_id varchar(64) not null,
    work_item_key_version varchar(32) not null check (
        work_item_key_version ~ '^k[1-9][0-9]*$'),
    generation bigint not null check (generation between 1 and 9007199254740991),
    aggregate_version bigint not null check (aggregate_version between 1 and 9007199254740991),
    active boolean not null,
    updated_at timestamptz not null,
    unique (source_id,dependency_id,generation),
    foreign key (episode_id,aggregate_version) references
        ingestion_quality.iq_quality_fuse_episode_history(episode_id,aggregate_version)
);
create unique index iq_quality_fuse_episode_one_active_idx
    on ingestion_quality.iq_quality_fuse_episode_current(source_id,dependency_id)
    where active;

create table ingestion_quality.iq_quality_recovery_task_history (
    task_id uuid not null,
    aggregate_version bigint not null check (aggregate_version between 1 and 9007199254740991),
    episode_id uuid not null,
    episode_generation bigint not null check (
        episode_generation between 1 and 9007199254740991),
    work_item_key varchar(256) not null,
    work_item_key_version varchar(32) not null check (
        work_item_key_version ~ '^k[1-9][0-9]*$'),
    source_id varchar(64) not null,
    dependency_id varchar(64) not null,
    status varchar(16) not null check (status='open'),
    priority varchar(8) not null check (priority in ('P0','P1','P2')),
    due_at timestamptz not null,
    owner_ref varchar(256) not null,
    trigger jsonb not null check (jsonb_typeof(trigger)='object'),
    current_evidence jsonb not null check (jsonb_typeof(current_evidence)='object'),
    watermark_utf8 bytea not null check (
        ingestion_quality.iq_utf8_scalar_count(watermark_utf8) between 1 and 512),
    occurred_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((occurred_at at time zone 'UTC') + interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    primary key (task_id,aggregate_version),
    unique (episode_id,aggregate_version),
    check (substring(task_id::text,15,1)='7'
        and substring(task_id::text,20,1) in ('8','9','a','b'))
);
create index iq_quality_recovery_task_history_owner_idx
    on ingestion_quality.iq_quality_recovery_task_history(
        source_id,occurred_at desc,task_id desc,aggregate_version desc);
create index iq_quality_recovery_task_history_retention_idx
    on ingestion_quality.iq_quality_recovery_task_history(
        legal_hold,retention_due_at,task_id,aggregate_version);

create table ingestion_quality.iq_quality_recovery_task_current (
    task_id uuid primary key,
    episode_id uuid not null unique,
    episode_generation bigint not null,
    work_item_key varchar(256) not null unique,
    work_item_key_version varchar(32) not null check (
        work_item_key_version ~ '^k[1-9][0-9]*$'),
    source_id varchar(64) not null,
    dependency_id varchar(64) not null,
    aggregate_version bigint not null,
    status varchar(16) not null check (status='open'),
    priority varchar(8) not null,
    due_at timestamptz not null,
    owner_ref varchar(256) not null,
    occurred_at timestamptz not null,
    updated_at timestamptz not null,
    foreign key (task_id,aggregate_version) references
        ingestion_quality.iq_quality_recovery_task_history(task_id,aggregate_version),
    foreign key (episode_id) references
        ingestion_quality.iq_quality_fuse_episode_current(episode_id)
);
create index iq_quality_recovery_task_current_page_idx
    on ingestion_quality.iq_quality_recovery_task_current(
        source_id,occurred_at desc,task_id desc);

create table ingestion_quality.iq_quality_recovery_task_affected_rule (
    task_id uuid not null references
        ingestion_quality.iq_quality_recovery_task_current(task_id) on delete cascade,
    rule_id varchar(64) not null,
    rule_version varchar(64) not null,
    primary key (task_id,rule_id,rule_version)
);
create index iq_quality_recovery_task_affected_rule_cover_idx
    on ingestion_quality.iq_quality_recovery_task_affected_rule(
        task_id,rule_id,rule_version);

-- Delivery is a transport sidecar. It has no foreign key or write path to eligibility state.
create table ingestion_quality.iq_quality_task_delivery (
    task_id uuid not null references
        ingestion_quality.iq_quality_recovery_task_current(task_id),
    target varchar(128) not null,
    route_sequence bigint not null check (route_sequence between 1 and 9007199254740991),
    status varchar(16) not null check (status in ('pending','retrying','confirmed','failed')),
    attempt bigint not null check (attempt between 0 and 9007199254740991),
    receipt_id varchar(256),
    last_error_code varchar(128),
    next_attempt_at timestamptz,
    lease_owner varchar(128),
    lease_generation bigint not null default 0 check (
        lease_generation between 0 and 9007199254740991),
    lease_expires_at timestamptz,
    updated_at timestamptz not null,
    primary key (task_id,target),
    check ((status='confirmed')=(receipt_id is not null)),
    check ((status='retrying')=(next_attempt_at is not null)),
    check ((lease_owner is null)=(lease_expires_at is null))
);
create index iq_quality_task_delivery_due_idx
    on ingestion_quality.iq_quality_task_delivery(
        status,next_attempt_at,task_id,target);

create table ingestion_quality.iq_quality_task_delivery_history (
    task_id uuid not null,
    target varchar(128) not null,
    route_sequence bigint not null,
    attempt bigint not null,
    status varchar(16) not null,
    receipt_id varchar(256),
    error_code varchar(128),
    occurred_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((occurred_at at time zone 'UTC') + interval '90 days') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    primary key (task_id,target,route_sequence,attempt,status)
);
create index iq_quality_task_delivery_history_retention_idx
    on ingestion_quality.iq_quality_task_delivery_history(
        legal_hold,retention_due_at,task_id,route_sequence);

create table ingestion_quality.iq_quality_task_outbox (
    event_id uuid primary key,
    task_id uuid not null references
        ingestion_quality.iq_quality_recovery_task_current(task_id),
    route_sequence bigint not null check (route_sequence between 1 and 9007199254740991),
    event_type varchar(256) not null check (
        event_type='scholarsense.ingestion-quality.quality-recovery-task.changed.v1'),
    schema_version varchar(64) not null check (
        schema_version='QUALITY-RECOVERY-TASK-EVENT-1.0.0'),
    payload_utf8 bytea not null check (octet_length(payload_utf8)<=65536),
    payload_digest char(64) not null check (payload_digest ~ '^[0-9a-f]{64}$'),
    status varchar(16) not null check (status in ('pending','claimed','delivered','failed')),
    attempts bigint not null check (attempts between 0 and 9007199254740991),
    available_at timestamptz not null,
    claimed_at timestamptz,
    lease_generation bigint not null default 0 check (
        lease_generation between 0 and 9007199254740991),
    last_error_code varchar(128),
    created_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((created_at at time zone 'UTC') + interval '90 days') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    unique (task_id,route_sequence),
    check ((status='claimed')=(claimed_at is not null))
);
create index iq_quality_task_outbox_due_idx
    on ingestion_quality.iq_quality_task_outbox(
        status,available_at,event_id);
create index iq_quality_task_outbox_retention_idx
    on ingestion_quality.iq_quality_task_outbox(
        legal_hold,retention_due_at,task_id,route_sequence);

create table ingestion_quality.iq_quality_fuse_idempotency (
    fuse_business_key varchar(512) primary key,
    event_id uuid not null unique,
    command_body_digest char(71) not null check (
        command_body_digest ~ '^sha256:[0-9a-f]{64}$'),
    episode_id uuid not null,
    recovery_task_id uuid not null,
    work_item_key varchar(256) not null,
    response_plan jsonb not null check (jsonb_typeof(response_plan)='object'),
    created_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    check (expires_at>created_at)
);
create index iq_quality_fuse_idempotency_retention_idx
    on ingestion_quality.iq_quality_fuse_idempotency(
        legal_hold,expires_at,episode_id);

create table ingestion_quality.iq_quality_fuse_audit (
    audit_id uuid primary key,
    episode_id uuid not null,
    task_id uuid not null,
    action varchar(64) not null check (action in ('quality-fuse-started','quality-fuse-updated')),
    outcome varchar(16) not null check (outcome='accepted'),
    service_ref varchar(256) not null,
    policy_version varchar(128) not null,
    authorization_generation bigint not null check (
        authorization_generation between 1 and 9007199254740991),
    trace_id char(32) not null,
    payload_digest char(71) not null,
    occurred_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((occurred_at at time zone 'UTC') + interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false
);

create table ingestion_quality.iq_quality_fuse_rejection_audit (
    audit_id uuid primary key,
    service_ref varchar(256) not null,
    action varchar(64) not null check (action='quality-fuse.apply'),
    result varchar(32) not null check (result in ('denied','dependency-unavailable')),
    policy_version varchar(128) not null check (
        policy_version='QUALITY-FUSE-WORKLOAD-AUTHORIZATION-1.0.0'),
    object_version bigint not null check (
        object_version between 1 and 9007199254740991),
    trace_id char(32) not null check (trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'),
    occurred_at timestamptz not null,
    retention_due_at timestamptz generated always as (
        ((occurred_at at time zone 'UTC') + interval '2 years') at time zone 'UTC') stored,
    legal_hold boolean not null default false,
    check (substring(audit_id::text,15,1)='7'
        and substring(audit_id::text,20,1) in ('8','9','a','b'))
);

-- A task read is durable evidence, but never exposes the public-platform receipt,
-- work-item key, relay payload or lease state back through the owner-local query API.
create table ingestion_quality.iq_quality_recovery_task_read_audit (
    audit_id uuid primary key,
    task_id uuid not null,
    aggregate_version bigint not null check (
        aggregate_version between 1 and 9007199254740991),
    action varchar(64) not null check (action in (
        'quality-recovery-task-list-read','quality-recovery-task-detail-read')),
    outcome varchar(16) not null check (outcome='accepted'),
    actor_search_token varchar(96) not null check (
        actor_search_token ~ '^ast_v1_k[1-9][0-9]*_[0-9a-f]{64}$'),
    source_ip_search_token varchar(96) not null check (
        source_ip_search_token ~ '^ipt_v1_k[1-9][0-9]*_[0-9a-f]{64}$'),
    trace_id char(32) not null check (trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'),
    payload_digest char(71) not null check (
        payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    occurred_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    foreign key (task_id,aggregate_version) references
        ingestion_quality.iq_quality_recovery_task_history(task_id,aggregate_version),
    check (substring(audit_id::text,15,1)='7'
        and substring(audit_id::text,20,1) in ('8','9','a','b')),
    check (expires_at=((occurred_at at time zone 'UTC') + interval '2 years')
        at time zone 'UTC')
);
create index iq_quality_recovery_task_read_audit_scope_idx
    on ingestion_quality.iq_quality_recovery_task_read_audit(
        task_id,aggregate_version,occurred_at desc);
create index iq_quality_recovery_task_read_audit_retention_idx
    on ingestion_quality.iq_quality_recovery_task_read_audit(
        legal_hold,expires_at,task_id,aggregate_version);

create function ingestion_quality.iq_guard_quality_fuse_history()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if tg_op='UPDATE' then
        raise exception using errcode='object_not_in_prerequisite_state',
            message='INGESTION_QUALITY_FUSE_HISTORY_IMMUTABLE';
    end if;
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_retention_executor');
    return old;
end
$$;
create trigger iq_quality_fuse_episode_history_guard
before update or delete on ingestion_quality.iq_quality_fuse_episode_history
for each row execute function ingestion_quality.iq_guard_quality_fuse_history();
create trigger iq_quality_recovery_task_history_guard
before update or delete on ingestion_quality.iq_quality_recovery_task_history
for each row execute function ingestion_quality.iq_guard_quality_fuse_history();
create trigger iq_quality_task_delivery_history_guard
before update or delete on ingestion_quality.iq_quality_task_delivery_history
for each row execute function ingestion_quality.iq_guard_quality_fuse_history();
create trigger iq_quality_recovery_task_read_audit_guard
before update or delete on ingestion_quality.iq_quality_recovery_task_read_audit
for each row execute function ingestion_quality.iq_guard_quality_fuse_history();
create trigger iq_quality_fuse_rejection_audit_guard
before update or delete on ingestion_quality.iq_quality_fuse_rejection_audit
for each row execute function ingestion_quality.iq_guard_quality_fuse_history();
create trigger iq_quality_fuse_audit_guard
before update or delete on ingestion_quality.iq_quality_fuse_audit
for each row execute function ingestion_quality.iq_guard_quality_fuse_history();

-- V000015's INSERT guard ran before ON CONFLICT resolution and rejected every legitimate
-- successor aggregate. Preserve first-version and +1 CAS semantics while allowing the insert
-- phase of an existing current-row upsert to reach its UPDATE phase.
create or replace function ingestion_quality.iq_guard_quality_eligibility_current()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if tg_op='INSERT' and new.aggregate_version<>1
       and not exists (
         select 1 from ingestion_quality.iq_quality_eligibility_current current_fact
          where current_fact.rule_id=new.rule_id
            and current_fact.rule_version=new.rule_version
            and current_fact.registry_version=new.registry_version
            and current_fact.eligibility_id=new.eligibility_id
            and current_fact.aggregate_version+1=new.aggregate_version) then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_ELIGIBILITY_VERSION_CONFLICT';
    elsif tg_op='UPDATE' and (
        new.eligibility_id<>old.eligibility_id
        or new.rule_id<>old.rule_id
        or new.rule_version<>old.rule_version
        or new.registry_version<>old.registry_version
        or new.aggregate_version<>old.aggregate_version+1) then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_ELIGIBILITY_VERSION_CONFLICT';
    end if;
    return new;
end
$$;

-- Extend the closed workload set only for the dedicated relay identity.
create or replace function ingestion_quality.iq_require_exclusive_workload(requested_role name)
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_direct_membership_valid boolean;
    iq_reachable_memberships integer;
begin
    select count(*)=1
           and coalesce(bool_and(granted_role.rolname=requested_role::text),false)
           and coalesce(bool_and(membership.inherit_option),false)
           and not coalesce(bool_or(membership.set_option),false)
           and not coalesce(bool_or(membership.admin_option),false)
      into iq_direct_membership_valid
      from pg_catalog.pg_auth_members membership
      join pg_catalog.pg_roles member_role on member_role.oid=membership.member
      join pg_catalog.pg_roles granted_role on granted_role.oid=membership.roleid
     where member_role.rolname=session_user;
    with recursive reachable(roleid) as (
      select membership.roleid from pg_catalog.pg_auth_members membership
      join pg_catalog.pg_roles member_role on member_role.oid=membership.member
      where member_role.rolname=session_user
      union
      select membership.roleid from pg_catalog.pg_auth_members membership
      join reachable on membership.member=reachable.roleid)
    select count(*) into iq_reachable_memberships from reachable;
    if requested_role::text not in (
         'scholarsense_ingestion_quality_online',
         'scholarsense_ingestion_quality_quality_worker',
         'scholarsense_ingestion_quality_relay',
         'scholarsense_ingestion_quality_retention_executor',
         'scholarsense_ingestion_quality_consumer_registry_authority',
         'scholarsense_ingestion_quality_eligibility_consumer',
         'scholarsense_ingestion_quality_task_relay')
       or not iq_direct_membership_valid or iq_reachable_memberships<>1
       or not pg_catalog.pg_has_role(session_user,requested_role,'USAGE')
       or pg_catalog.pg_has_role(session_user,
            'scholarsense_ingestion_quality_batch_owner','MEMBER')
       or pg_catalog.pg_has_role(session_user,
            'scholarsense_ingestion_quality_batch_owner','USAGE')
       or pg_catalog.pg_has_role(session_user,
            'scholarsense_ingestion_quality_batch_owner','SET') then
        raise exception using errcode='insufficient_privilege',
            message='INGESTION_QUALITY_WORKLOAD_ROLE_MISMATCH';
    end if;
end
$$;

create function ingestion_quality.iq_find_quality_snapshot_evidence_v2(
    requested_batch_id uuid,
    requested_snapshot_id uuid,
    requested_immutable_hash character)
returns table(
    snapshot_id uuid, batch_id uuid, source_id varchar, overall_result varchar,
    observation_start_at timestamptz, observation_end_at timestamptz,
    cutoff_at timestamptz, watermark_utf8 bytea, manifest_digest character,
    source_schema_version varchar, source_schema_digest character,
    qmdp_version varchar, qmdp_digest character, quality_gate_version varchar,
    quality_gate_digest character, qshm_version varchar, qshm_digest character,
    lineage_id uuid, effective_at timestamptz, immutable_hash character,
    formula_evidence jsonb)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_eligibility_consumer');
    return query
    select snapshot.snapshot_id,snapshot.batch_id,snapshot.source_id,
           snapshot.overall_result,snapshot.observation_start_at,
           snapshot.observation_end_at,snapshot.cutoff_at,snapshot.watermark_utf8,
           snapshot.manifest_digest,snapshot.source_schema_version,
           snapshot.source_schema_digest,snapshot.qmdp_version,snapshot.qmdp_digest,
           snapshot.quality_gate_version,snapshot.quality_gate_digest,
           snapshot.hash_profile_version,snapshot.hash_profile_digest,
           snapshot.lineage_id,snapshot.effective_at,snapshot.immutable_hash,
           coalesce((select jsonb_agg(jsonb_build_object(
             'metricId',metric.metric_id,'formulaId',metric.formula_id,
             'formulaVersion',metric.formula_version,'result',metric.result,
             'applicable',metric.applicable,'numerator',metric.numerator,
             'denominator',metric.denominator,'valueBasisPoints',metric.value_basis_points,
             'unit',metric.unit,'operator',metric.operator,
             'thresholdNumerator',metric.threshold_numerator,
             'thresholdDenominator',metric.threshold_denominator,
             'boundary',metric.boundary,'comparisonResult',case
               when metric.result='passed' then true
               when metric.result='failed' then false else null end)
             order by metric.metric_ordinal)
             from ingestion_quality.iq_quality_snapshot_metric metric
             where metric.snapshot_id=snapshot.snapshot_id),'[]'::jsonb)
      from ingestion_quality.iq_quality_snapshot snapshot
     where snapshot.batch_id=requested_batch_id
       and snapshot.snapshot_id=requested_snapshot_id
       and snapshot.immutable_hash=requested_immutable_hash;
end
$$;

create function ingestion_quality.iq_load_quality_eligibility_processing_state_v2(
    requested_event_id uuid,requested_source_id varchar)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_base jsonb;
    iq_dependency_id varchar;
    iq_rule record;
    iq_current_inbox jsonb;
    iq_response_plan jsonb;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_eligibility_consumer');
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('quality-eligibility:'||requested_source_id,0));
    select distinct member.dependency_id into strict iq_dependency_id
      from ingestion_quality.iq_rule_dependency_member member
     where member.source_id=requested_source_id;
    for iq_rule in
        select distinct member.rule_id,member.rule_version
          from ingestion_quality.iq_rule_dependency_member member
         where member.source_id=requested_source_id
         order by member.rule_id,member.rule_version
    loop
        perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
            'quality-eligibility-rule:'||iq_rule.rule_id||'@'||iq_rule.rule_version,0));
    end loop;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-fuse-episode:'||requested_source_id||'@'||iq_dependency_id,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-recovery-task:'||requested_source_id||'@'||iq_dependency_id,0));

    iq_base:=ingestion_quality.iq_load_quality_eligibility_processing_state(
        requested_event_id,requested_source_id);
    iq_current_inbox:=iq_base->'currentInbox';
    select idempotency.response_plan into iq_response_plan
      from ingestion_quality.iq_quality_fuse_idempotency idempotency
     where idempotency.event_id=requested_event_id;
    if iq_current_inbox is not null and jsonb_typeof(iq_current_inbox)='object' then
        iq_current_inbox:=iq_current_inbox||jsonb_build_object(
            'fuseTaskPlan',coalesce(iq_response_plan,'null'::jsonb));
    end if;
    return iq_base||jsonb_build_object(
      'currentInbox',coalesce(iq_current_inbox,'null'::jsonb),
      'currentEligibilities',coalesce((select jsonb_object_agg(
          history.rule_id||'@'||history.rule_version||'@'||current_fact.registry_version,
          jsonb_build_object('eligibilityId',current_fact.eligibility_id,
            'ruleId',history.rule_id,'ruleVersion',history.rule_version,
            'aggregateVersion',current_fact.aggregate_version,
            'status',current_fact.status,'reasonCode',current_fact.reason_code))
        from ingestion_quality.iq_quality_eligibility_current current_fact
        join ingestion_quality.iq_quality_eligibility_history history
          on history.eligibility_id=current_fact.eligibility_id
         and history.aggregate_version=current_fact.aggregate_version
        where exists (select 1 from ingestion_quality.iq_rule_dependency_member member
          where member.source_id=requested_source_id
            and member.registry_version=current_fact.registry_version
            and member.rule_id=current_fact.rule_id
            and member.rule_version=current_fact.rule_version)),'{}'::jsonb),
      'activeEpisodes',coalesce((select jsonb_object_agg(
          episode.source_id||'@'||episode.dependency_id,
          jsonb_build_object('episodeId',episode.episode_id,
            'recoveryTaskId',task.task_id,'workItemKey',task.work_item_key,
            'workItemKeyVersion',task.work_item_key_version,
            'sourceId',episode.source_id,'dependencyId',episode.dependency_id,
            'generation',episode.generation,
            'aggregateVersion',episode.aggregate_version))
        from ingestion_quality.iq_quality_fuse_episode_current episode
        join ingestion_quality.iq_quality_recovery_task_current task
          on task.episode_id=episode.episode_id
        where episode.source_id=requested_source_id and episode.active),'{}'::jsonb),
      'latestEpisodeGenerations',coalesce((select jsonb_object_agg(
          source_id||'@'||dependency_id,generation)
        from (select source_id,dependency_id,max(generation) generation
          from ingestion_quality.iq_quality_fuse_episode_history
          where source_id=requested_source_id group by source_id,dependency_id)
          as episode_generation),
        '{}'::jsonb));
end
$$;

create function ingestion_quality.iq_quality_public_task_reason(
    requested_internal_reason varchar)
returns varchar
language plpgsql
immutable
set search_path = pg_catalog
as $$
begin
    case requested_internal_reason
        when 'REQUIRED_MEMBER_FUSED' then
            return 'REQUIRED_MEMBER_FAILED';
        when 'THRESHOLD_UNSATISFIED' then
            return 'COMPOSITION_THRESHOLD_FAILED';
        else
            raise exception using errcode='check_violation',
                message='INGESTION_QUALITY_PUBLIC_TASK_REASON_INVALID';
    end case;
end
$$;

create function ingestion_quality.iq_quality_public_affected_rules(
    requested_internal_rules jsonb)
returns jsonb
language sql
immutable
set search_path = pg_catalog
as $$
    select coalesce(jsonb_agg(to_jsonb((rule->>'ruleId')||'@'||(rule->>'ruleVersion'))
        order by rule->>'ruleId',rule->>'ruleVersion'),'[]'::jsonb)
      from jsonb_array_elements(requested_internal_rules) rule
$$;

create function ingestion_quality.iq_quality_trigger_reason(
    requested_transitions jsonb)
returns varchar
language plpgsql
immutable
set search_path = pg_catalog
as $$
declare iq_reason varchar;
begin
    if jsonb_typeof(requested_transitions)<>'array' then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_FUSE_TRANSITION_INVALID';
    end if;
    select transition->>'evaluatedReasonCode' into iq_reason
      from jsonb_array_elements(requested_transitions) transition
     where transition->>'evaluatedReasonCode' in (
             'REQUIRED_MEMBER_FUSED','THRESHOLD_UNSATISFIED')
       and (transition->>'reasonCode'=transition->>'evaluatedReasonCode'
         or transition->>'reasonCode'='RECOVERY_RELAPSED')
     order by transition->>'ruleId',transition->>'ruleVersion'
     limit 1;
    if iq_reason is null then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_FUSE_TRIGGER_INVALID';
    end if;
    return iq_reason;
end
$$;

create function ingestion_quality.iq_append_quality_fuse_rejection_audit(
    requested_event_id uuid,
    requested_object_version bigint,
    requested_result varchar,
    requested_trace_id character)
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_audit_id uuid;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_eligibility_consumer');
    if requested_event_id is null
       or requested_object_version not between 1 and 9007199254740991
       or requested_result not in ('denied','dependency-unavailable')
       or requested_trace_id !~ '^(?!0{32}$)[0-9a-f]{32}$' then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_FUSE_REJECTION_AUDIT_INVALID';
    end if;
    iq_audit_id:=ingestion_quality.iq_quality_uuid_v7_derive(
        requested_event_id,'quality-fuse-rejection:'||requested_result);
    insert into ingestion_quality.iq_quality_fuse_rejection_audit values (
        iq_audit_id,session_user::text,'quality-fuse.apply',requested_result,
        'QUALITY-FUSE-WORKLOAD-AUTHORIZATION-1.0.0',requested_object_version,
        requested_trace_id,statement_timestamp())
    on conflict (audit_id) do nothing;
end
$$;

create function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    requested_event_id uuid,
    requested_source_id varchar,
    requested_source_version bigint,
    requested_payload_digest character,
    requested_mutation jsonb)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_now timestamptz:=statement_timestamp();
    iq_plan jsonb:=requested_mutation->'fuseTaskPlan';
    iq_base_mutation jsonb:=requested_mutation-'fuseTaskPlan';
    iq_base_outcome varchar;
    iq_action varchar;
    iq_business_key varchar;
    iq_command_digest char(71);
    iq_existing ingestion_quality.iq_quality_fuse_idempotency%rowtype;
    iq_episode ingestion_quality.iq_quality_fuse_episode_current%rowtype;
    iq_task ingestion_quality.iq_quality_recovery_task_current%rowtype;
    iq_episode_id uuid;
    iq_task_id uuid;
    iq_generation bigint;
    iq_expected_episode_version bigint;
    iq_next_episode_version bigint;
    iq_next_task_version bigint;
    iq_route_sequence bigint;
    iq_event_id uuid;
    iq_payload jsonb;
    iq_payload_utf8 bytea;
    iq_transition jsonb;
    iq_trigger_reason varchar;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_eligibility_consumer');
    if not ingestion_quality.iq_json_exact_object(requested_mutation,array[
         'outcome','cursor','pendingPair','dependencyState','decisions',
         'backfillRequest','quarantine','snapshotEvidence','fuseTaskPlan',
         'occurredAt','effectiveAt','traceId']) then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_FUSE_MUTATION_INVALID';
    end if;
    if iq_plan<>'null'::jsonb then
        if not ingestion_quality.iq_json_exact_object(iq_plan,array[
             'action','episodeId','recoveryTaskId','workItemKey','episodeGeneration',
             'workItemKeyVersion',
             'expectedEpisodeAggregateVersion','sourceId','sourceVersion','dependencyId',
             'dependencyVersion','batchId','snapshotId','snapshotHash',
             'qualityGateVersion','qualityGateDigest','qmdpVersion','qmdpDigest',
             'qshmVersion','qshmDigest','lineageId','watermark','affectedRules',
             'eligibilityEvidence','formulaEvidence','fuseBusinessKey',
             'commandBodyDigest','effectiveAt','occurredAt','transitions',
             'authorizationEvidence'])
           or jsonb_typeof(iq_plan->'affectedRules')<>'array'
           or jsonb_typeof(iq_plan->'eligibilityEvidence')<>'array'
           or jsonb_typeof(iq_plan->'formulaEvidence')<>'array'
           or jsonb_typeof(iq_plan->'transitions')<>'array'
           or jsonb_typeof(iq_plan->'authorizationEvidence')<>'object'
           or iq_plan->>'sourceId'<>requested_source_id
           or (iq_plan->>'sourceVersion')::bigint<>requested_source_version
           or iq_plan->>'workItemKeyVersion' !~ '^k[1-9][0-9]*$'
           or iq_plan->>'commandBodyDigest'<>trim(requested_payload_digest) then
            raise exception using errcode='check_violation',
                message='INGESTION_QUALITY_FUSE_PLAN_INVALID';
        end if;
        if not ingestion_quality.iq_json_exact_object(
             iq_plan->'authorizationEvidence',array[
               'environment','serviceRef','audience','capability','mtlsSanUriRef',
               'authorizationGeneration','policyVersion','policyDigest',
               'effectiveAt','trustedAt','expiresAt'])
           or coalesce(jsonb_typeof(
               iq_plan#>'{authorizationEvidence,authorizationGeneration}'),'null')<>'number'
           or coalesce(jsonb_typeof(
               iq_plan#>'{authorizationEvidence,trustedAt}'),'null')<>'string'
           or coalesce(jsonb_typeof(
               iq_plan#>'{authorizationEvidence,effectiveAt}'),'null')<>'string'
           or coalesce(jsonb_typeof(
               iq_plan#>'{authorizationEvidence,expiresAt}'),'null')<>'string'
           or (iq_plan#>>'{authorizationEvidence,environment}') not in (
               'test','stage','prod')
           or length(iq_plan#>>'{authorizationEvidence,serviceRef}') not between 1 and 256
           or (iq_plan#>>'{authorizationEvidence,audience}') is distinct from
               'urn:scholarsense:ingestion-quality:quality-fuse'
           or (iq_plan#>>'{authorizationEvidence,capability}') is distinct from
               'quality-fuse.apply'
           or (iq_plan#>>'{authorizationEvidence,mtlsSanUriRef}') !~ '^spiffe://'
           or (iq_plan#>>'{authorizationEvidence,policyVersion}') is distinct from
               'QUALITY-FUSE-WORKLOAD-AUTHORIZATION-1.0.0'
           or (iq_plan#>>'{authorizationEvidence,policyDigest}')
               !~ '^sha256:[0-9a-f]{64}$' then
            raise exception using errcode='insufficient_privilege',
                message='INGESTION_QUALITY_FUSE_AUTHORIZATION_INVALID';
        end if;
        if (iq_plan#>>'{authorizationEvidence,authorizationGeneration}')::bigint
                not between 1 and 9007199254740991
           or (iq_plan#>>'{authorizationEvidence,trustedAt}')::timestamptz<
              (iq_plan#>>'{authorizationEvidence,effectiveAt}')::timestamptz
           or (iq_plan#>>'{authorizationEvidence,trustedAt}')::timestamptz>=
              (iq_plan#>>'{authorizationEvidence,expiresAt}')::timestamptz then
            raise exception using errcode='insufficient_privilege',
                message='INGESTION_QUALITY_FUSE_AUTHORIZATION_INVALID';
        end if;
        iq_business_key:=iq_plan->>'fuseBusinessKey';
        iq_command_digest:=iq_plan->>'commandBodyDigest';
        select idempotency.* into iq_existing
          from ingestion_quality.iq_quality_fuse_idempotency idempotency
         where idempotency.fuse_business_key=iq_business_key for update;
        if iq_existing.fuse_business_key is not null then
            if trim(iq_existing.command_body_digest)<>trim(iq_command_digest) then
                raise exception using errcode='unique_violation',
                    message='INGESTION_QUALITY_IDEMPOTENCY_MISMATCH';
            end if;
            return jsonb_build_object('outcome','duplicate',
                'fuseTaskPlan',iq_existing.response_plan);
        end if;
        if jsonb_array_length(iq_plan->'affectedRules')<1
           or jsonb_array_length(iq_plan->'affectedRules')<>
              jsonb_array_length(iq_plan->'transitions')
           or jsonb_array_length(iq_plan->'affectedRules')<>
              jsonb_array_length(iq_plan->'eligibilityEvidence')
           or jsonb_array_length(iq_plan->'formulaEvidence')<1
           or exists (
             select 1 from jsonb_array_elements(iq_plan->'affectedRules') rule
              where not ingestion_quality.iq_json_exact_object(
                    rule,array['ruleId','ruleVersion'])
                 or not exists (
                    select 1 from ingestion_quality.iq_rule_dependency_member member
                     where member.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
                       and member.source_id=requested_source_id
                       and member.dependency_id=iq_plan->>'dependencyId'
                       and member.rule_id=rule->>'ruleId'
                       and member.rule_version=rule->>'ruleVersion'))
           or exists (
             select 1 from jsonb_array_elements(iq_plan->'transitions') transition
              where not ingestion_quality.iq_json_exact_object(transition,array[
                    'ruleId','ruleVersion','priorState','evaluatedState',
                    'appliedState','reasonCode','evaluatedReasonCode'])
                 or not (
                    (transition->>'appliedState'='fused'
                     and transition->>'evaluatedReasonCode' in (
                       'REQUIRED_MEMBER_FUSED','THRESHOLD_UNSATISFIED'))
                    or (iq_plan->>'action'='preserve'
                     and transition->>'priorState'='recovering'
                     and transition->>'evaluatedState'='eligible'
                     and transition->>'appliedState'='recovering'
                     and transition->>'reasonCode'='RECOVERY_LATCHED'
                     and transition->>'evaluatedReasonCode'='ALL_REQUIRED_ELIGIBLE'))
                 or not (iq_plan->'affectedRules') @> jsonb_build_array(
                    jsonb_build_object('ruleId',transition->>'ruleId',
                      'ruleVersion',transition->>'ruleVersion')))
           or (select count(*) from (
                select rule->>'ruleId',rule->>'ruleVersion'
                  from jsonb_array_elements(iq_plan->'affectedRules') rule
                 group by rule->>'ruleId',rule->>'ruleVersion') unique_rule)<>
              jsonb_array_length(iq_plan->'affectedRules') then
            raise exception using errcode='check_violation',
                message='INGESTION_QUALITY_FUSE_TRANSITION_INVALID';
        end if;
        if exists (
             select 1 from jsonb_array_elements(iq_plan->'eligibilityEvidence') evidence
              where not ingestion_quality.iq_json_exact_object(evidence,array[
                    'eligibilityId','businessKey','aggregateVersion','ruleId','ruleVersion',
                    'memberSetDigest','registryVersion','registryDigest','dccVersion',
                    'dccDigest','ruleCatalogVersion','ruleCatalogDigest','operator','threshold',
                    'priorState','evaluatedState','appliedState','reasonCode','members'])
                 or jsonb_typeof(evidence->'members')<>'array'
                 or jsonb_array_length(evidence->'members')<1
                 or evidence->>'businessKey'<>(evidence->>'ruleId')||'@'||
                    (evidence->>'ruleVersion')||'@'||(evidence->>'registryVersion')
                 or evidence->>'registryVersion'<>'RULE-DEPENDENCY-REGISTRY-1.0.0'
                 or evidence->>'dccVersion'<>'DCC-1.1.0'
                 or evidence->>'ruleCatalogVersion'<>'RC-1.0.0'
                 or evidence->>'memberSetDigest' !~ '^sha256:[0-9a-f]{64}$'
                 or evidence->>'registryDigest' !~ '^sha256:[0-9a-f]{64}$'
                 or evidence->>'dccDigest' !~ '^sha256:[0-9a-f]{64}$'
                 or evidence->>'ruleCatalogDigest' !~ '^sha256:[0-9a-f]{64}$'
                 or not (iq_plan->'affectedRules') @> jsonb_build_array(
                    jsonb_build_object('ruleId',evidence->>'ruleId',
                      'ruleVersion',evidence->>'ruleVersion'))
                 or not (iq_plan->'transitions') @> jsonb_build_array(
                    jsonb_build_object('ruleId',evidence->>'ruleId',
                      'ruleVersion',evidence->>'ruleVersion',
                      'priorState',evidence->>'priorState',
                      'evaluatedState',evidence->>'evaluatedState',
                      'appliedState',evidence->>'appliedState',
                      'reasonCode',evidence->>'reasonCode'))
                 or exists (
                    select 1 from jsonb_array_elements(evidence->'members') member
                     where not ingestion_quality.iq_json_exact_object(member,array[
                       'sourceId','sourceContractVersion','sourceVersion','dependencyId',
                       'dependencyContractVersion','dependencyVersion','requirement',
                       'compositionGroup','state','versionContinuous','watermark','failed'])
                        or member->>'requirement' not in ('required','optional')
                        or member->>'state' not in ('eligible','fused','recovering','missing')
                        or not exists (select 1
                          from ingestion_quality.iq_rule_dependency_member registry_member
                         where registry_member.registry_version=evidence->>'registryVersion'
                           and registry_member.rule_id=evidence->>'ruleId'
                           and registry_member.rule_version=evidence->>'ruleVersion'
                           and registry_member.source_id=member->>'sourceId'
                           and registry_member.source_contract_version=
                               member->>'sourceContractVersion'
                           and registry_member.dependency_id=member->>'dependencyId'
                           and registry_member.dependency_version=
                               member->>'dependencyContractVersion'
                           and registry_member.requirement=member->>'requirement'
                           and registry_member.composition_group=
                               member->>'compositionGroup'))) then
            raise exception using errcode='check_violation',
                message='INGESTION_QUALITY_FUSE_HANDOFF_EVIDENCE_INVALID';
        end if;
        if exists (
             select 1 from jsonb_array_elements(iq_plan->'formulaEvidence') formula
              where not ingestion_quality.iq_json_exact_object(formula,array[
                    'metricId','formulaId','formulaVersion','result','applicable',
                    'numerator','denominator','valueBasisPoints','unit','operator',
                    'thresholdNumerator','thresholdDenominator','boundary',
                    'comparisonResult'])
                 or formula->>'formulaId' not like 'QMDP-1.0.0/%'
                 or formula->>'formulaVersion'<>'1.0.0'
                 or formula->>'result' not in ('passed','failed','not-applicable')
                 or (formula->>'operator') not in ('>=','<=','=')
                 or (formula->>'boundary') not in ('inclusive','exclusive')
                 or (case formula->>'result' when 'passed' then true
                      when 'failed' then false else null end)
                    is distinct from (formula->>'comparisonResult')::boolean) then
            raise exception using errcode='check_violation',
                message='INGESTION_QUALITY_FUSE_FORMULA_EVIDENCE_INVALID';
        end if;
        if exists (
             select 1
               from jsonb_array_elements(iq_plan->'transitions') transition
               left join ingestion_quality.iq_quality_eligibility_current current_fact
                 on current_fact.rule_id=transition->>'ruleId'
                and current_fact.rule_version=transition->>'ruleVersion'
                and current_fact.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0'
              where coalesce(current_fact.status,'missing')<>
                    transition->>'priorState') then
            raise exception using errcode='serialization_failure',
                message='INGESTION_QUALITY_FUSE_VERSION_CONFLICT';
        end if;
    end if;

    iq_base_outcome:=ingestion_quality.iq_accept_quality_eligibility_event(
        requested_event_id,requested_source_id,requested_source_version,
        requested_payload_digest,iq_base_mutation);
    if iq_plan='null'::jsonb or iq_base_outcome<>'applied' then
        return jsonb_build_object('outcome',iq_base_outcome,
            'fuseTaskPlan','null'::jsonb);
    end if;

    if exists (
         select 1
           from jsonb_array_elements(iq_plan->'eligibilityEvidence') evidence
           left join ingestion_quality.iq_quality_eligibility_current current_fact
             on current_fact.rule_id=evidence->>'ruleId'
            and current_fact.rule_version=evidence->>'ruleVersion'
            and current_fact.registry_version=evidence->>'registryVersion'
           left join ingestion_quality.iq_quality_eligibility_history history
             on history.eligibility_id=current_fact.eligibility_id
            and history.aggregate_version=current_fact.aggregate_version
          where current_fact.eligibility_id is null
             or current_fact.eligibility_id<>(evidence->>'eligibilityId')::uuid
             or current_fact.aggregate_version<>(evidence->>'aggregateVersion')::bigint
             or current_fact.status<>evidence->>'appliedState'
             or current_fact.reason_code<>evidence->>'reasonCode'
             or trim(history.registry_digest)<>evidence->>'registryDigest'
             or history.catalog_version<>evidence->>'dccVersion'
             or trim(history.catalog_digest)<>evidence->>'dccDigest'
             or history.rule_catalog_version<>evidence->>'ruleCatalogVersion'
             or trim(history.rule_catalog_digest)<>evidence->>'ruleCatalogDigest'
             or history.composition_operator<>evidence->>'operator'
             or history.threshold is distinct from (evidence->>'threshold')::integer
             or evidence->'members' is distinct from (
                select jsonb_agg(jsonb_build_object(
                  'sourceId',member.source_id,
                  'sourceContractVersion',registry_member.source_contract_version,
                  'sourceVersion',member.source_version,
                  'dependencyId',member.dependency_id,
                  'dependencyContractVersion',registry_member.dependency_version,
                  'dependencyVersion',member.dependency_version,
                  'requirement',member.requirement,
                  'compositionGroup',registry_member.composition_group,
                  'state',member.state,'versionContinuous',member.version_continuous,
                  'watermark',convert_from(member.source_watermark_utf8,'UTF8'),
                  'failed',member.failed) order by member.dependency_id)
                  from ingestion_quality.iq_quality_eligibility_member_history member
                  join ingestion_quality.iq_rule_dependency_member registry_member
                    on registry_member.registry_version=evidence->>'registryVersion'
                   and registry_member.rule_id=evidence->>'ruleId'
                   and registry_member.rule_version=evidence->>'ruleVersion'
                   and registry_member.dependency_id=member.dependency_id
                 where member.eligibility_id=current_fact.eligibility_id
                   and member.aggregate_version=current_fact.aggregate_version)
             or evidence->>'memberSetDigest' is distinct from (
                select 'sha256:'||encode(sha256(convert_to(string_agg(
                  member.source_id||chr(31)||member.source_version::text||chr(31)||
                  member.dependency_id||chr(31)||member.dependency_version::text,
                  chr(30) order by member.dependency_id),'UTF8')),'hex')
                  from ingestion_quality.iq_quality_eligibility_member_history member
                 where member.eligibility_id=current_fact.eligibility_id
                   and member.aggregate_version=current_fact.aggregate_version)) then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_FUSE_HANDOFF_VERSION_CONFLICT';
    end if;
    if jsonb_array_length(iq_plan->'formulaEvidence')<>(select count(*)
         from ingestion_quality.iq_quality_snapshot_metric metric
        where metric.snapshot_id=(iq_plan->>'snapshotId')::uuid)
       or exists (
         select 1 from jsonb_array_elements(iq_plan->'formulaEvidence') formula
          where not exists (
            select 1 from ingestion_quality.iq_quality_snapshot_metric metric
             where metric.snapshot_id=(iq_plan->>'snapshotId')::uuid
               and metric.metric_id=formula->>'metricId'
               and metric.formula_id=formula->>'formulaId'
               and metric.formula_version=formula->>'formulaVersion'
               and metric.result=formula->>'result'
               and metric.applicable=(formula->>'applicable')::boolean
               and metric.numerator=(formula->>'numerator')::bigint
               and metric.denominator=(formula->>'denominator')::bigint
               and metric.value_basis_points is not distinct from
                   (formula->>'valueBasisPoints')::bigint
               and metric.unit=formula->>'unit'
               and metric.operator=formula->>'operator'
               and metric.threshold_numerator=(formula->>'thresholdNumerator')::bigint
               and metric.threshold_denominator=(formula->>'thresholdDenominator')::bigint
               and metric.boundary=formula->>'boundary'
               and (case metric.result when 'passed' then true
                    when 'failed' then false else null end) is not distinct from
                   (formula->>'comparisonResult')::boolean)) then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_FUSE_FORMULA_VERSION_CONFLICT';
    end if;

    iq_action:=iq_plan->>'action';
    if iq_action not in ('create','update','preserve') then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_FUSE_PLAN_INVALID';
    end if;
    iq_episode_id:=(iq_plan->>'episodeId')::uuid;
    iq_task_id:=(iq_plan->>'recoveryTaskId')::uuid;
    iq_generation:=(iq_plan->>'episodeGeneration')::bigint;
    iq_expected_episode_version:=(iq_plan->>'expectedEpisodeAggregateVersion')::bigint;
    -- Serialize route changes with the relay's session-level send permit without
    -- holding this transaction open across external I/O.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-task-route:'||iq_task_id::text,0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-fuse-episode:'||requested_source_id||'@'||(iq_plan->>'dependencyId'),0));
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'quality-recovery-task:'||requested_source_id||'@'||(iq_plan->>'dependencyId'),0));
    select episode.* into iq_episode
      from ingestion_quality.iq_quality_fuse_episode_current episode
     where episode.source_id=requested_source_id
       and episode.dependency_id=iq_plan->>'dependencyId' for update;
    select task.* into iq_task
      from ingestion_quality.iq_quality_recovery_task_current task
     where task.episode_id=iq_episode.episode_id for update;

    if iq_action='create' then
        if iq_episode.episode_id is not null and iq_episode.active then
            raise exception using errcode='serialization_failure',
                message='INGESTION_QUALITY_FUSE_ACTIVE_EPISODE_CONFLICT';
        end if;
        if iq_expected_episode_version<>0
           or iq_generation<>coalesce((select max(history.generation)+1
              from ingestion_quality.iq_quality_fuse_episode_history history
             where history.source_id=requested_source_id
               and history.dependency_id=iq_plan->>'dependencyId'),1) then
            raise exception using errcode='serialization_failure',
                message='INGESTION_QUALITY_FUSE_VERSION_CONFLICT';
        end if;
        iq_next_episode_version:=1;
        iq_next_task_version:=1;
    else
        if iq_episode.episode_id is null or not iq_episode.active
           or iq_episode.episode_id<>iq_episode_id
           or iq_task.task_id<>iq_task_id
           or iq_episode.work_item_key_version<>iq_plan->>'workItemKeyVersion'
           or iq_task.work_item_key_version<>iq_plan->>'workItemKeyVersion'
           or iq_episode.generation<>iq_generation
           or iq_episode.aggregate_version<>iq_expected_episode_version then
            raise exception using errcode='serialization_failure',
                message='INGESTION_QUALITY_FUSE_VERSION_CONFLICT';
        end if;
        if iq_action='preserve' then
            insert into ingestion_quality.iq_quality_fuse_idempotency values (
                iq_business_key,requested_event_id,iq_command_digest,iq_episode_id,
                iq_task_id,iq_plan->>'workItemKey',iq_plan,iq_now,iq_now+interval '90 days');
            return jsonb_build_object('outcome',iq_base_outcome,'fuseTaskPlan',iq_plan);
        end if;
        iq_next_episode_version:=iq_episode.aggregate_version+1;
        iq_next_task_version:=iq_task.aggregate_version+1;
    end if;

    iq_trigger_reason:=ingestion_quality.iq_quality_trigger_reason(
        iq_plan->'transitions');
    if iq_trigger_reason not in ('REQUIRED_MEMBER_FUSED','THRESHOLD_UNSATISFIED') then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_FUSE_TRIGGER_INVALID';
    end if;

    insert into ingestion_quality.iq_quality_fuse_episode_history values (
        iq_episode_id,iq_generation,iq_next_episode_version,requested_source_id,
        iq_plan->>'dependencyId',iq_plan->>'workItemKeyVersion','active',requested_event_id,
        (iq_plan->>'batchId')::uuid,(iq_plan->>'snapshotId')::uuid,
        iq_plan->>'snapshotHash',iq_trigger_reason,
        (iq_plan->>'dependencyVersion')::bigint,
        jsonb_build_object('qualityGateVersion',iq_plan->>'qualityGateVersion',
          'qualityGateDigest',iq_plan->>'qualityGateDigest',
          'qmdpVersion',iq_plan->>'qmdpVersion','qmdpDigest',iq_plan->>'qmdpDigest',
          'qshmVersion',iq_plan->>'qshmVersion','qshmDigest',iq_plan->>'qshmDigest',
          'sourceVersion',(iq_plan->>'sourceVersion')::bigint,
          'batchId',iq_plan->>'batchId','snapshotId',iq_plan->>'snapshotId',
          'snapshotHash',iq_plan->>'snapshotHash','lineageId',iq_plan->>'lineageId',
          'effectiveAt',iq_plan->>'effectiveAt',
          'eligibilities',iq_plan->'eligibilityEvidence',
          'formulaBoundaries',iq_plan->'formulaEvidence'),
        iq_plan->'transitions',convert_to(iq_plan->>'watermark','UTF8'),
        (iq_plan->>'occurredAt')::timestamptz,requested_mutation->>'traceId');
    insert into ingestion_quality.iq_quality_fuse_episode_current values (
        iq_episode_id,requested_source_id,iq_plan->>'dependencyId',
        iq_plan->>'workItemKeyVersion',iq_generation,
        iq_next_episode_version,true,iq_now)
    on conflict (episode_id) do update set
        aggregate_version=excluded.aggregate_version,active=true,updated_at=excluded.updated_at
    where ingestion_quality.iq_quality_fuse_episode_current.source_id=excluded.source_id
      and ingestion_quality.iq_quality_fuse_episode_current.dependency_id=excluded.dependency_id
      and ingestion_quality.iq_quality_fuse_episode_current.work_item_key_version=
          excluded.work_item_key_version
      and ingestion_quality.iq_quality_fuse_episode_current.generation=excluded.generation;
    if not found then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_FUSE_ACTIVE_EPISODE_CONFLICT';
    end if;

    insert into ingestion_quality.iq_quality_recovery_task_history values (
        iq_task_id,iq_next_task_version,iq_episode_id,iq_generation,
        iq_plan->>'workItemKey',iq_plan->>'workItemKeyVersion',requested_source_id,
        iq_plan->>'dependencyId','open','P1',
        (iq_plan->>'occurredAt')::timestamptz+interval '1 day',
        'source-owner:'||requested_source_id,
        jsonb_build_object('batchId',iq_plan->>'batchId','snapshotId',iq_plan->>'snapshotId',
          'reasonCode',iq_trigger_reason),
        jsonb_build_object('snapshotHash',iq_plan->>'snapshotHash',
          'qualityGateVersion',iq_plan->>'qualityGateVersion',
          'qualityGateDigest',iq_plan->>'qualityGateDigest',
          'qmdpVersion',iq_plan->>'qmdpVersion','qmdpDigest',iq_plan->>'qmdpDigest',
          'qshmVersion',iq_plan->>'qshmVersion','qshmDigest',iq_plan->>'qshmDigest',
          'sourceVersion',(iq_plan->>'sourceVersion')::bigint,
          'dependencyVersion',(iq_plan->>'dependencyVersion')::bigint,
          'lineageId',iq_plan->>'lineageId','effectiveAt',iq_plan->>'effectiveAt',
          'eligibilities',iq_plan->'eligibilityEvidence',
          'formulaBoundaries',iq_plan->'formulaEvidence'),
        convert_to(iq_plan->>'watermark','UTF8'),(iq_plan->>'occurredAt')::timestamptz);
    insert into ingestion_quality.iq_quality_recovery_task_current values (
        iq_task_id,iq_episode_id,iq_generation,iq_plan->>'workItemKey',
        iq_plan->>'workItemKeyVersion',requested_source_id,
        iq_plan->>'dependencyId',iq_next_task_version,'open','P1',
        (iq_plan->>'occurredAt')::timestamptz+interval '1 day',
        'source-owner:'||requested_source_id,(iq_plan->>'occurredAt')::timestamptz,iq_now)
on conflict (task_id) do update set
        aggregate_version=excluded.aggregate_version,occurred_at=excluded.occurred_at,
        updated_at=excluded.updated_at
    where ingestion_quality.iq_quality_recovery_task_current.episode_id=excluded.episode_id
      and ingestion_quality.iq_quality_recovery_task_current.work_item_key_version=
          excluded.work_item_key_version
      and ingestion_quality.iq_quality_recovery_task_current.aggregate_version+1=
          excluded.aggregate_version;
    if not found then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_FUSE_VERSION_CONFLICT';
    end if;
    insert into ingestion_quality.iq_quality_recovery_task_affected_rule
    select iq_task_id,rule->>'ruleId',rule->>'ruleVersion'
      from jsonb_array_elements(iq_plan->'affectedRules') rule
    on conflict do nothing;

    iq_route_sequence:=iq_next_task_version;
    iq_event_id:=ingestion_quality.iq_quality_uuid_v7_derive(
        requested_event_id,'quality-task-outbox:'||iq_task_id::text||':'||iq_route_sequence::text);
    iq_payload:=jsonb_build_object(
      'specversion','1.0','id',iq_event_id,'source','scholarsense/ingestion-quality',
      'type','scholarsense.ingestion-quality.quality-recovery-task.changed.v1',
      'subject','quality-recovery-task/'||iq_task_id::text,
      'time',(iq_plan->>'occurredAt')::timestamptz,'datacontenttype','application/json',
      'data',jsonb_build_object('eventId',iq_event_id,'taskId',iq_task_id,
        'episodeId',iq_episode_id,'workItemKey',iq_plan->>'workItemKey',
        'operation',case when iq_action='create' then 'create' else 'update' end,
        'routeSequence',iq_route_sequence,'sourceAggregateVersion',iq_next_task_version,
        'contractVersion','PIC-1.1.0','task',jsonb_build_object(
          'taskId',iq_task_id,'workItemKey',iq_plan->>'workItemKey','episodeId',iq_episode_id,
          'episodeGeneration',iq_generation,'sourceId',requested_source_id,
          'dependencyId',iq_plan->>'dependencyId','trigger',jsonb_build_object(
            'batchId',iq_plan->>'batchId','snapshotId',iq_plan->>'snapshotId',
            'reasonCode',ingestion_quality.iq_quality_public_task_reason(iq_trigger_reason)),
          'affectedRules',ingestion_quality.iq_quality_public_affected_rules(iq_plan->'affectedRules'),
          'ownerRef','source-owner:'||requested_source_id,'priority','P1',
          'dueAt',(iq_plan->>'occurredAt')::timestamptz+interval '1 day','status','open',
          'currentEvidence',jsonb_build_object('qualityGateVersion',iq_plan->>'qualityGateVersion',
            'qmdpVersion',iq_plan->>'qmdpVersion','qshmVersion',iq_plan->>'qshmVersion'),
          'watermark',iq_plan->>'watermark','aggregateVersion',iq_next_task_version,
          'createdAt',(iq_plan->>'occurredAt')::timestamptz,
          'updatedAt',(iq_plan->>'occurredAt')::timestamptz),
        'runtimeEvidenceClaim','none'));
    iq_payload_utf8:=convert_to(iq_payload::text,'UTF8');
    if octet_length(iq_payload_utf8)>65536 then
        raise exception using errcode='program_limit_exceeded',
            message='INGESTION_QUALITY_QUALITY_TASK_PAYLOAD_TOO_LARGE';
    end if;
    insert into ingestion_quality.iq_quality_task_outbox values (
        iq_event_id,iq_task_id,iq_route_sequence,
        'scholarsense.ingestion-quality.quality-recovery-task.changed.v1',
        'QUALITY-RECOVERY-TASK-EVENT-1.0.0',iq_payload_utf8,
        encode(sha256(iq_payload_utf8),'hex'),'pending',0,iq_now,null,0,null,iq_now);
    insert into ingestion_quality.iq_quality_task_delivery values (
        iq_task_id,'public-task-platform',iq_route_sequence,'pending',0,null,null,
        null,null,0,null,iq_now)
    on conflict (task_id,target) do update set
        route_sequence=excluded.route_sequence,status='pending',attempt=0,
        receipt_id=null,last_error_code=null,next_attempt_at=null,lease_owner=null,
        lease_generation=ingestion_quality.iq_quality_task_delivery.lease_generation+1,
        lease_expires_at=null,updated_at=excluded.updated_at
    where ingestion_quality.iq_quality_task_delivery.route_sequence+1=excluded.route_sequence;
    if not found then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_TASK_ROUTE_SEQUENCE_CONFLICT';
    end if;
    insert into ingestion_quality.iq_quality_task_delivery_history values (
        iq_task_id,'public-task-platform',iq_route_sequence,0,'pending',null,null,iq_now);
    insert into ingestion_quality.iq_quality_fuse_audit values (
        ingestion_quality.iq_quality_uuid_v7_derive(requested_event_id,
            'quality-fuse-audit:'||iq_episode_id::text||':'||iq_next_episode_version::text),
        iq_episode_id,iq_task_id,
        case when iq_action='create' then 'quality-fuse-started' else 'quality-fuse-updated' end,
        'accepted',iq_plan#>>'{authorizationEvidence,serviceRef}',
        iq_plan#>>'{authorizationEvidence,policyVersion}',
        (iq_plan#>>'{authorizationEvidence,authorizationGeneration}')::bigint,
        requested_mutation->>'traceId',requested_payload_digest,
        (iq_plan->>'occurredAt')::timestamptz);
    insert into ingestion_quality.iq_quality_fuse_idempotency values (
        iq_business_key,requested_event_id,iq_command_digest,iq_episode_id,iq_task_id,
        iq_plan->>'workItemKey',iq_plan,iq_now,iq_now+interval '90 days');
    return jsonb_build_object('outcome',iq_base_outcome,'fuseTaskPlan',iq_plan);
end
$$;

create function ingestion_quality.iq_claim_next_quality_task_outbox()
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_row ingestion_quality.iq_quality_task_outbox%rowtype;
    iq_current_route bigint;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_task_relay');
    loop
        iq_row:=null;
        select queued.* into iq_row
          from ingestion_quality.iq_quality_task_outbox queued
         where (queued.status='pending' and queued.available_at<=statement_timestamp())
            or (queued.status='claimed'
                and queued.claimed_at+interval '30 seconds'<=statement_timestamp())
         order by case when queued.status='claimed' then queued.claimed_at
                       else queued.available_at end,
                  queued.event_id
         for update skip locked limit 1;
        if iq_row.event_id is null then return null; end if;
        iq_current_route:=null;
        select delivery.route_sequence into iq_current_route
          from ingestion_quality.iq_quality_task_delivery delivery
          join ingestion_quality.iq_quality_recovery_task_current task
            on task.task_id=delivery.task_id
           and task.aggregate_version=delivery.route_sequence
         where delivery.task_id=iq_row.task_id
         for update;
        if iq_current_route is null or iq_current_route<>iq_row.route_sequence then
            update ingestion_quality.iq_quality_task_outbox queued
               set status='failed',claimed_at=null,
                   last_error_code='QUALITY_TASK_ROUTE_SUPERSEDED'
             where queued.event_id=iq_row.event_id;
            insert into ingestion_quality.iq_quality_task_delivery_history values (
                iq_row.task_id,'public-task-platform',iq_row.route_sequence,iq_row.attempts,
                'failed',null,'QUALITY_TASK_ROUTE_SUPERSEDED',statement_timestamp())
            on conflict do nothing;
            continue;
        end if;
        if iq_row.status='claimed' then
            insert into ingestion_quality.iq_quality_task_delivery_history values (
                iq_row.task_id,'public-task-platform',iq_row.route_sequence,iq_row.attempts,
                'retrying',null,'QUALITY_TASK_RELAY_LEASE_EXPIRED',statement_timestamp())
            on conflict do nothing;
        end if;
        if iq_row.attempts>=8 then
            update ingestion_quality.iq_quality_task_outbox queued
               set status='failed',claimed_at=null,
                   last_error_code='QUALITY_TASK_RELAY_ATTEMPTS_EXHAUSTED'
             where queued.event_id=iq_row.event_id;
            update ingestion_quality.iq_quality_task_delivery delivery
               set status='failed',receipt_id=null,
                   last_error_code='QUALITY_TASK_RELAY_ATTEMPTS_EXHAUSTED',
                   next_attempt_at=null,lease_owner=null,lease_expires_at=null,
                   updated_at=statement_timestamp()
             where delivery.task_id=iq_row.task_id
               and delivery.route_sequence=iq_row.route_sequence;
            insert into ingestion_quality.iq_quality_task_delivery_history values (
                iq_row.task_id,'public-task-platform',iq_row.route_sequence,iq_row.attempts,
                'failed',null,'QUALITY_TASK_RELAY_ATTEMPTS_EXHAUSTED',statement_timestamp())
            on conflict do nothing;
            continue;
        end if;
        if encode(sha256(iq_row.payload_utf8),'hex')<>trim(iq_row.payload_digest) then
            update ingestion_quality.iq_quality_task_outbox queued
               set status='failed',claimed_at=null,
                   last_error_code='QUALITY_TASK_PAYLOAD_INTEGRITY_INVALID'
             where queued.event_id=iq_row.event_id;
            update ingestion_quality.iq_quality_task_delivery delivery
               set status='failed',receipt_id=null,
                   last_error_code='QUALITY_TASK_PAYLOAD_INTEGRITY_INVALID',
                   next_attempt_at=null,lease_owner=null,lease_expires_at=null,
                   updated_at=statement_timestamp()
             where delivery.task_id=iq_row.task_id
               and delivery.route_sequence=iq_row.route_sequence;
            insert into ingestion_quality.iq_quality_task_delivery_history values (
                iq_row.task_id,'public-task-platform',iq_row.route_sequence,iq_row.attempts,
                'failed',null,'QUALITY_TASK_PAYLOAD_INTEGRITY_INVALID',statement_timestamp())
            on conflict do nothing;
            continue;
        end if;
        update ingestion_quality.iq_quality_task_outbox queued
           set status='claimed',claimed_at=statement_timestamp(),attempts=queued.attempts+1,
               lease_generation=queued.lease_generation+1,last_error_code=null
         where queued.event_id=iq_row.event_id;
        update ingestion_quality.iq_quality_task_delivery delivery
           set status='retrying',attempt=iq_row.attempts+1,
               next_attempt_at=statement_timestamp(),
               lease_owner=session_user,lease_generation=iq_row.lease_generation+1,
               lease_expires_at=statement_timestamp()+interval '30 seconds',
               last_error_code=null,updated_at=statement_timestamp()
         where delivery.task_id=iq_row.task_id
           and delivery.route_sequence=iq_row.route_sequence;
        return jsonb_build_object('eventId',iq_row.event_id,'taskId',iq_row.task_id,
            'routeSequence',iq_row.route_sequence,
            'payload',convert_from(iq_row.payload_utf8,'UTF8'),
            'payloadDigest','sha256:'||trim(iq_row.payload_digest),
            'attempt',iq_row.attempts+1,
            'leaseGeneration',iq_row.lease_generation+1);
    end loop;
end
$$;

create function ingestion_quality.iq_authorize_quality_task_send(
    requested_event_id uuid,requested_lease_generation bigint,
    requested_route_sequence bigint)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_task_relay');
    return exists (
        select 1
          from ingestion_quality.iq_quality_task_outbox queued
          join ingestion_quality.iq_quality_task_delivery delivery
            on delivery.task_id=queued.task_id
           and delivery.route_sequence=queued.route_sequence
           and delivery.lease_generation=queued.lease_generation
          join ingestion_quality.iq_quality_recovery_task_current task
            on task.task_id=queued.task_id
           and task.aggregate_version=queued.route_sequence
         where queued.event_id=requested_event_id
           and queued.status='claimed'
           and queued.lease_generation=requested_lease_generation
           and queued.route_sequence=requested_route_sequence);
end
$$;

create function ingestion_quality.iq_mark_quality_task_delivery_retry(
    requested_event_id uuid,requested_lease_generation bigint,
    requested_error_code varchar)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_row ingestion_quality.iq_quality_task_outbox%rowtype;
    iq_next_attempt_at timestamptz;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_task_relay');
    select queued.* into iq_row from ingestion_quality.iq_quality_task_outbox queued
     where queued.event_id=requested_event_id for update;
    if requested_error_code is null
       or requested_error_code!~'^QUALITY_TASK_[A-Z0-9_]{2,110}$' then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_TASK_RELAY_ERROR_INVALID';
    end if;
    if iq_row.status='pending'
       and iq_row.lease_generation=requested_lease_generation
       and iq_row.last_error_code=requested_error_code
       and exists (select 1
          from ingestion_quality.iq_quality_task_delivery delivery
          join ingestion_quality.iq_quality_recovery_task_current task
            on task.task_id=delivery.task_id
           and task.aggregate_version=delivery.route_sequence
         where delivery.task_id=iq_row.task_id
           and delivery.route_sequence=iq_row.route_sequence) then
        return true;
    end if;
    iq_next_attempt_at:=statement_timestamp()+
        make_interval(secs=>least(3600,power(2,least(12,iq_row.attempts))::integer));
    update ingestion_quality.iq_quality_task_outbox queued set status='pending',
        claimed_at=null,available_at=iq_next_attempt_at,last_error_code=requested_error_code
     where queued.event_id=requested_event_id and queued.status='claimed'
       and queued.lease_generation=requested_lease_generation
       and exists (select 1
          from ingestion_quality.iq_quality_task_delivery delivery
          join ingestion_quality.iq_quality_recovery_task_current task
            on task.task_id=delivery.task_id
           and task.aggregate_version=delivery.route_sequence
         where delivery.task_id=iq_row.task_id
           and delivery.route_sequence=iq_row.route_sequence);
    if not found then return false; end if;
    update ingestion_quality.iq_quality_task_delivery delivery set status='retrying',
        last_error_code=requested_error_code,next_attempt_at=iq_next_attempt_at,
        lease_owner=null,lease_expires_at=null,updated_at=statement_timestamp()
     where delivery.task_id=iq_row.task_id and delivery.route_sequence=iq_row.route_sequence;
    insert into ingestion_quality.iq_quality_task_delivery_history values (
        iq_row.task_id,'public-task-platform',iq_row.route_sequence,iq_row.attempts,
        'retrying',null,requested_error_code,statement_timestamp());
    -- DELIVERY_STATE_ORTHOGONALITY
    return true;
end
$$;

create function ingestion_quality.iq_complete_quality_task_delivery(
    requested_event_id uuid,requested_lease_generation bigint,requested_receipt_id varchar)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare iq_row ingestion_quality.iq_quality_task_outbox%rowtype;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_task_relay');
    select queued.* into iq_row from ingestion_quality.iq_quality_task_outbox queued
     where queued.event_id=requested_event_id for update;
    if requested_receipt_id is null
       or length(requested_receipt_id) not between 1 and 256
       or requested_receipt_id!~'^[A-Za-z0-9._:-]+$' then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_TASK_RECEIPT_INVALID';
    end if;
    if iq_row.status='delivered' and exists (
         select 1 from ingestion_quality.iq_quality_task_delivery delivery
          join ingestion_quality.iq_quality_recovery_task_current task
            on task.task_id=delivery.task_id
           and task.aggregate_version=delivery.route_sequence
          where delivery.task_id=iq_row.task_id
            and delivery.route_sequence=iq_row.route_sequence
            and delivery.status='confirmed'
            and delivery.receipt_id=requested_receipt_id) then
        return true;
    end if;
    update ingestion_quality.iq_quality_task_outbox queued set status='delivered',claimed_at=null
     where queued.event_id=requested_event_id and queued.status='claimed'
       and queued.lease_generation=requested_lease_generation
       and exists (select 1
          from ingestion_quality.iq_quality_task_delivery delivery
          join ingestion_quality.iq_quality_recovery_task_current task
            on task.task_id=delivery.task_id
           and task.aggregate_version=delivery.route_sequence
         where delivery.task_id=iq_row.task_id
           and delivery.route_sequence=iq_row.route_sequence);
    if not found then return false; end if;
    update ingestion_quality.iq_quality_task_delivery delivery set status='confirmed',
        receipt_id=requested_receipt_id,last_error_code=null,next_attempt_at=null,
        lease_owner=null,lease_expires_at=null,updated_at=statement_timestamp()
     where delivery.task_id=iq_row.task_id and delivery.route_sequence=iq_row.route_sequence;
    insert into ingestion_quality.iq_quality_task_delivery_history values (
        iq_row.task_id,'public-task-platform',iq_row.route_sequence,iq_row.attempts,
        'confirmed',requested_receipt_id,null,statement_timestamp());
    -- DELIVERY_STATE_ORTHOGONALITY
    return true;
end
$$;

create function ingestion_quality.iq_fail_quality_task_delivery(
    requested_event_id uuid,requested_lease_generation bigint,requested_error_code varchar)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare iq_row ingestion_quality.iq_quality_task_outbox%rowtype;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_task_relay');
    select queued.* into iq_row from ingestion_quality.iq_quality_task_outbox queued
     where queued.event_id=requested_event_id for update;
    if requested_error_code is null
       or requested_error_code!~'^QUALITY_TASK_[A-Z0-9_]{2,110}$' then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_TASK_RELAY_ERROR_INVALID';
    end if;
    if iq_row.status='failed' and iq_row.last_error_code=requested_error_code
       and exists (select 1
          from ingestion_quality.iq_quality_task_delivery delivery
          join ingestion_quality.iq_quality_recovery_task_current task
            on task.task_id=delivery.task_id
           and task.aggregate_version=delivery.route_sequence
         where delivery.task_id=iq_row.task_id
           and delivery.route_sequence=iq_row.route_sequence) then
        return true;
    end if;
    update ingestion_quality.iq_quality_task_outbox queued set status='failed',claimed_at=null,
        last_error_code=requested_error_code
     where queued.event_id=requested_event_id and queued.status='claimed'
       and queued.lease_generation=requested_lease_generation
       and exists (select 1
          from ingestion_quality.iq_quality_task_delivery delivery
          join ingestion_quality.iq_quality_recovery_task_current task
            on task.task_id=delivery.task_id
           and task.aggregate_version=delivery.route_sequence
         where delivery.task_id=iq_row.task_id
           and delivery.route_sequence=iq_row.route_sequence);
    if not found then return false; end if;
    update ingestion_quality.iq_quality_task_delivery delivery set status='failed',
        receipt_id=null,last_error_code=requested_error_code,next_attempt_at=null,
        lease_owner=null,lease_expires_at=null,updated_at=statement_timestamp()
     where delivery.task_id=iq_row.task_id and delivery.route_sequence=iq_row.route_sequence;
    insert into ingestion_quality.iq_quality_task_delivery_history values (
        iq_row.task_id,'public-task-platform',iq_row.route_sequence,iq_row.attempts,
        'failed',null,requested_error_code,statement_timestamp());
    -- DELIVERY_STATE_ORTHOGONALITY
    return true;
end
$$;

create function ingestion_quality.iq_find_quality_recovery_task_ids(
    requested_source_id varchar,
    requested_status varchar,
    requested_after_occurred_at timestamptz,
    requested_after_task_id uuid,
    requested_limit integer)
returns table(task_id uuid)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_online');
    if requested_source_id is null
       or requested_source_id!~'^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$'
       or requested_status is not null and requested_status<>'open'
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

create function ingestion_quality.iq_find_quality_recovery_task_page(
    requested_task_ids uuid[])
returns table(
    task_id uuid,episode_id uuid,episode_generation bigint,source_id varchar,
    dependency_id varchar,owner_ref varchar,priority varchar,due_at timestamptz,
    status varchar,watermark_utf8 bytea,trigger jsonb,current_evidence jsonb,
    aggregate_version bigint,occurred_at timestamptz,delivery_target varchar,
    delivery_status varchar,delivery_attempt bigint,next_attempt_at timestamptz,
    route_sequence bigint,trace_id character)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
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
           history.aggregate_version,current_fact.occurred_at,
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

create function ingestion_quality.iq_find_quality_recovery_task_page_rules(
    requested_task_ids uuid[])
returns table(task_id uuid,rule_id varchar,rule_version varchar)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_online');
    if coalesce(cardinality(requested_task_ids),0) not between 1 and 101 then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_RECOVERY_TASK_PAGE_INVALID';
    end if;
    return query
    select affected.task_id,affected.rule_id,affected.rule_version
      from unnest(requested_task_ids) with ordinality request(id,ordinal)
      join ingestion_quality.iq_quality_recovery_task_current current_fact
        on current_fact.task_id=request.id
      join ingestion_quality.iq_quality_recovery_task_affected_rule affected
        on affected.task_id=current_fact.task_id
     order by request.ordinal,affected.rule_id,affected.rule_version;
end
$$;

create function ingestion_quality.iq_append_quality_recovery_task_read_audit(
    requested_audit_id uuid,
    requested_task_id uuid,
    requested_aggregate_version bigint,
    requested_actor_search_token varchar,
    requested_source_ip_search_token varchar,
    requested_action varchar,
    requested_trace_id character,
    requested_occurred_at timestamptz,
    requested_payload_digest character)
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_online');
    if requested_actor_search_token !~ '^ast_v1_k[1-9][0-9]*_[0-9a-f]{64}$'
       or requested_source_ip_search_token !~ '^ipt_v1_k[1-9][0-9]*_[0-9a-f]{64}$'
       or requested_action not in (
          'quality-recovery-task-list-read','quality-recovery-task-detail-read')
       or requested_trace_id !~ '^[0-9a-f]{32}$' or requested_trace_id~'^0{32}$'
       or requested_payload_digest !~ '^sha256:[0-9a-f]{64}$'
       or not exists (select 1
          from ingestion_quality.iq_quality_recovery_task_current current_fact
          where current_fact.task_id=requested_task_id
            and current_fact.aggregate_version=requested_aggregate_version) then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_RECOVERY_TASK_READ_AUDIT_INVALID';
    end if;
    insert into ingestion_quality.iq_quality_recovery_task_read_audit(
        audit_id,task_id,aggregate_version,action,outcome,actor_search_token,
        source_ip_search_token,trace_id,payload_digest,occurred_at,expires_at)
    values (requested_audit_id,requested_task_id,requested_aggregate_version,
        requested_action,'accepted',requested_actor_search_token,
        requested_source_ip_search_token,requested_trace_id,requested_payload_digest,
        requested_occurred_at,
        ((requested_occurred_at at time zone 'UTC') + interval '2 years') at time zone 'UTC');
end
$$;

create function ingestion_quality.iq_cleanup_quality_fuse_expired(
    trusted_cutoff timestamptz)
returns bigint
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_episode_ids uuid[];
    iq_task_ids uuid[];
    iq_deleted bigint:=0;
    iq_count bigint;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_retention_executor');
    if trusted_cutoff is null or trusted_cutoff>statement_timestamp() then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_RETENTION_CUTOFF_INVALID';
    end if;

    delete from ingestion_quality.iq_quality_fuse_idempotency idempotency
     where idempotency.expires_at<=trusted_cutoff and not idempotency.legal_hold
       and not exists (select 1
         from ingestion_quality.iq_quality_fuse_episode_history episode
        where episode.episode_id=idempotency.episode_id and episode.legal_hold);
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;

    delete from ingestion_quality.iq_quality_task_outbox queued
     where queued.status in ('delivered','failed')
       and queued.retention_due_at<=trusted_cutoff and not queued.legal_hold
       and not exists (select 1
         from ingestion_quality.iq_quality_recovery_task_current task
         join ingestion_quality.iq_quality_fuse_episode_history episode
           on episode.episode_id=task.episode_id
        where task.task_id=queued.task_id and episode.legal_hold);
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;

    delete from ingestion_quality.iq_quality_task_delivery_history delivery_history
     where delivery_history.retention_due_at<=trusted_cutoff
       and not delivery_history.legal_hold
       and not exists (select 1
         from ingestion_quality.iq_quality_recovery_task_current task
         join ingestion_quality.iq_quality_fuse_episode_history episode
           on episode.episode_id=task.episode_id
        where task.task_id=delivery_history.task_id and episode.legal_hold);
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;

    delete from ingestion_quality.iq_quality_task_delivery delivery
     using ingestion_quality.iq_quality_recovery_task_current task
     where task.task_id=delivery.task_id
       and delivery.status in ('confirmed','failed')
       and ((delivery.updated_at at time zone 'UTC') + interval '90 days')
           at time zone 'UTC'<=trusted_cutoff
       and not exists (select 1 from ingestion_quality.iq_quality_task_outbox queued
         where queued.task_id=delivery.task_id)
       and not exists (select 1
         from ingestion_quality.iq_quality_fuse_episode_history episode
        where episode.episode_id=task.episode_id and episode.legal_hold);
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;

    delete from ingestion_quality.iq_quality_recovery_task_read_audit read_audit
     where read_audit.expires_at<=trusted_cutoff and not read_audit.legal_hold
       and not exists (select 1
         from ingestion_quality.iq_quality_recovery_task_history task_history
        where task_history.task_id=read_audit.task_id and task_history.legal_hold);
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;

    delete from ingestion_quality.iq_quality_fuse_rejection_audit rejection
     where rejection.retention_due_at<=trusted_cutoff and not rejection.legal_hold;
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;

    select array_agg(current_episode.episode_id),array_agg(task.task_id)
      into iq_episode_ids,iq_task_ids
      from ingestion_quality.iq_quality_fuse_episode_current current_episode
      join ingestion_quality.iq_quality_recovery_task_current task
        on task.episode_id=current_episode.episode_id
     where not current_episode.active
       and not exists (select 1
         from ingestion_quality.iq_quality_fuse_episode_history episode_history
        where episode_history.episode_id=current_episode.episode_id
          and (episode_history.legal_hold
            or episode_history.retention_due_at>trusted_cutoff))
       and not exists (select 1
         from ingestion_quality.iq_quality_recovery_task_history task_history
        where task_history.task_id=task.task_id
          and (task_history.legal_hold
            or task_history.retention_due_at>trusted_cutoff))
       and not exists (select 1 from ingestion_quality.iq_quality_task_outbox queued
         where queued.task_id=task.task_id)
       and not exists (select 1 from ingestion_quality.iq_quality_task_delivery delivery
         where delivery.task_id=task.task_id)
       and not exists (select 1
         from ingestion_quality.iq_quality_recovery_task_read_audit read_audit
        where read_audit.task_id=task.task_id);

    delete from ingestion_quality.iq_quality_recovery_task_affected_rule
     where task_id=any(coalesce(iq_task_ids,array[]::uuid[]));
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;
    delete from ingestion_quality.iq_quality_recovery_task_current
     where task_id=any(coalesce(iq_task_ids,array[]::uuid[]));
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;
    delete from ingestion_quality.iq_quality_recovery_task_history
     where task_id=any(coalesce(iq_task_ids,array[]::uuid[])) and not legal_hold
       and retention_due_at<=trusted_cutoff;
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;
    delete from ingestion_quality.iq_quality_fuse_audit
     where episode_id=any(coalesce(iq_episode_ids,array[]::uuid[]))
       and not legal_hold and retention_due_at<=trusted_cutoff;
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;
    delete from ingestion_quality.iq_quality_fuse_episode_current
     where episode_id=any(coalesce(iq_episode_ids,array[]::uuid[]));
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;
    delete from ingestion_quality.iq_quality_fuse_episode_history
     where episode_id=any(coalesce(iq_episode_ids,array[]::uuid[]))
       and not legal_hold and retention_due_at<=trusted_cutoff;
    get diagnostics iq_count=row_count; iq_deleted:=iq_deleted+iq_count;
    return iq_deleted;
end
$$;

alter table ingestion_quality.iq_quality_fuse_episode_history owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_fuse_episode_current owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_task_history owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_task_current owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_task_affected_rule owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_task_delivery owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_task_delivery_history owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_task_outbox owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_fuse_idempotency owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_fuse_audit owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_fuse_rejection_audit owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_recovery_task_read_audit owner to scholarsense_ingestion_quality_batch_owner;

revoke all privileges on table
    ingestion_quality.iq_quality_fuse_episode_history,
    ingestion_quality.iq_quality_fuse_episode_current,
    ingestion_quality.iq_quality_recovery_task_history,
    ingestion_quality.iq_quality_recovery_task_current,
    ingestion_quality.iq_quality_recovery_task_affected_rule,
    ingestion_quality.iq_quality_task_delivery,
    ingestion_quality.iq_quality_task_delivery_history,
    ingestion_quality.iq_quality_task_outbox,
    ingestion_quality.iq_quality_fuse_idempotency,
    ingestion_quality.iq_quality_fuse_audit,
    ingestion_quality.iq_quality_fuse_rejection_audit,
    ingestion_quality.iq_quality_recovery_task_read_audit
from public,scholarsense_ingestion_quality_online,
    scholarsense_ingestion_quality_eligibility_consumer,
    scholarsense_ingestion_quality_task_relay;

do $$
declare iq_function regprocedure;
begin
  for iq_function in
    select procedure.oid::regprocedure from pg_catalog.pg_proc procedure
     where procedure.pronamespace='ingestion_quality'::regnamespace
       and procedure.proname=any(array[
         'iq_guard_quality_fuse_history',
         'iq_quality_public_task_reason',
         'iq_quality_public_affected_rules',
         'iq_quality_trigger_reason',
         'iq_append_quality_fuse_rejection_audit',
         'iq_find_quality_snapshot_evidence_v2',
         'iq_load_quality_eligibility_processing_state_v2',
         'iq_accept_quality_eligibility_event_v2',
         'iq_claim_next_quality_task_outbox',
         'iq_authorize_quality_task_send',
         'iq_mark_quality_task_delivery_retry',
         'iq_complete_quality_task_delivery',
         'iq_fail_quality_task_delivery',
         'iq_find_quality_recovery_task_ids',
         'iq_find_quality_recovery_task_page',
         'iq_find_quality_recovery_task_page_rules',
         'iq_append_quality_recovery_task_read_audit',
         'iq_cleanup_quality_fuse_expired'])
  loop
    execute format('revoke all on function %s from public',iq_function);
    execute format('alter function %s owner to scholarsense_ingestion_quality_batch_owner',
        iq_function);
  end loop;
end
$$;

revoke all on function ingestion_quality.iq_require_exclusive_workload(name) from public;
revoke all on function ingestion_quality.iq_find_quality_snapshot_evidence_v2(
    uuid,uuid,character) from public;
revoke all on function ingestion_quality.iq_load_quality_eligibility_processing_state_v2(
    uuid,varchar) from public;
revoke all on function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    uuid,varchar,bigint,character,jsonb) from public;
revoke all on function ingestion_quality.iq_append_quality_fuse_rejection_audit(
    uuid,bigint,varchar,character) from public;
revoke all on function ingestion_quality.iq_claim_next_quality_task_outbox() from public;
revoke all on function ingestion_quality.iq_authorize_quality_task_send(
    uuid,bigint,bigint) from public;
revoke all on function ingestion_quality.iq_mark_quality_task_delivery_retry(
    uuid,bigint,varchar) from public;
revoke all on function ingestion_quality.iq_complete_quality_task_delivery(
    uuid,bigint,varchar) from public;
revoke all on function ingestion_quality.iq_fail_quality_task_delivery(
    uuid,bigint,varchar) from public;
revoke all on function ingestion_quality.iq_find_quality_recovery_task_ids(
    varchar,varchar,timestamptz,uuid,integer) from public;
revoke all on function ingestion_quality.iq_find_quality_recovery_task_page(uuid[]) from public;
revoke all on function ingestion_quality.iq_find_quality_recovery_task_page_rules(uuid[]) from public;
revoke all on function ingestion_quality.iq_append_quality_recovery_task_read_audit(
    uuid,uuid,bigint,varchar,varchar,varchar,character,timestamptz,character) from public;
revoke all on function ingestion_quality.iq_cleanup_quality_fuse_expired(timestamptz)
    from public;

grant execute on function ingestion_quality.iq_find_quality_snapshot_evidence_v2(
    uuid,uuid,character) to scholarsense_ingestion_quality_eligibility_consumer;
grant execute on function ingestion_quality.iq_load_quality_eligibility_processing_state_v2(
    uuid,varchar) to scholarsense_ingestion_quality_eligibility_consumer;
grant execute on function ingestion_quality.iq_accept_quality_eligibility_event_v2(
    uuid,varchar,bigint,character,jsonb)
    to scholarsense_ingestion_quality_eligibility_consumer;
grant execute on function ingestion_quality.iq_append_quality_fuse_rejection_audit(
    uuid,bigint,varchar,character)
    to scholarsense_ingestion_quality_eligibility_consumer;
grant execute on function ingestion_quality.iq_claim_next_quality_task_outbox()
    to scholarsense_ingestion_quality_task_relay;
grant execute on function ingestion_quality.iq_authorize_quality_task_send(
    uuid,bigint,bigint) to scholarsense_ingestion_quality_task_relay;
grant execute on function ingestion_quality.iq_mark_quality_task_delivery_retry(
    uuid,bigint,varchar) to scholarsense_ingestion_quality_task_relay;
grant execute on function ingestion_quality.iq_complete_quality_task_delivery(
    uuid,bigint,varchar) to scholarsense_ingestion_quality_task_relay;
grant execute on function ingestion_quality.iq_fail_quality_task_delivery(
    uuid,bigint,varchar) to scholarsense_ingestion_quality_task_relay;
grant execute on function ingestion_quality.iq_find_quality_recovery_task_ids(
    varchar,varchar,timestamptz,uuid,integer)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_find_quality_recovery_task_page(uuid[])
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_find_quality_recovery_task_page_rules(uuid[])
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_append_quality_recovery_task_read_audit(
    uuid,uuid,bigint,varchar,varchar,varchar,character,timestamptz,character)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_cleanup_quality_fuse_expired(timestamptz)
    to scholarsense_ingestion_quality_retention_executor;
