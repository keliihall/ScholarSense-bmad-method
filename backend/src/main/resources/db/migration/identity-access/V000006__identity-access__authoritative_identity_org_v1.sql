-- Story 1.6a: provider-neutral authority inbox, append-only facts, rebuildable
-- current projections, persistent job/checkpoint state, and fenced worker writes.

create table identity_access.ia_identity_source_inbox (
    batch_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    schema_version varchar(64) not null,
    source_version bigint not null check (source_version >= 1),
    from_watermark bigint not null check (from_watermark >= 0),
    to_watermark bigint not null check (to_watermark > from_watermark),
    envelope_digest char(64) not null check (envelope_digest ~ '^[0-9a-f]{64}$'),
    signature_digest char(64) not null check (signature_digest ~ '^[0-9a-f]{64}$'),
    encrypted_payload bytea not null,
    encrypted_data_key bytea not null,
    encryption_nonce bytea not null,
    encryption_key_ref varchar(255) not null,
    encryption_key_version varchar(64) not null,
    source_visible_at timestamptz not null,
    observed_at timestamptz not null,
    trace_id char(32) not null check (trace_id ~ '^[0-9a-f]{32}$'),
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    consumer_watermark bigint not null default 0 check (consumer_watermark >= 0),
    unique (source_id, feed_id, partition_id, to_watermark),
    constraint ia_identity_source_inbox_uuid_v7_ck check (
        substring(batch_id::text, 15, 1) = '7'
        and substring(batch_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_identity_source_inbox_contract_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and schema_version = 'IDENTITY-AUTHORITY-BATCH-1.0.0'
        and observed_at >= source_visible_at
        and octet_length(encrypted_payload) > 0
        and octet_length(encrypted_data_key) > 0
        and octet_length(encryption_nonce) > 0
        and expires_at > observed_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_source_archive (
    batch_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    schema_version varchar(64) not null,
    source_version bigint not null check (source_version >= 1),
    from_watermark bigint not null check (from_watermark >= 0),
    to_watermark bigint not null check (to_watermark > from_watermark),
    mapping_version varchar(64) not null,
    mapping_digest char(64) not null,
    envelope_digest char(64) not null,
    signature_digest char(64) not null,
    encrypted_payload bytea not null,
    encrypted_data_key bytea not null,
    encryption_nonce bytea not null,
    encryption_key_ref varchar(255) not null,
    encryption_key_version varchar(64) not null,
    source_visible_at timestamptz not null,
    observed_at timestamptz not null,
    applied_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    consumer_watermark bigint not null default 0 check (consumer_watermark >= 0),
    unique (source_id, feed_id, partition_id, consumer_projection, to_watermark),
    constraint ia_identity_source_archive_uuid_v7_ck check (
        substring(batch_id::text, 15, 1) = '7'
        and substring(batch_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_identity_source_archive_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'identity-org'
        and schema_version = 'IDENTITY-AUTHORITY-BATCH-1.0.0'
        and mapping_version = 'IDENTITY-ROLE-MAPPING-1.0.0'
        and mapping_digest ~ '^[0-9a-f]{64}$'
        and envelope_digest ~ '^[0-9a-f]{64}$'
        and signature_digest ~ '^[0-9a-f]{64}$'
        and octet_length(encrypted_payload) > 0
        and octet_length(encrypted_data_key) > 0
        and octet_length(encryption_nonce) > 0
        and observed_at >= source_visible_at
        and applied_at >= observed_at
        and expires_at > applied_at
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_source_fact (
    fact_id uuid primary key,
    batch_id uuid not null
        references identity_access.ia_identity_source_archive(batch_id),
    event_id uuid not null unique,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    record_kind varchar(32) not null,
    external_ref_digest char(64) not null,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    effective_from timestamptz not null,
    effective_to timestamptz,
    payload_digest char(64) not null,
    aggregate_version bigint not null check (aggregate_version >= 1),
    applied_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    consumer_watermark bigint not null default 0 check (consumer_watermark >= 0),
    unique (source_id, record_kind, external_ref_digest, source_version),
    constraint ia_identity_source_fact_uuid_v7_ck check (
        substring(fact_id::text, 15, 1) = '7'
        and substring(fact_id::text, 20, 1) ~ '^[89ab]$'
        and substring(event_id::text, 15, 1) = '7'
        and substring(event_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_identity_source_fact_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection in ('identity-org', 'responsibility')
        and record_kind in ('account', 'organization', 'employment-role', 'responsibility')
        and payload_digest ~ '^[0-9a-f]{64}$'
        and trace_id ~ '^[0-9a-f]{32}$'
        and (effective_to is null or effective_to > effective_from)
        and expires_at > applied_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_authoritative_account_current (
    account_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    external_ref_digest char(64) not null,
    subject_binding_token varchar(160) not null unique,
    status varchar(16) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    mapping_version varchar(64) not null,
    applied_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    legal_hold boolean not null default false,
    unique (source_id, external_ref_digest),
    constraint ia_authoritative_account_uuid_v7_ck check (
        substring(account_id::text, 15, 1) = '7'
        and substring(account_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_authoritative_account_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'identity-org'
        and external_ref_digest ~ '^[0-9a-f]{64}$'
        and subject_binding_token ~ '^[a-z]+_v1_k[0-9]+_[0-9a-f]{64}$'
        and status in ('active', 'inactive')
        and (effective_to is null or effective_to > effective_from)
        and mapping_version = 'IDENTITY-ROLE-MAPPING-1.0.0'
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_authoritative_subject_binding_history (
    subject_binding_token varchar(160) primary key,
    account_id uuid not null
        references identity_access.ia_authoritative_account_current(account_id),
    key_version varchar(16) not null,
    is_current boolean not null,
    source_version bigint not null check (source_version >= 1),
    first_seen_at timestamptz not null,
    last_seen_at timestamptz not null,
    read_approved_until timestamptz,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_authoritative_subject_binding_history_shape_ck check (
        subject_binding_token ~ '^[a-z]+_v1_k[0-9]+_[0-9a-f]{64}$'
        and key_version ~ '^k[0-9]+$'
        and last_seen_at >= first_seen_at
        and (
            (is_current and read_approved_until is null)
            or (not is_current and read_approved_until is not null)
        )
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create unique index ia_authoritative_subject_binding_current_uk
    on identity_access.ia_authoritative_subject_binding_history (account_id)
    where is_current;

create table identity_access.ia_authoritative_organization_current (
    organization_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    external_ref_digest char(64) not null,
    parent_external_ref_digest char(64),
    display_name varchar(128) not null,
    organization_type varchar(32) not null,
    status varchar(16) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    applied_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    legal_hold boolean not null default false,
    unique (source_id, external_ref_digest),
    constraint ia_authoritative_organization_uuid_v7_ck check (
        substring(organization_id::text, 15, 1) = '7'
        and substring(organization_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_authoritative_organization_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'identity-org'
        and external_ref_digest ~ '^[0-9a-f]{64}$'
        and (parent_external_ref_digest is null
            or parent_external_ref_digest ~ '^[0-9a-f]{64}$')
        and (parent_external_ref_digest is null
            or parent_external_ref_digest <> external_ref_digest)
        and length(btrim(display_name)) between 1 and 128
        and display_name = btrim(display_name)
        and organization_type in ('school', 'college', 'department')
        and status in ('active', 'inactive')
        and (effective_to is null or effective_to > effective_from)
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_authoritative_role_current (
    binding_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    account_id uuid not null,
    organization_id uuid not null,
    external_ref_digest char(64) not null,
    source_role_code varchar(64) not null,
    target_role_id varchar(32) not null,
    mapping_version varchar(64) not null,
    mapping_digest char(64) not null,
    status varchar(16) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    applied_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    legal_hold boolean not null default false,
    foreign key (account_id) references identity_access.ia_authoritative_account_current(account_id),
    foreign key (organization_id)
        references identity_access.ia_authoritative_organization_current(organization_id),
    unique (external_ref_digest),
    constraint ia_authoritative_role_uuid_v7_ck check (
        substring(binding_id::text, 15, 1) = '7'
        and substring(binding_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_authoritative_role_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'identity-org'
        and external_ref_digest ~ '^[0-9a-f]{64}$'
        and source_role_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
        and target_role_id in (
            'R1-COUNSELOR', 'R2-COLLEGE-MANAGER', 'R3-STUDENT-AFFAIRS',
            'R4-SCHOOL-LEADER', 'R5-COLLABORATOR', 'R6-DATA-OWNER',
            'R7-PLATFORM-OPS')
        and mapping_version = 'IDENTITY-ROLE-MAPPING-1.0.0'
        and mapping_digest ~ '^[0-9a-f]{64}$'
        and status in ('active', 'inactive')
        and (effective_to is null or effective_to > effective_from)
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_sync_job (
    job_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    status varchar(16) not null,
    health varchar(16) not null,
    freshness varchar(16) not null,
    requested_at timestamptz not null,
    completed_at timestamptz,
    last_successful_watermark bigint not null default 0,
    next_attempt_at timestamptz,
    retry_budget integer not null check (retry_budget >= 0),
    reason_code varchar(128),
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_identity_sync_job_uuid_v7_ck check (
        substring(job_id::text, 15, 1) = '7'
        and substring(job_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_identity_sync_job_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection in ('identity-org', 'responsibility')
        and status in ('queued', 'running', 'succeeded', 'failed', 'cancelled')
        and health in ('healthy', 'degraded')
        and freshness in ('fresh', 'stale')
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_sync_attempt (
    job_id uuid not null,
    attempt_no integer not null check (attempt_no >= 1),
    fencing_token bigint not null check (fencing_token >= 1),
    status varchar(16) not null,
    input_watermark bigint not null check (input_watermark >= 0),
    output_watermark bigint,
    started_at timestamptz not null,
    completed_at timestamptz,
    reason_code varchar(128),
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (job_id, attempt_no),
    unique (job_id, fencing_token),
    foreign key (job_id) references identity_access.ia_identity_sync_job(job_id),
    constraint ia_identity_sync_attempt_shape_ck check (
        status in ('running', 'succeeded', 'failed', 'cancelled')
        and (output_watermark is null or output_watermark >= input_watermark)
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_sync_failure_resolution (
    resolution_id uuid primary key,
    failed_job_id uuid not null unique,
    replacement_job_id uuid not null unique,
    resolved_by varchar(128) not null,
    resolution_code varchar(128) not null,
    trace_id char(32) not null,
    resolved_at timestamptz not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    foreign key (failed_job_id)
        references identity_access.ia_identity_sync_job(job_id),
    foreign key (replacement_job_id)
        references identity_access.ia_identity_sync_job(job_id),
    constraint ia_identity_sync_failure_resolution_uuid_v7_ck check (
        substring(resolution_id::text, 15, 1) = '7'
        and substring(resolution_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_identity_sync_failure_resolution_shape_ck check (
        resolved_by ~ '^[A-Za-z0-9._:@/-]{3,128}$'
        and resolution_code ~ '^[A-Z][A-Z0-9_]{2,127}$'
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_sync_lease (
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    job_id uuid not null,
    attempt_no integer not null,
    fencing_token bigint not null check (fencing_token >= 1),
    lease_owner varchar(128) not null,
    acquired_at timestamptz not null,
    lease_expires_at timestamptz not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (source_id, feed_id, partition_id, consumer_projection),
    foreign key (job_id, attempt_no)
        references identity_access.ia_identity_sync_attempt(job_id, attempt_no),
    constraint ia_identity_sync_lease_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection in ('identity-org', 'responsibility')
        and lease_expires_at > acquired_at
        and expires_at > acquired_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_sync_checkpoint (
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    source_version bigint not null default 0 check (source_version >= 0),
    source_watermark bigint not null default 0 check (source_watermark >= 0),
    aggregate_version bigint not null default 0 check (aggregate_version >= 0),
    last_successful_at timestamptz,
    health varchar(16) not null default 'degraded',
    freshness varchar(16) not null default 'stale',
    updated_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (source_id, feed_id, partition_id, consumer_projection),
    constraint ia_identity_sync_checkpoint_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection in ('identity-org', 'responsibility')
        and health in ('healthy', 'degraded')
        and freshness in ('fresh', 'stale')
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_rejected_record (
    rejection_id uuid primary key,
    batch_id uuid,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    source_version bigint,
    source_watermark bigint,
    payload_digest char(64),
    reason_code varchar(128) not null,
    replayable boolean not null,
    job_id uuid,
    attempt_no integer,
    trace_id char(32) not null,
    rejected_at timestamptz not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_identity_rejected_record_uuid_v7_ck check (
        substring(rejection_id::text, 15, 1) = '7'
        and substring(rejection_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_identity_rejected_record_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection in ('identity-org', 'responsibility')
        and (payload_digest is null or payload_digest ~ '^[0-9a-f]{64}$')
        and reason_code ~ '^[A-Z][A-Z0-9_]{2,127}$'
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_replay_request (
    request_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    requested_from bigint not null check (requested_from >= 1),
    requested_to bigint not null check (requested_to >= requested_from),
    status varchar(16) not null default 'requested',
    trace_id char(32) not null,
    requested_at timestamptz not null,
    covered_at timestamptz,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    unique (
        source_id, feed_id, partition_id, consumer_projection,
        requested_from, requested_to, trace_id),
    constraint ia_identity_replay_request_uuid_v7_ck check (
        substring(request_id::text, 15, 1) = '7'
        and substring(request_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_identity_replay_request_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection in ('identity-org', 'responsibility')
        and status in ('requested', 'covered')
        and trace_id ~ '^[0-9a-f]{32}$'
        and ((status = 'requested' and covered_at is null)
          or (status = 'covered' and covered_at is not null))
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_reconciliation_sample (
    reconciliation_id uuid primary key,
    source_id varchar(64) not null,
    scope varchar(128) not null,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    expected_digest char(64) not null,
    actual_digest char(64) not null,
    expected_count bigint not null check (expected_count >= 0),
    actual_count bigint not null check (actual_count >= 0),
    matched_count bigint not null check (matched_count >= 0),
    missing_count bigint not null check (missing_count >= 0),
    unexpected_count bigint not null check (unexpected_count >= 0),
    version_drift_count bigint not null check (version_drift_count >= 0),
    generated_at timestamptz not null,
    trace_id char(32) not null,
    mutation_applied boolean not null default false,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_identity_reconciliation_uuid_v7_ck check (
        substring(reconciliation_id::text, 15, 1) = '7'
        and substring(reconciliation_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_identity_reconciliation_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and expected_digest ~ '^[0-9a-f]{64}$'
        and actual_digest ~ '^[0-9a-f]{64}$'
        and trace_id ~ '^[0-9a-f]{32}$'
        and mutation_applied = false
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_slo_evidence (
    evidence_id uuid primary key,
    account_id uuid,
    record_kind varchar(32) not null,
    source_id varchar(64) not null,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    source_visible_at timestamptz not null,
    applied_at timestamptz not null,
    authorization_effective_at timestamptz not null,
    within_fifteen_minutes boolean not null,
    late_reason_code varchar(128),
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_identity_slo_evidence_uuid_v7_ck check (
        substring(evidence_id::text, 15, 1) = '7'
        and substring(evidence_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_identity_slo_evidence_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and record_kind in ('account', 'organization', 'employment-role')
        and applied_at >= source_visible_at
        and authorization_effective_at >= applied_at
        and (
            (account_id is null
             and late_reason_code = 'IDENTITY_AUTHORIZATION_READBACK_EMPTY')
            or (account_id is not null
                and late_reason_code is distinct from
                    'IDENTITY_AUTHORIZATION_READBACK_EMPTY')
        )
        and ((within_fifteen_minutes
              and late_reason_code is null
              and authorization_effective_at - source_visible_at <= interval '15 minutes')
          or (not within_fifteen_minutes and late_reason_code is not null))
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_identity_slo_compensation (
    evidence_id uuid primary key,
    account_id uuid,
    record_kind varchar(32) not null,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    source_visible_at timestamptz not null,
    applied_at timestamptz not null,
    attempted_authorization_effective_at timestamptz not null,
    within_fifteen_minutes boolean not null,
    late_reason_code varchar(128),
    reason_code varchar(128) not null,
    trace_id char(32) not null,
    status varchar(16) not null default 'pending',
    requested_at timestamptz not null,
    completed_at timestamptz,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_identity_slo_compensation_shape_ck check (
        reason_code = 'IDENTITY_SLO_EVIDENCE_WRITE_FAILED'
        and record_kind in ('account', 'organization', 'employment-role')
        and status in ('pending', 'completed')
        and ((status = 'pending' and completed_at is null)
          or (status = 'completed' and completed_at is not null))
        and (
            (account_id is null
             and late_reason_code = 'IDENTITY_AUTHORIZATION_READBACK_EMPTY')
            or (account_id is not null
                and late_reason_code is distinct from
                    'IDENTITY_AUTHORIZATION_READBACK_EMPTY')
        )
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > requested_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create index ia_identity_source_inbox_expiry_idx
    on identity_access.ia_identity_source_inbox (legal_hold, expires_at, consumer_watermark);
create index ia_identity_source_archive_expiry_idx
    on identity_access.ia_identity_source_archive
        (legal_hold, expires_at, consumer_watermark);
create index ia_identity_source_fact_expiry_idx
    on identity_access.ia_identity_source_fact (legal_hold, expires_at, consumer_watermark);
create index ia_identity_source_fact_history_idx
    on identity_access.ia_identity_source_fact
        (source_id, record_kind, external_ref_digest, source_version);
create index ia_authoritative_account_subject_idx
    on identity_access.ia_authoritative_account_current
        (subject_binding_token, status, effective_from, effective_to);
create index ia_authoritative_subject_binding_account_idx
    on identity_access.ia_authoritative_subject_binding_history
        (account_id, is_current, key_version);
create index ia_authoritative_organization_parent_idx
    on identity_access.ia_authoritative_organization_current
        (parent_external_ref_digest, status);
create index ia_authoritative_role_account_idx
    on identity_access.ia_authoritative_role_current
        (account_id, status, effective_from, effective_to);
create index ia_identity_sync_job_due_idx
    on identity_access.ia_identity_sync_job (status, next_attempt_at, requested_at);
create unique index ia_identity_sync_job_one_active_uk
    on identity_access.ia_identity_sync_job
        (source_id, feed_id, partition_id, consumer_projection)
    where status in ('queued', 'running');
create index ia_identity_sync_job_expiry_idx
    on identity_access.ia_identity_sync_job (legal_hold, expires_at);
create index ia_identity_sync_attempt_job_idx
    on identity_access.ia_identity_sync_attempt (job_id, attempt_no, fencing_token);
create index ia_identity_sync_attempt_expiry_idx
    on identity_access.ia_identity_sync_attempt (legal_hold, expires_at);
create index ia_identity_sync_failure_resolution_expiry_idx
    on identity_access.ia_identity_sync_failure_resolution (legal_hold, expires_at);
create index ia_identity_sync_lease_expiry_idx
    on identity_access.ia_identity_sync_lease (legal_hold, expires_at);
create index ia_identity_replay_request_pending_idx
    on identity_access.ia_identity_replay_request
        (source_id, feed_id, partition_id, consumer_projection, status, requested_from);
create index ia_identity_replay_request_expiry_idx
    on identity_access.ia_identity_replay_request (legal_hold, expires_at);
create index ia_identity_rejected_due_idx
    on identity_access.ia_identity_rejected_record (replayable, rejected_at, reason_code);
create index ia_identity_rejected_expiry_idx
    on identity_access.ia_identity_rejected_record (legal_hold, expires_at);
create index ia_identity_reconciliation_expiry_idx
    on identity_access.ia_identity_reconciliation_sample (legal_hold, expires_at);
create index ia_identity_slo_window_idx
    on identity_access.ia_identity_slo_evidence
        (authorization_effective_at, within_fifteen_minutes);
create index ia_identity_slo_expiry_idx
    on identity_access.ia_identity_slo_evidence (legal_hold, expires_at);
create index ia_identity_slo_compensation_pending_idx
    on identity_access.ia_identity_slo_compensation (status, requested_at);

do $migration$
begin
    if not exists (
        select 1 from pg_catalog.pg_roles
        where rolname = 'scholarsense_identity_sync_worker'
    ) then
        create role scholarsense_identity_sync_worker nologin;
    end if;
    if not exists (
        select 1 from pg_catalog.pg_roles
        where rolname = 'scholarsense_identity_current_reader'
    ) then
        create role scholarsense_identity_current_reader nologin;
    end if;
end
$migration$;

revoke all privileges on all tables in schema identity_access
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
grant usage on schema identity_access
    to scholarsense_identity_sync_worker, scholarsense_identity_current_reader;

grant select, insert on identity_access.ia_identity_source_inbox
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_identity_source_archive
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_identity_source_fact
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_authoritative_account_current
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_authoritative_subject_binding_history
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_authoritative_organization_current
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_authoritative_role_current
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_identity_sync_job
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_identity_sync_attempt
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_identity_sync_failure_resolution
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_identity_sync_lease
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_identity_sync_checkpoint
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_identity_replay_request
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_identity_rejected_record
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_identity_reconciliation_sample
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_identity_slo_evidence
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_identity_slo_compensation
    to scholarsense_identity_sync_worker;
grant insert (
    audit_id, actor_pseudonym, session_pseudonym, action, result, occurred_at,
    source_ip_pseudonym, trace_id, profile_version, schema_version,
    producer_module, actor_type, actor_search_token, role_ids,
    authorization_context, object_type, object_search_token, outcome,
    reason_code, purpose, projection_scope, recorded_at, time_source_profile,
    source_ip_search_token, tokenization_profile_version, key_version,
    aggregate_type, aggregate_id_search_token, aggregate_version,
    idempotency_key_digest, policy_versions, retention_schedule_version
) on identity_access.ia_local_audit_fact
    to scholarsense_identity_sync_worker;
grant insert (
    event_id, audit_id, event_type, schema_version, envelope, created_at,
    delivery_status, attempts, next_attempt_at
) on identity_access.ia_local_audit_outbox
    to scholarsense_identity_sync_worker;

grant select on identity_access.ia_authoritative_account_current
    to scholarsense_identity_current_reader;
grant select on identity_access.ia_authoritative_subject_binding_history
    to scholarsense_identity_current_reader;
grant select on identity_access.ia_authoritative_organization_current
    to scholarsense_identity_current_reader;
grant select on identity_access.ia_authoritative_role_current
    to scholarsense_identity_current_reader;
grant select on identity_access.ia_identity_sync_checkpoint
    to scholarsense_identity_current_reader;
grant scholarsense_identity_current_reader to scholarsense_identity_online;
