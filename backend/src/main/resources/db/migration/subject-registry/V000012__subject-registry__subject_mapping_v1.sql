create extension if not exists btree_gist with schema public;

create schema if not exists subject_registry;

do $$ begin
    create role scholarsense_subject_registry_online nologin;
exception when duplicate_object then null;
end $$;
do $$ begin
    create role scholarsense_subject_registry_relay nologin;
exception when duplicate_object then null;
end $$;

create table subject_registry.sr_student_ref_reservation (
    student_ref uuid primary key,
    authority_source_id varchar(64) not null
        check (authority_source_id = 'SRC-P0-STUDENT-001'),
    issued_at timestamptz not null,
    status varchar(16) not null check (status in ('active','tombstoned')),
    tombstoned_at timestamptz,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    check (substring(student_ref::text, 15, 1) = '7'
        and substring(student_ref::text, 20, 1) in ('8','9','a','b')),
    check ((status = 'tombstoned') = (tombstoned_at is not null))
);

create table subject_registry.sr_identifier_secret (
    identifier_id uuid primary key,
    source_id varchar(64) not null
        check (source_id ~ '^SRC-P[01]-[A-Z-]+-[0-9]{3}$'),
    identifier_type varchar(64) not null,
    environment varchar(32) not null,
    key_ref varchar(256) not null,
    key_version varchar(64) not null,
    protected_identifier_token varchar(76) not null
        check (protected_identifier_token ~ '^hmac-sha256:[0-9a-f]{64}$'),
    ciphertext text not null check (ciphertext ~ '^aesgcm-v1:'),
    purpose varchar(64) not null,
    created_at timestamptz not null,
    check (substring(identifier_id::text, 15, 1) = '7'
        and substring(identifier_id::text, 20, 1) in ('8','9','a','b')),
    unique (source_id, identifier_type, environment, key_ref, key_version,
            protected_identifier_token)
);

create table subject_registry.sr_subject_mapping (
    mapping_id uuid primary key,
    mapping_aggregate_id uuid not null,
    identifier_id uuid not null
        references subject_registry.sr_identifier_secret(identifier_id),
    student_ref uuid not null
        references subject_registry.sr_student_ref_reservation(student_ref),
    effective_period tstzrange not null,
    mapping_version bigint not null
        check (mapping_version between 1 and 9007199254740991),
    created_at timestamptz not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    check (not isempty(effective_period)
        and lower_inc(effective_period) and not upper_inc(effective_period)),
    check (substring(mapping_id::text, 15, 1) = '7'
        and substring(mapping_id::text, 20, 1) in ('8','9','a','b')),
    check (substring(mapping_aggregate_id::text, 15, 1) = '7'
        and substring(mapping_aggregate_id::text, 20, 1) in ('8','9','a','b')),
    unique (mapping_aggregate_id, mapping_version),
    exclude using gist (identifier_id with =, effective_period with &&)
);

create table subject_registry.sr_mapping_exception (
    exception_id uuid primary key,
    identifier_id uuid not null
        references subject_registry.sr_identifier_secret(identifier_id),
    official_identifier_id uuid not null
        references subject_registry.sr_identifier_secret(identifier_id),
    exception_code varchar(64) not null check (exception_code in (
        'no-match','ambiguous','interval-overlap','version-regression',
        'reissue-unproven','revocation-chain-incomplete')),
    source_owner varchar(128) not null,
    status varchar(16) not null check (status in (
        'open','in-review','resolved','dismissed')),
    resolution_code varchar(64),
    aggregate_version bigint not null
        check (aggregate_version between 1 and 9007199254740991),
    detected_at timestamptz not null,
    updated_at timestamptz not null,
    check (substring(exception_id::text, 15, 1) = '7'
        and substring(exception_id::text, 20, 1) in ('8','9','a','b')),
    check ((status in ('resolved','dismissed')) = (resolution_code is not null))
);

