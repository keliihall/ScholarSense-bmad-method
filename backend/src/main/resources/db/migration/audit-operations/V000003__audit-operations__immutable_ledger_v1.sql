-- Story 1.4: audit-owned online tamper-evident ledger. This is not a WORM archive.
create schema if not exists audit_operations;

create table audit_operations.ao_audit_ledger (
    ledger_sequence bigint primary key,
    audit_id uuid not null,
    source_event_id uuid not null,
    previous_hash char(64) not null,
    entry_hash char(64) not null,
    hash_profile_version varchar(64) not null,
    producer_module varchar(64) not null,
    event_type varchar(128) not null,
    event_schema_version varchar(64) not null,
    fact_schema_version varchar(64) not null,
    source_created_at timestamptz not null,
    collected_at timestamptz not null,
    trace_id char(32) not null,
    aggregate_version bigint,
    payload_fingerprint char(64) not null,
    payload jsonb not null,
    retention_schedule_version varchar(64) not null,
    constraint ao_audit_ledger_sequence_ck check (ledger_sequence >= 1),
    constraint ao_audit_ledger_audit_id_uq unique (audit_id),
    constraint ao_audit_ledger_source_event_id_uq unique (source_event_id),
    constraint ao_audit_ledger_uuid_v7_ck check (
        substring(audit_id::text, 15, 1) = '7'
        and substring(audit_id::text, 20, 1) ~ '^[89ab]$'
        and substring(source_event_id::text, 15, 1) = '7'
        and substring(source_event_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ao_audit_ledger_hash_ck check (
        previous_hash ~ '^[0-9a-f]{64}$'
        and entry_hash ~ '^[0-9a-f]{64}$'
        and payload_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    constraint ao_audit_ledger_version_ck check (
        hash_profile_version = 'AUDIT-LEDGER-HASH-1.0.0'
        and event_schema_version = 'LOCAL-AUDIT-OUTBOX-1.0.0'
        and fact_schema_version = 'LOCAL-AUDIT-FACT-1.0.0'
        and retention_schedule_version = 'RS-1.0.0'
    ),
    constraint ao_audit_ledger_shape_ck check (
        producer_module ~ '^[a-z][a-z0-9-]{2,63}$'
        and event_type ~ '^[a-z][a-z0-9.-]{2,127}$'
        and trace_id ~ '^[0-9a-f]{32}$'
        and (aggregate_version is null or aggregate_version >= 1)
        and jsonb_typeof(payload) = 'object'
        and collected_at >= source_created_at
    )
);

create table audit_operations.ao_audit_ledger_head (
    singleton_id smallint primary key,
    ledger_sequence bigint not null,
    entry_hash char(64) not null,
    updated_at timestamptz not null,
    constraint ao_audit_ledger_head_singleton_ck check (singleton_id = 1),
    constraint ao_audit_ledger_head_sequence_ck check (ledger_sequence >= 0),
    constraint ao_audit_ledger_head_hash_ck check (entry_hash ~ '^[0-9a-f]{64}$')
);

insert into audit_operations.ao_audit_ledger_head (
    singleton_id, ledger_sequence, entry_hash, updated_at)
values (1, 0, repeat('0', 64), '1970-01-01T00:00:00Z');

create table audit_operations.ao_ingestion_receipt (
    receipt_id uuid primary key,
    audit_id uuid not null,
    source_event_id uuid not null,
    payload_fingerprint char(64) not null,
    outcome varchar(32) not null,
    ledger_sequence bigint,
    entry_hash char(64),
    duplicate_observed_count bigint not null default 0,
    first_observed_at timestamptz not null,
    last_observed_at timestamptz not null,
    trace_id char(32) not null,
    constraint ao_ingestion_receipt_pair_uq unique (audit_id, source_event_id),
    constraint ao_ingestion_receipt_outcome_ck check (
        outcome in ('appended', 'exact-duplicate', 'collision', 'rejected')
    ),
    constraint ao_ingestion_receipt_shape_ck check (
        payload_fingerprint ~ '^[0-9a-f]{64}$'
        and duplicate_observed_count >= 0
        and last_observed_at >= first_observed_at
        and trace_id ~ '^[0-9a-f]{32}$'
        and ((outcome in ('appended', 'exact-duplicate'))
            = (ledger_sequence is not null and entry_hash is not null))
    )
);

create table audit_operations.ao_verification_run (
    run_id uuid primary key,
    mode varchar(32) not null,
    start_sequence bigint not null,
    end_sequence bigint not null,
    verified_head_sequence bigint not null,
    verified_head_hash char(64) not null,
    healthy boolean not null,
    started_at timestamptz not null,
    completed_at timestamptz not null,
    trace_id char(32) not null,
    constraint ao_verification_run_mode_ck check (mode in ('incremental', 'full-chain')),
    constraint ao_verification_run_range_ck check (
        start_sequence >= 0 and end_sequence >= start_sequence
        and verified_head_sequence >= 0 and completed_at >= started_at
    )
);

create table audit_operations.ao_integrity_finding (
    finding_id uuid primary key,
    code varchar(96) not null,
    severity varchar(16) not null,
    policy_version varchar(64) not null,
    hash_profile_version varchar(64) not null,
    sequence_start bigint,
    sequence_end bigint,
    source_range varchar(160),
    safe_digest char(64) not null,
    trace_id char(32) not null,
    occurred_at timestamptz not null,
    detected_at timestamptz not null,
    runbook_ref varchar(128) not null,
    constraint ao_integrity_finding_code_ck check (code in (
        'AUDIT_LEDGER_SEQUENCE_GAP',
        'AUDIT_LEDGER_PREVIOUS_HASH_MISMATCH',
        'AUDIT_LEDGER_ENTRY_HASH_MISMATCH',
        'AUDIT_LEDGER_HEAD_MISMATCH',
        'AUDIT_INGESTION_DUPLICATE_CONFLICT',
        'AUDIT_INGESTION_CONTRACT_REJECTED',
        'AUDIT_INGESTION_BACKLOG'
    )),
    constraint ao_integrity_finding_severity_ck check (severity in ('warning', 'critical')),
    constraint ao_integrity_finding_range_ck check (
        (sequence_start is null and sequence_end is null)
        or (sequence_start >= 1 and sequence_end >= sequence_start)
    ),
    constraint ao_integrity_finding_safe_ck check (
        safe_digest ~ '^[0-9a-f]{64}$'
        and trace_id ~ '^[0-9a-f]{32}$'
        and runbook_ref ~ '^runbook://audit/[a-z0-9-]{3,80}$'
        and detected_at >= occurred_at
    )
);

create table audit_operations.ao_finding_disposition (
    disposition_id uuid primary key,
    finding_id uuid not null,
    disposition varchar(32) not null,
    disposition_digest char(64) not null,
    disposed_at timestamptz not null,
    trace_id char(32) not null,
    foreign key (finding_id) references audit_operations.ao_integrity_finding(finding_id),
    constraint ao_finding_disposition_value_ck check (
        disposition in ('acknowledged', 'resolved', 'false-positive')
    )
);

create table audit_operations.ao_alert_outbox (
    alert_id uuid primary key,
    finding_id uuid not null,
    event_type varchar(32) not null,
    deduplication_key char(64) not null,
    safe_payload jsonb not null,
    delivery_status varchar(32) not null default 'pending',
    attempts integer not null default 0,
    next_attempt_at timestamptz not null,
    claimed_at timestamptz,
    last_error_code varchar(128),
    created_at timestamptz not null,
    foreign key (finding_id) references audit_operations.ao_integrity_finding(finding_id),
    constraint ao_alert_outbox_event_ck check (event_type in ('active', 'resolved')),
    constraint ao_alert_outbox_delivery_ck check (
        delivery_status in ('pending', 'retrying', 'confirmed', 'failed')
        and attempts >= 0 and jsonb_typeof(safe_payload) = 'object'
    )
);

create table audit_operations.ao_availability_observation (
    observation_id uuid primary key,
    state varchar(16) not null,
    policy_version varchar(64) not null,
    reason_codes jsonb not null,
    unconfirmed_count bigint,
    oldest_unconfirmed_age_seconds bigint,
    chain_healthy boolean,
    measured_at timestamptz not null,
    fresh_until timestamptz not null,
    trace_id char(32) not null,
    recovery_healthy_count integer not null,
    constraint ao_availability_observation_state_ck check (
        state in ('healthy', 'degraded', 'blocked')
    ),
    constraint ao_availability_observation_shape_ck check (
        policy_version = 'AUDIT-INGESTION-POLICY-1.0.0'
        and jsonb_typeof(reason_codes) = 'array'
        and (unconfirmed_count is null or unconfirmed_count >= 0)
        and (oldest_unconfirmed_age_seconds is null or oldest_unconfirmed_age_seconds >= 0)
        and recovery_healthy_count between 0 and 1
        and (state <> 'healthy' or recovery_healthy_count = 0)
        and fresh_until >= measured_at and trace_id ~ '^[0-9a-f]{32}$'
    )
);

create index ao_alert_outbox_delivery_idx
    on audit_operations.ao_alert_outbox (delivery_status, next_attempt_at, alert_id);
create index ao_integrity_finding_code_idx
    on audit_operations.ao_integrity_finding (code, detected_at, finding_id);

create unique index ao_integrity_finding_contract_rejection_uq
    on audit_operations.ao_integrity_finding (code, source_range, safe_digest)
    where code = 'AUDIT_INGESTION_CONTRACT_REJECTED';
create index ao_availability_observation_time_idx
    on audit_operations.ao_availability_observation (measured_at desc, observation_id);

do $migration$
begin
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'scholarsense_audit_ledger_writer') then
        create role scholarsense_audit_ledger_writer nologin;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'scholarsense_audit_verifier') then
        create role scholarsense_audit_verifier nologin;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'scholarsense_audit_alert_delivery') then
        create role scholarsense_audit_alert_delivery nologin;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'scholarsense_audit_online') then
        create role scholarsense_audit_online nologin;
    end if;
