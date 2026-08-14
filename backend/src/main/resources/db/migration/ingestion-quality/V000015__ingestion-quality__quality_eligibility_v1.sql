do $$ begin
    create role scholarsense_ingestion_quality_eligibility_consumer nologin;
exception when duplicate_object then null;
end $$;
alter role scholarsense_ingestion_quality_eligibility_consumer
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
grant usage on schema ingestion_quality
    to scholarsense_ingestion_quality_eligibility_consumer;
revoke create on schema ingestion_quality
    from scholarsense_ingestion_quality_eligibility_consumer;

-- Frozen owner-local projection of RULE-DEPENDENCY-REGISTRY-1.0.0.
create table ingestion_quality.iq_rule_dependency_registry (
    registry_version varchar(64) primary key check (
        registry_version = 'RULE-DEPENDENCY-REGISTRY-1.0.0'),
    registry_digest char(71) not null check (
        registry_digest ~ '^sha256:[0-9a-f]{64}$'),
    catalog_version varchar(64) not null check (catalog_version = 'DCC-1.1.0'),
    catalog_digest char(71) not null check (
        catalog_digest = 'sha256:aeb19962071e2144a85bb2e07fd124eff0d69edf93f7202e821204616a60f219'),
    rule_catalog_version varchar(64) not null check (rule_catalog_version = 'RC-1.0.0'),
    rule_catalog_digest char(71) not null check (
        rule_catalog_digest = 'sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a'),
    effective_at timestamptz not null,
    created_at timestamptz not null
);

create table ingestion_quality.iq_rule_dependency_rule (
    registry_version varchar(64) not null references
        ingestion_quality.iq_rule_dependency_registry(registry_version),
    rule_id varchar(64) not null check (
        rule_id in ('ACC-SAFE-001','ACC-SAFE-002','ECON-012','NIGHT-001','ACADEMIC-001')),
    rule_version varchar(64) not null check (rule_version = '1.0.0'),
    composition_operator varchar(16) not null check (composition_operator = 'all-of'),
    threshold integer check (threshold is null),
    primary key (registry_version, rule_id, rule_version)
);

create table ingestion_quality.iq_rule_dependency_member (
    registry_version varchar(64) not null,
    rule_id varchar(64) not null,
    rule_version varchar(64) not null,
    member_ordinal integer not null check (member_ordinal between 0 and 10),
    source_id varchar(64) not null check (source_id ~ '^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$'),
    source_contract_version varchar(128) not null check (btrim(source_contract_version) <> ''),
    dependency_id varchar(64) not null check (
        dependency_id ~ '^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$'),
    dependency_version varchar(64) not null check (dependency_version = '1.0.0'),
    requirement varchar(16) not null check (requirement = 'required'),
    composition_group varchar(64) not null check (composition_group = 'primary'),
    primary key (registry_version, rule_id, rule_version, dependency_id),
    unique (registry_version, rule_id, rule_version, member_ordinal),
    unique (registry_version, rule_id, rule_version, source_id),
    foreign key (registry_version, rule_id, rule_version) references
        ingestion_quality.iq_rule_dependency_rule(
            registry_version, rule_id, rule_version)
);
create index iq_rule_dependency_member_source_idx
    on ingestion_quality.iq_rule_dependency_member(source_id, dependency_id);

