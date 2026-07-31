-- Story 1.6b: responsibility-specific encrypted source custody, append-only facts,
-- rebuildable current scope, college exception queue, daily reconciliation, and SLO evidence.

create table identity_access.ia_responsibility_source_inbox (
    batch_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    schema_version varchar(64) not null,
    contract_version varchar(64) not null,
    source_version bigint not null check (source_version >= 1),
    from_watermark bigint not null check (from_watermark >= 0),
    to_watermark bigint not null check (to_watermark >= from_watermark),
    supporting_identity_org_watermarks jsonb not null,
    envelope_digest char(64) not null,
    signature_digest char(64) not null,
    encrypted_payload bytea not null,
    encrypted_data_key bytea not null,
    encryption_nonce bytea not null,
    encryption_key_ref varchar(255) not null,
    encryption_key_version varchar(64) not null,
    source_visible_at timestamptz not null,
    observed_at timestamptz not null,
    processing_status varchar(16) not null default 'received',
    consumer_watermark bigint not null default 0 check (consumer_watermark >= 0),
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_responsibility_source_inbox_uuid_v7_ck check (
        substring(batch_id::text, 15, 1) = '7'
        and substring(batch_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_source_inbox_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'responsibility'
        and schema_version = 'RESPONSIBILITY-BATCH-1.0.0'
        and contract_version = 'RESPONSIBILITY-AUTHORITY-1.0.0'
        and to_watermark >= from_watermark
        and jsonb_typeof(supporting_identity_org_watermarks) = 'object'
        and supporting_identity_org_watermarks <> '{}'::jsonb
        and envelope_digest ~ '^[0-9a-f]{64}$'
        and signature_digest ~ '^[0-9a-f]{64}$'
        and octet_length(encrypted_payload) > 0
        and octet_length(encrypted_data_key) > 0
        and octet_length(encryption_nonce) > 0
        and observed_at >= source_visible_at
        and processing_status in ('received', 'processing', 'applied', 'rejected')
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > observed_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_source_archive (
    batch_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    schema_version varchar(64) not null,
    contract_version varchar(64) not null,
    source_version bigint not null check (source_version >= 1),
    from_watermark bigint not null check (from_watermark >= 0),
    to_watermark bigint not null check (to_watermark >= from_watermark),
    supporting_identity_org_watermarks jsonb not null,
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
    consumer_watermark bigint not null default 0 check (consumer_watermark >= 0),
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_responsibility_source_archive_uuid_v7_ck check (
        substring(batch_id::text, 15, 1) = '7'
        and substring(batch_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_source_archive_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'responsibility'
        and schema_version = 'RESPONSIBILITY-BATCH-1.0.0'
        and contract_version = 'RESPONSIBILITY-AUTHORITY-1.0.0'
        and to_watermark >= from_watermark
        and jsonb_typeof(supporting_identity_org_watermarks) = 'object'
        and supporting_identity_org_watermarks <> '{}'::jsonb
        and envelope_digest ~ '^[0-9a-f]{64}$'
        and signature_digest ~ '^[0-9a-f]{64}$'
        and octet_length(encrypted_payload) > 0
        and octet_length(encrypted_data_key) > 0
        and octet_length(encryption_nonce) > 0
        and observed_at >= source_visible_at
        and applied_at >= observed_at
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > applied_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create unique index ia_responsibility_inbox_advancing_watermark_uk
    on identity_access.ia_responsibility_source_inbox (
        source_id, feed_id, partition_id, consumer_projection, to_watermark)
    where to_watermark > from_watermark;

create unique index ia_responsibility_archive_advancing_watermark_uk
    on identity_access.ia_responsibility_source_archive (
        source_id, feed_id, partition_id, consumer_projection, to_watermark)
    where to_watermark > from_watermark;

create table identity_access.ia_responsibility_source_fact (
    fact_id uuid primary key,
    batch_id uuid not null
        references identity_access.ia_responsibility_source_archive(batch_id),
    event_id uuid not null unique,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    relation_ref_token varchar(160) not null,
    student_ref_purpose varchar(64) not null,
    student_ref_key_version varchar(64) not null,
    student_ref_token varchar(160) not null,
    student_ref_digest char(64) not null,
    student_equivalence_digest char(64) not null,
    counselor_account_ref_digest char(64) not null,
    college_organization_ref_digest char(64) not null,
    responsibility_type varchar(16) not null,
    relation_status varchar(16) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    record_version bigint not null check (record_version >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    payload_digest char(64) not null,
    supporting_identity_org_watermarks jsonb not null,
    recipient_validity varchar(32) not null,
    recipient_reason_code varchar(128) not null,
    recipient_mapped boolean not null,
    applied_at timestamptz not null,
    trace_id char(32) not null,
    consumer_watermark bigint not null default 0 check (consumer_watermark >= 0),
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    unique (source_id, relation_ref_token, record_version),
    constraint ia_responsibility_source_fact_uuid_v7_ck check (
        substring(fact_id::text, 15, 1) = '7'
        and substring(fact_id::text, 20, 1) ~ '^[89ab]$'
        and substring(event_id::text, 15, 1) = '7'
        and substring(event_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_source_fact_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'responsibility'
        and relation_ref_token ~ '^rtok_[A-Za-z0-9_-]{32,128}$'
        and student_ref_purpose = 'RESPONSIBILITY-STUDENT-REF'
        and student_ref_key_version in ('resp-student-v1', 'resp-student-v2')
        and student_ref_token ~ '^stok_[A-Za-z0-9_-]{32,128}$'
        and student_ref_digest ~ '^[0-9a-f]{64}$'
        and student_equivalence_digest ~ '^[0-9a-f]{64}$'
        and counselor_account_ref_digest ~ '^[0-9a-f]{64}$'
        and college_organization_ref_digest ~ '^[0-9a-f]{64}$'
        and responsibility_type in ('primary', 'secondary')
        and relation_status in ('active', 'inactive')
        and (effective_to is null or effective_to > effective_from)
        and payload_digest ~ '^[0-9a-f]{64}$'
        and jsonb_typeof(supporting_identity_org_watermarks) = 'object'
        and supporting_identity_org_watermarks <> '{}'::jsonb
        and recipient_validity in (
            'valid', 'invalid', 'dependency-unavailable')
        and recipient_reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$'
        and (not recipient_mapped or recipient_validity = 'valid')
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > applied_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_current (
    relation_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    relation_ref_token varchar(160) not null unique,
    student_ref_purpose varchar(64) not null,
    student_ref_key_version varchar(64) not null,
    student_ref_token varchar(160) not null,
    student_ref_digest char(64) not null,
    student_equivalence_digest char(64) not null,
    counselor_account_ref_digest char(64) not null,
    college_organization_ref_digest char(64) not null,
    counselor_account_id uuid,
    college_organization_id uuid,
    responsibility_type varchar(16) not null,
    relation_status varchar(16) not null,
    recipient_validity varchar(32) not null,
    recipient_reason_code varchar(128) not null,
    quality_gate_status varchar(16) not null default 'trusted',
    effective_from timestamptz not null,
    effective_to timestamptz,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    record_version bigint not null check (record_version >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    applied_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    legal_hold boolean not null default false,
    foreign key (counselor_account_id)
        references identity_access.ia_authoritative_account_current(account_id),
    foreign key (college_organization_id)
        references identity_access.ia_authoritative_organization_current(organization_id),
    constraint ia_responsibility_current_uuid_v7_ck check (
        substring(relation_id::text, 15, 1) = '7'
        and substring(relation_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_current_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'responsibility'
        and relation_ref_token ~ '^rtok_[A-Za-z0-9_-]{32,128}$'
        and student_ref_purpose = 'RESPONSIBILITY-STUDENT-REF'
        and student_ref_key_version in ('resp-student-v1', 'resp-student-v2')
        and student_ref_token ~ '^stok_[A-Za-z0-9_-]{32,128}$'
        and student_ref_digest ~ '^[0-9a-f]{64}$'
        and student_equivalence_digest ~ '^[0-9a-f]{64}$'
        and counselor_account_ref_digest ~ '^[0-9a-f]{64}$'
        and college_organization_ref_digest ~ '^[0-9a-f]{64}$'
        and responsibility_type in ('primary', 'secondary')
        and relation_status in ('active', 'inactive')
        and recipient_validity in ('valid', 'invalid', 'dependency-unavailable')
        and recipient_reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$'
        and quality_gate_status in ('trusted', 'blocked')
        and (
            (recipient_validity = 'valid'
             and counselor_account_id is not null
             and college_organization_id is not null)
            or (recipient_validity <> 'valid'
                and counselor_account_id is null
                and college_organization_id is null)
        )
        and (effective_to is null or effective_to > effective_from)
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_exception_history (
    exception_event_id uuid primary key,
    exception_id uuid not null,
    business_key_digest char(64) not null,
    college_organization_ref_digest char(64) not null,
    student_ref_digest char(64) not null,
    event_type varchar(16) not null,
    reason_code varchar(128) not null,
    source_kind varchar(24) not null,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    occurred_at timestamptz not null,
    trace_id char(32) not null,
    consumer_watermark bigint not null default 0 check (consumer_watermark >= 0),
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    unique (exception_id, aggregate_version),
    constraint ia_responsibility_exception_history_uuid_v7_ck check (
        substring(exception_event_id::text, 15, 1) = '7'
        and substring(exception_event_id::text, 20, 1) ~ '^[89ab]$'
        and substring(exception_id::text, 15, 1) = '7'
        and substring(exception_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_exception_history_shape_ck check (
        business_key_digest ~ '^[0-9a-f]{64}$'
        and college_organization_ref_digest ~ '^[0-9a-f]{64}$'
        and student_ref_digest ~ '^[0-9a-f]{64}$'
        and event_type in ('opened', 'updated', 'resolved')
        and reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$'
        and source_kind in ('incremental', 'reconciliation')
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > occurred_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_exception_current (
    exception_id uuid primary key,
    business_key_digest char(64) not null unique,
    college_organization_ref_digest char(64) not null,
    student_ref_digest char(64) not null,
    reason_code varchar(128) not null,
    source_kind varchar(24) not null,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    first_seen_at timestamptz not null,
    last_seen_at timestamptz not null,
    resolved_at timestamptz,
    status varchar(16) not null,
    aggregate_version bigint not null check (aggregate_version >= 1),
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_responsibility_exception_current_uuid_v7_ck check (
        substring(exception_id::text, 15, 1) = '7'
        and substring(exception_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_exception_current_shape_ck check (
        business_key_digest ~ '^[0-9a-f]{64}$'
        and college_organization_ref_digest ~ '^[0-9a-f]{64}$'
        and student_ref_digest ~ '^[0-9a-f]{64}$'
        and reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$'
        and source_kind in ('incremental', 'reconciliation')
        and last_seen_at >= first_seen_at
        and status in ('open', 'resolved')
        and (
            (status = 'open' and resolved_at is null)
            or (status = 'resolved' and resolved_at >= last_seen_at)
        )
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_reconciliation_job (
    job_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    business_date date not null,
    job_kind varchar(32) not null,
    status varchar(16) not null,
    requested_at timestamptz not null,
    next_attempt_at timestamptz,
    completed_at timestamptz,
    retry_budget integer not null check (retry_budget >= 0),
    reason_code varchar(128),
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    unique (
        source_id, feed_id, partition_id, consumer_projection,
        business_date, job_kind),
    constraint ia_responsibility_reconciliation_job_uuid_v7_ck check (
        substring(job_id::text, 15, 1) = '7'
        and substring(job_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_reconciliation_job_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'responsibility'
        and job_kind = 'full-reconciliation'
        and status in (
            'queued', 'running', 'succeeded', 'failed', 'missed', 'cancelled')
        and (reason_code is null
            or reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$')
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > requested_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_reconciliation_attempt (
    job_id uuid not null,
    attempt_no integer not null check (attempt_no >= 1),
    fencing_token bigint not null check (fencing_token >= 1),
    status varchar(16) not null,
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
    foreign key (job_id)
        references identity_access.ia_responsibility_reconciliation_job(job_id),
    constraint ia_responsibility_reconciliation_attempt_shape_ck check (
        status in ('running', 'succeeded', 'failed', 'cancelled')
        and (reason_code is null
            or reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$')
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > started_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_reconciliation_lease (
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    business_date date not null,
    job_kind varchar(32) not null,
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
    primary key (
        source_id, feed_id, partition_id, consumer_projection,
        business_date, job_kind),
    foreign key (job_id, attempt_no)
        references identity_access.ia_responsibility_reconciliation_attempt(
            job_id, attempt_no),
    constraint ia_responsibility_reconciliation_lease_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'responsibility'
        and job_kind = 'full-reconciliation'
        and lease_expires_at > acquired_at
        and expires_at > acquired_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_reconciliation_run (
    run_id uuid primary key,
    job_id uuid not null unique
        references identity_access.ia_responsibility_reconciliation_job(job_id),
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    contract_version varchar(64) not null,
    schema_version varchar(64) not null,
    business_date date not null,
    source_version bigint not null check (source_version >= 1),
    through_watermark bigint not null check (through_watermark >= 0),
    supporting_identity_org_watermarks jsonb not null,
    expected_count bigint not null check (expected_count >= 0),
    actual_count bigint not null check (actual_count >= 0),
    expected_digest char(64) not null,
    actual_digest char(64) not null,
    matched_count bigint not null check (matched_count >= 0),
    missing_count bigint not null check (missing_count >= 0),
    unexpected_count bigint not null check (unexpected_count >= 0),
    version_drift_count bigint not null check (version_drift_count >= 0),
    match_rate numeric(7,6) not null check (match_rate between 0 and 1),
    active_unmapped_count bigint not null check (active_unmapped_count >= 0),
    exception_count bigint not null check (exception_count >= 0),
    job_outcome varchar(16) not null,
    reconciliation_outcome varchar(32) not null,
    reason_code varchar(128) not null,
    fencing_token bigint not null check (fencing_token >= 1),
    started_at timestamptz not null,
    completed_at timestamptz not null,
    trace_id char(32) not null,
    consumer_watermark bigint not null default 0 check (consumer_watermark >= 0),
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_responsibility_reconciliation_run_uuid_v7_ck check (
        substring(run_id::text, 15, 1) = '7'
        and substring(run_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_reconciliation_run_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'responsibility'
        and contract_version = 'RESPONSIBILITY-AUTHORITY-1.0.0'
        and schema_version = 'RESPONSIBILITY-SNAPSHOT-1.0.0'
        and jsonb_typeof(supporting_identity_org_watermarks) = 'object'
        and supporting_identity_org_watermarks <> '{}'::jsonb
        and expected_digest ~ '^[0-9a-f]{64}$'
        and actual_digest ~ '^[0-9a-f]{64}$'
        and job_outcome in ('succeeded', 'failed', 'missed')
        and reconciliation_outcome in (
            'matched', 'differences-found', 'threshold-failed')
        and reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$'
        and completed_at >= started_at
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > completed_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_reconciliation_detail (
    detail_id uuid primary key,
    run_id uuid not null
        references identity_access.ia_responsibility_reconciliation_run(run_id),
    difference_type varchar(24) not null,
    relation_ref_token varchar(160) not null,
    student_ref_digest char(64) not null,
    expected_record_version bigint,
    actual_record_version bigint,
    expected_payload_digest char(64),
    actual_payload_digest char(64),
    reason_code varchar(128) not null,
    trace_id char(32) not null,
    consumer_watermark bigint not null default 0 check (consumer_watermark >= 0),
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    unique (run_id, difference_type, relation_ref_token),
    constraint ia_responsibility_reconciliation_detail_uuid_v7_ck check (
        substring(detail_id::text, 15, 1) = '7'
        and substring(detail_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_reconciliation_detail_shape_ck check (
        difference_type in (
            'missing', 'unexpected', 'version-drift', 'duplicate')
        and relation_ref_token ~ '^rtok_[A-Za-z0-9_-]{32,128}$'
        and student_ref_digest ~ '^[0-9a-f]{64}$'
        and (expected_payload_digest is null
            or expected_payload_digest ~ '^[0-9a-f]{64}$')
        and (actual_payload_digest is null
            or actual_payload_digest ~ '^[0-9a-f]{64}$')
        and reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$'
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_slo_evidence (
    evidence_id uuid primary key,
    relation_id uuid,
    student_ref_digest char(64) not null,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    source_visible_at timestamptz not null,
    applied_at timestamptz not null,
    authorization_effective_at timestamptz not null,
    within_fifteen_minutes boolean not null,
    late_reason_code varchar(128),
    trace_id char(32) not null,
    consumer_watermark bigint not null check (consumer_watermark >= 1),
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    unique (
        relation_id, source_version, source_watermark, aggregate_version),
    constraint ia_responsibility_slo_evidence_uuid_v7_ck check (
        substring(evidence_id::text, 15, 1) = '7'
        and substring(evidence_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_slo_evidence_shape_ck check (
        student_ref_digest ~ '^[0-9a-f]{64}$'
        and applied_at >= source_visible_at
        and authorization_effective_at >= applied_at
        and (
            (within_fifteen_minutes
             and late_reason_code is null
             and authorization_effective_at - source_visible_at
                <= interval '15 minutes')
            or (not within_fifteen_minutes
                and late_reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$')
        )
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > authorization_effective_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_slo_compensation (
    evidence_id uuid primary key,
    relation_id uuid,
    student_ref_digest char(64) not null,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    source_visible_at timestamptz not null,
    applied_at timestamptz not null,
    attempted_authorization_effective_at timestamptz not null,
    within_fifteen_minutes boolean not null,
    late_reason_code varchar(128) not null,
    reason_code varchar(128) not null,
    status varchar(16) not null default 'pending',
    requested_at timestamptz not null,
    completed_at timestamptz,
    trace_id char(32) not null,
    consumer_watermark bigint not null check (consumer_watermark >= 1),
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_responsibility_slo_compensation_shape_ck check (
        student_ref_digest ~ '^[0-9a-f]{64}$'
        and late_reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$'
        and reason_code = 'RESPONSIBILITY_SLO_EVIDENCE_WRITE_FAILED'
        and status in ('pending', 'completed')
        and (
            (status = 'pending' and completed_at is null)
            or (status = 'completed' and completed_at >= requested_at)
        )
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > requested_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create index ia_responsibility_source_inbox_expiry_idx
    on identity_access.ia_responsibility_source_inbox
        (legal_hold, expires_at, consumer_watermark);
create index ia_responsibility_source_archive_expiry_idx
    on identity_access.ia_responsibility_source_archive
        (legal_hold, expires_at, consumer_watermark);
create index ia_responsibility_source_fact_history_idx
    on identity_access.ia_responsibility_source_fact
        (source_id, relation_ref_token, record_version);
create index ia_responsibility_source_fact_as_of_idx
    on identity_access.ia_responsibility_source_fact
        (source_id, feed_id, partition_id, source_watermark, relation_ref_token);
create index ia_responsibility_source_fact_expiry_idx
    on identity_access.ia_responsibility_source_fact
        (legal_hold, expires_at, consumer_watermark);
create index ia_responsibility_current_student_idx
    on identity_access.ia_responsibility_current
        (student_ref_digest, relation_status, effective_from, effective_to, relation_id);
create index ia_responsibility_current_recipient_idx
    on identity_access.ia_responsibility_current
        (counselor_account_id, relation_status, effective_from, effective_to, relation_id);
create index ia_responsibility_exception_college_open_idx
    on identity_access.ia_responsibility_exception_current
        (college_organization_ref_digest, status, last_seen_at desc, exception_id);
create index ia_responsibility_exception_student_idx
    on identity_access.ia_responsibility_exception_current
        (student_ref_digest, status, exception_id);
create index ia_responsibility_exception_history_expiry_idx
    on identity_access.ia_responsibility_exception_history
        (legal_hold, expires_at, consumer_watermark);
create index ia_responsibility_reconciliation_job_due_idx
    on identity_access.ia_responsibility_reconciliation_job
        (status, next_attempt_at, business_date, job_id);
create index ia_responsibility_reconciliation_job_expiry_idx
    on identity_access.ia_responsibility_reconciliation_job
        (legal_hold, expires_at);
create index ia_responsibility_reconciliation_attempt_expiry_idx
    on identity_access.ia_responsibility_reconciliation_attempt
        (legal_hold, expires_at);
create index ia_responsibility_reconciliation_lease_expiry_idx
    on identity_access.ia_responsibility_reconciliation_lease
        (legal_hold, expires_at);
create index ia_responsibility_reconciliation_run_business_date_idx
    on identity_access.ia_responsibility_reconciliation_run
        (business_date desc, reconciliation_outcome, run_id);
create index ia_responsibility_reconciliation_run_expiry_idx
    on identity_access.ia_responsibility_reconciliation_run
        (legal_hold, expires_at, consumer_watermark);
create index ia_responsibility_reconciliation_detail_expiry_idx
    on identity_access.ia_responsibility_reconciliation_detail
        (legal_hold, expires_at, consumer_watermark);
create index ia_responsibility_slo_window_idx
    on identity_access.ia_responsibility_slo_evidence
        (authorization_effective_at, within_fifteen_minutes, evidence_id);
create index ia_responsibility_slo_expiry_idx
    on identity_access.ia_responsibility_slo_evidence
        (legal_hold, expires_at, consumer_watermark);
create index ia_responsibility_slo_compensation_pending_idx
    on identity_access.ia_responsibility_slo_compensation
        (status, requested_at, consumer_watermark, evidence_id);

revoke all privileges on identity_access.ia_responsibility_source_inbox
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_source_archive
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_source_fact
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_current
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_exception_history
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_exception_current
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_reconciliation_job
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_reconciliation_attempt
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_reconciliation_lease
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_reconciliation_run
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_reconciliation_detail
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_slo_evidence
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;
revoke all privileges on identity_access.ia_responsibility_slo_compensation
    from scholarsense_identity_sync_worker, scholarsense_identity_current_reader;

grant select, insert, update on identity_access.ia_responsibility_source_inbox
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_responsibility_source_archive
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_responsibility_source_fact
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_responsibility_current
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_responsibility_exception_history
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_responsibility_exception_current
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_responsibility_reconciliation_job
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_responsibility_reconciliation_attempt
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_responsibility_reconciliation_lease
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_responsibility_reconciliation_run
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_responsibility_reconciliation_detail
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_responsibility_slo_evidence
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_responsibility_slo_compensation
    to scholarsense_identity_sync_worker;

grant select on identity_access.ia_responsibility_current
    to scholarsense_identity_current_reader;
grant select on identity_access.ia_responsibility_exception_current
    to scholarsense_identity_current_reader;
grant select on identity_access.ia_responsibility_reconciliation_run
    to scholarsense_identity_current_reader;