end
$migration$;

revoke all privileges on audit_operations.ao_audit_ledger
    from public, scholarsense_audit_ledger_writer, scholarsense_audit_verifier,
    scholarsense_audit_alert_delivery, scholarsense_audit_online;
revoke all privileges on audit_operations.ao_audit_ledger_head
    from public, scholarsense_audit_ledger_writer, scholarsense_audit_verifier,
    scholarsense_audit_alert_delivery, scholarsense_audit_online;
revoke all privileges on audit_operations.ao_ingestion_receipt
    from public, scholarsense_audit_ledger_writer, scholarsense_audit_verifier,
    scholarsense_audit_alert_delivery, scholarsense_audit_online;
revoke all privileges on audit_operations.ao_verification_run
    from public, scholarsense_audit_ledger_writer, scholarsense_audit_verifier,
    scholarsense_audit_alert_delivery, scholarsense_audit_online;
revoke all privileges on audit_operations.ao_integrity_finding
    from public, scholarsense_audit_ledger_writer, scholarsense_audit_verifier,
    scholarsense_audit_alert_delivery, scholarsense_audit_online;
revoke all privileges on audit_operations.ao_finding_disposition
    from public, scholarsense_audit_ledger_writer, scholarsense_audit_verifier,
    scholarsense_audit_alert_delivery, scholarsense_audit_online;
