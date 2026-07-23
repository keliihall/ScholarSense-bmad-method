-- Story 1.5: rebuildable authorized search projection and audit-domain retention evidence.
-- The online ledger remains append-only; no production deletion capability is introduced here.

create table audit_operations.ao_audit_search_projection (
    ledger_sequence bigint primary key,
    record_id uuid not null unique,
    occurred_at timestamptz not null,
    outcome varchar(32) not null,
    fact_schema_version varchar(64) not null,
    policy_version varchar(64) not null,
    retention_schedule_version varchar(64) not null,
    actor_search_token varchar(96),
    object_type varchar(64),
    object_search_token varchar(96),
    action varchar(128) not null,
    trace_id char(32) not null,
    producer_module varchar(64) not null,
    event_type varchar(128) not null,
    reason_code varchar(128) not null,
    business_action_category varchar(64) not null,
    business_object_category varchar(64),
    role_package_summary varchar(128),
    projection_scope varchar(64),
    source_network_recorded boolean not null,
    constraint ao_audit_search_projection_versions_ck check (
        fact_schema_version = 'LOCAL-AUDIT-FACT-1.0.0'
        and retention_schedule_version = 'RS-1.0.0'
    ),
    constraint ao_audit_search_projection_shape_ck check (
        ledger_sequence >= 1
        and trace_id ~ '^[0-9a-f]{32}$'
        and action ~ '^[a-z][a-z0-9.-]{2,127}$'
        and (actor_search_token is null or actor_search_token ~ '^ast_v1_k[0-9]+_[0-9a-f]{64}$')
        and (object_search_token is null or object_search_token ~ '^ost_v1_k[0-9]+_[0-9a-f]{64}$')
    )
);

create table audit_operations.ao_audit_search_projection_watermark (
    singleton_id smallint primary key,
    ledger_sequence bigint not null,
    projected_at timestamptz not null,
    constraint ao_audit_search_projection_watermark_singleton_ck check (singleton_id = 1),
    constraint ao_audit_search_projection_watermark_sequence_ck check (ledger_sequence >= 0)
);

insert into audit_operations.ao_audit_search_projection (
    ledger_sequence, record_id, occurred_at, outcome, fact_schema_version,
    policy_version, retention_schedule_version, actor_search_token, object_type,
    object_search_token, action, trace_id, producer_module, event_type, reason_code,
    business_action_category, business_object_category, role_package_summary,
    projection_scope, source_network_recorded)
select
    ledger_sequence,
    audit_id,
    (payload ->> 'occurredAt')::timestamptz,
    payload ->> 'outcome',
    fact_schema_version,
    coalesce(payload -> 'authorizationContext' ->> 'policyVersion', 'RFP-1.0.0'),
    retention_schedule_version,
    payload ->> 'actorSearchToken',
    payload ->> 'objectType',
    payload ->> 'objectSearchToken',
    payload ->> 'action',
    trace_id,
    producer_module,
    event_type,
    payload ->> 'reasonCode',
    split_part(payload ->> 'action', '.', 1),
    payload ->> 'objectType',
    nullif(payload -> 'roleIds' ->> 0, ''),
    payload ->> 'projectionScope',
    payload ->> 'sourceIpSearchToken' is not null
from audit_operations.ao_audit_ledger
order by ledger_sequence;

insert into audit_operations.ao_audit_search_projection_watermark (
    singleton_id, ledger_sequence, projected_at)
select 1, ledger_sequence, clock_timestamp()
from audit_operations.ao_audit_ledger_head
where singleton_id = 1;

create table audit_operations.ao_local_audit_fact (
    audit_id uuid primary key,
    fact jsonb not null,
    filter_category_digest char(64) not null,
    created_at timestamptz not null,
    constraint ao_local_audit_fact_shape_ck check (
        jsonb_typeof(fact) = 'object'
        and filter_category_digest ~ '^[0-9a-f]{64}$'
        and not (fact ?| array['actorRef', 'objectRef', 'actorSearchToken', 'objectSearchToken'])
    )
);

