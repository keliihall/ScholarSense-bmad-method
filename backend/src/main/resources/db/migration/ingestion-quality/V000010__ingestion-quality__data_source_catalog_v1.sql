create schema if not exists ingestion_quality;

do $$ begin
    create role scholarsense_ingestion_quality_online nologin;
exception when duplicate_object then null;
end $$;
do $$ begin
    create role scholarsense_ingestion_quality_relay nologin;
exception when duplicate_object then null;
end $$;

create table ingestion_quality.iq_data_source_catalog (
    catalog_id uuid primary key,
    catalog_release_id uuid unique,
    contract_version varchar(64) not null,
    status varchar(32) not null check (status in ('draft','invalid','publishable','published')),
    aggregate_version bigint not null check (aggregate_version > 0),
    content_digest char(71) not null check (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    evidence_set_digest char(71),
    validation_errors jsonb not null default '[]'::jsonb,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    published_at timestamptz,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'ingestion-quality',
    legal_hold boolean not null default false,
    expires_at timestamptz,
    check ((status = 'published') =
        (catalog_release_id is not null and evidence_set_digest is not null and published_at is not null))
);

create table ingestion_quality.iq_source_id_reservation (
    source_id varchar(64) primary key,
    first_catalog_id uuid not null references ingestion_quality.iq_data_source_catalog(catalog_id),
    first_purpose varchar(128) not null,
    reserved_at timestamptz not null,
    retired_at timestamptz
);

create table ingestion_quality.iq_dependency_id_reservation (
    dependency_id varchar(64) primary key,
    source_id varchar(64) not null unique,
    first_catalog_id uuid not null references ingestion_quality.iq_data_source_catalog(catalog_id),
    reserved_at timestamptz not null,
    retired_at timestamptz
);

create table ingestion_quality.iq_source_contract (
    catalog_id uuid not null references ingestion_quality.iq_data_source_catalog(catalog_id),
    source_id varchar(64) not null references ingestion_quality.iq_source_id_reservation(source_id),
    purpose varchar(128) not null,
    schema_version varchar(64) not null,
    quality_gate_version varchar(64) not null,
    evidence_uri varchar(512) not null,
    runtime_evidence_claim varchar(32) not null check (runtime_evidence_claim in ('none','target-verified')),
    descriptor jsonb not null,
    primary key (catalog_id, source_id)
);

create table ingestion_quality.iq_dependency_binding (
    catalog_id uuid not null references ingestion_quality.iq_data_source_catalog(catalog_id),
    source_id varchar(64) not null,
    dependency_id varchar(64) not null references ingestion_quality.iq_dependency_id_reservation(dependency_id),
    requirement varchar(16) not null check (requirement in ('required','optional')),
    combination_operator varchar(16) not null check (combination_operator in ('all-of','any-of','threshold')),
    primary key (catalog_id, dependency_id),
    unique (catalog_id, source_id),
    foreign key (catalog_id, source_id)
        references ingestion_quality.iq_source_contract(catalog_id, source_id)
);

create table ingestion_quality.iq_catalog_validation_attempt (
    attempt_id uuid primary key,
    catalog_id uuid not null references ingestion_quality.iq_data_source_catalog(catalog_id),
    aggregate_version bigint not null,
    result varchar(32) not null check (result in ('invalid','publishable')),
    errors jsonb not null,
    validated_at timestamptz not null,
    trace_id varchar(64) not null,
    unique (catalog_id, aggregate_version)
);

create table ingestion_quality.iq_catalog_evidence (
    evidence_id uuid primary key,
    catalog_id uuid not null references ingestion_quality.iq_data_source_catalog(catalog_id),
    source_id varchar(64) not null,
    evidence_uri varchar(512) not null,
    evidence_digest char(71) not null,
    environment varchar(16) not null,
    authority varchar(256) not null,
    candidate_commit char(40) not null,
    candidate_tree char(40) not null,
    result varchar(16) not null check (result in ('pass','fail','skip','unsupported')),
    occurred_at timestamptz not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'ingestion-quality',
    legal_hold boolean not null default false,
    expires_at timestamptz,
    unique (catalog_id, source_id, evidence_digest),
    foreign key (catalog_id, source_id)
        references ingestion_quality.iq_source_contract(catalog_id, source_id)
);

create table ingestion_quality.iq_catalog_current (
    singleton boolean primary key default true check (singleton),
    catalog_id uuid not null unique references ingestion_quality.iq_data_source_catalog(catalog_id),
    aggregate_version bigint not null,
    switched_at timestamptz not null
);

create table ingestion_quality.iq_catalog_idempotency (
    idempotency_key_digest char(64) primary key,
    request_digest char(71) not null,
    catalog_id uuid not null references ingestion_quality.iq_data_source_catalog(catalog_id),
    response jsonb not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    check (expires_at > created_at)
);

create table ingestion_quality.iq_local_audit_fact (
    audit_id uuid primary key,
    actor_search_token varchar(128) not null,
    action varchar(128) not null,
    result varchar(32) not null,
    catalog_id uuid,
    aggregate_version bigint,
    trace_id varchar(64) not null,
    occurred_at timestamptz not null,
    authorization_context jsonb not null,
    schema_version varchar(64) not null default 'LOCAL-AUDIT-FACT-1.0.0',
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'ingestion-quality',
    legal_hold boolean not null default false,
    expires_at timestamptz
);

create table ingestion_quality.iq_local_audit_outbox (
    event_id uuid primary key,
    audit_id uuid not null unique references ingestion_quality.iq_local_audit_fact(audit_id),
    event_type varchar(128) not null,
    schema_version varchar(64) not null,
    producer varchar(64) not null,
    payload jsonb not null,
    payload_digest char(64) not null,
    status varchar(16) not null default 'pending' check (status in ('pending','retrying','delivered','failed')),
    attempts bigint not null default 0,
    available_at timestamptz not null,
    claimed_until timestamptz,
    delivered_at timestamptz,
    last_error_code varchar(128),
    created_at timestamptz not null
);

create index iq_catalog_status_updated_idx
    on ingestion_quality.iq_data_source_catalog(status, updated_at desc, catalog_id);
create index iq_validation_catalog_idx
    on ingestion_quality.iq_catalog_validation_attempt(catalog_id, aggregate_version desc);
create index iq_evidence_retention_idx
    on ingestion_quality.iq_catalog_evidence(legal_hold, expires_at, source_id);
create index iq_idempotency_expiry_idx
    on ingestion_quality.iq_catalog_idempotency(expires_at);
create index iq_audit_outbox_due_idx
    on ingestion_quality.iq_local_audit_outbox(status, available_at, event_id);

revoke all privileges on schema ingestion_quality from public;
revoke all privileges on all tables in schema ingestion_quality from public;
grant usage on schema ingestion_quality to scholarsense_ingestion_quality_online;
grant usage on schema ingestion_quality to scholarsense_ingestion_quality_relay;
grant select, insert, update on ingestion_quality.iq_data_source_catalog
    to scholarsense_ingestion_quality_online;
grant select, insert on ingestion_quality.iq_source_id_reservation
    to scholarsense_ingestion_quality_online;
grant select, insert on ingestion_quality.iq_dependency_id_reservation
    to scholarsense_ingestion_quality_online;
grant select, insert on ingestion_quality.iq_source_contract
    to scholarsense_ingestion_quality_online;
grant select, insert on ingestion_quality.iq_dependency_binding
    to scholarsense_ingestion_quality_online;
grant select, insert on ingestion_quality.iq_catalog_validation_attempt
    to scholarsense_ingestion_quality_online;
grant select, insert on ingestion_quality.iq_catalog_evidence
    to scholarsense_ingestion_quality_online;
grant select, insert, update on ingestion_quality.iq_catalog_current
    to scholarsense_ingestion_quality_online;
grant select, insert on ingestion_quality.iq_catalog_idempotency
    to scholarsense_ingestion_quality_online;
grant select, insert on ingestion_quality.iq_local_audit_fact
    to scholarsense_ingestion_quality_online;
grant select, insert on ingestion_quality.iq_local_audit_outbox
    to scholarsense_ingestion_quality_online;
grant select on ingestion_quality.iq_local_audit_fact
    to scholarsense_ingestion_quality_relay;
grant select, update on ingestion_quality.iq_local_audit_outbox
    to scholarsense_ingestion_quality_relay;