revoke all privileges on audit_operations.ao_alert_outbox
    from public, scholarsense_audit_ledger_writer, scholarsense_audit_verifier,
    scholarsense_audit_alert_delivery, scholarsense_audit_online;
revoke all privileges on audit_operations.ao_availability_observation
    from public, scholarsense_audit_ledger_writer, scholarsense_audit_verifier,
    scholarsense_audit_alert_delivery, scholarsense_audit_online;

grant usage on schema audit_operations
    to scholarsense_audit_ledger_writer, scholarsense_audit_verifier,
    scholarsense_audit_alert_delivery, scholarsense_audit_online;
grant select, insert on audit_operations.ao_audit_ledger
    to scholarsense_audit_ledger_writer;
grant select on audit_operations.ao_audit_ledger
    to scholarsense_audit_verifier;
grant select on audit_operations.ao_audit_ledger_head
    to scholarsense_audit_ledger_writer, scholarsense_audit_verifier;
grant update (ledger_sequence, entry_hash, updated_at)
    on audit_operations.ao_audit_ledger_head to scholarsense_audit_ledger_writer;
grant select, insert on audit_operations.ao_ingestion_receipt
    to scholarsense_audit_ledger_writer;
grant update (outcome, ledger_sequence, entry_hash, duplicate_observed_count, last_observed_at, trace_id)
    on audit_operations.ao_ingestion_receipt to scholarsense_audit_ledger_writer;
grant select, insert on audit_operations.ao_verification_run
    to scholarsense_audit_verifier;
grant select, insert on audit_operations.ao_integrity_finding
    to scholarsense_audit_ledger_writer, scholarsense_audit_verifier;
grant select on audit_operations.ao_finding_disposition
    to scholarsense_audit_verifier;
grant select, insert on audit_operations.ao_alert_outbox
    to scholarsense_audit_ledger_writer, scholarsense_audit_verifier;
grant select on audit_operations.ao_alert_outbox
    to scholarsense_audit_alert_delivery;
grant update (delivery_status, attempts, next_attempt_at, claimed_at, last_error_code)
    on audit_operations.ao_alert_outbox to scholarsense_audit_alert_delivery;
grant select, insert on audit_operations.ao_availability_observation
    to scholarsense_audit_ledger_writer, scholarsense_audit_verifier;
grant select on audit_operations.ao_availability_observation
    to scholarsense_audit_online;