create table audit_operations.ao_local_audit_outbox (
    event_id uuid primary key,
    audit_id uuid not null unique,
    event_type varchar(128) not null,
    schema_version varchar(64) not null,
    envelope jsonb not null,
    delivery_status varchar(32) not null default 'pending',
    attempts bigint not null default 0,
    next_attempt_at timestamptz not null,
    claimed_at timestamptz,
    confirmed_at timestamptz,
    last_error_code varchar(128),
    created_at timestamptz not null,
    foreign key (audit_id) references audit_operations.ao_local_audit_fact(audit_id),
    constraint ao_local_audit_outbox_shape_ck check (
        jsonb_typeof(envelope) = 'object'
        and delivery_status in ('pending', 'retrying', 'confirmed', 'failed', 'quarantine')
        and attempts >= 0
    )
);

create table audit_operations.ao_audit_search_csrf_proof (
    browser_session_digest char(64) not null,
    proof_digest char(64) not null,
    consumed_at timestamptz not null,
    expires_at timestamptz not null,
    primary key (browser_session_digest, proof_digest),
    constraint ao_audit_search_csrf_proof_shape_ck check (
        browser_session_digest ~ '^[0-9a-f]{64}$'
        and proof_digest ~ '^[0-9a-f]{64}$'
        and expires_at > consumed_at
    )
);

create table audit_operations.ao_archive_manifest (
    manifest_id uuid primary key,
    schedule_version varchar(64) not null,
    scope_type varchar(32) not null,
    fixture_id varchar(128) not null,
    scope_hash char(64) not null,
    sequence_start bigint not null,
    sequence_end bigint not null,
    record_count bigint not null,
    first_previous_hash char(64) not null,
    last_entry_hash char(64) not null,
    archive_object_id varchar(256) not null,
    archive_object_version_id varchar(256) not null,
    content_digest char(64) not null,
    created_by varchar(128) not null,
    trusted_created_at timestamptz not null,
    trace_id char(32) not null,
    read_back_verified boolean not null,
    retention_until timestamptz not null,
    legal_hold boolean not null default false,
    constraint ao_archive_manifest_object_version_uq unique (archive_object_id, archive_object_version_id),
    constraint ao_archive_manifest_scope_ck check (scope_type = 'audit-domain' and fixture_id <> ''),
    constraint ao_archive_manifest_range_ck check (
        sequence_start >= 1 and sequence_end >= sequence_start
        and record_count = sequence_end - sequence_start + 1
        and retention_until > trusted_created_at
    )
);

create table audit_operations.ao_legal_hold (
    hold_id uuid primary key,
    aggregate_version bigint not null,
    purpose varchar(256) not null,
    scope_type varchar(32) not null,
    scope_value varchar(256) not null,
    authority varchar(256) not null,
    start_at timestamptz not null,
    end_at timestamptz not null,
    review_at timestamptz not null,
    created_at timestamptz not null,
    constraint ao_legal_hold_window_ck check (
        start_at < end_at and review_at >= start_at and review_at < end_at
    ),
    constraint ao_legal_hold_version_ck check (aggregate_version >= 1)
);

create table audit_operations.ao_retention_execution (
    execution_id uuid primary key,
    schedule_version varchar(64) not null,
    scope_type varchar(32) not null,
    fixture_id varchar(128) not null,
    scope_hash char(64) not null,
    as_of_sequence bigint not null,
    request_digest char(64) not null,
    state varchar(32) not null,
    attempt_no bigint not null,
    fencing_token bigint not null,
    aggregate_version bigint not null,
    source_ledger_head bigint not null,
    projection_watermark bigint not null,
    archive_digest char(64),
    non_production_evidence boolean not null,
    unmet_guards jsonb not null,
    trace_id char(32) not null,
    trusted_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ao_retention_execution_idempotency_uq unique (
        execution_id, schedule_version, scope_hash, as_of_sequence, request_digest),
    constraint ao_retention_execution_state_ck check (
        state in ('queued', 'running', 'succeeded', 'failed', 'blocked', 'cancelled')
    ),
    constraint ao_retention_execution_boundary_ck check (
        schedule_version = 'RS-1.0.0'
        and scope_type = 'audit-domain'
        and fixture_id <> ''
        and non_production_evidence
        and as_of_sequence >= 0
        and source_ledger_head >= as_of_sequence
        and projection_watermark >= as_of_sequence
        and attempt_no >= 1 and fencing_token >= 1 and aggregate_version >= 1
        and jsonb_typeof(unmet_guards) = 'array'
    )
);