create table ingestion_quality.iq_quality_event_inbox (
    event_id uuid primary key,
    event_source varchar(256) not null check (
        event_source = 'urn:scholarsense:ingestion-quality'),
    source_id varchar(64) not null check (source_id ~ '^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$'),
    source_version bigint not null check (source_version between 1 and 9007199254740991),
    payload_digest char(71) not null check (payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    outcome varchar(32) not null check (outcome in (
        'applied','pending-publication','duplicate','old','gap','poisoned')),
    received_at timestamptz not null,
    processed_at timestamptz not null,
    check (substring(event_id::text, 15, 1) = '7'
        and substring(event_id::text, 20, 1) in ('8','9','a','b'))
);
create index iq_quality_event_inbox_source_order_idx
    on ingestion_quality.iq_quality_event_inbox(source_id, source_version, event_id);

create table ingestion_quality.iq_quality_dependency_cursor (
    source_id varchar(64) primary key,
    dependency_id varchar(64) not null unique,
    source_version bigint not null check (source_version between 1 and 9007199254740991),
    lineage_id uuid not null,
    lineage_revision bigint not null check (lineage_revision between 0 and 9007199254740991),
    batch_id uuid not null,
    stage varchar(32) not null check (stage in ('pending-publication','terminal')),
    paused boolean not null,
    aggregate_version bigint not null check (aggregate_version between 1 and 9007199254740991),
    updated_at timestamptz not null,
    check (substring(lineage_id::text, 15, 1) = '7'
        and substring(lineage_id::text, 20, 1) in ('8','9','a','b')),
    check (substring(batch_id::text, 15, 1) = '7'
        and substring(batch_id::text, 20, 1) in ('8','9','a','b'))
);

create table ingestion_quality.iq_quality_pending_pair (
    source_id varchar(64) primary key references
        ingestion_quality.iq_quality_dependency_cursor(source_id) on delete cascade,
    assessed_event_id uuid not null unique,
    batch_id uuid not null,
    snapshot_id uuid not null,
    snapshot_immutable_hash char(71) not null check (
        snapshot_immutable_hash ~ '^sha256:[0-9a-f]{64}$'),
    source_version bigint not null check (source_version between 1 and 9007199254740991),
    lineage_id uuid not null,
    created_at timestamptz not null
);

create table ingestion_quality.iq_quality_event_quarantine (
    event_id uuid primary key references ingestion_quality.iq_quality_event_inbox(event_id),
    source_id varchar(64) not null,
    reason_code varchar(128) not null check (reason_code ~ '^INGESTION_QUALITY_[A-Z0-9_]+$'),
    payload_digest char(71) not null check (payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    quarantined_at timestamptz not null
);

create table ingestion_quality.iq_quality_backfill_request (
    request_id uuid primary key,
    source_id varchar(64) not null,
    expected_source_version bigint not null check (
        expected_source_version between 1 and 9007199254740991),
    actual_source_version bigint not null check (
        actual_source_version between 1 and 9007199254740991),
    cursor_aggregate_version bigint not null check (
        cursor_aggregate_version between 0 and 9007199254740991),
    status varchar(16) not null check (status in ('pending','completed','unrecoverable')),
    requested_at timestamptz not null,
    completed_at timestamptz,
    unique (source_id, expected_source_version, actual_source_version),
    check (expected_source_version < actual_source_version),
    check ((status = 'pending') = (completed_at is null))
);
create index iq_quality_backfill_pending_idx
    on ingestion_quality.iq_quality_backfill_request(status, requested_at, request_id);

create table ingestion_quality.iq_dependency_quality_current (
    dependency_id varchar(64) primary key,
    source_id varchar(64) not null unique,
    source_version bigint not null check (source_version between 1 and 9007199254740991),
    dependency_version bigint not null check (dependency_version between 1 and 9007199254740991),
    lineage_id uuid not null,
    lineage_revision bigint not null check (lineage_revision between 0 and 9007199254740991),
    status varchar(16) not null check (status in ('eligible','fused','recovering','missing')),
    version_continuous boolean not null,
    watermark_utf8 bytea not null check (
        ingestion_quality.iq_utf8_scalar_count(watermark_utf8) between 1 and 512),
    snapshot_id uuid not null references ingestion_quality.iq_quality_snapshot(snapshot_id),
    snapshot_immutable_hash char(71) not null check (
        snapshot_immutable_hash ~ '^sha256:[0-9a-f]{64}$'),
    qmdp_version varchar(64) not null check (qmdp_version = 'QMDP-1.0.0'),
    qmdp_digest char(71) not null check (qmdp_digest ~ '^sha256:[0-9a-f]{64}$'),
    qshm_version varchar(64) not null check (qshm_version = 'QSHM-1.0.0'),
    qshm_digest char(71) not null check (qshm_digest ~ '^sha256:[0-9a-f]{64}$'),
    updated_at timestamptz not null
);
create index iq_dependency_quality_lineage_idx
    on ingestion_quality.iq_dependency_quality_current(
        source_id, source_version, lineage_revision, dependency_id);

create table ingestion_quality.iq_quality_eligibility_history (
    eligibility_id uuid not null,
    rule_id varchar(64) not null,
    rule_version varchar(64) not null,
    registry_version varchar(64) not null,
    registry_digest char(71) not null check (registry_digest ~ '^sha256:[0-9a-f]{64}$'),
    catalog_version varchar(64) not null,
    catalog_digest char(71) not null,
    rule_catalog_version varchar(64) not null,
    rule_catalog_digest char(71) not null,
    aggregate_version bigint not null check (aggregate_version between 1 and 9007199254740991),
    status varchar(16) not null check (status in ('eligible','fused','recovering','missing')),
    reason_code varchar(64) not null,
    composition_operator varchar(16) not null check (
        composition_operator in ('all-of','any-of','threshold')),
    threshold integer,
    effective_at timestamptz not null,
    occurred_at timestamptz not null,
    trace_id char(32) not null check (
        trace_id ~ '^[0-9a-f]{32}$' and trace_id !~ '^0{32}$'),
    producer varchar(64) not null check (producer = 'ingestion-quality'),
    retention_due_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (eligibility_id, aggregate_version),
    unique (rule_id, rule_version, registry_version, aggregate_version),
    check (substring(eligibility_id::text, 15, 1) = '7'
        and substring(eligibility_id::text, 20, 1) in ('8','9','a','b')),
    check (effective_at <= occurred_at),
    check (retention_due_at =
        ((occurred_at at time zone 'UTC') + interval '2 years') at time zone 'UTC')
);
create index iq_quality_eligibility_history_retention_idx
    on ingestion_quality.iq_quality_eligibility_history(
        legal_hold, retention_due_at, eligibility_id, aggregate_version);

create table ingestion_quality.iq_quality_eligibility_current (
    rule_id varchar(64) not null,
    rule_version varchar(64) not null,
    registry_version varchar(64) not null,
    eligibility_id uuid not null unique,
    aggregate_version bigint not null check (aggregate_version between 1 and 9007199254740991),
    status varchar(16) not null check (status in ('eligible','fused','recovering','missing')),
    reason_code varchar(64) not null,
    composition_operator varchar(16) not null,
    threshold integer,
    effective_at timestamptz not null,
    occurred_at timestamptz not null,
    primary key (rule_id, rule_version, registry_version)
);
create index iq_quality_eligibility_current_page_idx
    on ingestion_quality.iq_quality_eligibility_current(
        occurred_at desc, eligibility_id desc);
create index iq_quality_eligibility_current_status_page_idx
    on ingestion_quality.iq_quality_eligibility_current(
        status, occurred_at desc, eligibility_id desc);

create table ingestion_quality.iq_quality_eligibility_member_history (
    eligibility_id uuid not null,
    aggregate_version bigint not null,
    member_ordinal integer not null check (member_ordinal between 0 and 10),
    source_id varchar(64) not null,
    source_version bigint not null check (source_version between 1 and 9007199254740991),
    dependency_id varchar(64) not null,
    dependency_version bigint not null check (dependency_version between 1 and 9007199254740991),
    requirement varchar(16) not null check (requirement in ('required','optional')),
    state varchar(16) not null check (state in ('eligible','fused','recovering','missing')),
    version_continuous boolean not null,
    source_watermark_utf8 bytea not null,
    dependency_watermark_utf8 bytea not null,
    snapshot_id uuid,
    snapshot_immutable_hash char(71),
    qmdp_version varchar(64) not null check (qmdp_version = 'QMDP-1.0.0'),
    qmdp_digest char(71) not null,
    qshm_version varchar(64) not null check (qshm_version = 'QSHM-1.0.0'),
    qshm_digest char(71) not null,
    lineage_id uuid,
    failed boolean not null,
    primary key (eligibility_id, aggregate_version, dependency_id),
    unique (eligibility_id, aggregate_version, member_ordinal),
    foreign key (eligibility_id, aggregate_version) references
        ingestion_quality.iq_quality_eligibility_history(
            eligibility_id, aggregate_version) on delete cascade,
    check ((state = 'missing') or (snapshot_id is not null and lineage_id is not null)),
    check ((snapshot_id is null) = (snapshot_immutable_hash is null))
);
create index iq_quality_eligibility_member_owner_idx
    on ingestion_quality.iq_quality_eligibility_member_history(
        source_id, eligibility_id, aggregate_version, member_ordinal);

create table ingestion_quality.iq_quality_eligibility_audit (
    audit_id uuid primary key,
    eligibility_id uuid not null,
    aggregate_version bigint not null,
    action varchar(96) not null,
    outcome varchar(16) not null check (outcome in ('accepted','rejected')),
    trace_id char(32) not null,
    occurred_at timestamptz not null,
    payload_digest char(71) not null check (payload_digest ~ '^sha256:[0-9a-f]{64}$'),
    expires_at timestamptz not null,
    actor_search_token varchar(96) check (
        actor_search_token is null or actor_search_token ~ '^ast_v1_k[1-9][0-9]*_[0-9a-f]{64}$'),
    source_ip_search_token varchar(96) check (
        source_ip_search_token is null or source_ip_search_token ~ '^ipt_v1_k[1-9][0-9]*_[0-9a-f]{64}$')
);
create index iq_quality_eligibility_audit_object_idx
    on ingestion_quality.iq_quality_eligibility_audit(
        eligibility_id, occurred_at desc, audit_id);

create table ingestion_quality.iq_quality_eligibility_audit_token_binding (
    eligibility_id uuid primary key,
    object_search_token varchar(96) not null unique check (
        object_search_token ~ '^ost_v1_k1_[0-9a-f]{64}$'),
    aggregate_search_token varchar(96) not null unique check (
        aggregate_search_token ~ '^agt_v1_k1_[0-9a-f]{64}$')
);

create table ingestion_quality.iq_quality_eligibility_outbox (
    event_id uuid primary key,
    eligibility_id uuid not null,
    aggregate_version bigint not null check (aggregate_version between 1 and 9007199254740991),
    event_type varchar(160) not null check (
        event_type = 'scholarsense.ingestion-quality.quality-eligibility.changed.v1'),
    schema_version varchar(64) not null check (
        schema_version = 'QUALITY-ELIGIBILITY-EVENT-1.0.0'),
    payload_utf8 bytea not null check (octet_length(payload_utf8) between 2 and 65536),
    payload_digest char(64) not null check (payload_digest ~ '^[0-9a-f]{64}$'),
    status varchar(16) not null default 'pending' check (
        status in ('pending','retrying','delivered','failed')),
    attempts bigint not null default 0 check (attempts between 0 and 8),
    available_at timestamptz not null,
    claimed_until timestamptz,
    delivered_at timestamptz,
    last_error_code varchar(128),
    created_at timestamptz not null,
    unique (eligibility_id, aggregate_version),
    check (payload_digest = encode(sha256(payload_utf8), 'hex'))
);
create index iq_quality_eligibility_outbox_due_idx
    on ingestion_quality.iq_quality_eligibility_outbox(status, available_at, event_id);

create table ingestion_quality.iq_quality_eligibility_idempotency (
    idempotency_key_digest char(71) primary key check (
        idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_digest char(71) not null check (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    eligibility_id uuid not null,
    aggregate_version bigint not null check (aggregate_version between 1 and 9007199254740991),
    response jsonb not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    check (expires_at =
        ((created_at at time zone 'UTC') + interval '90 days') at time zone 'UTC')
);

insert into ingestion_quality.iq_rule_dependency_registry values (
    'RULE-DEPENDENCY-REGISTRY-1.0.0',
    'sha256:cd1915107c0c2657a430c2d5ba0abd420d9bd8d06f6cdcaed2a570c71ce6655a',
    'DCC-1.1.0',
    'sha256:aeb19962071e2144a85bb2e07fd124eff0d69edf93f7202e821204616a60f219',
    'RC-1.0.0',
    'sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a',
    '2026-08-10T11:31:55Z', '2026-08-10T11:31:55Z');

insert into ingestion_quality.iq_rule_dependency_rule values
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-001','1.0.0','all-of',null),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-002','1.0.0','all-of',null),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ECON-012','1.0.0','all-of',null),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','NIGHT-001','1.0.0','all-of',null),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACADEMIC-001','1.0.0','all-of',null);

insert into ingestion_quality.iq_rule_dependency_member values
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-001','1.0.0',0,'SRC-P0-ACCOMMODATION-001','ACCOMMODATION-SLICE-1.0.0','DEP-P0-ACCOMMODATION-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-001','1.0.0',1,'SRC-P0-CALENDAR-001','BC-1.0.0','DEP-P0-CALENDAR-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-001','1.0.0',2,'SRC-P0-CAMPUS-ACCESS-001','CAMPUS-ACCESS-SLICE-1.0.0','DEP-P0-CAMPUS-ACCESS-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-001','1.0.0',3,'SRC-P0-DEVICE-001','DEVICE-SLICE-1.0.0','DEP-P0-DEVICE-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-001','1.0.0',4,'SRC-P0-DORM-ACCESS-001','DORM-ACCESS-SLICE-1.0.0','DEP-P0-DORM-ACCESS-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-001','1.0.0',5,'SRC-P0-LEAVE-001','LEAVE-SLICE-1.0.0','DEP-P0-LEAVE-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-001','1.0.0',6,'SRC-P0-TIMETABLE-001','TIMETABLE-SLICE-1.0.0','DEP-P0-TIMETABLE-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-002','1.0.0',0,'SRC-P0-ACCOMMODATION-001','ACCOMMODATION-SLICE-1.0.0','DEP-P0-ACCOMMODATION-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-002','1.0.0',1,'SRC-P0-CALENDAR-001','BC-1.0.0','DEP-P0-CALENDAR-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-002','1.0.0',2,'SRC-P0-DEVICE-001','DEVICE-SLICE-1.0.0','DEP-P0-DEVICE-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-002','1.0.0',3,'SRC-P0-DORM-ACCESS-001','DORM-ACCESS-SLICE-1.0.0','DEP-P0-DORM-ACCESS-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-002','1.0.0',4,'SRC-P0-LEAVE-001','LEAVE-SLICE-1.0.0','DEP-P0-LEAVE-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACC-SAFE-002','1.0.0',5,'SRC-P0-TIMETABLE-001','TIMETABLE-SLICE-1.0.0','DEP-P0-TIMETABLE-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ECON-012','1.0.0',0,'SRC-P0-CALENDAR-001','BC-1.0.0','DEP-P0-CALENDAR-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ECON-012','1.0.0',1,'SRC-P0-CARD-001','CARD-SLICE-1.0.0','DEP-P0-CONSUMPTION-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ECON-012','1.0.0',2,'SRC-P0-LEAVE-001','LEAVE-SLICE-1.0.0','DEP-P0-LEAVE-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ECON-012','1.0.0',3,'SRC-P1-OFFCAMPUS-001','OFFCAMPUS-SLICE-1.0.0','DEP-P1-OFFCAMPUS-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','NIGHT-001','1.0.0',0,'SRC-P0-CALENDAR-001','BC-1.0.0','DEP-P0-CALENDAR-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','NIGHT-001','1.0.0',1,'SRC-P0-LEAVE-001','LEAVE-SLICE-1.0.0','DEP-P0-LEAVE-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','NIGHT-001','1.0.0',2,'SRC-P1-NETWORK-001','NETWORK-AGGREGATE-1.0.0','DEP-P1-NETWORK-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','NIGHT-001','1.0.0',3,'SRC-P1-OFFCAMPUS-001','OFFCAMPUS-SLICE-1.0.0','DEP-P1-OFFCAMPUS-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACADEMIC-001','1.0.0',0,'SRC-P1-ACADEMIC-001','ACADEMIC-NODE-1.0.0','DEP-P1-ACADEMIC-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACADEMIC-001','1.0.0',1,'SRC-P0-CALENDAR-001','BC-1.0.0','DEP-P0-CALENDAR-001','1.0.0','required','primary'),
 ('RULE-DEPENDENCY-REGISTRY-1.0.0','ACADEMIC-001','1.0.0',2,'SRC-P0-TIMETABLE-001','TIMETABLE-SLICE-1.0.0','DEP-P0-TIMETABLE-001','1.0.0','required','primary');

create function ingestion_quality.iq_quality_uuid_v7_derive(
    namespace_id uuid, discriminator text)
returns uuid
language sql
immutable
strict
set search_path = pg_catalog
as $$
with material as (
  select replace(namespace_id::text, '-', '') source_hex,
         encode(sha256(convert_to(namespace_id::text || ':' || discriminator, 'UTF8')), 'hex') digest_hex
), raw as (
  select substring(source_hex from 1 for 12) || '7' ||
         substring(digest_hex from 14 for 3) || '8' ||
         substring(digest_hex from 18 for 15) value from material
)
select (substring(value from 1 for 8) || '-' || substring(value from 9 for 4) || '-' ||
        substring(value from 13 for 4) || '-' || substring(value from 17 for 4) || '-' ||
        substring(value from 21 for 12))::uuid from raw
$$;

create function ingestion_quality.iq_guard_quality_eligibility_history()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if tg_op = 'UPDATE' then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_ELIGIBILITY_HISTORY_IMMUTABLE';
    end if;
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_retention_executor');
    return old;
end
$$;
create trigger iq_quality_eligibility_history_guard
before update or delete on ingestion_quality.iq_quality_eligibility_history
for each row execute function ingestion_quality.iq_guard_quality_eligibility_history();
create trigger iq_quality_eligibility_member_history_guard
before update or delete on ingestion_quality.iq_quality_eligibility_member_history
for each row execute function ingestion_quality.iq_guard_quality_eligibility_history();

create function ingestion_quality.iq_guard_quality_eligibility_current()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if tg_op = 'INSERT' and new.aggregate_version <> 1 then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_ELIGIBILITY_VERSION_CONFLICT';
    elsif tg_op = 'UPDATE' and (
        new.eligibility_id <> old.eligibility_id
        or new.rule_id <> old.rule_id
        or new.rule_version <> old.rule_version
        or new.registry_version <> old.registry_version
        or new.aggregate_version <> old.aggregate_version + 1) then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_ELIGIBILITY_VERSION_CONFLICT';
    end if;
    return new;
end
$$;
create trigger iq_quality_eligibility_current_guard
before insert or update on ingestion_quality.iq_quality_eligibility_current
for each row execute function ingestion_quality.iq_guard_quality_eligibility_current();

-- V000014's closed workload guard is extended additively for the new consumer identity.
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
    select count(*) = 1
           and coalesce(bool_and(granted_role.rolname = requested_role::text), false)
           and coalesce(bool_and(membership.inherit_option), false)
           and not coalesce(bool_or(membership.set_option), false)
           and not coalesce(bool_or(membership.admin_option), false)
      into iq_direct_membership_valid
      from pg_catalog.pg_auth_members membership
      join pg_catalog.pg_roles member_role on member_role.oid = membership.member
      join pg_catalog.pg_roles granted_role on granted_role.oid = membership.roleid
     where member_role.rolname = session_user;
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
         'scholarsense_ingestion_quality_eligibility_consumer')
       or not iq_direct_membership_valid or iq_reachable_memberships <> 1
       or not pg_catalog.pg_has_role(session_user, requested_role, 'USAGE')
       or pg_catalog.pg_has_role(session_user,
            'scholarsense_ingestion_quality_batch_owner', 'MEMBER')
       or pg_catalog.pg_has_role(session_user,
            'scholarsense_ingestion_quality_batch_owner', 'USAGE')
       or pg_catalog.pg_has_role(session_user,
            'scholarsense_ingestion_quality_batch_owner', 'SET') then
        raise exception using errcode='insufficient_privilege',
            message='INGESTION_QUALITY_WORKLOAD_ROLE_MISMATCH';
    end if;
end
$$;

create function ingestion_quality.iq_find_quality_snapshot_evidence(
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
    lineage_id uuid, effective_at timestamptz, immutable_hash character)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_eligibility_consumer');
    return query
    select snapshot.snapshot_id, snapshot.batch_id, snapshot.source_id,
           snapshot.overall_result, snapshot.observation_start_at,
           snapshot.observation_end_at, snapshot.cutoff_at, snapshot.watermark_utf8,
           snapshot.manifest_digest, snapshot.source_schema_version,
           snapshot.source_schema_digest, snapshot.qmdp_version, snapshot.qmdp_digest,
           snapshot.quality_gate_version, snapshot.quality_gate_digest,
           snapshot.hash_profile_version, snapshot.hash_profile_digest,
           snapshot.lineage_id, snapshot.effective_at, snapshot.immutable_hash
      from ingestion_quality.iq_quality_snapshot snapshot
     where snapshot.batch_id=requested_batch_id
       and snapshot.snapshot_id=requested_snapshot_id
       and snapshot.immutable_hash=requested_immutable_hash;
end
$$;

create function ingestion_quality.iq_load_quality_eligibility_processing_state(
    requested_event_id uuid, requested_source_id varchar)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_result jsonb;
    iq_rule record;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_eligibility_consumer');
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('quality-eligibility:' || requested_source_id, 0));
    for iq_rule in
        select distinct member.rule_id,member.rule_version
          from ingestion_quality.iq_rule_dependency_member member
         where member.source_id=requested_source_id
         order by member.rule_id,member.rule_version
    loop
        perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
            'quality-eligibility-rule:' || iq_rule.rule_id || '@' ||
            iq_rule.rule_version, 0));
    end loop;
    select jsonb_build_object(
      'currentInbox', (select jsonb_build_object(
          'payloadDigest', trim(inbox.payload_digest), 'outcome', inbox.outcome)
        from ingestion_quality.iq_quality_event_inbox inbox
        where inbox.event_id=requested_event_id),
      'cursor', (select jsonb_build_object(
          'sourceId', cursor.source_id, 'dependencyId', cursor.dependency_id,
          'sourceVersion', cursor.source_version, 'lineageId', cursor.lineage_id,
          'lineageRevision', cursor.lineage_revision, 'batchId', cursor.batch_id,
          'stage', cursor.stage, 'paused', cursor.paused,
          'aggregateVersion', cursor.aggregate_version)
        from ingestion_quality.iq_quality_dependency_cursor cursor
        where cursor.source_id=requested_source_id),
      'pendingPair', (select jsonb_build_object(
          'assessedEventId', pair.assessed_event_id, 'batchId', pair.batch_id,
          'snapshotId', pair.snapshot_id,
          'snapshotImmutableHash', trim(pair.snapshot_immutable_hash),
          'sourceVersion', pair.source_version, 'lineageId', pair.lineage_id)
        from ingestion_quality.iq_quality_pending_pair pair
        where pair.source_id=requested_source_id),
      'dependencyStates', coalesce((select jsonb_agg(jsonb_build_object(
          'sourceId', dependency.source_id, 'sourceVersion', dependency.source_version,
          'lineageId', dependency.lineage_id,
          'lineageRevision', dependency.lineage_revision,
          'dependencyId', dependency.dependency_id,
          'dependencyVersion', dependency.dependency_version,
          'status', dependency.status,
          'versionContinuous', dependency.version_continuous,
          'watermark', convert_from(dependency.watermark_utf8, 'UTF8'))
          order by dependency.dependency_id)
        from ingestion_quality.iq_dependency_quality_current dependency), '[]'::jsonb))
      into iq_result;
    return iq_result;
