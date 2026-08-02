-- Story 1.6c: immutable access invalidation facts, transactional business
-- outbox, route-scoped consumer watermarks, fenced jobs, and reconciliation.

-- V2 replay uses the existing encrypted custody tables. V7 intentionally
-- froze them to V1, so the successor migration must widen both checks before
-- a V2 heartbeat or replay batch can be archived.
alter table identity_access.ia_responsibility_source_inbox
    drop constraint ia_responsibility_source_inbox_shape_ck,
    add constraint ia_responsibility_source_inbox_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'responsibility'
        and schema_version = 'RESPONSIBILITY-BATCH-1.0.0'
        and contract_version in (
            'RESPONSIBILITY-AUTHORITY-1.0.0',
            'RESPONSIBILITY-AUTHORITY-2.0.0')
        and to_watermark >= from_watermark
        and jsonb_typeof(supporting_identity_org_watermarks) = 'object'
        and supporting_identity_org_watermarks <> '{}'::jsonb
        and envelope_digest ~ '^[0-9a-f]{64}$'
        and signature_digest ~ '^[0-9a-f]{64}$'
        and octet_length(encrypted_payload) > 0
        and octet_length(encrypted_data_key) > 0
        and octet_length(encryption_nonce) > 0
        and observed_at >= source_visible_at
        and processing_status in (
            'received', 'processing', 'applied', 'rejected')
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > observed_at
        and retention_schedule_version = 'RS-1.0.0'
    );

alter table identity_access.ia_responsibility_source_archive
    drop constraint ia_responsibility_source_archive_shape_ck,
    add constraint ia_responsibility_source_archive_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'responsibility'
        and schema_version = 'RESPONSIBILITY-BATCH-1.0.0'
        and contract_version in (
            'RESPONSIBILITY-AUTHORITY-1.0.0',
            'RESPONSIBILITY-AUTHORITY-2.0.0')
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
    );

alter table identity_access.ia_responsibility_reconciliation_run
    drop constraint ia_responsibility_reconciliation_run_shape_ck,
    add constraint ia_responsibility_reconciliation_run_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and consumer_projection = 'responsibility'
        and contract_version in (
            'RESPONSIBILITY-AUTHORITY-1.0.0',
            'RESPONSIBILITY-AUTHORITY-2.0.0')
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
    );

-- V1 and V2 replay the same authoritative watermark into isolated
-- projections.  The V7 indexes omitted contract_version and therefore made
-- a complete V2 replay collide with the already archived V1 row.
drop index identity_access.ia_responsibility_inbox_advancing_watermark_uk;
create unique index ia_responsibility_inbox_advancing_watermark_uk
    on identity_access.ia_responsibility_source_inbox (
        source_id, feed_id, partition_id, consumer_projection,
        contract_version, to_watermark)
    where to_watermark > from_watermark;

drop index identity_access.ia_responsibility_archive_advancing_watermark_uk;
create unique index ia_responsibility_archive_advancing_watermark_uk
    on identity_access.ia_responsibility_source_archive (
        source_id, feed_id, partition_id, consumer_projection,
        contract_version, to_watermark)
    where to_watermark > from_watermark;

-- The live projection needs the exact current source payload digest for
-- self-contained invalidation facts and for the V2 shadow cutover copy.
alter table identity_access.ia_responsibility_current
    add column payload_digest char(64);

update identity_access.ia_responsibility_current current
   set payload_digest = fact.payload_digest
  from identity_access.ia_responsibility_source_fact fact
 where fact.source_id=current.source_id
   and fact.feed_id=current.feed_id
   and fact.partition_id=current.partition_id
   and fact.consumer_projection=current.consumer_projection
   and fact.relation_ref_token=current.relation_ref_token
   and fact.record_version=current.record_version;

alter table identity_access.ia_responsibility_current
    alter column payload_digest set not null,
    add constraint ia_responsibility_current_payload_digest_ck
        check (payload_digest ~ '^[0-9a-f]{64}$');

-- V2 authority metadata is nullable for approved V1 dual-read rows. V1 rows
-- remain dynamically fail-closed but cannot publish a fabricated lineage.
alter table identity_access.ia_responsibility_current
    add column access_lineage_id varchar(160),
    add column access_event_id uuid,
    add column access_change_kind varchar(24),
    add column access_reason_code varchar(64),
    add column access_effective_at timestamptz,
    add column access_supersedes_id uuid,
    add constraint ia_responsibility_current_access_v2_ck check (
        (
            access_lineage_id is null
            and access_event_id is null
            and access_change_kind is null
            and access_reason_code is null
            and access_effective_at is null
            and access_supersedes_id is null
        )
        or
        (
            access_lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
            and substring(access_event_id::text, 15, 1) = '7'
            and substring(access_event_id::text, 20, 1) ~ '^[89ab]$'
            and access_change_kind in (
                'corrected', 'revoked', 'expired',
                'invalidated', 'revalidated')
            and access_reason_code in (
                'DIRECT_RESPONSIBILITY_CHANGE',
                'ACCOUNT_DISABLED',
                'R1_EMPLOYMENT_INVALID',
                'COLLEGE_INVALID',
                'RELATION_EXPIRED',
                'COMPLETE_SNAPSHOT_MISSING',
                'QUALITY_GATE_INVALID',
                'RECONCILIATION_RECOVERED',
                'SOURCE_CORRECTION')
            and access_effective_at is not null
            and (
                access_supersedes_id is null
                or (
                    substring(access_supersedes_id::text, 15, 1) = '7'
                    and substring(access_supersedes_id::text, 20, 1)
                        ~ '^[89ab]$'
                )
            )
        )
    );

create unique index ia_responsibility_current_access_lineage_uk
    on identity_access.ia_responsibility_current (access_lineage_id)
    where access_lineage_id is not null;