create table audit_operations.ao_retention_execution_step (
    execution_id uuid not null,
    step_no integer not null,
    step_name varchar(64) not null,
    status varchar(32) not null,
    evidence_reference varchar(256),
    error_code varchar(128),
    trusted_at timestamptz not null,
    primary key (execution_id, step_no),
    foreign key (execution_id) references audit_operations.ao_retention_execution(execution_id),
    constraint ao_retention_execution_step_status_ck check (
        status in ('pending', 'succeeded', 'failed', 'blocked') and step_no >= 1
    )
);

create index ao_audit_search_projection_sort_idx
    on audit_operations.ao_audit_search_projection (occurred_at desc, ledger_sequence desc);
create index ao_audit_search_projection_actor_idx
    on audit_operations.ao_audit_search_projection (actor_search_token, occurred_at desc, ledger_sequence desc);
create index ao_audit_search_projection_object_idx
    on audit_operations.ao_audit_search_projection (object_type, object_search_token, occurred_at desc, ledger_sequence desc);
create index ao_audit_search_projection_action_outcome_idx
    on audit_operations.ao_audit_search_projection (action, outcome, occurred_at desc, ledger_sequence desc);
create index ao_audit_search_projection_trace_idx
    on audit_operations.ao_audit_search_projection (trace_id, occurred_at desc, ledger_sequence desc);
create index ao_local_audit_outbox_delivery_idx
    on audit_operations.ao_local_audit_outbox (delivery_status, next_attempt_at, event_id);
create index ao_audit_search_csrf_proof_expiry_idx
    on audit_operations.ao_audit_search_csrf_proof (expires_at);
create index ao_legal_hold_scope_window_idx
    on audit_operations.ao_legal_hold (scope_type, scope_value, start_at, end_at);
create index ao_retention_execution_state_idx
    on audit_operations.ao_retention_execution (state, updated_at, execution_id);

do $migration$
begin
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'scholarsense_audit_search') then
        create role scholarsense_audit_search nologin;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'scholarsense_audit_retention_executor') then
        create role scholarsense_audit_retention_executor nologin;
    end if;
end
$migration$;

revoke all privileges on audit_operations.ao_audit_search_projection,
    audit_operations.ao_audit_search_projection_watermark,
    audit_operations.ao_local_audit_fact,
    audit_operations.ao_local_audit_outbox,
    audit_operations.ao_audit_search_csrf_proof,
    audit_operations.ao_archive_manifest,
    audit_operations.ao_legal_hold,
    audit_operations.ao_retention_execution,
    audit_operations.ao_retention_execution_step
from public, scholarsense_audit_ledger_writer, scholarsense_audit_verifier,
    scholarsense_audit_online, scholarsense_audit_search,
    scholarsense_audit_retention_executor;

grant usage on schema audit_operations
    to scholarsense_audit_search, scholarsense_audit_retention_executor;

grant select on audit_operations.ao_audit_search_projection,
    audit_operations.ao_audit_search_projection_watermark,
    audit_operations.ao_retention_execution,
    audit_operations.ao_retention_execution_step
to scholarsense_audit_search;

grant insert on audit_operations.ao_audit_search_projection
    to scholarsense_audit_ledger_writer;
grant select, update (ledger_sequence, projected_at)
    on audit_operations.ao_audit_search_projection_watermark
    to scholarsense_audit_ledger_writer;

grant select, insert on audit_operations.ao_local_audit_fact,
    audit_operations.ao_local_audit_outbox
to scholarsense_audit_online;
grant select, insert, delete on audit_operations.ao_audit_search_csrf_proof
to scholarsense_audit_online;
grant update (delivery_status, attempts, next_attempt_at, claimed_at, confirmed_at, last_error_code)
    on audit_operations.ao_local_audit_outbox to scholarsense_audit_online;

grant select on audit_operations.ao_audit_ledger,
    audit_operations.ao_audit_ledger_head,
    audit_operations.ao_integrity_finding,
    audit_operations.ao_verification_run
to scholarsense_audit_retention_executor;
grant select, insert on audit_operations.ao_archive_manifest,
    audit_operations.ao_legal_hold,
    audit_operations.ao_retention_execution,
    audit_operations.ao_retention_execution_step
to scholarsense_audit_retention_executor;
grant update (aggregate_version, end_at, review_at)
    on audit_operations.ao_legal_hold to scholarsense_audit_retention_executor;
grant update (state, attempt_no, fencing_token, aggregate_version, source_ledger_head,
    projection_watermark, archive_digest, unmet_guards, trace_id, trusted_at, updated_at)
    on audit_operations.ao_retention_execution to scholarsense_audit_retention_executor;