end
$$;

create function ingestion_quality.iq_accept_quality_eligibility_event(
    requested_event_id uuid,
    requested_source_id varchar,
    requested_source_version bigint,
    requested_payload_digest character,
    requested_mutation jsonb)
returns varchar
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_now timestamptz := statement_timestamp();
    iq_existing ingestion_quality.iq_quality_event_inbox%rowtype;
    iq_outcome varchar := requested_mutation ->> 'outcome';
    iq_cursor jsonb := requested_mutation -> 'cursor';
    iq_pair jsonb := requested_mutation -> 'pendingPair';
    iq_dependency jsonb := requested_mutation -> 'dependencyState';
    iq_snapshot jsonb := requested_mutation -> 'snapshotEvidence';
    iq_decision jsonb;
    iq_registry ingestion_quality.iq_rule_dependency_registry%rowtype;
    iq_current ingestion_quality.iq_quality_eligibility_current%rowtype;
    iq_eligibility_id uuid;
    iq_outbox_event_id uuid;
    iq_aggregate_version bigint;
    iq_trace_id char(32) := requested_mutation ->> 'traceId';
    iq_occurred_at timestamptz := (requested_mutation ->> 'occurredAt')::timestamptz;
    iq_effective_at timestamptz := (requested_mutation ->> 'effectiveAt')::timestamptz;
    iq_members jsonb;
    iq_failed jsonb;
    iq_fact jsonb;
    iq_envelope jsonb;
    iq_payload bytea;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_eligibility_consumer');
    if substring(requested_event_id::text, 15, 1) <> '7'
       or substring(requested_event_id::text, 20, 1) not in ('8','9','a','b')
       or requested_source_id !~ '^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$'
       or requested_source_version not between 1 and 9007199254740991
       or requested_payload_digest !~ '^sha256:[0-9a-f]{64}$'
       or iq_trace_id !~ '^[0-9a-f]{32}$' or iq_trace_id ~ '^0{32}$'
       or iq_effective_at > iq_occurred_at
       or not ingestion_quality.iq_json_exact_object(requested_mutation, array[
          'outcome','cursor','pendingPair','dependencyState','decisions',
          'backfillRequest','quarantine','snapshotEvidence','occurredAt',
          'effectiveAt','traceId'])
       or iq_outcome not in (
          'applied','pending-publication','duplicate','old','gap','poisoned')
       or jsonb_typeof(requested_mutation -> 'decisions') <> 'array' then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_ELIGIBILITY_MUTATION_INVALID';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('quality-eligibility:' || requested_source_id, 0));
    select inbox.* into iq_existing
      from ingestion_quality.iq_quality_event_inbox inbox
     where inbox.event_id=requested_event_id for update;
    if iq_existing.event_id is not null then
        if trim(iq_existing.payload_digest) <> trim(requested_payload_digest) then
            raise exception using errcode='unique_violation',
                message='INGESTION_QUALITY_IDEMPOTENCY_MISMATCH';
        end if;
        if iq_existing.outcome <> 'gap' then return 'duplicate'; end if;
    end if;

    if iq_outcome = 'duplicate' then return 'duplicate'; end if;

    insert into ingestion_quality.iq_quality_event_inbox(
        event_id,event_source,source_id,source_version,payload_digest,
        outcome,received_at,processed_at)
    values (requested_event_id,'urn:scholarsense:ingestion-quality',
        requested_source_id,requested_source_version,requested_payload_digest,
        iq_outcome,iq_now,iq_now)
    on conflict (event_id) do update set
        outcome=excluded.outcome, processed_at=excluded.processed_at;

    if iq_outcome = 'old' then return iq_outcome; end if;
    if iq_outcome = 'poisoned' then
        if not ingestion_quality.iq_json_exact_object(
            requested_mutation -> 'quarantine',
            array['eventId','sourceId','reasonCode','payloadDigest'])
           or requested_mutation #>> '{quarantine,eventId}' <> requested_event_id::text
           or requested_mutation #>> '{quarantine,sourceId}' <> requested_source_id
           or requested_mutation #>> '{quarantine,payloadDigest}' <>
              trim(requested_payload_digest) then
            raise exception using errcode='check_violation',
                message='INGESTION_QUALITY_QUARANTINE_INVALID';
        end if;
        insert into ingestion_quality.iq_quality_event_quarantine values (
            requested_event_id,requested_source_id,
            requested_mutation #>> '{quarantine,reasonCode}',
            requested_payload_digest,iq_now)
        on conflict (event_id) do nothing;
        return iq_outcome;
    end if;

    if iq_outcome = 'gap' then
        if requested_mutation -> 'backfillRequest' = 'null'::jsonb then
            raise exception using errcode='check_violation',
                message='INGESTION_QUALITY_BACKFILL_INVALID';
        end if;
        if iq_cursor <> 'null'::jsonb then
            update ingestion_quality.iq_quality_dependency_cursor
               set paused=true,
                   aggregate_version=(iq_cursor ->> 'aggregateVersion')::bigint,
                   updated_at=iq_now
             where source_id=requested_source_id
               and aggregate_version + 1=(iq_cursor ->> 'aggregateVersion')::bigint;
            if not found then
                raise exception using errcode='serialization_failure',
                    message='INGESTION_QUALITY_SEQUENCE_CONFLICT';
            end if;
        end if;
        insert into ingestion_quality.iq_quality_backfill_request values (
            ingestion_quality.iq_quality_uuid_v7_derive(requested_event_id,'backfill'),
            requested_source_id,
            (requested_mutation #>> '{backfillRequest,expectedSourceVersion}')::bigint,
            (requested_mutation #>> '{backfillRequest,actualSourceVersion}')::bigint,
            (requested_mutation #>> '{backfillRequest,cursorAggregateVersion}')::bigint,
            'pending',iq_now,null)
        on conflict (source_id,expected_source_version,actual_source_version) do nothing;
        return iq_outcome;
    end if;

    if iq_cursor = 'null'::jsonb
       or iq_cursor ->> 'sourceId' <> requested_source_id
       or (iq_cursor ->> 'sourceVersion')::bigint <> requested_source_version then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_CURSOR_INVALID';
    end if;
    insert into ingestion_quality.iq_quality_dependency_cursor values (
        iq_cursor ->> 'sourceId',iq_cursor ->> 'dependencyId',
        (iq_cursor ->> 'sourceVersion')::bigint,(iq_cursor ->> 'lineageId')::uuid,
        (iq_cursor ->> 'lineageRevision')::bigint,(iq_cursor ->> 'batchId')::uuid,
        iq_cursor ->> 'stage',(iq_cursor ->> 'paused')::boolean,
        (iq_cursor ->> 'aggregateVersion')::bigint,iq_now)
    on conflict (source_id) do update set
        dependency_id=excluded.dependency_id,source_version=excluded.source_version,
        lineage_id=excluded.lineage_id,lineage_revision=excluded.lineage_revision,
        batch_id=excluded.batch_id,stage=excluded.stage,paused=excluded.paused,
        aggregate_version=excluded.aggregate_version,updated_at=excluded.updated_at
    where ingestion_quality.iq_quality_dependency_cursor.aggregate_version + 1 =
          excluded.aggregate_version;
    if not found then
        raise exception using errcode='serialization_failure',
            message='INGESTION_QUALITY_SEQUENCE_CONFLICT';
    end if;

    if iq_outcome = 'pending-publication' then
        if iq_pair = 'null'::jsonb then
            raise exception using errcode='check_violation',
                message='INGESTION_QUALITY_PAIR_INVALID';
        end if;
        insert into ingestion_quality.iq_quality_pending_pair values (
            requested_source_id,(iq_pair ->> 'assessedEventId')::uuid,
            (iq_pair ->> 'batchId')::uuid,(iq_pair ->> 'snapshotId')::uuid,
            iq_pair ->> 'snapshotImmutableHash',(iq_pair ->> 'sourceVersion')::bigint,
            (iq_pair ->> 'lineageId')::uuid,iq_now)
        on conflict (source_id) do update set
            assessed_event_id=excluded.assessed_event_id,batch_id=excluded.batch_id,
            snapshot_id=excluded.snapshot_id,
            snapshot_immutable_hash=excluded.snapshot_immutable_hash,
            source_version=excluded.source_version,lineage_id=excluded.lineage_id,
            created_at=excluded.created_at;
        return iq_outcome;
    end if;

    if iq_dependency = 'null'::jsonb or iq_snapshot = 'null'::jsonb then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_ELIGIBILITY_EVIDENCE_INVALID';
    end if;
    delete from ingestion_quality.iq_quality_pending_pair
     where source_id=requested_source_id;
    insert into ingestion_quality.iq_dependency_quality_current values (
        iq_dependency ->> 'dependencyId',iq_dependency ->> 'sourceId',
        (iq_dependency ->> 'sourceVersion')::bigint,
        (iq_dependency ->> 'dependencyVersion')::bigint,
        (iq_dependency ->> 'lineageId')::uuid,
        (iq_dependency ->> 'lineageRevision')::bigint,
        iq_dependency ->> 'status',(iq_dependency ->> 'versionContinuous')::boolean,
        convert_to(iq_dependency ->> 'watermark','UTF8'),
        (iq_snapshot ->> 'snapshotId')::uuid,iq_snapshot ->> 'immutableHash',
        iq_snapshot ->> 'qmdpVersion',iq_snapshot ->> 'qmdpDigest',
        iq_snapshot ->> 'qshmVersion',iq_snapshot ->> 'qshmDigest',iq_now)
    on conflict (dependency_id) do update set
        source_version=excluded.source_version,
        dependency_version=excluded.dependency_version,
        lineage_id=excluded.lineage_id,lineage_revision=excluded.lineage_revision,
        status=excluded.status,version_continuous=excluded.version_continuous,
        watermark_utf8=excluded.watermark_utf8,snapshot_id=excluded.snapshot_id,
        snapshot_immutable_hash=excluded.snapshot_immutable_hash,
        qmdp_version=excluded.qmdp_version,qmdp_digest=excluded.qmdp_digest,
        qshm_version=excluded.qshm_version,qshm_digest=excluded.qshm_digest,
        updated_at=excluded.updated_at;

    select registry.* into strict iq_registry
      from ingestion_quality.iq_rule_dependency_registry registry
     where registry.registry_version='RULE-DEPENDENCY-REGISTRY-1.0.0';
    for iq_decision in select value from jsonb_array_elements(
            requested_mutation -> 'decisions')
    loop
        if not ingestion_quality.iq_json_exact_object(iq_decision,
            array['ruleId','ruleVersion','status','reasonCode','failedMembers'])
           or jsonb_typeof(iq_decision -> 'failedMembers') <> 'array' then
            raise exception using errcode='check_violation',
                message='INGESTION_QUALITY_COMPOSITION_INVALID';
        end if;
        select current_fact.* into iq_current
          from ingestion_quality.iq_quality_eligibility_current current_fact
         where current_fact.rule_id=iq_decision ->> 'ruleId'
           and current_fact.rule_version=iq_decision ->> 'ruleVersion'
           and current_fact.registry_version=iq_registry.registry_version
         for update;
        iq_aggregate_version := coalesce(iq_current.aggregate_version + 1, 1);
        iq_eligibility_id := coalesce(iq_current.eligibility_id,
            ingestion_quality.iq_quality_uuid_v7_derive(
                requested_event_id,'eligibility:' || (iq_decision ->> 'ruleId')));
        iq_outbox_event_id := ingestion_quality.iq_quality_uuid_v7_derive(
            requested_event_id,'outbox:' || (iq_decision ->> 'ruleId') || ':' ||
            iq_aggregate_version::text);
        insert into ingestion_quality.iq_quality_eligibility_history values (
            iq_eligibility_id,iq_decision ->> 'ruleId',iq_decision ->> 'ruleVersion',
            iq_registry.registry_version,trim(iq_registry.registry_digest),
            iq_registry.catalog_version,trim(iq_registry.catalog_digest),
            iq_registry.rule_catalog_version,trim(iq_registry.rule_catalog_digest),
            iq_aggregate_version,iq_decision ->> 'status',iq_decision ->> 'reasonCode',
            'all-of',null,iq_effective_at,iq_occurred_at,iq_trace_id,
            'ingestion-quality',
            ((iq_occurred_at at time zone 'UTC') + interval '2 years') at time zone 'UTC',
            false);
        insert into ingestion_quality.iq_quality_eligibility_member_history
        select iq_eligibility_id,iq_aggregate_version,member.member_ordinal,
               member.source_id,coalesce(dependency.source_version,1),
               member.dependency_id,coalesce(dependency.dependency_version,1),
               member.requirement,coalesce(dependency.status,'missing'),
               coalesce(dependency.version_continuous,false),
               coalesce(dependency.watermark_utf8,convert_to('missing','UTF8')),
               coalesce(dependency.watermark_utf8,convert_to('missing','UTF8')),
               dependency.snapshot_id,trim(dependency.snapshot_immutable_hash),
               coalesce(dependency.qmdp_version,'QMDP-1.0.0'),
               coalesce(trim(dependency.qmdp_digest),
                 'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8'),
               coalesce(dependency.qshm_version,'QSHM-1.0.0'),
               coalesce(trim(dependency.qshm_digest),
                 'sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2'),
               dependency.lineage_id,
               (iq_decision -> 'failedMembers') ? member.dependency_id
          from ingestion_quality.iq_rule_dependency_member member
          left join ingestion_quality.iq_dependency_quality_current dependency
            on dependency.dependency_id=member.dependency_id
         where member.registry_version=iq_registry.registry_version
           and member.rule_id=iq_decision ->> 'ruleId'
           and member.rule_version=iq_decision ->> 'ruleVersion'
         order by member.member_ordinal;

        insert into ingestion_quality.iq_quality_eligibility_current values (
            iq_decision ->> 'ruleId',iq_decision ->> 'ruleVersion',
            iq_registry.registry_version,iq_eligibility_id,iq_aggregate_version,
            iq_decision ->> 'status',iq_decision ->> 'reasonCode','all-of',null,
            iq_effective_at,iq_occurred_at)
        on conflict (rule_id,rule_version,registry_version) do update set
            aggregate_version=excluded.aggregate_version,status=excluded.status,
            reason_code=excluded.reason_code,composition_operator=excluded.composition_operator,
            threshold=excluded.threshold,effective_at=excluded.effective_at,
            occurred_at=excluded.occurred_at;

        select coalesce(jsonb_agg(jsonb_build_object(
            'sourceId',member.source_id,'sourceVersion',member.source_version::text,
            'dependencyId',member.dependency_id,
            'dependencyVersion',member.dependency_version::text,
            'requirement',member.requirement,'state',member.state,
            'versionContinuous',member.version_continuous,
            'sourceWatermark',convert_from(member.source_watermark_utf8,'UTF8'),
            'dependencyWatermark',convert_from(member.dependency_watermark_utf8,'UTF8'),
            'snapshotId',member.snapshot_id,
            'snapshotImmutableHash',trim(member.snapshot_immutable_hash),
            'qualityMetricDecisionProfileVersion',member.qmdp_version,
            'qualityMetricDecisionProfileDigest',trim(member.qmdp_digest),
            'qualitySnapshotHashProfileVersion',member.qshm_version,
            'qualitySnapshotHashProfileDigest',trim(member.qshm_digest),
            'lineageId',member.lineage_id) order by member.member_ordinal),'[]'::jsonb),
            coalesce(jsonb_agg(to_jsonb(member.dependency_id)
                order by member.dependency_id) filter (where member.failed),'[]'::jsonb)
          into iq_members,iq_failed
          from ingestion_quality.iq_quality_eligibility_member_history member
         where member.eligibility_id=iq_eligibility_id
           and member.aggregate_version=iq_aggregate_version;
        iq_fact := jsonb_build_object(
            'eligibilityId',iq_eligibility_id,'businessKey',
              (iq_decision ->> 'ruleId') || '@' || (iq_decision ->> 'ruleVersion') ||
              '@' || iq_registry.registry_version,
            'aggregateVersion',iq_aggregate_version,'status',iq_decision ->> 'status',
            'reasonCode',iq_decision ->> 'reasonCode','ruleId',iq_decision ->> 'ruleId',
            'ruleVersion',iq_decision ->> 'ruleVersion',
            'registryVersion',iq_registry.registry_version,
            'registryDigest',trim(iq_registry.registry_digest),
            'catalogVersion',iq_registry.catalog_version,
            'catalogDigest',trim(iq_registry.catalog_digest),
            'ruleCatalogVersion',iq_registry.rule_catalog_version,
            'ruleCatalogDigest',trim(iq_registry.rule_catalog_digest),
            'operator','all-of','threshold',null,'members',iq_members,
            'failedMembers',iq_failed,'effectiveAt',iq_effective_at,
            'occurredAt',iq_occurred_at,'traceId',iq_trace_id,
            'producer','ingestion-quality');
        iq_envelope := jsonb_build_object(
            'specversion','1.0','type',
              'scholarsense.ingestion-quality.quality-eligibility.changed.v1',
            'source','https://scholarsense.suda.edu.cn/ingestion-quality',
            'id',iq_outbox_event_id,'subject','quality-eligibility/' || iq_eligibility_id::text,
            'time',iq_occurred_at,'datacontenttype','application/json',
            'traceparent','00-' || iq_trace_id || '-1111111111111111-01',
            'data',jsonb_build_object(
              'eventId',iq_outbox_event_id,'aggregateId',iq_eligibility_id,
              'aggregateType','QualityEligibility','aggregateVersion',iq_aggregate_version,
              'contractVersion','PIC-1.0.0',
              'schemaVersion','QUALITY-ELIGIBILITY-EVENT-1.0.0',
              'occurredAt',iq_occurred_at,'traceId',iq_trace_id,
              'producer','ingestion-quality','runtimeEvidenceClaim','none',
              'qualityEligibility',iq_fact));
        iq_payload := convert_to(iq_envelope::text,'UTF8');
        insert into ingestion_quality.iq_quality_eligibility_outbox values (
            iq_outbox_event_id,iq_eligibility_id,iq_aggregate_version,
            'scholarsense.ingestion-quality.quality-eligibility.changed.v1',
            'QUALITY-ELIGIBILITY-EVENT-1.0.0',iq_payload,
            encode(sha256(iq_payload),'hex'),'pending',0,iq_now,null,null,null,iq_now);
        insert into ingestion_quality.iq_quality_eligibility_audit(
            audit_id,eligibility_id,aggregate_version,action,outcome,trace_id,
            occurred_at,payload_digest,expires_at) values (
            ingestion_quality.iq_quality_uuid_v7_derive(
                requested_event_id,'audit:' || (iq_decision ->> 'ruleId')),
            iq_eligibility_id,iq_aggregate_version,'quality-eligibility-derived',
            'accepted',iq_trace_id,iq_occurred_at,requested_payload_digest,
            ((iq_occurred_at at time zone 'UTC') + interval '3 years') at time zone 'UTC');
        insert into ingestion_quality.iq_quality_eligibility_audit_token_binding values (
            iq_eligibility_id,
            'ost_v1_k1_' || encode(sha256(convert_to(
                'quality-eligibility:' || iq_eligibility_id::text,'UTF8')),'hex'),
            'agt_v1_k1_' || encode(sha256(convert_to(
                'quality-eligibility-aggregate:' || iq_eligibility_id::text,'UTF8')),'hex'))
        on conflict (eligibility_id) do nothing;
    end loop;
    return iq_outcome;
end
$$;

create function ingestion_quality.iq_find_quality_eligibility_ids(
    requested_status varchar,
    requested_rule_id varchar,
    requested_after_occurred_at timestamptz,
    requested_after_eligibility_id uuid,
    requested_limit integer)
returns table(eligibility_id uuid)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_online');
    if requested_status is not null and requested_status not in (
         'eligible','fused','recovering','missing')
       or requested_limit not between 1 and 101
       or ((requested_after_occurred_at is null) <>
           (requested_after_eligibility_id is null)) then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_ELIGIBILITY_QUERY_INVALID';
    end if;
    return query
    select current_fact.eligibility_id
      from ingestion_quality.iq_quality_eligibility_current current_fact
     where (requested_status is null or current_fact.status=requested_status)
       and (requested_rule_id is null or current_fact.rule_id=requested_rule_id)
       and (requested_after_occurred_at is null or
            (current_fact.occurred_at,current_fact.eligibility_id) <
            (requested_after_occurred_at,requested_after_eligibility_id))
     order by current_fact.occurred_at desc,current_fact.eligibility_id desc
     limit requested_limit;
end
$$;

create function ingestion_quality.iq_find_quality_eligibility_page(
    requested_eligibility_ids uuid[])
returns table(
    eligibility_id uuid, rule_id varchar, rule_version varchar,
    registry_version varchar, registry_digest character,
    catalog_version varchar, catalog_digest character,
    rule_catalog_version varchar, rule_catalog_digest character,
    aggregate_version bigint, status varchar, reason_code varchar,
    composition_operator varchar, threshold integer,
    effective_at timestamptz, occurred_at timestamptz, trace_id character)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_online');
    if coalesce(cardinality(requested_eligibility_ids),0) not between 1 and 101
       or (select count(distinct value) from unnest(requested_eligibility_ids) value)
          <> cardinality(requested_eligibility_ids) then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_ELIGIBILITY_PAGE_INVALID';
    end if;
    return query
    select history.eligibility_id,history.rule_id,history.rule_version,
           history.registry_version,history.registry_digest,
           history.catalog_version,history.catalog_digest,
           history.rule_catalog_version,history.rule_catalog_digest,
           history.aggregate_version,history.status,history.reason_code,
           history.composition_operator,history.threshold,
           history.effective_at,history.occurred_at,history.trace_id
      from unnest(requested_eligibility_ids) with ordinality request(id,ordinal)
      join ingestion_quality.iq_quality_eligibility_current current_fact
        on current_fact.eligibility_id=request.id
      join ingestion_quality.iq_quality_eligibility_history history
        on history.eligibility_id=current_fact.eligibility_id
       and history.aggregate_version=current_fact.aggregate_version
     order by request.ordinal;
end
$$;

create function ingestion_quality.iq_find_quality_eligibility_page_members(
    requested_eligibility_ids uuid[])
returns table(
    eligibility_id uuid, aggregate_version bigint, member_ordinal integer,
    source_id varchar, source_version bigint, dependency_id varchar,
    dependency_version bigint, requirement varchar, state varchar,
    version_continuous boolean, source_watermark_utf8 bytea,
    dependency_watermark_utf8 bytea, snapshot_id uuid,
    snapshot_immutable_hash character, qmdp_version varchar,
    qmdp_digest character, qshm_version varchar, qshm_digest character,
    lineage_id uuid, failed boolean)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_online');
    if coalesce(cardinality(requested_eligibility_ids),0) not between 1 and 101 then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_ELIGIBILITY_PAGE_INVALID';
    end if;
    return query
    select member.eligibility_id,member.aggregate_version,member.member_ordinal,
           member.source_id,member.source_version,member.dependency_id,
           member.dependency_version,member.requirement,member.state,
           member.version_continuous,member.source_watermark_utf8,
           member.dependency_watermark_utf8,member.snapshot_id,
           member.snapshot_immutable_hash,member.qmdp_version,member.qmdp_digest,
           member.qshm_version,member.qshm_digest,member.lineage_id,member.failed
      from unnest(requested_eligibility_ids) with ordinality request(id,ordinal)
      join ingestion_quality.iq_quality_eligibility_current current_fact
        on current_fact.eligibility_id=request.id
      join ingestion_quality.iq_quality_eligibility_member_history member
        on member.eligibility_id=current_fact.eligibility_id
       and member.aggregate_version=current_fact.aggregate_version
     order by request.ordinal,member.member_ordinal;
end
$$;

create function ingestion_quality.iq_resolve_quality_eligibility_source(
    requested_eligibility_id uuid)
returns table(source_id varchar)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_online');
    return query
    select distinct member.source_id
      from ingestion_quality.iq_quality_eligibility_current current_fact
      join ingestion_quality.iq_quality_eligibility_member_history member
        on member.eligibility_id=current_fact.eligibility_id
       and member.aggregate_version=current_fact.aggregate_version
     where current_fact.eligibility_id=requested_eligibility_id
     order by member.source_id;
end
$$;

create function ingestion_quality.iq_append_quality_eligibility_read_audit(
    requested_audit_id uuid,
    requested_eligibility_id uuid,
    requested_aggregate_version bigint,
    requested_object_search_token varchar,
    requested_aggregate_search_token varchar,
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
declare iq_binding ingestion_quality.iq_quality_eligibility_audit_token_binding%rowtype;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_online');
    select binding.* into strict iq_binding
      from ingestion_quality.iq_quality_eligibility_audit_token_binding binding
     where binding.eligibility_id=requested_eligibility_id;
    if requested_object_search_token <> iq_binding.object_search_token
       or requested_aggregate_search_token <> iq_binding.aggregate_search_token
       or requested_actor_search_token !~ '^ast_v1_k[1-9][0-9]*_[0-9a-f]{64}$'
       or requested_source_ip_search_token !~ '^ipt_v1_k[1-9][0-9]*_[0-9a-f]{64}$'
       or requested_action not in (
          'quality-eligibility-list-read','quality-eligibility-detail-read')
       or requested_trace_id !~ '^[0-9a-f]{32}$' or requested_trace_id ~ '^0{32}$'
       or requested_payload_digest !~ '^sha256:[0-9a-f]{64}$'
       or not exists (select 1
          from ingestion_quality.iq_quality_eligibility_current current_fact
          where current_fact.eligibility_id=requested_eligibility_id
            and current_fact.aggregate_version=requested_aggregate_version) then
        raise exception using errcode='check_violation',
            message='INGESTION_QUALITY_ELIGIBILITY_READ_AUDIT_INVALID';
    end if;
    insert into ingestion_quality.iq_quality_eligibility_audit(
        audit_id,eligibility_id,aggregate_version,action,outcome,trace_id,
        occurred_at,payload_digest,expires_at,actor_search_token,source_ip_search_token)
    values (requested_audit_id,requested_eligibility_id,requested_aggregate_version,
        requested_action,'accepted',requested_trace_id,requested_occurred_at,
        requested_payload_digest,
        ((requested_occurred_at at time zone 'UTC') + interval '3 years') at time zone 'UTC',
        requested_actor_search_token,requested_source_ip_search_token);
end
$$;

create function ingestion_quality.iq_claim_next_quality_eligibility_outbox()
returns table(
    event_id uuid, attempts bigint, claimed_until timestamptz,
    payload_utf8 bytea, payload_digest character)
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare iq_now timestamptz := statement_timestamp();
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_relay');
    return query
    with candidate as (
      select queued.event_id
        from ingestion_quality.iq_quality_eligibility_outbox queued
       where ((queued.status='pending' and queued.available_at <= iq_now)
          or (queued.status='retrying' and queued.claimed_until <= iq_now))
         and queued.attempts < 8
       order by coalesce(queued.claimed_until,queued.available_at),queued.event_id
       limit 1 for update skip locked
    ), claimed as (
      update ingestion_quality.iq_quality_eligibility_outbox queued
         set status='retrying',attempts=queued.attempts+1,
             claimed_until=iq_now + interval '5 minutes',last_error_code=null
        from candidate where queued.event_id=candidate.event_id
      returning queued.event_id,queued.attempts,queued.claimed_until,
                queued.payload_utf8,queued.payload_digest)
    select * from claimed;
end
$$;

create function ingestion_quality.iq_release_quality_eligibility_outbox(
    requested_event_id uuid, expected_attempt bigint)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare iq_now timestamptz := statement_timestamp();
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_relay');
    update ingestion_quality.iq_quality_eligibility_outbox queued
       set status=case when queued.attempts >= 8 then 'failed' else 'pending' end,
           available_at=iq_now + least(interval '1 hour',
             interval '1 second' * power(2,greatest(queued.attempts-1,0))),
           claimed_until=null,
           last_error_code=case when queued.attempts >= 8
             then 'QUALITY_ELIGIBILITY_RELAY_ATTEMPTS_EXHAUSTED'
             else 'QUALITY_ELIGIBILITY_RELAY_UNAVAILABLE' end
     where queued.event_id=requested_event_id and queued.status='retrying'
       and queued.attempts=expected_attempt;
    return found;
end
$$;

create function ingestion_quality.iq_deliver_quality_eligibility_outbox(
    requested_event_id uuid, expected_attempt bigint)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare iq_now timestamptz := statement_timestamp();
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_relay');
    update ingestion_quality.iq_quality_eligibility_outbox queued
       set status='delivered',claimed_until=null,delivered_at=iq_now,last_error_code=null
     where queued.event_id=requested_event_id and queued.status='retrying'
       and queued.attempts=expected_attempt and queued.claimed_until > iq_now;
    return found;
end
$$;

create function ingestion_quality.iq_fail_quality_eligibility_outbox(
    requested_event_id uuid, expected_attempt bigint)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_relay');
    update ingestion_quality.iq_quality_eligibility_outbox queued
       set status='failed',claimed_until=null,delivered_at=null,
           last_error_code='QUALITY_ELIGIBILITY_PAYLOAD_INTEGRITY_INVALID'
     where queued.event_id=requested_event_id and queued.status='retrying'
       and queued.attempts=expected_attempt;
    return found;
end
$$;

create function ingestion_quality.iq_cleanup_quality_eligibility_expired(
    trusted_cutoff timestamptz)
returns bigint
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_ids uuid[];
    iq_deleted bigint := 0;
    iq_count bigint;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_retention_executor');
    if trusted_cutoff is null or trusted_cutoff > statement_timestamp() then
        raise exception using errcode='invalid_parameter_value',
            message='INGESTION_QUALITY_RETENTION_CUTOFF_INVALID';
    end if;
    delete from ingestion_quality.iq_quality_eligibility_idempotency
     where expires_at <= trusted_cutoff;
    get diagnostics iq_count=row_count; iq_deleted := iq_deleted + iq_count;
    delete from ingestion_quality.iq_quality_event_quarantine quarantine
     using ingestion_quality.iq_quality_event_inbox inbox
     where quarantine.event_id=inbox.event_id
       and inbox.processed_at + interval '90 days' <= trusted_cutoff;
    get diagnostics iq_count=row_count; iq_deleted := iq_deleted + iq_count;
    delete from ingestion_quality.iq_quality_event_inbox inbox
     where inbox.processed_at + interval '90 days' <= trusted_cutoff
       and not exists (select 1 from ingestion_quality.iq_quality_event_quarantine quarantine
          where quarantine.event_id=inbox.event_id);
    get diagnostics iq_count=row_count; iq_deleted := iq_deleted + iq_count;
    select array_agg(current_fact.eligibility_id) into iq_ids
      from ingestion_quality.iq_quality_eligibility_current current_fact
      join ingestion_quality.iq_quality_eligibility_history history
        on history.eligibility_id=current_fact.eligibility_id
       and history.aggregate_version=current_fact.aggregate_version
     where not history.legal_hold and history.retention_due_at <= trusted_cutoff
       and not exists (
         select 1 from ingestion_quality.iq_quality_eligibility_outbox queued
          where queued.eligibility_id=current_fact.eligibility_id
            and queued.status not in ('delivered','failed'));
    delete from ingestion_quality.iq_quality_eligibility_current
     where eligibility_id=any(coalesce(iq_ids,array[]::uuid[]));
    get diagnostics iq_count=row_count; iq_deleted := iq_deleted + iq_count;
    delete from ingestion_quality.iq_quality_eligibility_history
     where eligibility_id=any(coalesce(iq_ids,array[]::uuid[]))
       and not legal_hold and retention_due_at <= trusted_cutoff;
    get diagnostics iq_count=row_count; iq_deleted := iq_deleted + iq_count;
    delete from ingestion_quality.iq_quality_eligibility_outbox
     where eligibility_id=any(coalesce(iq_ids,array[]::uuid[]))
       and status in ('delivered','failed') and created_at <= trusted_cutoff;
    get diagnostics iq_count=row_count; iq_deleted := iq_deleted + iq_count;
    return iq_deleted;
end
$$;

-- All new relations and routines remain owner-local; workloads receive only exact entrypoints.
alter table ingestion_quality.iq_rule_dependency_registry owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_rule_dependency_rule owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_rule_dependency_member owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_event_inbox owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_dependency_cursor owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_pending_pair owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_event_quarantine owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_backfill_request owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_dependency_quality_current owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_eligibility_history owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_eligibility_current owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_eligibility_member_history owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_eligibility_audit owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_eligibility_audit_token_binding owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_eligibility_outbox owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_eligibility_idempotency owner to scholarsense_ingestion_quality_batch_owner;

revoke all privileges on table
    ingestion_quality.iq_rule_dependency_registry,
    ingestion_quality.iq_rule_dependency_rule,
    ingestion_quality.iq_rule_dependency_member,
    ingestion_quality.iq_quality_event_inbox,
    ingestion_quality.iq_quality_dependency_cursor,
    ingestion_quality.iq_quality_pending_pair,
    ingestion_quality.iq_quality_event_quarantine,
    ingestion_quality.iq_quality_backfill_request,
    ingestion_quality.iq_dependency_quality_current,
    ingestion_quality.iq_quality_eligibility_history,
    ingestion_quality.iq_quality_eligibility_current,
    ingestion_quality.iq_quality_eligibility_member_history,
    ingestion_quality.iq_quality_eligibility_audit,
    ingestion_quality.iq_quality_eligibility_audit_token_binding,
    ingestion_quality.iq_quality_eligibility_outbox,
    ingestion_quality.iq_quality_eligibility_idempotency
from public,
    scholarsense_ingestion_quality_online,
    scholarsense_ingestion_quality_quality_worker,
    scholarsense_ingestion_quality_relay,
    scholarsense_ingestion_quality_retention_executor,
    scholarsense_ingestion_quality_consumer_registry_authority,
    scholarsense_ingestion_quality_eligibility_consumer;

do $$
declare iq_function regprocedure;
begin
  for iq_function in
    select procedure.oid::regprocedure
      from pg_catalog.pg_proc procedure
     where procedure.pronamespace='ingestion_quality'::regnamespace
       and procedure.proname=any(array[
         'iq_quality_uuid_v7_derive','iq_guard_quality_eligibility_history',
         'iq_guard_quality_eligibility_current','iq_find_quality_snapshot_evidence',
         'iq_load_quality_eligibility_processing_state',
         'iq_accept_quality_eligibility_event','iq_find_quality_eligibility_ids',
         'iq_find_quality_eligibility_page','iq_find_quality_eligibility_page_members',
         'iq_resolve_quality_eligibility_source',
         'iq_append_quality_eligibility_read_audit',
         'iq_claim_next_quality_eligibility_outbox',
         'iq_release_quality_eligibility_outbox',
         'iq_deliver_quality_eligibility_outbox',
         'iq_fail_quality_eligibility_outbox',
         'iq_cleanup_quality_eligibility_expired'])
  loop
    execute format('revoke all on function %s from public',iq_function);
    execute format('alter function %s owner to scholarsense_ingestion_quality_batch_owner',
        iq_function);
  end loop;
end
$$;

revoke all on function ingestion_quality.iq_find_quality_snapshot_evidence(
    uuid,uuid,character) from public;
revoke all on function ingestion_quality.iq_quality_uuid_v7_derive(uuid,text) from public;
revoke all on function ingestion_quality.iq_require_exclusive_workload(name) from public;
revoke all on function ingestion_quality.iq_load_quality_eligibility_processing_state(
    uuid,varchar) from public;
revoke all on function ingestion_quality.iq_accept_quality_eligibility_event(
    uuid,varchar,bigint,character,jsonb) from public;
revoke all on function ingestion_quality.iq_find_quality_eligibility_ids(
    varchar,varchar,timestamptz,uuid,integer) from public;
revoke all on function ingestion_quality.iq_find_quality_eligibility_page(uuid[]) from public;
revoke all on function ingestion_quality.iq_find_quality_eligibility_page_members(uuid[]) from public;
revoke all on function ingestion_quality.iq_resolve_quality_eligibility_source(uuid) from public;
revoke all on function ingestion_quality.iq_append_quality_eligibility_read_audit(
    uuid,uuid,bigint,varchar,varchar,varchar,varchar,varchar,character,timestamptz,character) from public;
revoke all on function ingestion_quality.iq_claim_next_quality_eligibility_outbox() from public;
revoke all on function ingestion_quality.iq_release_quality_eligibility_outbox(uuid,bigint) from public;
revoke all on function ingestion_quality.iq_deliver_quality_eligibility_outbox(uuid,bigint) from public;
revoke all on function ingestion_quality.iq_fail_quality_eligibility_outbox(uuid,bigint) from public;
revoke all on function ingestion_quality.iq_cleanup_quality_eligibility_expired(timestamptz) from public;

grant execute on function ingestion_quality.iq_find_quality_snapshot_evidence(
    uuid,uuid,character) to scholarsense_ingestion_quality_eligibility_consumer;
grant execute on function ingestion_quality.iq_load_quality_eligibility_processing_state(
    uuid,varchar) to scholarsense_ingestion_quality_eligibility_consumer;
grant execute on function ingestion_quality.iq_accept_quality_eligibility_event(
    uuid,varchar,bigint,character,jsonb)
    to scholarsense_ingestion_quality_eligibility_consumer;
grant execute on function ingestion_quality.iq_find_quality_eligibility_ids(
    varchar,varchar,timestamptz,uuid,integer)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_find_quality_eligibility_page(uuid[])
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_find_quality_eligibility_page_members(uuid[])
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_resolve_quality_eligibility_source(uuid)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_append_quality_eligibility_read_audit(
    uuid,uuid,bigint,varchar,varchar,varchar,varchar,varchar,character,timestamptz,character)
    to scholarsense_ingestion_quality_online;
grant execute on function ingestion_quality.iq_claim_next_quality_eligibility_outbox()
    to scholarsense_ingestion_quality_relay;
grant execute on function ingestion_quality.iq_release_quality_eligibility_outbox(uuid,bigint)
    to scholarsense_ingestion_quality_relay;
grant execute on function ingestion_quality.iq_deliver_quality_eligibility_outbox(uuid,bigint)
    to scholarsense_ingestion_quality_relay;
grant execute on function ingestion_quality.iq_fail_quality_eligibility_outbox(uuid,bigint)
    to scholarsense_ingestion_quality_relay;
grant execute on function ingestion_quality.iq_cleanup_quality_eligibility_expired(timestamptz)
    to scholarsense_ingestion_quality_retention_executor;