create table subject_registry.sr_correction_event (
    event_id uuid primary key,
    lineage_id uuid not null,
    exception_id uuid not null
        references subject_registry.sr_mapping_exception(exception_id),
    supersedes_event_id uuid
        references subject_registry.sr_correction_event(event_id),
    aggregate_version bigint not null
        check (aggregate_version between 1 and 9007199254740991),
    correction_type varchar(16) not null check (correction_type in (
        'merge','split','correct','revoke')),
    link_type varchar(16) not null check (link_type in (
        'alias','merged-into','split-into')),
    source_student_ref uuid not null
        references subject_registry.sr_student_ref_reservation(student_ref),
    reason varchar(64) not null check (reason in (
        'AUTHORITY_CORRECTION','AUTHORITY_MERGE',
        'AUTHORITY_SPLIT','AUTHORITY_REVOCATION')),
    effective_at timestamptz not null,
    created_at timestamptz not null,
    check (substring(event_id::text, 15, 1) = '7'
        and substring(event_id::text, 20, 1) in ('8','9','a','b')),
    check (substring(lineage_id::text, 15, 1) = '7'
        and substring(lineage_id::text, 20, 1) in ('8','9','a','b')),
    check ((aggregate_version = 1) = (supersedes_event_id is null)),
    unique (lineage_id, aggregate_version)
);

create table subject_registry.sr_correction_target (
    event_id uuid not null
        references subject_registry.sr_correction_event(event_id),
    target_student_ref uuid not null
        references subject_registry.sr_student_ref_reservation(student_ref),
    ordinal integer not null check (ordinal > 0),
    manual_selection_required boolean not null,
    primary key (event_id, target_student_ref),
    unique (event_id, ordinal)
);

create table subject_registry.sr_mapping_recompute_outbox (
    request_id uuid primary key,
    correction_lineage_id uuid not null,
    source_id varchar(64) not null check (
        source_id ~ '^SRC-P[01]-[A-Z-]+-[0-9]{3}$'),
    source_watermark varchar(128) not null,
    trace_id char(32) not null check (trace_id ~ '^[0-9a-f]{32}$'),
    affected_student_ref_count integer not null check (affected_student_ref_count > 0),
    payload jsonb not null,
    status varchar(16) not null default 'pending'
        check (status in ('pending','retrying','delivered','failed')),
    attempts bigint not null default 0 check (attempts >= 0),
    available_at timestamptz not null,
    claimed_until timestamptz,
    delivered_at timestamptz,
    last_error_code varchar(128),
    created_at timestamptz not null,
    check (substring(request_id::text, 15, 1) = '7'
        and substring(request_id::text, 20, 1) in ('8','9','a','b')),
    check (octet_length(convert_to(payload::text, 'UTF8')) <= 65536),
    check (payload::text !~* '(sourceNativeIdentifier|protectedIdentifierToken|ciphertext|keyValue|candidateStudentRefs)')
);