-- A role correction may move the same source role to a different account or
-- college.  Preserve every authoritative association so post-commit impact
-- and SLO fan-out can invalidate both the old and new affected scopes rather
-- than consulting only the overwritten current row.
create table identity_access.ia_authoritative_role_binding_history (
    source_id varchar(64) not null,
    role_external_ref_digest char(64) not null,
    source_version bigint not null check (source_version >= 1),
    binding_id uuid not null,
    account_id uuid not null,
    organization_id uuid not null,
    source_role_code varchar(64) not null,
    target_role_id varchar(32) not null,
    mapping_version varchar(64) not null,
    mapping_digest char(64) not null,
    status varchar(16) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    recorded_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (source_id, role_external_ref_digest, source_version),
    constraint ia_authoritative_role_binding_history_uuid_v7_ck check (
        substring(binding_id::text, 15, 1) = '7'
        and substring(binding_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_authoritative_role_binding_history_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and role_external_ref_digest ~ '^[0-9a-f]{64}$'
        and source_role_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
        and target_role_id in (
            'R1-COUNSELOR', 'R2-COLLEGE-MANAGER',
            'R3-STUDENT-AFFAIRS', 'R4-SCHOOL-LEADER',
            'R5-COLLABORATOR', 'R6-DATA-OWNER',
            'R7-PLATFORM-OPS')
        and mapping_version = 'IDENTITY-ROLE-MAPPING-1.0.0'
        and mapping_digest ~ '^[0-9a-f]{64}$'
        and status in ('active', 'inactive')
        and (effective_to is null or effective_to > effective_from)
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > recorded_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create index ia_authoritative_role_binding_history_impact_idx
    on identity_access.ia_authoritative_role_binding_history (
        role_external_ref_digest, account_id, organization_id,
        source_version desc);

insert into identity_access.ia_authoritative_role_binding_history (
    source_id, role_external_ref_digest, source_version, binding_id,
    account_id, organization_id, source_role_code, target_role_id,
    mapping_version, mapping_digest, status, effective_from, effective_to,
    recorded_at, trace_id, retention_effective_at, expires_at, legal_hold)
select source_id, external_ref_digest, source_version, binding_id,
       account_id, organization_id, source_role_code, target_role_id,
       mapping_version, mapping_digest, status, effective_from, effective_to,
       applied_at, trace_id, retention_effective_at,
       applied_at + interval '2190 days', legal_hold
  from identity_access.ia_authoritative_role_current
on conflict do nothing;

-- A source relation token and its lineage form a permanent one-to-one
-- binding.  Neither a replay nor a later correction may fork a new root.
create table identity_access.ia_responsibility_lineage_binding (
    source_id varchar(64) not null,
    relation_ref_token varchar(160) not null,
    lineage_id varchar(160) not null,
    bound_event_id uuid not null,
    bound_at timestamptz not null,
    trace_id char(32) not null,
    primary key (source_id, relation_ref_token),
    unique (source_id, lineage_id),
    unique (source_id, relation_ref_token, lineage_id),
    constraint ia_responsibility_lineage_binding_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and relation_ref_token ~ '^rtok_[A-Za-z0-9_-]{32,128}$'
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and substring(bound_event_id::text, 15, 1) = '7'
        and substring(bound_event_id::text, 20, 1) ~ '^[89ab]$'
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

-- V2 replay facts are kept apart from the immutable V1 table.  This lets a
-- zero-based successor replay retain every source event without weakening
-- any V1 uniqueness or custody invariant.
create table identity_access.ia_responsibility_v2_source_fact (
    fact_id uuid primary key,
    batch_id uuid not null
        references identity_access.ia_responsibility_source_archive(batch_id),
    event_id uuid not null unique,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    relation_ref_token varchar(160) not null,
    student_ref_digest char(64) not null,
    student_equivalence_digest char(64) not null,
    relation_status varchar(16) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    record_version bigint not null check (record_version >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    payload_digest char(64) not null,
    supporting_identity_org_watermarks jsonb not null,
    recipient_mapped boolean not null,
    lineage_id varchar(160) not null,
    supersedes_id uuid,
    change_kind varchar(24) not null,
    reason_code varchar(64) not null,
    change_effective_at timestamptz not null,
    applied_at timestamptz not null,
    trace_id char(32) not null,
    consumer_watermark bigint not null default 0
        check (consumer_watermark >= 0),
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    unique (source_id, relation_ref_token, record_version),
    unique (source_id, feed_id, partition_id, lineage_id, aggregate_version),
    foreign key (source_id, relation_ref_token, lineage_id)
        references identity_access.ia_responsibility_lineage_binding (
            source_id, relation_ref_token, lineage_id),
    foreign key (supersedes_id)
        references identity_access.ia_responsibility_v2_source_fact(event_id),
    constraint ia_responsibility_v2_source_fact_uuid_v7_ck check (
        substring(fact_id::text, 15, 1) = '7'
        and substring(fact_id::text, 20, 1) ~ '^[89ab]$'
        and substring(event_id::text, 15, 1) = '7'
        and substring(event_id::text, 20, 1) ~ '^[89ab]$'
        and (
            supersedes_id is null
            or (
                substring(supersedes_id::text, 15, 1) = '7'
                and substring(supersedes_id::text, 20, 1) ~ '^[89ab]$'
            )
        )
    ),
    constraint ia_responsibility_v2_source_fact_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and feed_id = 'responsibility-authority'
        and consumer_projection = 'responsibility'
        and partition_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        and length(partition_id) <= 64
        and relation_ref_token ~ '^rtok_[A-Za-z0-9_-]{32,128}$'
        and student_ref_digest ~ '^[0-9a-f]{64}$'
        and student_equivalence_digest ~ '^[0-9a-f]{64}$'
        and relation_status in ('active', 'inactive')
        and (effective_to is null or effective_to > effective_from)
        and payload_digest ~ '^[0-9a-f]{64}$'
        and jsonb_typeof(supporting_identity_org_watermarks) = 'object'
        and supporting_identity_org_watermarks <> '{}'::jsonb
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and change_kind in (
            'corrected', 'revoked', 'expired', 'invalidated', 'revalidated')
        and reason_code in (
            'DIRECT_RESPONSIBILITY_CHANGE', 'RELATION_EXPIRED',
            'COMPLETE_SNAPSHOT_MISSING', 'QUALITY_GATE_INVALID',
            'RECONCILIATION_RECOVERED', 'SOURCE_CORRECTION')
        and change_effective_at >= effective_from
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > applied_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create index ia_responsibility_v2_source_fact_snapshot_idx
    on identity_access.ia_responsibility_v2_source_fact (
        source_id, feed_id, partition_id, source_watermark,
        relation_ref_token, record_version desc);

-- The cutover gate is driven by a source-authenticated complete manifest,
-- never by caller-supplied expected/actual digests.  Header, current entries,
-- and lineage summaries are append-only custody evidence.
create table identity_access.ia_responsibility_v2_reconciliation_snapshot (
    snapshot_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    schema_version varchar(64) not null,
    contract_version varchar(64) not null,
    business_date date not null,
    cutoff_at timestamptz not null,
    source_version bigint not null check (source_version >= 1),
    through_watermark bigint not null check (through_watermark >= 1),
    supporting_identity_org_watermarks jsonb not null,
    expected_count bigint not null check (expected_count >= 0),
    canonical_digest char(64) not null,
    lineage_count bigint not null check (lineage_count >= 0),
    canonical_lineage_digest char(64) not null,
    envelope_digest char(64) not null,
    signature_digest char(64) not null,
    recorded_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    unique (source_id, feed_id, partition_id, business_date, snapshot_id),
    constraint ia_responsibility_v2_snapshot_uuid_v7_ck check (
        substring(snapshot_id::text, 15, 1) = '7'
        and substring(snapshot_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_v2_snapshot_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and feed_id = 'responsibility-authority'
        and consumer_projection = 'responsibility'
        and partition_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        and length(partition_id) <= 64
        and schema_version = 'RESPONSIBILITY-SNAPSHOT-1.0.0'
        and contract_version = 'RESPONSIBILITY-AUTHORITY-2.0.0'
        and jsonb_typeof(supporting_identity_org_watermarks) = 'object'
        and supporting_identity_org_watermarks <> '{}'::jsonb
        and canonical_digest ~ '^[0-9a-f]{64}$'
        and canonical_lineage_digest ~ '^[0-9a-f]{64}$'
        and envelope_digest ~ '^[0-9a-f]{64}$'
        and signature_digest ~ '^[0-9a-f]{64}$'
        and trace_id ~ '^[0-9a-f]{32}$'
        and expires_at > recorded_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_responsibility_v2_snapshot_entry (
    snapshot_id uuid not null references identity_access
        .ia_responsibility_v2_reconciliation_snapshot(snapshot_id),
    relation_ref_token varchar(160) not null,
    student_equivalence_digest char(64) not null,
    record_version bigint not null check (record_version >= 1),
    payload_digest char(64) not null,
    primary key (snapshot_id, relation_ref_token),
    constraint ia_responsibility_v2_snapshot_entry_shape_ck check (
        relation_ref_token ~ '^rtok_[A-Za-z0-9_-]{32,128}$'
        and student_equivalence_digest ~ '^[0-9a-f]{64}$'
        and payload_digest ~ '^[0-9a-f]{64}$'
    )
);

create table identity_access.ia_responsibility_v2_snapshot_lineage (
    snapshot_id uuid not null references identity_access
        .ia_responsibility_v2_reconciliation_snapshot(snapshot_id),
    relation_ref_token varchar(160) not null,
    lineage_id varchar(160) not null,
    root_event_id uuid not null,
    head_event_id uuid not null,
    event_count bigint not null check (event_count >= 1),
    canonical_chain_digest char(64) not null,
    primary key (snapshot_id, lineage_id),
    unique (snapshot_id, relation_ref_token),
    constraint ia_responsibility_v2_snapshot_lineage_uuid_v7_ck check (
        substring(root_event_id::text, 15, 1) = '7'
        and substring(root_event_id::text, 20, 1) ~ '^[89ab]$'
        and substring(head_event_id::text, 15, 1) = '7'
        and substring(head_event_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_v2_snapshot_lineage_shape_ck check (
        relation_ref_token ~ '^rtok_[A-Za-z0-9_-]{32,128}$'
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and canonical_chain_digest ~ '^[0-9a-f]{64}$'
    )
);

-- A separately authenticated operator/approval command is the idempotency
-- root for every requested V2 cutover. Routine ingestion cannot observe or
-- mutate these commands, and completion cannot rewrite their authorization
-- identity, route, business date, profile, request time, or trace.
create table identity_access.ia_responsibility_v2_cutover_command (
    command_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    business_date date not null,
    operator_ref varchar(160) not null,
    approval_ref varchar(160) not null,
    profile_digest char(64) not null,
    signature_digest char(64) not null,
    status varchar(24) not null default 'requested',
    reason_code varchar(96) not null default
        'RESPONSIBILITY_V2_CUTOVER_REQUESTED',
    snapshot_id uuid references identity_access
        .ia_responsibility_v2_reconciliation_snapshot(snapshot_id),
    requested_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    trace_id char(32) not null,
    constraint ia_responsibility_v2_cutover_command_uuid_v7_ck check (
        substring(command_id::text, 15, 1) = '7'
        and substring(command_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_responsibility_v2_cutover_command_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and feed_id = 'responsibility-authority'
        and consumer_projection = 'responsibility'
        and partition_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        and length(partition_id) <= 64
        and operator_ref ~ '^[A-Za-z0-9:/._-]{8,160}$'
        and approval_ref ~ '^[A-Za-z0-9:/._-]{8,160}$'
        and profile_digest ~ '^[0-9a-f]{64}$'
        and signature_digest ~ '^[0-9a-f]{64}$'
        and status in ('requested', 'denied', 'failed', 'activated')
        and reason_code ~ '^RESPONSIBILITY_V2_[A-Z0-9_]+$'
        and updated_at >= requested_at
        and (
            (status = 'requested'
                and reason_code = 'RESPONSIBILITY_V2_CUTOVER_REQUESTED'
                and snapshot_id is null
                and completed_at is null)
            or (status in ('denied', 'failed')
                and completed_at is not null
                and completed_at >= requested_at)
            or (status = 'activated'
                and snapshot_id is not null
                and completed_at is not null
                and completed_at >= requested_at)
        )
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

create index ia_responsibility_v2_cutover_command_route_idx
    on identity_access.ia_responsibility_v2_cutover_command (
        source_id, feed_id, partition_id, consumer_projection,
        business_date, status, requested_at);

create table identity_access.ia_responsibility_v2_shadow_checkpoint (
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    consumer_projection varchar(64) not null,
    source_version bigint not null default 0 check (source_version >= 0),
    source_watermark bigint not null default 0 check (source_watermark >= 0),
    aggregate_version bigint not null default 0
        check (aggregate_version >= 0),
    last_successful_at timestamptz,
    replay_started_at_zero boolean not null default false,
    reconciliation_status varchar(24) not null default 'pending',
    reconciliation_watermark bigint not null default 0
        check (reconciliation_watermark >= 0),
    reconciliation_expected_count bigint not null default 0
        check (reconciliation_expected_count >= 0),
    reconciliation_actual_count bigint not null default 0
        check (reconciliation_actual_count >= 0),
    reconciliation_expected_digest char(64),
    reconciliation_actual_digest char(64),
    reconciliation_lineage_conflicts bigint not null default 0
        check (reconciliation_lineage_conflicts >= 0),
    reconciliation_snapshot_id uuid references identity_access
        .ia_responsibility_v2_reconciliation_snapshot(snapshot_id),
    reconciliation_snapshot_source_version bigint not null default 0
        check (reconciliation_snapshot_source_version >= 0),
    reconciliation_envelope_digest char(64),
    reconciliation_signature_digest char(64),
    reconciliation_expected_lineage_count bigint not null default 0
        check (reconciliation_expected_lineage_count >= 0),
    reconciliation_actual_lineage_count bigint not null default 0
        check (reconciliation_actual_lineage_count >= 0),
    reconciliation_expected_lineage_digest char(64),
    reconciliation_actual_lineage_digest char(64),
    reconciliation_live_source_version bigint not null default 0
        check (reconciliation_live_source_version >= 0),
    reconciliation_live_watermark bigint not null default 0
        check (reconciliation_live_watermark >= 0),
    invalidation_materialized boolean not null default false,
    invalidation_materialized_count bigint not null default 0
        check (invalidation_materialized_count >= 0),
    active boolean not null default false,
    reconciled_at timestamptz,
    activated_at timestamptz,
    updated_at timestamptz not null,
    trace_id char(32) not null,
    primary key (
        source_id, feed_id, partition_id, consumer_projection),
    constraint ia_responsibility_v2_shadow_checkpoint_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and feed_id = 'responsibility-authority'
        and consumer_projection = 'responsibility'
        and partition_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        and length(partition_id) <= 64
        and reconciliation_status in (
            'pending', 'matched', 'differences-found')
        and (
            reconciliation_expected_digest is null
            or reconciliation_expected_digest ~ '^[0-9a-f]{64}$'
        )
        and (
            reconciliation_actual_digest is null
            or reconciliation_actual_digest ~ '^[0-9a-f]{64}$'
        )
        and (
            reconciliation_expected_lineage_digest is null
            or reconciliation_expected_lineage_digest ~ '^[0-9a-f]{64}$'
        )
        and (
            reconciliation_actual_lineage_digest is null
            or reconciliation_actual_lineage_digest ~ '^[0-9a-f]{64}$'
        )
        and (
            reconciliation_envelope_digest is null
            or reconciliation_envelope_digest ~ '^[0-9a-f]{64}$'
        )
        and (
            reconciliation_signature_digest is null
            or reconciliation_signature_digest ~ '^[0-9a-f]{64}$'
        )
        and (
            reconciliation_status <> 'matched'
            or active
            or (
                reconciliation_snapshot_id is not null
                and reconciliation_snapshot_source_version = source_version
                and reconciliation_watermark = source_watermark
                and reconciliation_expected_digest is not null
                and reconciliation_actual_digest is not null
                and reconciliation_expected_lineage_digest is not null
                and reconciliation_actual_lineage_digest is not null
            )
        )
        and (not active or activated_at is not null)
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

create table identity_access.ia_responsibility_v2_shadow_current (
    relation_id uuid not null unique,
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
    counselor_account_id uuid,
    college_organization_id uuid,
    responsibility_type varchar(16) not null,
    relation_status varchar(16) not null,
    recipient_validity varchar(32) not null,
    recipient_reason_code varchar(128) not null,
    quality_gate_status varchar(16) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    record_version bigint not null check (record_version >= 1),
    aggregate_version bigint not null check (aggregate_version >= 1),
    payload_digest char(64) not null,
    applied_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retention_effective_at timestamptz not null,
    legal_hold boolean not null default false,
    access_lineage_id varchar(160) not null,
    access_event_id uuid not null,
    access_change_kind varchar(24) not null,
    access_reason_code varchar(64) not null,
    access_effective_at timestamptz not null,
    access_supersedes_id uuid,
    primary key (
        source_id, feed_id, partition_id, relation_ref_token),
    unique (source_id, relation_ref_token),
    unique (source_id, access_lineage_id),
    foreign key (source_id, relation_ref_token, access_lineage_id)
        references identity_access.ia_responsibility_lineage_binding (
            source_id, relation_ref_token, lineage_id),
    foreign key (counselor_account_id)
        references identity_access.ia_authoritative_account_current(
            account_id),
    foreign key (college_organization_id)
        references identity_access.ia_authoritative_organization_current(
            organization_id),
    constraint ia_responsibility_v2_shadow_current_uuid_v7_ck check (
        substring(relation_id::text, 15, 1) = '7'
        and substring(relation_id::text, 20, 1) ~ '^[89ab]$'
        and substring(access_event_id::text, 15, 1) = '7'
        and substring(access_event_id::text, 20, 1) ~ '^[89ab]$'
        and (
            access_supersedes_id is null
            or (
                substring(access_supersedes_id::text, 15, 1) = '7'
                and substring(access_supersedes_id::text, 20, 1)
                    ~ '^[89ab]$'
            )
        )
    ),
    constraint ia_responsibility_v2_shadow_current_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and feed_id = 'responsibility-authority'
        and consumer_projection = 'responsibility'
        and partition_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        and length(partition_id) <= 64
        and relation_ref_token ~ '^rtok_[A-Za-z0-9_-]{32,128}$'
        and student_ref_purpose = 'RESPONSIBILITY-STUDENT-REF'
        and student_ref_key_version in (
            'resp-student-v1', 'resp-student-v2')
        and student_ref_token ~ '^stok_[A-Za-z0-9_-]{32,128}$'
        and student_ref_digest ~ '^[0-9a-f]{64}$'
        and student_equivalence_digest ~ '^[0-9a-f]{64}$'
        and counselor_account_ref_digest ~ '^[0-9a-f]{64}$'
        and college_organization_ref_digest ~ '^[0-9a-f]{64}$'
        and responsibility_type in ('primary', 'secondary')
        and relation_status in ('active', 'inactive')
        and recipient_validity in (
            'valid', 'invalid', 'dependency-unavailable')
        and recipient_reason_code ~ '^RESPONSIBILITY_[A-Z0-9_]+$'
        and quality_gate_status in ('trusted', 'blocked')
        and (
            (recipient_validity = 'valid'
             and counselor_account_id is not null
             and college_organization_id is not null)
            or
            (recipient_validity <> 'valid'
             and counselor_account_id is null
             and college_organization_id is null)
        )
        and (effective_to is null or effective_to > effective_from)
        and payload_digest ~ '^[0-9a-f]{64}$'
        and access_lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and access_event_id = relation_id
        and access_change_kind in (
            'corrected', 'revoked', 'expired',
            'invalidated', 'revalidated')
        and access_reason_code in (
            'DIRECT_RESPONSIBILITY_CHANGE',
            'ACCOUNT_DISABLED',
            'R1_EMPLOYMENT_INVALID',
            'COLLEGE_INVALID',
            'RELATION_EXPIRED',
            'COMPLETE_SNAPSHOT_MISSING',
            'QUALITY_GATE_INVALID',
            'RECONCILIATION_RECOVERED',
            'SOURCE_CORRECTION')
        and trace_id ~ '^[0-9a-f]{32}$'
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create index ia_responsibility_v2_shadow_current_student_idx
    on identity_access.ia_responsibility_v2_shadow_current (
        source_id, feed_id, partition_id,
        student_equivalence_digest, relation_ref_token);

create index ia_responsibility_v2_shadow_current_expiry_idx
    on identity_access.ia_responsibility_v2_shadow_current (
        source_id, feed_id, partition_id, relation_status, effective_to)
    where relation_status = 'active' and effective_to is not null;

create table identity_access
    .ia_responsibility_v2_shadow_invalidation_fact (
    event_id uuid primary key,
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    trace_id char(32) not null,
    change_kind varchar(24) not null,
    reason_code varchar(64) not null,
    lineage_id varchar(160) not null,
    supersedes_id uuid references identity_access
        .ia_responsibility_v2_shadow_invalidation_fact(event_id),
    aggregate_version bigint not null check (aggregate_version >= 1),
    effective_at timestamptz not null,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 1),
    dependency_vector jsonb not null,
    subject_token varchar(160) not null,
    scope_token varchar(160) not null,
    object_digest char(64) not null,
    authorization_state varchar(24) not null,
    account_active boolean not null,
    r1_employment_valid boolean not null,
    college_active boolean not null,
    relation_effective boolean not null,
    policy_version varchar(64) not null,
    source_payload_digest char(64) not null,
    retain_until timestamptz not null,
    legal_hold boolean not null default false,
    staged_at timestamptz not null,
    unique (
        source_id, feed_id, partition_id, lineage_id, aggregate_version),
    foreign key (source_id, lineage_id)
        references identity_access.ia_responsibility_lineage_binding (
            source_id, lineage_id),
    constraint ia_responsibility_v2_shadow_fact_uuid_v7_ck check (
        substring(event_id::text, 15, 1) = '7'
        and substring(event_id::text, 20, 1) ~ '^[89ab]$'
        and (
            supersedes_id is null
            or (
                substring(supersedes_id::text, 15, 1) = '7'
                and substring(supersedes_id::text, 20, 1) ~ '^[89ab]$'
            )
        )
    ),
    constraint ia_responsibility_v2_shadow_fact_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and feed_id = 'responsibility-authority'
        and partition_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        and length(partition_id) <= 64
        and trace_id ~ '^[0-9a-f]{32}$'
        and change_kind in (
            'corrected', 'revoked', 'expired',
            'invalidated', 'revalidated')
        and reason_code in (
            'DIRECT_RESPONSIBILITY_CHANGE',
            'ACCOUNT_DISABLED',
            'R1_EMPLOYMENT_INVALID',
            'COLLEGE_INVALID',
            'RELATION_EXPIRED',
            'COMPLETE_SNAPSHOT_MISSING',
            'QUALITY_GATE_INVALID',
            'RECONCILIATION_RECOVERED',
            'SOURCE_CORRECTION')
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and (
            (aggregate_version = 1 and supersedes_id is null)
            or (aggregate_version > 1 and supersedes_id is not null)
        )
        and jsonb_typeof(dependency_vector) = 'object'
        and dependency_vector <> '{}'::jsonb
        and subject_token ~ '^subtok_[A-Za-z0-9_-]{32,128}$'
        and scope_token ~ '^scptok_[A-Za-z0-9_-]{32,128}$'
        and object_digest ~ '^[0-9a-f]{64}$'
        and authorization_state in ('invalidated', 'revalidated')
        and (
            (change_kind = 'revalidated'
             and reason_code = 'RECONCILIATION_RECOVERED'
             and authorization_state = 'revalidated'
             and account_active
             and r1_employment_valid
             and college_active
             and relation_effective)
            or
            (change_kind <> 'revalidated'
             and authorization_state = 'invalidated')
        )
        and (change_kind <> 'expired' or reason_code = 'RELATION_EXPIRED')
        and policy_version = 'RFP-1.0.0'
        and source_payload_digest ~ '^[0-9a-f]{64}$'
        and retain_until > effective_at
    )
);

create table identity_access.ia_responsibility_v2_shadow_lineage_head (
    source_id varchar(64) not null,
    feed_id varchar(64) not null,
    partition_id varchar(64) not null,
    lineage_id varchar(160) not null,
    relation_ref_token varchar(160) not null,
    current_event_id uuid not null unique
        references identity_access
            .ia_responsibility_v2_shadow_invalidation_fact(event_id),
    current_version bigint not null check (current_version >= 1),
    updated_at timestamptz not null,
    trace_id char(32) not null,
    primary key (source_id, feed_id, partition_id, lineage_id),
    unique (source_id, feed_id, partition_id, relation_ref_token),
    foreign key (source_id, relation_ref_token, lineage_id)
        references identity_access.ia_responsibility_lineage_binding (
            source_id, relation_ref_token, lineage_id),
    constraint ia_responsibility_v2_shadow_head_shape_ck check (
        source_id = 'SRC-P0-RESPONSIBILITY-001'
        and feed_id = 'responsibility-authority'
        and partition_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        and length(partition_id) <= 64
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and relation_ref_token ~ '^rtok_[A-Za-z0-9_-]{32,128}$'
        and substring(current_event_id::text, 15, 1) = '7'
        and substring(current_event_id::text, 20, 1) ~ '^[89ab]$'
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

create table identity_access.ia_access_invalidation_fact (
    event_id uuid primary key,
    schema_version varchar(64) not null,
    event_type varchar(128) not null,
    producer varchar(64) not null,
    trace_id char(32) not null,
    change_kind varchar(24) not null,
    reason_code varchar(64) not null,
    lineage_id varchar(160) not null,
    supersedes_id uuid
        references identity_access.ia_access_invalidation_fact(event_id),
    cause_event_id uuid
        references identity_access.ia_access_invalidation_fact(event_id),
    aggregate_type varchar(32) not null,
    aggregate_id varchar(160) not null,
    aggregate_version bigint not null check (aggregate_version >= 1),
    invalidation_version bigint not null check (invalidation_version >= 1),
    effective_at timestamptz not null,
    source_id varchar(64) not null,
    source_version bigint not null check (source_version >= 1),
    source_watermark bigint not null check (source_watermark >= 0),
    dependency_vector jsonb not null,
    subject_token varchar(160) not null,
    scope_token varchar(160) not null,
    object_digest char(64) not null,
    authorization_state varchar(24) not null,
    account_active boolean not null,
    r1_employment_valid boolean not null,
    college_active boolean not null,
    relation_effective boolean not null,
    policy_version varchar(64) not null,
    event_payload jsonb not null,
    payload_digest char(64) not null,
    source_payload_digest char(64) not null,
    occurred_at timestamptz not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'identity-access',
    retain_until timestamptz not null,
    legal_hold boolean not null default false,
    unique (lineage_id, aggregate_version),
    unique (aggregate_type, aggregate_id, aggregate_version),
    constraint ia_access_invalidation_fact_uuid_v7_ck check (
        substring(event_id::text, 15, 1) = '7'
        and substring(event_id::text, 20, 1) ~ '^[89ab]$'
        and (
            supersedes_id is null
            or (
                substring(supersedes_id::text, 15, 1) = '7'
                and substring(supersedes_id::text, 20, 1) ~ '^[89ab]$'
            )
        )
        and (
            cause_event_id is null
            or (
                substring(cause_event_id::text, 15, 1) = '7'
                and substring(cause_event_id::text, 20, 1) ~ '^[89ab]$'
            )
        )
    ),
    constraint ia_access_invalidation_fact_shape_ck check (
        schema_version = 'ACCESS-INVALIDATION-DATA-1.0.0'
        and event_type =
            'scholarsense.identity-access.responsibility.changed.v1'
        and producer = 'identity-access'
        and trace_id ~ '^[0-9a-f]{32}$'
        and change_kind in (
            'corrected', 'revoked', 'expired', 'invalidated', 'revalidated')
        and reason_code in (
            'DIRECT_RESPONSIBILITY_CHANGE',
            'ACCOUNT_DISABLED',
            'R1_EMPLOYMENT_INVALID',
            'COLLEGE_INVALID',
            'RELATION_EXPIRED',
            'COMPLETE_SNAPSHOT_MISSING',
            'QUALITY_GATE_INVALID',
            'RECONCILIATION_RECOVERED',
            'SOURCE_CORRECTION')
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and aggregate_type in ('responsibility-scope', 'identity-cause')
        and aggregate_id ~ '^(lin|cause)_[A-Za-z0-9_-]{32,128}$'
        and (
            (aggregate_type = 'responsibility-scope'
             and aggregate_id = lineage_id)
            or
            (aggregate_type = 'identity-cause'
             and aggregate_id like 'cause_%'
             and cause_event_id is null)
        )
        and aggregate_version = invalidation_version
        and (
            (aggregate_version = 1 and supersedes_id is null)
            or
            (aggregate_version > 1 and supersedes_id is not null)
        )
        and source_id = 'SRC-P0-RESPONSIBILITY-001'
        and jsonb_typeof(dependency_vector) = 'array'
        and subject_token ~ '^subtok_[A-Za-z0-9_-]{32,128}$'
        and scope_token ~ '^scptok_[A-Za-z0-9_-]{32,128}$'
        and object_digest ~ '^[0-9a-f]{64}$'
        and authorization_state in ('invalidated', 'revalidated')
        and (
            (change_kind = 'revalidated'
             and reason_code = 'RECONCILIATION_RECOVERED'
             and authorization_state = 'revalidated'
             and account_active
             and r1_employment_valid
             and college_active
             and relation_effective)
            or
            (change_kind <> 'revalidated'
             and authorization_state = 'invalidated')
        )
        and (
            change_kind <> 'expired'
            or reason_code = 'RELATION_EXPIRED'
        )
        and policy_version = 'RFP-1.0.0'
        and jsonb_typeof(event_payload) = 'object'
        and octet_length(event_payload::text) <= 65536
        and payload_digest ~ '^[0-9a-f]{64}$'
        and source_payload_digest ~ '^[0-9a-f]{64}$'
        and retain_until > effective_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create table identity_access.ia_access_invalidation_lineage_head (
    lineage_id varchar(160) primary key,
    aggregate_type varchar(32) not null,
    aggregate_id varchar(160) not null,
    current_event_id uuid not null unique
        references identity_access.ia_access_invalidation_fact(event_id),
    current_version bigint not null check (current_version >= 1),
    fencing_token bigint not null check (fencing_token >= 1),
    updated_at timestamptz not null,
    trace_id char(32) not null,
    constraint ia_access_invalidation_lineage_head_shape_ck check (
        lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and aggregate_type in ('responsibility-scope', 'identity-cause')
        and aggregate_id ~ '^(lin|cause)_[A-Za-z0-9_-]{32,128}$'
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

-- Consumer evidence is bound to the immutable event route and canonical
-- digest; the fence additionally binds the authorization state it applies.
create unique index ia_access_invalidation_fact_event_route_digest_uk
    on identity_access.ia_access_invalidation_fact (
        event_id, aggregate_type, lineage_id, aggregate_version,
        payload_digest);

create unique index ia_access_invalidation_fact_event_lineage_version_uk
    on identity_access.ia_access_invalidation_fact (
        event_id, lineage_id, aggregate_version);

create unique index ia_access_invalidation_fact_event_fence_uk
    on identity_access.ia_access_invalidation_fact (
        event_id, aggregate_type, lineage_id, aggregate_version,
        authorization_state, payload_digest);

create table identity_access.ia_access_invalidation_outbox (
    outbox_id uuid primary key,
    event_id uuid not null unique
        references identity_access.ia_access_invalidation_fact(event_id),
    event_type varchar(128) not null,
    event_payload jsonb not null,
    payload_digest char(64) not null,
    delivery_key varchar(255) not null unique,
    status varchar(24) not null default 'pending',
    attempts bigint not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null,
    lease_owner varchar(128),
    lease_expires_at timestamptz,
    fencing_token bigint not null default 0 check (fencing_token >= 0),
    created_at timestamptz not null,
    published_at timestamptz,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retain_until timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_access_invalidation_outbox_uuid_v7_ck check (
        substring(outbox_id::text, 15, 1) = '7'
        and substring(outbox_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_access_invalidation_outbox_shape_ck check (
        event_type =
            'scholarsense.identity-access.responsibility.changed.v1'
        and jsonb_typeof(event_payload) = 'object'
        and octet_length(event_payload::text) <= 65536
        and payload_digest ~ '^[0-9a-f]{64}$'
        and status in (
            'pending', 'claimed', 'published', 'retry', 'quarantined')
        and trace_id ~ '^[0-9a-f]{32}$'
        and retain_until > created_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create index ia_access_invalidation_outbox_due_idx
    on identity_access.ia_access_invalidation_outbox (
        status, next_attempt_at, created_at);

create table identity_access.ia_access_invalidation_delivery_attempt (
    attempt_id uuid primary key,
    outbox_id uuid not null
        references identity_access.ia_access_invalidation_outbox(outbox_id),
    attempt_no bigint not null check (attempt_no >= 1),
    fencing_token bigint not null check (fencing_token >= 1),
    outcome varchar(24) not null,
    reason_code varchar(64) not null,
    attempted_at timestamptz not null,
    trace_id char(32) not null,
    unique (outbox_id, attempt_no),
    constraint ia_access_invalidation_delivery_attempt_uuid_v7_ck check (
        substring(attempt_id::text, 15, 1) = '7'
        and substring(attempt_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_access_invalidation_delivery_attempt_shape_ck check (
        outcome in ('published', 'retry', 'quarantined')
        and reason_code ~ '^ACCESS_INVALIDATION_[A-Z0-9_]+$'
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

create table identity_access.ia_access_invalidation_consumer_registry (
    consumer_id varchar(64) primary key,
    consumer_kind varchar(64) not null,
    owner_module varchar(64) not null,
    owner_story varchar(32) not null,
    lifecycle varchar(32) not null,
    applicable_release varchar(64) not null,
    required boolean not null,
    activation_at timestamptz,
    initial_watermark bigint check (initial_watermark >= 0),
    runtime_evidence_claim varchar(32) not null,
    activation_rule varchar(255) not null,
    updated_at timestamptz not null,
    constraint ia_access_invalidation_consumer_registry_shape_ck check (
        consumer_id ~ '^[a-z][a-z0-9-]{1,63}$'
        and lifecycle in (
            'active', 'planned/not-installed', 'disabled', 'quarantined')
        and runtime_evidence_claim in ('current-runtime', 'none')
        and (
            (lifecycle = 'active'
             and activation_at is not null
             and initial_watermark is not null)
            or
            (lifecycle = 'planned/not-installed'
             and not required
             and activation_at is null
             and initial_watermark is null
             and runtime_evidence_claim = 'none')
            or lifecycle in ('disabled', 'quarantined')
        )
    )
);

-- Planned consumers are visible contract entries; runtimeEvidenceClaim=none.
insert into identity_access.ia_access_invalidation_consumer_registry (
    consumer_id, consumer_kind, owner_module, owner_story, lifecycle,
    applicable_release, required, activation_at, initial_watermark,
    runtime_evidence_claim, activation_rule, updated_at)
values
    (
        'authorization-current-scope', 'invalidation-fence-read-model',
        'identity-access', '1.6c', 'active', '1.6c', true,
        '2026-07-31T10:51:41Z', 0, 'current-runtime',
        'replay-from-approved-zero-and-reconcile-before-required',
        '2026-07-31T10:51:41Z'
    ),
    (
        'public-task', 'business-projection', 'public-task-owner', '5.5',
        'planned/not-installed', 'owner-story-activation', false,
        null, null, 'none',
        'owner-registers-replay-start-backfills-and-reconciles-before-active',
        '2026-07-31T10:51:41Z'
    ),
    (
        'reporting-export', 'business-projection', 'reporting', '3.14c',
        'planned/not-installed', 'owner-story-activation', false,
        null, null, 'none',
        'owner-registers-replay-start-backfills-and-reconciles-before-active',
        '2026-07-31T10:51:41Z'
    ),
    (
        'responsibility-transfer', 'business-projection', 'cluecare', '3.9b',
        'planned/not-installed', 'owner-story-activation', false,
        null, null, 'none',
        'owner-registers-replay-start-backfills-and-reconciles-before-active',
        '2026-07-31T10:51:41Z'
    ),
    (
        'mobile-surface-verification', 'surface-verification',
        'experience-mobile', '7.2c', 'planned/not-installed',
        'owner-story-verification', false, null, null, 'none',
        'verify-volatile-state-clear-and-next-request-server-deny',
        '2026-07-31T10:51:41Z'
    );

create table identity_access.ia_access_invalidation_observed_ack (
    observed_ack_id uuid primary key,
    consumer_id varchar(64) not null
        references identity_access.ia_access_invalidation_consumer_registry(
            consumer_id),
    event_id uuid not null
        references identity_access.ia_access_invalidation_fact(event_id),
    aggregate_type varchar(32) not null,
    lineage_id varchar(160) not null,
    applied_version bigint not null check (applied_version >= 1),
    applied_fact_id uuid not null,
    applied_payload_digest char(64) not null,
    observed_at timestamptz not null,
    trace_id char(32) not null,
    unique (consumer_id, event_id),
    foreign key (
        event_id, aggregate_type, lineage_id, applied_version,
        applied_payload_digest)
        references identity_access.ia_access_invalidation_fact (
            event_id, aggregate_type, lineage_id, aggregate_version,
            payload_digest),
    constraint ia_access_invalidation_observed_ack_uuid_v7_ck check (
        substring(observed_ack_id::text, 15, 1) = '7'
        and substring(observed_ack_id::text, 20, 1) ~ '^[89ab]$'
        and substring(applied_fact_id::text, 15, 1) = '7'
        and substring(applied_fact_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_access_invalidation_observed_ack_shape_ck check (
        aggregate_type in ('responsibility-scope', 'identity-cause')
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and applied_payload_digest ~ '^[0-9a-f]{64}$'
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

create table identity_access.ia_access_invalidation_propagation (
    event_id uuid primary key
        references identity_access.ia_access_invalidation_fact(event_id),
    lineage_id varchar(160) not null,
    target_version bigint not null check (target_version >= 1),
    required_consumer_count integer not null
        check (required_consumer_count >= 0),
    applied_required_consumer_count integer not null
        check (applied_required_consumer_count >= 0),
    reconciliation_status varchar(24) not null,
    propagation_status varchar(24) not null,
    last_checked_at timestamptz not null,
    trace_id char(32) not null,
    constraint ia_access_invalidation_propagation_lineage_version_uk
        unique (lineage_id, target_version),
    constraint ia_access_invalidation_propagation_fact_fk
        foreign key (event_id, lineage_id, target_version)
        references identity_access.ia_access_invalidation_fact (
            event_id, lineage_id, aggregate_version),
    constraint ia_access_invalidation_propagation_shape_ck check (
        lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and applied_required_consumer_count <= required_consumer_count
        and reconciliation_status in ('pending', 'healthy', 'gap', 'conflict')
        and propagation_status in (
            'pending', 'complete', 'lagging', 'quarantined')
        and (
            propagation_status <> 'complete'
            or (
                reconciliation_status = 'healthy'
                and applied_required_consumer_count =
                    required_consumer_count
            )
        )
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

create table identity_access.ia_access_invalidation_local_inbox (
    consumer_id varchar(64) not null
        references identity_access.ia_access_invalidation_consumer_registry(
            consumer_id),
    event_id uuid not null
        references identity_access.ia_access_invalidation_fact(event_id),
    event_payload jsonb not null,
    payload_digest char(64) not null,
    received_at timestamptz not null,
    trace_id char(32) not null,
    primary key (consumer_id, event_id),
    constraint ia_access_invalidation_local_inbox_shape_ck check (
        consumer_id = 'authorization-current-scope'
        and jsonb_typeof(event_payload) = 'object'
        and octet_length(event_payload::text) <= 65536
        and payload_digest ~ '^[0-9a-f]{64}$'
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

create table identity_access.ia_access_invalidation_local_apply (
    apply_id uuid primary key,
    consumer_id varchar(64) not null,
    event_id uuid not null,
    aggregate_type varchar(32) not null,
    lineage_id varchar(160) not null,
    applied_version bigint not null check (applied_version >= 1),
    payload_digest char(64) not null,
    decision varchar(32) not null,
    applied_at timestamptz not null,
    trace_id char(32) not null,
    foreign key (consumer_id, event_id)
        references identity_access.ia_access_invalidation_local_inbox(
            consumer_id, event_id),
    foreign key (
        event_id, aggregate_type, lineage_id, applied_version,
        payload_digest)
        references identity_access.ia_access_invalidation_fact (
            event_id, aggregate_type, lineage_id, aggregate_version,
            payload_digest),
    unique (consumer_id, event_id),
    unique (
        apply_id, consumer_id, event_id, applied_version, payload_digest),
    constraint ia_access_invalidation_local_apply_uuid_v7_ck check (
        substring(apply_id::text, 15, 1) = '7'
        and substring(apply_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_access_invalidation_local_apply_shape_ck check (
        aggregate_type in ('responsibility-scope', 'identity-cause')
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and payload_digest ~ '^[0-9a-f]{64}$'
        and decision in (
            'applied', 'duplicate', 'old-ignored',
            'gap-backfill-required', 'conflict')
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

create table identity_access.ia_access_invalidation_consumer_applied_outbox (
    applied_fact_id uuid primary key,
    apply_id uuid not null unique,
    consumer_id varchar(64) not null,
    event_id uuid not null,
    applied_version bigint not null check (applied_version >= 1),
    payload_digest char(64) not null,
    status varchar(24) not null default 'pending',
    attempts bigint not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null,
    created_at timestamptz not null,
    trace_id char(32) not null,
    foreign key (
        apply_id, consumer_id, event_id, applied_version, payload_digest)
        references identity_access.ia_access_invalidation_local_apply (
            apply_id, consumer_id, event_id, applied_version,
            payload_digest),
    unique (
        applied_fact_id, consumer_id, event_id,
        applied_version, payload_digest),
    constraint ia_access_invalidation_consumer_applied_outbox_uuid_v7_ck check (
        substring(applied_fact_id::text, 15, 1) = '7'
        and substring(applied_fact_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_access_invalidation_consumer_applied_outbox_shape_ck check (
        consumer_id = 'authorization-current-scope'
        and payload_digest ~ '^[0-9a-f]{64}$'
        and status in ('pending', 'published', 'retry', 'quarantined')
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

-- In this release the sole active consumer is local.  Bind producer-observed
-- acknowledgement to its immutable applied-fact outbox; future remote
-- consumers can add a separately authenticated ingress evidence table.
alter table identity_access.ia_access_invalidation_observed_ack
    add foreign key (
        applied_fact_id, consumer_id, event_id,
        applied_version, applied_payload_digest)
    references identity_access
        .ia_access_invalidation_consumer_applied_outbox (
            applied_fact_id, consumer_id, event_id,
            applied_version, payload_digest);

create table identity_access.ia_access_invalidation_consumer_watermark (
    consumer_id varchar(64) not null
        references identity_access.ia_access_invalidation_consumer_registry(
            consumer_id),
    producer varchar(64) not null,
    aggregate_type varchar(32) not null,
    lineage_id varchar(160) not null,
    current_watermark bigint not null check (current_watermark >= 0),
    last_event_id uuid,
    last_payload_digest char(64),
    applied_at timestamptz,
    fencing_token bigint not null default 0 check (fencing_token >= 0),
    trace_id char(32) not null,
    primary key (
        consumer_id, producer, aggregate_type, lineage_id),
    constraint ia_access_invalidation_consumer_watermark_fact_fk
        foreign key (
        last_event_id, aggregate_type, lineage_id, current_watermark,
        last_payload_digest)
        references identity_access.ia_access_invalidation_fact (
            event_id, aggregate_type, lineage_id, aggregate_version,
            payload_digest),
    constraint ia_access_invalidation_consumer_watermark_shape_ck check (
        consumer_id = 'authorization-current-scope'
        and producer = 'identity-access'
        and aggregate_type in ('responsibility-scope', 'identity-cause')
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and (
            (current_watermark = 0
             and last_event_id is null
             and last_payload_digest is null
             and applied_at is null)
            or
            (current_watermark > 0
             and last_event_id is not null
             and last_payload_digest ~ '^[0-9a-f]{64}$'
             and applied_at is not null)
        )
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

-- The authorization read path consults this consumer-owned fence rather than
-- inferring freshness from producer state.  A single transaction advances
-- the fence together with inbox/apply/applied-fact rows.
create table identity_access.ia_access_invalidation_local_fence (
    consumer_id varchar(64) not null
        references identity_access.ia_access_invalidation_consumer_registry(
            consumer_id),
    aggregate_type varchar(32) not null,
    lineage_id varchar(160) not null,
    current_event_id uuid not null,
    current_version bigint not null check (current_version >= 1),
    current_state varchar(24) not null,
    payload_digest char(64) not null,
    applied_at timestamptz not null,
    fencing_token bigint not null check (fencing_token >= 1),
    trace_id char(32) not null,
    primary key (consumer_id, aggregate_type, lineage_id),
    unique (consumer_id, current_event_id),
    foreign key (
        current_event_id, aggregate_type, lineage_id, current_version,
        current_state, payload_digest)
        references identity_access.ia_access_invalidation_fact (
            event_id, aggregate_type, lineage_id, aggregate_version,
            authorization_state, payload_digest),
    constraint ia_access_invalidation_local_fence_shape_ck check (
        consumer_id = 'authorization-current-scope'
        and aggregate_type in ('responsibility-scope', 'identity-cause')
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and current_state in ('invalidated', 'revalidated')
        and payload_digest ~ '^[0-9a-f]{64}$'
        and trace_id ~ '^[0-9a-f]{32}$'
    )
);

-- A detected version gap is durable work, not merely an in-memory decision.
-- The uniqueness key makes redelivery idempotent while preserving the exact
-- consumer watermark/fencing context that triggered the request.
create table identity_access.ia_access_invalidation_backfill_request (
    request_id uuid primary key,
    consumer_id varchar(64) not null
        references identity_access.ia_access_invalidation_consumer_registry(
            consumer_id),
    producer varchar(64) not null,
    aggregate_type varchar(32) not null,
    lineage_id varchar(160) not null,
    from_version bigint not null check (from_version >= 1),
    through_version bigint not null,
    trigger_event_id uuid not null,
    trigger_version bigint not null check (trigger_version >= 2),
    trigger_payload_digest char(64) not null,
    watermark_fencing_token bigint not null
        check (watermark_fencing_token >= 0),
    status varchar(24) not null,
    attempts bigint not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null,
    lease_owner varchar(128),
    lease_expires_at timestamptz,
    claim_fencing_token bigint not null default 0
        check (claim_fencing_token >= 0),
    last_error_code varchar(128),
    completed_at timestamptz,
    requested_at timestamptz not null,
    updated_at timestamptz not null,
    trace_id char(32) not null,
    foreign key (
        trigger_event_id, aggregate_type, lineage_id, trigger_version,
        trigger_payload_digest)
        references identity_access.ia_access_invalidation_fact (
            event_id, aggregate_type, lineage_id, aggregate_version,
            payload_digest),
    constraint ia_access_invalidation_backfill_request_uuid_v7_ck check (
        substring(request_id::text, 15, 1) = '7'
        and substring(request_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_access_invalidation_backfill_request_shape_ck check (
        consumer_id = 'authorization-current-scope'
        and producer = 'identity-access'
        and aggregate_type in ('responsibility-scope', 'identity-cause')
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and through_version >= from_version
        and trigger_version = through_version + 1
        and trigger_payload_digest ~ '^[0-9a-f]{64}$'
        and status in ('pending', 'running', 'completed', 'quarantined')
        and (
            (status = 'running'
             and lease_owner is not null
             and lease_expires_at is not null)
            or
            (status <> 'running'
             and lease_owner is null
             and lease_expires_at is null)
        )
        and (
            (status = 'completed' and completed_at is not null)
            or (status <> 'completed' and completed_at is null)
        )
        and (
            last_error_code is null
            or last_error_code ~ '^ACCESS_INVALIDATION_[A-Z0-9_]+$'
        )
        and trace_id ~ '^[0-9a-f]{32}$'
    ),
    unique (
        consumer_id, producer, aggregate_type, lineage_id,
        from_version, through_version, trigger_event_id,
        trigger_payload_digest, watermark_fencing_token)
);

create index ia_access_invalidation_backfill_request_due_idx
    on identity_access.ia_access_invalidation_backfill_request (
        status, next_attempt_at, requested_at, request_id);

create table identity_access.ia_access_invalidation_job (
    job_id uuid primary key,
    job_kind varchar(16) not null,
    lineage_id varchar(160) not null,
    cause_event_id uuid
        references identity_access.ia_access_invalidation_fact(event_id),
    status varchar(24) not null default 'pending',
    due_at timestamptz not null,
    lease_owner varchar(128),
    lease_expires_at timestamptz,
    fencing_token bigint not null default 0 check (fencing_token >= 0),
    cursor_value bigint not null default 0 check (cursor_value >= 0),
    cursor_lineage_id varchar(160),
    attempts bigint not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null,
    reason_code varchar(64),
    last_error_code varchar(128),
    trace_id char(32) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retain_until timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_access_invalidation_job_uuid_v7_ck check (
        substring(job_id::text, 15, 1) = '7'
        and substring(job_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_access_invalidation_job_shape_ck check (
        job_kind in ('expiry', 'impact')
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and status in (
            'pending', 'running', 'retry', 'completed', 'quarantined')
        and (
            cursor_lineage_id is null
            or cursor_lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        )
        and (
            last_error_code is null
            or last_error_code ~ '^ACCESS_INVALIDATION_[A-Z0-9_]+$'
        )
        and trace_id ~ '^[0-9a-f]{32}$'
        and retain_until > created_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

create index ia_access_invalidation_job_due_idx
    on identity_access.ia_access_invalidation_job (
        status, next_attempt_at, due_at);

create table identity_access.ia_access_invalidation_reconciliation (
    reconciliation_id uuid primary key,
    reconciliation_kind varchar(24) not null,
    consumer_id varchar(64),
    lineage_id varchar(160) not null,
    target_version bigint not null check (target_version >= 1),
    observed_watermark bigint not null check (observed_watermark >= 0),
    outcome varchar(24) not null,
    reason_code varchar(64) not null,
    gap_consumer_ids jsonb not null,
    conflict_consumer_ids jsonb not null,
    checked_at timestamptz not null,
    trace_id char(32) not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retain_until timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_access_invalidation_reconciliation_uuid_v7_ck check (
        substring(reconciliation_id::text, 15, 1) = '7'
        and substring(reconciliation_id::text, 20, 1) ~ '^[89ab]$'
    ),
    constraint ia_access_invalidation_reconciliation_shape_ck check (
        reconciliation_kind in ('incremental', 'daily-full')
        and lineage_id ~ '^lin_[A-Za-z0-9_-]{32,128}$'
        and outcome in ('healthy', 'gap', 'conflict', 'lagging')
        and reason_code ~ '^ACCESS_INVALIDATION_[A-Z0-9_]+$'
        and jsonb_typeof(gap_consumer_ids) = 'array'
        and jsonb_typeof(conflict_consumer_ids) = 'array'
        and trace_id ~ '^[0-9a-f]{32}$'
        and retain_until > checked_at
        and retention_schedule_version = 'RS-1.0.0'
    )
);

do $$
begin
    if not exists (
        select 1
          from pg_catalog.pg_roles
         where rolname =
               'scholarsense_identity_invalidation_consumer'
    ) then
        create role scholarsense_identity_invalidation_consumer nologin;
    end if;
    if not exists (
        select 1
          from pg_catalog.pg_roles
         where rolname =
               'scholarsense_identity_responsibility_v2_cutover'
    ) then
        create role scholarsense_identity_responsibility_v2_cutover nologin;
    end if;
end
$$;

-- Pre-created roles must not retain elevated attributes. V8 rebuilds the
-- invalidation table capability sets explicitly below.
alter role scholarsense_identity_sync_worker
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
alter role scholarsense_identity_invalidation_consumer
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
alter role scholarsense_identity_responsibility_v2_cutover
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
alter role scholarsense_identity_current_reader
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
do $cutover_database_acl$
begin
    execute format(
        'revoke create on database %I from '
        'scholarsense_identity_responsibility_v2_cutover',
        current_database());
end
$cutover_database_acl$;
do $role_isolation$
declare
    membership_grantor text;
    granted_role text;
begin
    for membership_grantor in
        select grantor.rolname
          from pg_catalog.pg_auth_members membership
          join pg_catalog.pg_roles granted
            on granted.oid=membership.roleid
          join pg_catalog.pg_roles member
            on member.oid=membership.member
          join pg_catalog.pg_roles grantor
            on grantor.oid=membership.grantor
         where granted.rolname='scholarsense_identity_sync_worker'
           and member.rolname=
               'scholarsense_identity_invalidation_consumer'
    loop
        execute format(
            'revoke scholarsense_identity_sync_worker from '
            'scholarsense_identity_invalidation_consumer granted by %I',
            membership_grantor);
    end loop;
    for membership_grantor in
        select grantor.rolname
          from pg_catalog.pg_auth_members membership
          join pg_catalog.pg_roles granted
            on granted.oid=membership.roleid
          join pg_catalog.pg_roles member
            on member.oid=membership.member
          join pg_catalog.pg_roles grantor
            on grantor.oid=membership.grantor
         where granted.rolname=
               'scholarsense_identity_invalidation_consumer'
           and member.rolname='scholarsense_identity_sync_worker'
    loop
        execute format(
            'revoke scholarsense_identity_invalidation_consumer from '
            'scholarsense_identity_sync_worker granted by %I',
            membership_grantor);
    end loop;
    if pg_has_role(
            'scholarsense_identity_invalidation_consumer',
            'scholarsense_identity_sync_worker',
            'member')
       or pg_has_role(
            'scholarsense_identity_sync_worker',
            'scholarsense_identity_invalidation_consumer',
            'member') then
        raise exception
            'ACCESS_INVALIDATION_DATABASE_ROLE_MEMBERSHIP_INVALID'
            using errcode='42501';
    end if;
    -- The cutover group may have login members, but it must neither inherit
    -- another group nor be inherited by another nologin application group.
    for granted_role, membership_grantor in
        select granted.rolname, grantor.rolname
          from pg_catalog.pg_auth_members membership
          join pg_catalog.pg_roles granted
            on granted.oid=membership.roleid
          join pg_catalog.pg_roles member
            on member.oid=membership.member
          join pg_catalog.pg_roles grantor
            on grantor.oid=membership.grantor
         where member.rolname=
               'scholarsense_identity_responsibility_v2_cutover'
    loop
        execute format(
            'revoke %I from '
            'scholarsense_identity_responsibility_v2_cutover granted by %I',
            granted_role,
            membership_grantor);
    end loop;
    for granted_role, membership_grantor in
        select member.rolname, grantor.rolname
          from pg_catalog.pg_auth_members membership
          join pg_catalog.pg_roles granted
            on granted.oid=membership.roleid
          join pg_catalog.pg_roles member
            on member.oid=membership.member
          join pg_catalog.pg_roles grantor
            on grantor.oid=membership.grantor
         where granted.rolname=
               'scholarsense_identity_responsibility_v2_cutover'
           and not member.rolcanlogin
    loop
        execute format(
            'revoke scholarsense_identity_responsibility_v2_cutover '
            'from %I granted by %I',
            granted_role,
            membership_grantor);
    end loop;
    if exists (
        select 1
          from pg_catalog.pg_auth_members membership
          join pg_catalog.pg_roles granted
            on granted.oid=membership.roleid
          join pg_catalog.pg_roles member
            on member.oid=membership.member
         where member.rolname=
                   'scholarsense_identity_responsibility_v2_cutover'
            or (granted.rolname=
                    'scholarsense_identity_responsibility_v2_cutover'
                and not member.rolcanlogin)
    ) then
        raise exception
            'RESPONSIBILITY_V2_CUTOVER_ROLE_MEMBERSHIP_INVALID'
            using errcode='42501';
    end if;
    for granted_role, membership_grantor in
        select granted.rolname, grantor.rolname
          from pg_catalog.pg_auth_members membership
          join pg_catalog.pg_roles granted
            on granted.oid=membership.roleid
          join pg_catalog.pg_roles member
            on member.oid=membership.member
          join pg_catalog.pg_roles grantor
            on grantor.oid=membership.grantor
         where member.rolname='scholarsense_identity_current_reader'
    loop
        execute format(
            'revoke %I from scholarsense_identity_current_reader granted by %I',
            granted_role,
            membership_grantor);
    end loop;
    if exists (
        select 1
          from pg_catalog.pg_auth_members membership
          join pg_catalog.pg_roles member
            on member.oid=membership.member
         where member.rolname='scholarsense_identity_current_reader'
    ) then
        raise exception
            'IDENTITY_CURRENT_READER_ROLE_MEMBERSHIP_INVALID'
            using errcode='42501';
    end if;
end
$role_isolation$;
revoke all privileges on schema identity_access
    from scholarsense_identity_invalidation_consumer,
         scholarsense_identity_responsibility_v2_cutover;
revoke all privileges on all tables in schema identity_access
    from scholarsense_identity_invalidation_consumer,
         scholarsense_identity_responsibility_v2_cutover;

-- Table-level REVOKE does not erase a pre-created role's column ACLs. Clear
-- every column capability before rebuilding the cutover matrix below.
do $cutover_column_acl$
declare
    target_schema text;
    target_table text;
    target_columns text;
begin
    for target_schema, target_table, target_columns in
        select namespace.nspname,
               relation.relname,
               string_agg(
                   format('%I', attribute.attname),
                   ', ' order by attribute.attnum) as column_list
          from pg_catalog.pg_class relation
          join pg_catalog.pg_namespace namespace
            on namespace.oid=relation.relnamespace
          join pg_catalog.pg_attribute attribute
            on attribute.attrelid=relation.oid
           and attribute.attnum>0
           and not attribute.attisdropped
         where namespace.nspname='identity_access'
           and relation.relkind in ('r', 'p', 'v', 'm', 'f')
         group by namespace.nspname, relation.relname
    loop
        execute format(
            'revoke select (%s) on table %I.%I from '
            'scholarsense_identity_responsibility_v2_cutover',
            target_columns, target_schema, target_table);
        execute format(
            'revoke insert (%s) on table %I.%I from '
            'scholarsense_identity_responsibility_v2_cutover',
            target_columns, target_schema, target_table);
        execute format(
            'revoke update (%s) on table %I.%I from '
            'scholarsense_identity_responsibility_v2_cutover',
            target_columns, target_schema, target_table);
        execute format(
            'revoke references (%s) on table %I.%I from '
            'scholarsense_identity_responsibility_v2_cutover',
            target_columns, target_schema, target_table);
    end loop;
end
$cutover_column_acl$;

grant usage on schema identity_access
    to scholarsense_identity_invalidation_consumer,
       scholarsense_identity_responsibility_v2_cutover;

revoke all privileges on identity_access.ia_responsibility_lineage_binding
    from public;
revoke all privileges on identity_access
    .ia_authoritative_role_binding_history from public;
revoke all privileges on identity_access
    .ia_responsibility_v2_shadow_checkpoint from public;
revoke all privileges on identity_access.ia_responsibility_v2_shadow_current
    from public;
revoke all privileges on identity_access
    .ia_responsibility_v2_shadow_invalidation_fact from public;
revoke all privileges on identity_access
    .ia_responsibility_v2_shadow_lineage_head from public;
revoke all privileges on identity_access
    .ia_responsibility_v2_source_fact from public;
revoke all privileges on identity_access
    .ia_responsibility_v2_reconciliation_snapshot from public;
revoke all privileges on identity_access
    .ia_responsibility_v2_snapshot_entry from public;
revoke all privileges on identity_access
    .ia_responsibility_v2_snapshot_lineage from public;
revoke all privileges on identity_access
    .ia_responsibility_v2_cutover_command from public;

-- Default privileges may have pre-granted capabilities to either application
-- role. Reset every Story 1.6c responsibility table before exact re-grants.
revoke all privileges on table
    identity_access.ia_responsibility_lineage_binding,
    identity_access.ia_authoritative_role_binding_history,
    identity_access.ia_responsibility_v2_source_fact,
    identity_access.ia_responsibility_v2_reconciliation_snapshot,
    identity_access.ia_responsibility_v2_snapshot_entry,
    identity_access.ia_responsibility_v2_snapshot_lineage,
    identity_access.ia_responsibility_v2_cutover_command,
    identity_access.ia_responsibility_v2_shadow_checkpoint,
    identity_access.ia_responsibility_v2_shadow_current,
    identity_access.ia_responsibility_v2_shadow_invalidation_fact,
    identity_access.ia_responsibility_v2_shadow_lineage_head
    from scholarsense_identity_sync_worker,
         scholarsense_identity_current_reader,
         scholarsense_identity_responsibility_v2_cutover;

-- V1/V2 routine ingestion may upsert live rows, but destructive replacement
-- and the reconciliation/activation gate belong only to the cutover role.
revoke delete on identity_access.ia_responsibility_current
    from public,
         scholarsense_identity_sync_worker,
         scholarsense_identity_current_reader;

grant select, insert on identity_access.ia_responsibility_lineage_binding
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access
    .ia_authoritative_role_binding_history
    to scholarsense_identity_sync_worker;
grant select on identity_access
    .ia_responsibility_v2_shadow_checkpoint
    to scholarsense_identity_sync_worker;
grant insert (
    source_id, feed_id, partition_id, consumer_projection,
    source_version, source_watermark, aggregate_version,
    replay_started_at_zero, updated_at, trace_id
) on identity_access.ia_responsibility_v2_shadow_checkpoint
    to scholarsense_identity_sync_worker;
grant update (
    source_version, source_watermark, aggregate_version,
    last_successful_at, replay_started_at_zero, updated_at, trace_id
) on identity_access.ia_responsibility_v2_shadow_checkpoint
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access
    .ia_responsibility_v2_shadow_current
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access
    .ia_responsibility_v2_shadow_invalidation_fact
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access
    .ia_responsibility_v2_shadow_lineage_head
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access
    .ia_responsibility_v2_source_fact
    to scholarsense_identity_sync_worker;

-- The isolated cutover principal can read the frozen shadow evidence, append
-- an authenticated reconciliation manifest, advance only gate columns, and
-- atomically replace the approved live route. It cannot ingest V2 source rows.
grant select on identity_access.ia_responsibility_lineage_binding
    to scholarsense_identity_responsibility_v2_cutover;
grant select on identity_access.ia_responsibility_v2_source_fact
    to scholarsense_identity_responsibility_v2_cutover;
grant select on identity_access
    .ia_responsibility_v2_shadow_checkpoint
    to scholarsense_identity_responsibility_v2_cutover;
grant select on identity_access.ia_responsibility_v2_shadow_current
    to scholarsense_identity_responsibility_v2_cutover;
grant select on identity_access
    .ia_responsibility_v2_shadow_invalidation_fact
    to scholarsense_identity_responsibility_v2_cutover;
grant select on identity_access
    .ia_responsibility_v2_shadow_lineage_head
    to scholarsense_identity_responsibility_v2_cutover;
grant select, insert on identity_access
    .ia_responsibility_v2_reconciliation_snapshot
    to scholarsense_identity_responsibility_v2_cutover;
grant select, insert on identity_access
    .ia_responsibility_v2_snapshot_entry
    to scholarsense_identity_responsibility_v2_cutover;
grant select, insert on identity_access
    .ia_responsibility_v2_snapshot_lineage
    to scholarsense_identity_responsibility_v2_cutover;
grant select, insert on identity_access
    .ia_responsibility_v2_cutover_command
    to scholarsense_identity_responsibility_v2_cutover;
grant update (
    status, reason_code, snapshot_id, updated_at, completed_at
) on identity_access.ia_responsibility_v2_cutover_command
    to scholarsense_identity_responsibility_v2_cutover;
grant update (
    reconciliation_status, reconciliation_watermark,
    reconciliation_expected_count, reconciliation_actual_count,
    reconciliation_expected_digest, reconciliation_actual_digest,
    reconciliation_lineage_conflicts, reconciliation_snapshot_id,
    reconciliation_snapshot_source_version,
    reconciliation_envelope_digest, reconciliation_signature_digest,
    reconciliation_expected_lineage_count,
    reconciliation_actual_lineage_count,
    reconciliation_expected_lineage_digest,
    reconciliation_actual_lineage_digest,
    reconciliation_live_source_version, reconciliation_live_watermark,
    invalidation_materialized, invalidation_materialized_count,
    active, reconciled_at, activated_at, trace_id
) on identity_access.ia_responsibility_v2_shadow_checkpoint
    to scholarsense_identity_responsibility_v2_cutover;
grant insert, delete on identity_access.ia_responsibility_current
    to scholarsense_identity_responsibility_v2_cutover;
grant select (
    source_id, feed_id, partition_id, consumer_projection
) on identity_access.ia_responsibility_current
    to scholarsense_identity_responsibility_v2_cutover;
grant select, insert on identity_access.ia_identity_sync_checkpoint
    to scholarsense_identity_responsibility_v2_cutover;
grant update (
    source_version, source_watermark, aggregate_version,
    last_successful_at, health, freshness, updated_at, trace_id,
    retention_effective_at
) on identity_access.ia_identity_sync_checkpoint
    to scholarsense_identity_responsibility_v2_cutover;

-- Append-only guarantee: the writer receives SELECT/INSERT only; no role used
-- by the application receives UPDATE, DELETE, or TRUNCATE on immutable facts.
revoke all privileges on identity_access.ia_access_invalidation_fact
    from public;
revoke all privileges on identity_access.ia_access_invalidation_lineage_head
    from public;
revoke all privileges on identity_access.ia_access_invalidation_outbox
    from public;
revoke all privileges on identity_access.ia_access_invalidation_delivery_attempt
    from public;
revoke all privileges on identity_access.ia_access_invalidation_consumer_registry
    from public;
revoke all privileges on identity_access.ia_access_invalidation_observed_ack
    from public;
revoke all privileges on identity_access.ia_access_invalidation_propagation
    from public;
revoke all privileges on identity_access.ia_access_invalidation_local_inbox
    from public;
revoke all privileges on identity_access.ia_access_invalidation_local_apply
    from public;
revoke all privileges on identity_access.ia_access_invalidation_consumer_applied_outbox
    from public;
revoke all privileges on identity_access.ia_access_invalidation_consumer_watermark
    from public;
revoke all privileges on identity_access.ia_access_invalidation_local_fence
    from public;
revoke all privileges on identity_access.ia_access_invalidation_backfill_request
    from public;
revoke all privileges on identity_access.ia_access_invalidation_job
    from public;
revoke all privileges on identity_access.ia_access_invalidation_reconciliation
    from public;

-- Default privileges may have pre-granted capabilities to existing roles.
-- Reset both known producer/read groups before rebuilding their exact V8 ACL.
revoke all privileges on table
    identity_access.ia_access_invalidation_fact,
    identity_access.ia_access_invalidation_lineage_head,
    identity_access.ia_access_invalidation_outbox,
    identity_access.ia_access_invalidation_delivery_attempt,
    identity_access.ia_access_invalidation_consumer_registry,
    identity_access.ia_access_invalidation_observed_ack,
    identity_access.ia_access_invalidation_propagation,
    identity_access.ia_access_invalidation_local_inbox,
    identity_access.ia_access_invalidation_local_apply,
    identity_access.ia_access_invalidation_consumer_applied_outbox,
    identity_access.ia_access_invalidation_consumer_watermark,
    identity_access.ia_access_invalidation_local_fence,
    identity_access.ia_access_invalidation_backfill_request,
    identity_access.ia_access_invalidation_job,
    identity_access.ia_access_invalidation_reconciliation
    from scholarsense_identity_sync_worker,
         scholarsense_identity_current_reader;

grant select, insert on identity_access.ia_access_invalidation_fact
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_access_invalidation_lineage_head
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_access_invalidation_outbox
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_access_invalidation_delivery_attempt
    to scholarsense_identity_sync_worker;
grant select on identity_access.ia_access_invalidation_consumer_registry
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_access_invalidation_observed_ack
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_access_invalidation_propagation
    to scholarsense_identity_sync_worker;
-- Producer/coordinator can observe consumer-owned evidence and publish the
-- observed acknowledgement, but cannot manufacture inbox/apply/watermark or
-- fence state.  Only the applied-fact observer may change outbox delivery
-- status after the consumer has appended the immutable row.
grant select on identity_access.ia_access_invalidation_local_inbox
    to scholarsense_identity_sync_worker;
grant select on identity_access.ia_access_invalidation_local_apply
    to scholarsense_identity_sync_worker;
grant select on identity_access
    .ia_access_invalidation_consumer_applied_outbox
    to scholarsense_identity_sync_worker;
grant update (status, attempts) on identity_access
    .ia_access_invalidation_consumer_applied_outbox
    to scholarsense_identity_sync_worker;
grant select on identity_access.ia_access_invalidation_consumer_watermark
    to scholarsense_identity_sync_worker;
grant select on identity_access.ia_access_invalidation_local_fence
    to scholarsense_identity_sync_worker;
grant select on identity_access.ia_access_invalidation_backfill_request
    to scholarsense_identity_sync_worker;
grant select, insert, update on identity_access.ia_access_invalidation_job
    to scholarsense_identity_sync_worker;
grant select, insert on identity_access.ia_access_invalidation_reconciliation
    to scholarsense_identity_sync_worker;

-- Activation materializes the reconciled shadow facts and their audit trail
-- in one cutover transaction. These are the only non-responsibility writes
-- available to the cutover role.
grant select, insert on identity_access.ia_access_invalidation_fact
    to scholarsense_identity_responsibility_v2_cutover;
grant select, insert on identity_access.ia_access_invalidation_lineage_head
    to scholarsense_identity_responsibility_v2_cutover;
grant update (
    current_event_id, current_version, fencing_token, updated_at, trace_id
) on identity_access.ia_access_invalidation_lineage_head
    to scholarsense_identity_responsibility_v2_cutover;
grant insert on identity_access.ia_access_invalidation_outbox
    to scholarsense_identity_responsibility_v2_cutover;
grant select on identity_access.ia_access_invalidation_consumer_registry
    to scholarsense_identity_responsibility_v2_cutover;
grant insert on identity_access.ia_access_invalidation_propagation
    to scholarsense_identity_responsibility_v2_cutover;
grant select, insert on identity_access.ia_access_invalidation_job
    to scholarsense_identity_responsibility_v2_cutover;
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
    to scholarsense_identity_responsibility_v2_cutover;
grant insert (
    event_id, audit_id, event_type, schema_version, envelope, created_at,
    delivery_status, attempts, next_attempt_at
) on identity_access.ia_local_audit_outbox
    to scholarsense_identity_responsibility_v2_cutover;

-- The local authorization consumer connects with a separate login that is a
-- member only of this role.  It can read immutable producer facts and append
-- or advance its own evidence, but cannot write producer/coordinator tables.
grant select on identity_access.ia_access_invalidation_fact
    to scholarsense_identity_invalidation_consumer;
grant select on identity_access.ia_access_invalidation_consumer_registry
    to scholarsense_identity_invalidation_consumer;
grant select, insert on identity_access.ia_access_invalidation_local_inbox
    to scholarsense_identity_invalidation_consumer;
grant select, insert on identity_access.ia_access_invalidation_local_apply
    to scholarsense_identity_invalidation_consumer;
grant select, insert on identity_access
    .ia_access_invalidation_consumer_applied_outbox
    to scholarsense_identity_invalidation_consumer;
grant select, insert, update on identity_access
    .ia_access_invalidation_consumer_watermark
    to scholarsense_identity_invalidation_consumer;
grant select, insert, update on identity_access
    .ia_access_invalidation_local_fence
    to scholarsense_identity_invalidation_consumer;
grant select, insert, update on identity_access
    .ia_access_invalidation_backfill_request
    to scholarsense_identity_invalidation_consumer;

grant select on identity_access.ia_access_invalidation_consumer_registry
    to scholarsense_identity_current_reader;
grant select on identity_access.ia_access_invalidation_propagation
    to scholarsense_identity_current_reader;
grant select on identity_access.ia_access_invalidation_consumer_watermark
    to scholarsense_identity_current_reader;
grant select on identity_access.ia_access_invalidation_local_fence
    to scholarsense_identity_current_reader;