create table subject_registry.sr_repair_idempotency (
    idempotency_scope_digest char(71) primary key
        check (idempotency_scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    request_digest char(71) not null
        check (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    exception_id uuid not null
        references subject_registry.sr_mapping_exception(exception_id),
    response jsonb,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    check (expires_at = created_at + interval '90 days')
);

create table subject_registry.sr_local_audit_fact (
    audit_id uuid primary key,
    actor_search_token varchar(128) not null,
    action varchar(128) not null,
    result_code varchar(64) not null,
    object_id uuid not null,
    aggregate_version bigint not null
        check (aggregate_version between 1 and 9007199254740991),
    trace_id char(32) not null check (trace_id ~ '^[0-9a-f]{32}$'),
    occurred_at timestamptz not null,
    authorization_context jsonb not null,
    schema_version varchar(64) not null default 'LOCAL-AUDIT-FACT-1.0.0',
    retention_schedule_version varchar(64) not null default 'RS-1.0.0',
    retention_owner varchar(64) not null default 'subject-registry',
    legal_hold boolean not null default false,
    expires_at timestamptz not null,
    check (expires_at = ((occurred_at at time zone 'UTC') + interval '3 years') at time zone 'UTC'),
    check (authorization_context::text !~* '(sourceNativeIdentifier|protectedIdentifierToken|ciphertext|keyValue)')
);

create table subject_registry.sr_local_audit_outbox (
    event_id uuid primary key,
    audit_id uuid not null unique
        references subject_registry.sr_local_audit_fact(audit_id),
    event_type varchar(128) not null,
    schema_version varchar(64) not null,
    producer varchar(64) not null,
    payload jsonb not null,
    payload_digest char(64) not null check (payload_digest ~ '^[0-9a-f]{64}$'),
    status varchar(16) not null default 'pending'
        check (status in ('pending','retrying','delivered','failed')),
    attempts bigint not null default 0 check (attempts >= 0),
    available_at timestamptz not null,
    claimed_until timestamptz,
    delivered_at timestamptz,
    last_error_code varchar(128),
    created_at timestamptz not null,
    check (payload::text !~* '(sourceNativeIdentifier|protectedIdentifierToken|ciphertext|keyValue|candidateStudentRefs)')
);

create index sr_mapping_exception_status_idx
    on subject_registry.sr_mapping_exception(status, detected_at desc, exception_id);
create index sr_mapping_identifier_idx
    on subject_registry.sr_subject_mapping(identifier_id, mapping_version desc);
create index sr_recompute_outbox_due_idx
    on subject_registry.sr_mapping_recompute_outbox(status, available_at, request_id);
create index sr_repair_idempotency_expiry_idx
    on subject_registry.sr_repair_idempotency(expires_at);
create index sr_audit_outbox_due_idx
    on subject_registry.sr_local_audit_outbox(status, available_at, event_id);

create function subject_registry.sr_require_initial_exception_open()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if new.status <> 'open' or new.aggregate_version <> 1
       or new.resolution_code is not null then
        raise exception using
            errcode = 'check_violation',
            message = 'SUBJECT_REGISTRY_INITIAL_EXCEPTION_STATE_INVALID';
    end if;
    return new;
end
$$;

create trigger sr_mapping_exception_initial_open
before insert on subject_registry.sr_mapping_exception
for each row execute function subject_registry.sr_require_initial_exception_open();

create function subject_registry.sr_issue_student_ref(
    requested_student_ref uuid,
    requested_authority_source_id varchar,
    requested_issued_at timestamptz,
    requested_audit_id uuid,
    requested_audit_outbox_id uuid,
    requested_actor_search_token varchar,
    requested_trace_id varchar)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    if requested_authority_source_id <> 'SRC-P0-STUDENT-001' then
        raise exception using
            errcode = 'insufficient_privilege',
            message = 'SUBJECT_REGISTRY_AUTHORITY_PROOF_REQUIRED';
    end if;
    insert into subject_registry.sr_student_ref_reservation
      (student_ref, authority_source_id, issued_at, status)
    values (requested_student_ref, requested_authority_source_id,
            requested_issued_at, 'active');
    insert into subject_registry.sr_local_audit_fact
      (audit_id, actor_search_token, action, result_code, object_id,
       aggregate_version, trace_id, occurred_at, authorization_context, expires_at)
    values (requested_audit_id, requested_actor_search_token,
            'student-ref.issue', 'issued', requested_student_ref, 1,
            requested_trace_id, requested_issued_at,
            jsonb_build_object('authoritySourceId', requested_authority_source_id),
            ((requested_issued_at at time zone 'UTC') + interval '3 years') at time zone 'UTC');
    insert into subject_registry.sr_local_audit_outbox
      (event_id, audit_id, event_type, schema_version, producer, payload,
       payload_digest, available_at, created_at)
    values (requested_audit_outbox_id, requested_audit_id,
            'subject-registry.audit-fact.recorded', 'LOCAL-AUDIT-FACT-1.0.0',
            'subject-registry', jsonb_build_object('action', 'student-ref.issue'),
            repeat('0', 64), requested_issued_at, requested_issued_at);
    return true;
end
$$;

create function subject_registry.sr_record_subject_mapping(
    requested_identifier_id uuid,
    requested_mapping_id uuid,
    requested_mapping_aggregate_id uuid,
    requested_student_ref uuid,
    requested_source_id varchar,
    requested_identifier_type varchar,
    requested_environment varchar,
    requested_key_ref varchar,
    requested_key_version varchar,
    requested_token varchar,
    requested_ciphertext text,
    requested_purpose varchar,
    requested_effective_from timestamptz,
    requested_effective_to timestamptz,
    requested_mapping_version bigint,
    requested_created_at timestamptz,
    requested_audit_id uuid,
    requested_audit_outbox_id uuid,
    requested_actor_search_token varchar,
    requested_trace_id varchar)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    sr_identifier_id uuid;
begin
    if not exists (
        select 1 from subject_registry.sr_student_ref_reservation
         where student_ref = requested_student_ref) then
        if requested_source_id <> 'SRC-P0-STUDENT-001' then
            raise exception using
                errcode = 'insufficient_privilege',
                message = 'SUBJECT_REGISTRY_AUTHORITY_PROOF_REQUIRED';
        end if;
        insert into subject_registry.sr_student_ref_reservation
          (student_ref, authority_source_id, issued_at, status)
        values (requested_student_ref, requested_source_id, requested_created_at, 'active');
    end if;
    insert into subject_registry.sr_identifier_secret
      (identifier_id, source_id, identifier_type, environment, key_ref,
       key_version, protected_identifier_token, ciphertext, purpose, created_at)
    values (requested_identifier_id, requested_source_id, requested_identifier_type,
            requested_environment, requested_key_ref, requested_key_version,
            requested_token, requested_ciphertext, requested_purpose, requested_created_at)
    on conflict (source_id, identifier_type, environment, key_ref, key_version,
                 protected_identifier_token) do nothing;
    select identifier_id
      into sr_identifier_id
      from subject_registry.sr_identifier_secret
     where source_id = requested_source_id
       and identifier_type = requested_identifier_type
       and environment = requested_environment
       and key_ref = requested_key_ref
       and key_version = requested_key_version
       and protected_identifier_token = requested_token;
    insert into subject_registry.sr_subject_mapping
      (mapping_id, mapping_aggregate_id, identifier_id, student_ref,
       effective_period, mapping_version, created_at)
    values (requested_mapping_id, requested_mapping_aggregate_id,
            sr_identifier_id, requested_student_ref,
            tstzrange(requested_effective_from, requested_effective_to, '[)'),
            requested_mapping_version, requested_created_at);
    insert into subject_registry.sr_local_audit_fact
      (audit_id, actor_search_token, action, result_code, object_id,
       aggregate_version, trace_id, occurred_at, authorization_context, expires_at)
    values (requested_audit_id, requested_actor_search_token,
            'subject-mapping.ingest', 'mapped', requested_mapping_id,
            requested_mapping_version, requested_trace_id, requested_created_at,
            jsonb_build_object('sourceId', requested_source_id),
            ((requested_created_at at time zone 'UTC') + interval '3 years') at time zone 'UTC');
    insert into subject_registry.sr_local_audit_outbox
      (event_id, audit_id, event_type, schema_version, producer, payload,
       payload_digest, available_at, created_at)
    values (requested_audit_outbox_id, requested_audit_id,
            'subject-registry.audit-fact.recorded', 'LOCAL-AUDIT-FACT-1.0.0',
            'subject-registry',
            jsonb_build_object('action', 'subject-mapping.ingest',
                               'resultCode', 'mapped'),
            repeat('0', 64), requested_created_at, requested_created_at);
    return true;
end
$$;

create function subject_registry.sr_record_mapping_exception(
    requested_identifier_id uuid,
    requested_exception_id uuid,
    requested_source_id varchar,
    requested_identifier_type varchar,
    requested_environment varchar,
    requested_key_ref varchar,
    requested_key_version varchar,
    requested_token varchar,
    requested_ciphertext text,
    requested_purpose varchar,
    requested_official_identifier_id uuid,
    requested_official_environment varchar,
    requested_official_key_ref varchar,
    requested_official_key_version varchar,
    requested_official_token varchar,
    requested_official_ciphertext text,
    requested_exception_code varchar,
    requested_source_owner varchar,
    requested_detected_at timestamptz,
    requested_audit_id uuid,
    requested_audit_outbox_id uuid,
    requested_actor_search_token varchar,
    requested_trace_id varchar)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    sr_source_identifier_id uuid;
    sr_official_identifier_id uuid;
begin
    insert into subject_registry.sr_identifier_secret
      (identifier_id, source_id, identifier_type, environment, key_ref,
       key_version, protected_identifier_token, ciphertext, purpose, created_at)
    values (requested_identifier_id, requested_source_id, requested_identifier_type,
            requested_environment, requested_key_ref, requested_key_version,
            requested_token, requested_ciphertext, requested_purpose,
            requested_detected_at)
    on conflict (source_id, identifier_type, environment, key_ref, key_version,
                 protected_identifier_token) do nothing;
    select identifier_id
      into sr_source_identifier_id
      from subject_registry.sr_identifier_secret
     where source_id = requested_source_id
       and identifier_type = requested_identifier_type
       and environment = requested_environment
       and key_ref = requested_key_ref
       and key_version = requested_key_version
       and protected_identifier_token = requested_token;
    insert into subject_registry.sr_identifier_secret
      (identifier_id, source_id, identifier_type, environment, key_ref,
       key_version, protected_identifier_token, ciphertext, purpose, created_at)
    values (requested_official_identifier_id, 'SRC-P0-STUDENT-001', 'student-number',
            requested_official_environment, requested_official_key_ref,
            requested_official_key_version, requested_official_token,
            requested_official_ciphertext, 'STUDENT_OFFICIAL_REF', requested_detected_at)
    on conflict (source_id, identifier_type, environment, key_ref, key_version,
                 protected_identifier_token) do nothing;
    select identifier_id
      into sr_official_identifier_id
      from subject_registry.sr_identifier_secret
     where source_id = 'SRC-P0-STUDENT-001'
       and identifier_type = 'student-number'
       and environment = requested_official_environment
       and key_ref = requested_official_key_ref
       and key_version = requested_official_key_version
       and protected_identifier_token = requested_official_token;
    insert into subject_registry.sr_mapping_exception
      (exception_id, identifier_id, official_identifier_id,
       exception_code, source_owner, status,
       aggregate_version, detected_at, updated_at)
    values (requested_exception_id, sr_source_identifier_id, sr_official_identifier_id,
            requested_exception_code,
            requested_source_owner, 'open', 1,
            requested_detected_at, requested_detected_at);
    insert into subject_registry.sr_local_audit_fact
      (audit_id, actor_search_token, action, result_code, object_id,
       aggregate_version, trace_id, occurred_at, authorization_context, expires_at)
    values (requested_audit_id, requested_actor_search_token,
            'subject-mapping.ingest', 'isolated', requested_exception_id, 1,
            requested_trace_id, requested_detected_at,
            jsonb_build_object('sourceId', requested_source_id,
                               'exceptionCode', requested_exception_code),
            ((requested_detected_at at time zone 'UTC') + interval '3 years') at time zone 'UTC');
    insert into subject_registry.sr_local_audit_outbox
      (event_id, audit_id, event_type, schema_version, producer, payload,
       payload_digest, available_at, created_at)
    values (requested_audit_outbox_id, requested_audit_id,
            'subject-registry.audit-fact.recorded', 'LOCAL-AUDIT-FACT-1.0.0',
            'subject-registry',
            jsonb_build_object('action', 'subject-mapping.ingest',
                               'resultCode', 'isolated'),
            repeat('0', 64), requested_detected_at, requested_detected_at);
    return true;
end
$$;

create function subject_registry.sr_repair_mapping_exception(
    requested_exception_id uuid,
    requested_expected_version bigint,
    requested_idempotency_scope_digest varchar,
    requested_request_digest varchar,
    requested_correction_event_id uuid,
    requested_lineage_id uuid,
    requested_correction_type varchar,
    requested_link_type varchar,
    requested_source_student_ref uuid,
    requested_target_student_refs uuid[],
    requested_reason varchar,
    requested_effective_at timestamptz,
    requested_recompute_request_id uuid,
    requested_source_watermark varchar,
    requested_audit_id uuid,
    requested_audit_outbox_id uuid,
    requested_actor_search_token varchar,
    requested_trace_id varchar)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    sr_stored_request_digest char(71);
    sr_stored_response jsonb;
    sr_current_status varchar(16);
    sr_current_version bigint;
    sr_source_id varchar(64);
    sr_new_version bigint;
    sr_response_value jsonb;
    sr_target_student_ref uuid;
    sr_target_ordinal integer := 0;
begin
    insert into subject_registry.sr_repair_idempotency
      (idempotency_scope_digest, request_digest, exception_id, response,
       created_at, expires_at)
    values (requested_idempotency_scope_digest, requested_request_digest,
            requested_exception_id, null, requested_effective_at,
            requested_effective_at + interval '90 days')
    on conflict (idempotency_scope_digest) do nothing;

    select request_digest, response
      into sr_stored_request_digest, sr_stored_response
      from subject_registry.sr_repair_idempotency
     where idempotency_scope_digest = requested_idempotency_scope_digest
     for update;
    if sr_stored_request_digest <> requested_request_digest then
        raise exception using
            errcode = 'unique_violation',
            message = 'SUBJECT_REGISTRY_IDEMPOTENCY_MISMATCH';
    end if;
    if sr_stored_response is not null then
        return sr_stored_response;
    end if;

    select exception.status, exception.aggregate_version, source.source_id
      into sr_current_status, sr_current_version, sr_source_id
      from subject_registry.sr_mapping_exception exception
      join subject_registry.sr_identifier_secret source
        on source.identifier_id=exception.identifier_id
     where exception.exception_id = requested_exception_id
     for update;
    if not found then
        raise exception using
            errcode = 'insufficient_privilege',
            message = 'SUBJECT_REGISTRY_FORBIDDEN';
    end if;
    if sr_current_version <> requested_expected_version then
        raise exception using
            errcode = 'serialization_failure',
            message = 'SUBJECT_REGISTRY_VERSION_CONFLICT';
    end if;
    if sr_current_status = 'open' then
        sr_new_version := sr_current_version + 2;
    elsif sr_current_status = 'in-review' then
        sr_new_version := sr_current_version + 1;
    else
        raise exception using
            errcode = 'check_violation',
            message = 'SUBJECT_REGISTRY_INVALID_TRANSITION';
    end if;
    if sr_new_version > 9007199254740991 then
        raise exception using
            errcode = 'serialization_failure',
            message = 'SUBJECT_REGISTRY_VERSION_CONFLICT';
    end if;
    if coalesce(array_length(requested_target_student_refs, 1), 0) < 1
       or requested_source_student_ref = any(requested_target_student_refs) then
        raise exception using
            errcode = 'check_violation',
            message = 'SUBJECT_REGISTRY_LINEAGE_INVALID';
    end if;

    update subject_registry.sr_mapping_exception
       set status = 'resolved',
           resolution_code = 'mapping-corrected',
           aggregate_version = sr_new_version,
           updated_at = requested_effective_at
     where exception_id = requested_exception_id
       and aggregate_version = requested_expected_version;
    if not found then
        raise exception using
            errcode = 'serialization_failure',
            message = 'SUBJECT_REGISTRY_VERSION_CONFLICT';
    end if;

    insert into subject_registry.sr_correction_event
      (event_id, lineage_id, exception_id, aggregate_version,
       correction_type, link_type, source_student_ref, reason,
       effective_at, created_at)
    values (requested_correction_event_id, requested_lineage_id,
            requested_exception_id, 1, requested_correction_type,
            requested_link_type, requested_source_student_ref,
            requested_reason, requested_effective_at, requested_effective_at);
    foreach sr_target_student_ref in array requested_target_student_refs loop
        sr_target_ordinal := sr_target_ordinal + 1;
        insert into subject_registry.sr_correction_target
          (event_id, target_student_ref, ordinal, manual_selection_required)
        values (requested_correction_event_id, sr_target_student_ref,
                sr_target_ordinal, requested_link_type = 'split-into');
    end loop;
    insert into subject_registry.sr_mapping_recompute_outbox
      (request_id, correction_lineage_id, source_id, source_watermark, trace_id,
       affected_student_ref_count, payload, available_at, created_at)
    values (requested_recompute_request_id, requested_lineage_id,
            sr_source_id, requested_source_watermark, requested_trace_id,
            array_length(requested_target_student_refs, 1) + 1,
            jsonb_build_object(
                'specversion', '1.0',
                'source', 'urn:scholarsense:subject-registry',
                'id', requested_recompute_request_id,
                'type', 'cn.edu.suda.scholarsense.subject-mapping.changed.v1',
                'time', requested_effective_at,
                'datacontenttype', 'application/json',
                'data', jsonb_build_object(
                    'schemaVersion', 'SUBJECT-MAPPING-CHANGED-1.0.0',
                    'contractVersion', 'SUBJECT-REGISTRY-1.0.0',
                    'aggregateType', 'SubjectMapping',
                    'aggregateId', requested_lineage_id,
                    'aggregateVersion', 1,
                    'occurredAt', requested_effective_at,
                    'producer', 'subject-registry',
                    'correlationId', requested_recompute_request_id,
                    'causationId', requested_correction_event_id,
                    'supersedesId', null,
                    'reason', case requested_reason
                        when 'AUTHORITY_MERGE' then 'SUBJECT_MERGED'
                        when 'AUTHORITY_SPLIT' then 'SUBJECT_SPLIT'
                        when 'AUTHORITY_REVOCATION' then 'MAPPING_REVOKED'
                        else 'AUTHORITY_CORRECTION' end,
                    'effectiveAt', requested_effective_at,
                    'lineageId', requested_lineage_id,
                    'traceId', requested_trace_id,
                    'mappingVersion', sr_new_version,
                    'relationType', requested_link_type,
                    'sourceId', sr_source_id,
                    'sourceWatermark', requested_source_watermark,
                    'affectedStudentRefs', array_prepend(
                        requested_source_student_ref, requested_target_student_refs))),
            requested_effective_at, requested_effective_at);
    insert into subject_registry.sr_local_audit_fact
      (audit_id, actor_search_token, action, result_code, object_id,
       aggregate_version, trace_id, occurred_at, authorization_context, expires_at)
    values (requested_audit_id, requested_actor_search_token,
            'subject-mapping.repair', 'resolved', requested_exception_id,
            sr_new_version, requested_trace_id, requested_effective_at,
            jsonb_build_object('policyVersion', 'RFP-1.0.0',
                               'purpose', 'SUBJECT_MAPPING_EXCEPTION_REPAIR'),
            ((requested_effective_at at time zone 'UTC') + interval '3 years') at time zone 'UTC');
    insert into subject_registry.sr_local_audit_outbox
      (event_id, audit_id, event_type, schema_version, producer, payload,
       payload_digest, available_at, created_at)
    values (requested_audit_outbox_id, requested_audit_id,
            'subject-registry.audit-fact.recorded', 'LOCAL-AUDIT-FACT-1.0.0',
            'subject-registry',
            jsonb_build_object('action', 'subject-mapping.repair',
                               'resultCode', 'resolved'),
            repeat('0', 64), requested_effective_at, requested_effective_at);

    sr_response_value := jsonb_build_object(
        'exceptionId', requested_exception_id,
        'status', 'resolved',
        'aggregateVersion', sr_new_version,
        'correctionEventId', requested_correction_event_id,
        'recomputeRequestId', requested_recompute_request_id);
    update subject_registry.sr_repair_idempotency
       set response = sr_response_value
     where idempotency_scope_digest = requested_idempotency_scope_digest;
    return sr_response_value;
end
$$;

create function subject_registry.sr_find_pending_recompute_request(
    requested_request_id uuid)
returns table (
    request_id uuid,
    owner_source_id varchar,
    queued_at timestamptz,
    trace_id char(32))
language sql
stable
security definer
set search_path = pg_catalog
as $$
    select request.request_id, request.source_id,
           request.created_at, request.trace_id
      from subject_registry.sr_mapping_recompute_outbox request
     where request.request_id=requested_request_id
$$;

revoke all privileges on schema subject_registry from public;
revoke all privileges on all tables in schema subject_registry from public;
revoke all on function subject_registry.sr_require_initial_exception_open()
    from public;
revoke all on function subject_registry.sr_issue_student_ref(
    uuid, varchar, timestamptz, uuid, uuid, varchar, varchar)
    from public;
revoke all on function subject_registry.sr_record_subject_mapping(
    uuid, uuid, uuid, uuid, varchar, varchar, varchar, varchar, varchar,
    varchar, text, varchar, timestamptz, timestamptz, bigint, timestamptz,
    uuid, uuid, varchar, varchar)
    from public;
revoke all on function subject_registry.sr_record_mapping_exception(
    uuid, uuid, varchar, varchar, varchar, varchar, varchar, varchar,
    text, varchar, uuid, varchar, varchar, varchar, varchar, text,
    varchar, varchar, timestamptz, uuid, uuid, varchar, varchar)
    from public;
revoke all on function subject_registry.sr_repair_mapping_exception(
    uuid, bigint, varchar, varchar, uuid, uuid, varchar, varchar, uuid,
    uuid[], varchar, timestamptz, uuid, varchar, uuid, uuid, varchar, varchar)
    from public;
revoke all on function subject_registry.sr_find_pending_recompute_request(uuid)
    from public;

grant usage on schema subject_registry
    to scholarsense_subject_registry_online, scholarsense_subject_registry_relay;
grant select on subject_registry.sr_student_ref_reservation,
    subject_registry.sr_identifier_secret,
    subject_registry.sr_subject_mapping,
    subject_registry.sr_mapping_exception,
    subject_registry.sr_correction_event,
    subject_registry.sr_correction_target,
    subject_registry.sr_repair_idempotency
    to scholarsense_subject_registry_online;
grant select on subject_registry.sr_local_audit_fact
    to scholarsense_subject_registry_relay;
grant select on subject_registry.sr_local_audit_outbox
    to scholarsense_subject_registry_relay;
grant update (status, attempts, available_at, claimed_until, delivered_at, last_error_code)
    on subject_registry.sr_local_audit_outbox
    to scholarsense_subject_registry_relay;
grant select on subject_registry.sr_mapping_recompute_outbox
    to scholarsense_subject_registry_relay;
grant update (status, attempts, available_at, claimed_until, delivered_at, last_error_code)
    on subject_registry.sr_mapping_recompute_outbox
    to scholarsense_subject_registry_relay;

grant execute on function subject_registry.sr_issue_student_ref(
    uuid, varchar, timestamptz, uuid, uuid, varchar, varchar)
    to scholarsense_subject_registry_online;
grant execute on function subject_registry.sr_record_subject_mapping(
    uuid, uuid, uuid, uuid, varchar, varchar, varchar, varchar, varchar,
    varchar, text, varchar, timestamptz, timestamptz, bigint, timestamptz,
    uuid, uuid, varchar, varchar)
    to scholarsense_subject_registry_online;
grant execute on function subject_registry.sr_record_mapping_exception(
    uuid, uuid, varchar, varchar, varchar, varchar, varchar, varchar,
    text, varchar, uuid, varchar, varchar, varchar, varchar, text,
    varchar, varchar, timestamptz, uuid, uuid, varchar, varchar)
    to scholarsense_subject_registry_online;
grant execute on function subject_registry.sr_repair_mapping_exception(
    uuid, bigint, varchar, varchar, uuid, uuid, varchar, varchar, uuid,
    uuid[], varchar, timestamptz, uuid, varchar, uuid, uuid, varchar, varchar)
    to scholarsense_subject_registry_online;
grant execute on function subject_registry.sr_find_pending_recompute_request(uuid)
    to scholarsense_subject_registry_online;
