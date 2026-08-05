alter table ingestion_quality.iq_catalog_current
    add column pointer_version bigint;

update ingestion_quality.iq_catalog_current
   set pointer_version = 1
 where pointer_version is null;

alter table ingestion_quality.iq_catalog_current
    alter column pointer_version set not null;

alter table ingestion_quality.iq_catalog_current
    add constraint iq_catalog_current_pointer_version_positive
    check (pointer_version between 1 and 9007199254740991),
    add constraint iq_catalog_current_aggregate_version_safe
    check (aggregate_version between 1 and 9007199254740991);

alter table ingestion_quality.iq_data_source_catalog
    add constraint iq_data_source_catalog_aggregate_version_safe
    check (aggregate_version between 1 and 9007199254740991);
alter table ingestion_quality.iq_catalog_validation_attempt
    add constraint iq_catalog_validation_attempt_aggregate_version_safe
    check (aggregate_version between 1 and 9007199254740991);
alter table ingestion_quality.iq_local_audit_fact
    add constraint iq_local_audit_fact_aggregate_version_safe
    check (aggregate_version is null
        or aggregate_version between 1 and 9007199254740991);

do $$
begin
    if exists (select 1 from ingestion_quality.iq_catalog_evidence) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_LEGACY_EVIDENCE_REQUIRES_CONTROLLED_REIMPORT';
    end if;
end $$;

alter table ingestion_quality.iq_catalog_evidence
    add column contract_version varchar(64) not null,
    add column schema_version varchar(64) not null,
    add column quality_gate_version varchar(64) not null,
    add column handoff_revision bigint not null,
    add column handoff_digest char(71) not null,
    add column input_digest char(71) not null,
    add column scenarios jsonb not null,
    add column signature_digest char(71) not null,
    add column cleanup_result varchar(16) not null,
    add column runtime_evidence_claim varchar(32) not null,
    add constraint iq_catalog_evidence_contract_version
        check (contract_version = 'DCC-1.0.0'),
    add constraint iq_catalog_evidence_quality_gate_version
        check (quality_gate_version = 'QG-1.0.0'),
    add constraint iq_catalog_evidence_handoff_revision
        check (handoff_revision between 1 and 9007199254740991),
    add constraint iq_catalog_evidence_handoff_digest
        check (handoff_digest ~ '^sha256:[0-9a-f]{64}$'),
    add constraint iq_catalog_evidence_input_digest
        check (input_digest ~ '^sha256:[0-9a-f]{64}$'),
    add constraint iq_catalog_evidence_scenarios_nonempty
        check (jsonb_typeof(scenarios) = 'array' and jsonb_array_length(scenarios) > 0),
    add constraint iq_catalog_evidence_signature_digest
        check (signature_digest ~ '^sha256:[0-9a-f]{64}$'),
    add constraint iq_catalog_evidence_cleanup_pass
        check (cleanup_result = 'pass'),
    add constraint iq_catalog_evidence_runtime_claim
        check (runtime_evidence_claim = 'target-verified');

update ingestion_quality.iq_data_source_catalog
   set retention_schedule_version = 'RS-1.0.0',
       retention_owner = 'ingestion-quality',
       expires_at = ((created_at at time zone 'UTC') + interval '3 years') at time zone 'UTC';
update ingestion_quality.iq_catalog_evidence
   set retention_schedule_version = 'RS-1.0.0',
       retention_owner = 'ingestion-quality',
       expires_at = ((occurred_at at time zone 'UTC') + interval '3 years') at time zone 'UTC';
update ingestion_quality.iq_local_audit_fact
   set retention_schedule_version = 'RS-1.0.0',
       retention_owner = 'ingestion-quality',
       expires_at = ((occurred_at at time zone 'UTC') + interval '3 years') at time zone 'UTC';
update ingestion_quality.iq_catalog_idempotency
   set retention_schedule_version = 'RS-1.0.0',
       expires_at = created_at + interval '90 days';

alter table ingestion_quality.iq_data_source_catalog
    alter column expires_at set not null,
    add constraint iq_data_source_catalog_retention_schedule
        check (retention_schedule_version = 'RS-1.0.0'),
    add constraint iq_data_source_catalog_retention_owner
        check (retention_owner = 'ingestion-quality'),
    add constraint iq_data_source_catalog_retention_end
        check (expires_at =
            ((created_at at time zone 'UTC') + interval '3 years') at time zone 'UTC');
alter table ingestion_quality.iq_catalog_evidence
    alter column expires_at set not null,
    add constraint iq_catalog_evidence_retention_schedule
        check (retention_schedule_version = 'RS-1.0.0'),
    add constraint iq_catalog_evidence_retention_owner
        check (retention_owner = 'ingestion-quality'),
    add constraint iq_catalog_evidence_retention_end
        check (expires_at =
            ((occurred_at at time zone 'UTC') + interval '3 years') at time zone 'UTC');
alter table ingestion_quality.iq_local_audit_fact
    alter column expires_at set not null,
    add constraint iq_local_audit_fact_retention_schedule
        check (retention_schedule_version = 'RS-1.0.0'),
    add constraint iq_local_audit_fact_retention_owner
        check (retention_owner = 'ingestion-quality'),
    add constraint iq_local_audit_fact_retention_end
        check (expires_at =
            ((occurred_at at time zone 'UTC') + interval '3 years') at time zone 'UTC');
alter table ingestion_quality.iq_catalog_idempotency
    add constraint iq_catalog_idempotency_retention_schedule
        check (retention_schedule_version = 'RS-1.0.0'),
    add constraint iq_catalog_idempotency_retention_end
        check (expires_at = created_at + interval '90 days');

create index iq_catalog_retention_idx
    on ingestion_quality.iq_data_source_catalog(legal_hold, expires_at, catalog_id);
create index iq_audit_retention_idx
    on ingestion_quality.iq_local_audit_fact(legal_hold, expires_at, audit_id);

alter table ingestion_quality.iq_source_id_reservation
    alter column first_catalog_id drop not null;
alter table ingestion_quality.iq_dependency_id_reservation
    alter column first_catalog_id drop not null;

alter table ingestion_quality.iq_catalog_idempotency
    alter column response drop not null;

revoke insert on ingestion_quality.iq_data_source_catalog,
    ingestion_quality.iq_catalog_evidence,
    ingestion_quality.iq_catalog_idempotency,
    ingestion_quality.iq_local_audit_fact
    from scholarsense_ingestion_quality_online;
revoke insert on ingestion_quality.iq_source_id_reservation,
    ingestion_quality.iq_dependency_id_reservation,
    ingestion_quality.iq_source_contract,
    ingestion_quality.iq_dependency_binding
    from scholarsense_ingestion_quality_online;
revoke insert on ingestion_quality.iq_catalog_validation_attempt,
    ingestion_quality.iq_catalog_current
    from scholarsense_ingestion_quality_online;
revoke select on ingestion_quality.iq_local_audit_fact
    from scholarsense_ingestion_quality_online;
grant insert (
    catalog_id, catalog_release_id, contract_version, status, aggregate_version,
    content_digest, evidence_set_digest, validation_errors, created_at, updated_at,
    published_at, expires_at)
    on ingestion_quality.iq_data_source_catalog
    to scholarsense_ingestion_quality_online;
grant insert (
    idempotency_key_digest, request_digest, catalog_id, response, created_at, expires_at)
    on ingestion_quality.iq_catalog_idempotency
    to scholarsense_ingestion_quality_online;
grant insert (
    audit_id, actor_search_token, action, result, catalog_id, aggregate_version,
    trace_id, occurred_at, authorization_context, expires_at)
    on ingestion_quality.iq_local_audit_fact
    to scholarsense_ingestion_quality_online;

grant update (request_digest, catalog_id, response, created_at, expires_at)
    on ingestion_quality.iq_catalog_idempotency
    to scholarsense_ingestion_quality_online;

revoke update on ingestion_quality.iq_data_source_catalog,
    ingestion_quality.iq_catalog_current
    from scholarsense_ingestion_quality_online;

create function ingestion_quality.iq_reject_published_catalog_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if old.status = 'published'
       and ((to_jsonb(new) - 'legal_hold')
              is distinct from (to_jsonb(old) - 'legal_hold')
            or new.legal_hold = old.legal_hold) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_PUBLISHED_CATALOG_IMMUTABLE';
    end if;
    if old.status <> 'published' and new.status = 'published' then
        if old.status <> 'publishable'
           or new.aggregate_version <> old.aggregate_version + 1
           or old.validation_errors <> '[]'::jsonb
           or new.validation_errors <> '[]'::jsonb
           or not exists (
             select 1
               from ingestion_quality.iq_catalog_validation_attempt validation_attempt
              where validation_attempt.catalog_id = old.catalog_id
                and validation_attempt.aggregate_version = old.aggregate_version
                and validation_attempt.result = 'publishable'
                and validation_attempt.errors = '[]'::jsonb)
           or (select count(*)
              from ingestion_quality.iq_source_contract source_contract
             where source_contract.catalog_id = new.catalog_id) <> 17
           or (select count(*)
                 from ingestion_quality.iq_dependency_binding binding
                where binding.catalog_id = new.catalog_id) <> 11
           or (select count(*)
                 from ingestion_quality.iq_catalog_evidence evidence
                where evidence.catalog_id = new.catalog_id) <> 17
           or exists (
             select 1
               from ingestion_quality.iq_source_contract source_contract
              where source_contract.catalog_id = new.catalog_id
                and (select count(*)
                       from ingestion_quality.iq_catalog_evidence evidence
                      where evidence.catalog_id = source_contract.catalog_id
                        and evidence.source_id = source_contract.source_id) <> 1)
           or exists (
             select 1
               from ingestion_quality.iq_catalog_evidence evidence
              where evidence.catalog_id = new.catalog_id
                and evidence.result <> 'pass') then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_PUBLISHED_CATALOG_INCOMPLETE';
        end if;
    end if;
    return new;
end
$$;

create trigger iq_data_source_catalog_published_immutable
before update on ingestion_quality.iq_data_source_catalog
for each row execute function ingestion_quality.iq_reject_published_catalog_update();

revoke all on function ingestion_quality.iq_reject_published_catalog_update()
    from public;

create function ingestion_quality.iq_reject_initially_published_catalog()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if new.status = 'published' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_INITIAL_PUBLISHED_CATALOG_REJECTED';
    end if;
    return new;
end
$$;

create trigger iq_data_source_catalog_initial_published_rejected
before insert on ingestion_quality.iq_data_source_catalog
for each row execute function ingestion_quality.iq_reject_initially_published_catalog();

revoke all on function ingestion_quality.iq_reject_initially_published_catalog()
    from public;

create function ingestion_quality.iq_guard_catalog_child_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    perform 1
      from ingestion_quality.iq_data_source_catalog catalog
     where catalog.catalog_id = new.catalog_id
     for update;
    if not found then
        raise exception using
            errcode = 'foreign_key_violation',
            message = 'INGESTION_QUALITY_CATALOG_PARENT_MISSING';
    end if;
    if exists (
        select 1
          from ingestion_quality.iq_data_source_catalog catalog
         where catalog.catalog_id = new.catalog_id
           and catalog.status = 'published') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_PUBLISHED_CATALOG_IMMUTABLE';
    end if;
    return new;
end
$$;

create trigger iq_source_contract_published_insert_rejected
before insert on ingestion_quality.iq_source_contract
for each row execute function ingestion_quality.iq_guard_catalog_child_insert();
create trigger iq_dependency_binding_published_insert_rejected
before insert on ingestion_quality.iq_dependency_binding
for each row execute function ingestion_quality.iq_guard_catalog_child_insert();
create trigger iq_catalog_evidence_published_insert_rejected
before insert on ingestion_quality.iq_catalog_evidence
for each row execute function ingestion_quality.iq_guard_catalog_child_insert();

revoke all on function ingestion_quality.iq_guard_catalog_child_insert()
    from public;

create function ingestion_quality.iq_add_catalog_source(
    requested_catalog_id uuid,
    requested_source_id varchar,
    requested_purpose varchar,
    requested_schema_version varchar,
    requested_quality_gate_version varchar,
    requested_evidence_uri varchar,
    requested_runtime_evidence_claim varchar,
    requested_descriptor jsonb)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    perform 1
      from ingestion_quality.iq_data_source_catalog catalog
     where catalog.catalog_id = requested_catalog_id
       and catalog.status = 'draft'
     for update;
    if not found then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_CATALOG_SOURCE_INSERT_INVALID';
    end if;

    insert into ingestion_quality.iq_source_id_reservation
      (source_id, first_catalog_id, first_purpose, reserved_at)
    select requested_source_id, requested_catalog_id, requested_purpose, catalog.created_at
      from ingestion_quality.iq_data_source_catalog catalog
     where catalog.catalog_id = requested_catalog_id
    on conflict (source_id) do nothing;

    if not exists (
        select 1
          from ingestion_quality.iq_source_id_reservation reservation
         where reservation.source_id = requested_source_id
           and reservation.first_purpose = requested_purpose) then
        return false;
    end if;

    insert into ingestion_quality.iq_source_contract
      (catalog_id, source_id, purpose, schema_version, quality_gate_version,
       evidence_uri, runtime_evidence_claim, descriptor)
    values (requested_catalog_id, requested_source_id, requested_purpose,
            requested_schema_version, requested_quality_gate_version,
            requested_evidence_uri, requested_runtime_evidence_claim,
            requested_descriptor);
    return true;
end
$$;

create function ingestion_quality.iq_add_catalog_dependency(
    requested_catalog_id uuid,
    requested_source_id varchar,
    requested_dependency_id varchar,
    requested_requirement varchar,
    requested_combination_operator varchar)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    perform 1
      from ingestion_quality.iq_data_source_catalog catalog
     where catalog.catalog_id = requested_catalog_id
       and catalog.status = 'draft'
     for update;
    if not found then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_CATALOG_DEPENDENCY_INSERT_INVALID';
    end if;

    insert into ingestion_quality.iq_dependency_id_reservation
      (dependency_id, source_id, first_catalog_id, reserved_at)
    select requested_dependency_id, requested_source_id, requested_catalog_id,
           catalog.created_at
      from ingestion_quality.iq_data_source_catalog catalog
     where catalog.catalog_id = requested_catalog_id
    on conflict (dependency_id) do nothing;

    if not exists (
        select 1
          from ingestion_quality.iq_dependency_id_reservation reservation
         where reservation.dependency_id = requested_dependency_id
           and reservation.source_id = requested_source_id) then
        return false;
    end if;

    insert into ingestion_quality.iq_dependency_binding
      (catalog_id, source_id, dependency_id, requirement, combination_operator)
    values (requested_catalog_id, requested_source_id, requested_dependency_id,
            requested_requirement, requested_combination_operator);
    return true;
end
$$;

revoke all on function ingestion_quality.iq_add_catalog_source(
    uuid, varchar, varchar, varchar, varchar, varchar, varchar, jsonb)
    from public;
grant execute on function ingestion_quality.iq_add_catalog_source(
    uuid, varchar, varchar, varchar, varchar, varchar, varchar, jsonb)
    to scholarsense_ingestion_quality_online;
revoke all on function ingestion_quality.iq_add_catalog_dependency(
    uuid, varchar, varchar, varchar, varchar)
    from public;
grant execute on function ingestion_quality.iq_add_catalog_dependency(
    uuid, varchar, varchar, varchar, varchar)
    to scholarsense_ingestion_quality_online;

create function ingestion_quality.iq_require_valid_current_catalog()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    perform 1
      from ingestion_quality.iq_data_source_catalog catalog
     where catalog.catalog_id = new.catalog_id
     for update;
    if not found then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_CURRENT_POINTER_INVALID';
    end if;
    if not exists (
           select 1
             from ingestion_quality.iq_data_source_catalog catalog
            where catalog.catalog_id = new.catalog_id
              and catalog.status = 'published'
              and catalog.aggregate_version = new.aggregate_version
              and catalog.published_at = new.switched_at)
       or (tg_op = 'INSERT' and new.pointer_version <> 1)
       or (tg_op = 'UPDATE'
           and (new.pointer_version <> old.pointer_version + 1
                or new.catalog_id = old.catalog_id
                or new.switched_at <= old.switched_at)) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_CURRENT_POINTER_INVALID';
    end if;
    return new;
end
$$;

create trigger iq_catalog_current_integrity
before insert or update on ingestion_quality.iq_catalog_current
for each row execute function ingestion_quality.iq_require_valid_current_catalog();

revoke all on function ingestion_quality.iq_require_valid_current_catalog()
    from public;

create function ingestion_quality.iq_record_catalog_validation(
    requested_catalog_id uuid,
    requested_expected_version bigint,
    requested_status varchar,
    requested_new_version bigint,
    requested_errors jsonb,
    requested_validated_at timestamptz,
    requested_attempt_id uuid,
    requested_trace_id varchar)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    if requested_new_version <> requested_expected_version + 1
       or requested_status not in ('invalid', 'publishable')
       or jsonb_typeof(requested_errors) <> 'array'
       or (requested_status = 'publishable'
           and jsonb_array_length(requested_errors) <> 0)
       or (requested_status = 'invalid'
           and jsonb_array_length(requested_errors) = 0)
       or requested_trace_id !~ '^[0-9a-f]{32}$' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_CATALOG_VALIDATION_TRANSITION_INVALID';
    end if;

    perform 1
      from ingestion_quality.iq_data_source_catalog catalog
     where catalog.catalog_id = requested_catalog_id
       and catalog.aggregate_version = requested_expected_version
       and catalog.status <> 'published'
     for update;
    if not found then
        return false;
    end if;

    update ingestion_quality.iq_data_source_catalog
       set status = requested_status,
           aggregate_version = requested_new_version,
           validation_errors = requested_errors,
           updated_at = requested_validated_at
     where catalog_id = requested_catalog_id
       and aggregate_version = requested_expected_version
       and status <> 'published';
    if not found then
        return false;
    end if;

    insert into ingestion_quality.iq_catalog_validation_attempt
      (attempt_id, catalog_id, aggregate_version, result, errors, validated_at, trace_id)
    values (requested_attempt_id, requested_catalog_id, requested_new_version,
            requested_status, requested_errors, requested_validated_at,
            requested_trace_id);
    return true;
end
$$;

create function ingestion_quality.iq_publish_catalog(
    requested_catalog_id uuid,
    requested_expected_version bigint,
    requested_new_version bigint,
    requested_catalog_release_id uuid,
    requested_evidence_set_digest varchar,
    requested_published_at timestamptz,
    requested_expected_current_version bigint,
    requested_evidence jsonb)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    if requested_new_version <> requested_expected_version + 1
       or requested_evidence_set_digest !~ '^sha256:[0-9a-f]{64}$'
       or jsonb_typeof(requested_evidence) <> 'array'
       or jsonb_array_length(requested_evidence) <> 17 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_CATALOG_PUBLICATION_TRANSITION_INVALID';
    end if;

    perform 1
      from ingestion_quality.iq_data_source_catalog catalog
     where catalog.catalog_id = requested_catalog_id
       and catalog.aggregate_version = requested_expected_version
       and catalog.status = 'publishable'
     for update;
    if not found then
        return false;
    end if;

    begin
        insert into ingestion_quality.iq_catalog_evidence
          (evidence_id, catalog_id, source_id, evidence_uri, evidence_digest, environment,
           authority, candidate_commit, candidate_tree, handoff_revision, handoff_digest,
           result, occurred_at, contract_version, schema_version, quality_gate_version,
           input_digest, scenarios, signature_digest, cleanup_result,
           runtime_evidence_claim, expires_at)
        select evidence_id, requested_catalog_id, source_id, evidence_uri, evidence_digest,
               environment, authority, candidate_commit, candidate_tree, handoff_revision,
               handoff_digest, result, occurred_at, contract_version, schema_version,
               quality_gate_version, input_digest, scenarios, signature_digest,
               cleanup_result, runtime_evidence_claim, expires_at
          from pg_catalog.jsonb_to_recordset(requested_evidence) as evidence_rows(
               evidence_id uuid,
               source_id varchar,
               evidence_uri varchar,
               evidence_digest varchar,
               environment varchar,
               authority varchar,
               candidate_commit varchar,
               candidate_tree varchar,
               handoff_revision bigint,
               handoff_digest varchar,
               result varchar,
               occurred_at timestamptz,
               contract_version varchar,
               schema_version varchar,
               quality_gate_version varchar,
               input_digest varchar,
               scenarios jsonb,
               signature_digest varchar,
               cleanup_result varchar,
               runtime_evidence_claim varchar,
               expires_at timestamptz);

        update ingestion_quality.iq_data_source_catalog
           set catalog_release_id = requested_catalog_release_id,
               status = 'published',
               aggregate_version = requested_new_version,
               evidence_set_digest = requested_evidence_set_digest,
               validation_errors = '[]'::jsonb,
               updated_at = requested_published_at,
               published_at = requested_published_at
         where catalog_id = requested_catalog_id
           and aggregate_version = requested_expected_version
           and status = 'publishable';
        if not found then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_CATALOG_PUBLICATION_TRANSITION_INVALID';
        end if;

        if requested_expected_current_version = 0 then
            insert into ingestion_quality.iq_catalog_current
              (singleton, catalog_id, aggregate_version, pointer_version, switched_at)
            values (true, requested_catalog_id, requested_new_version, 1,
                    requested_published_at)
            on conflict (singleton) do nothing;
        else
            update ingestion_quality.iq_catalog_current
               set catalog_id = requested_catalog_id,
                   aggregate_version = requested_new_version,
                   pointer_version = pointer_version + 1,
                   switched_at = requested_published_at
             where singleton = true
               and pointer_version = requested_expected_current_version
               and pointer_version < 9007199254740991;
        end if;
        if not found then
            raise exception using
                errcode = 'P1001',
                message = 'INGESTION_QUALITY_CURRENT_POINTER_CONFLICT';
        end if;
    exception when sqlstate 'P1001' then
        return false;
    end;
    return true;
end
$$;

revoke all on function ingestion_quality.iq_record_catalog_validation(
    uuid, bigint, varchar, bigint, jsonb, timestamptz, uuid, varchar)
    from public;
grant execute on function ingestion_quality.iq_record_catalog_validation(
    uuid, bigint, varchar, bigint, jsonb, timestamptz, uuid, varchar)
    to scholarsense_ingestion_quality_online;
revoke all on function ingestion_quality.iq_publish_catalog(
    uuid, bigint, bigint, uuid, varchar, timestamptz, bigint, jsonb)
    from public;
grant execute on function ingestion_quality.iq_publish_catalog(
    uuid, bigint, bigint, uuid, varchar, timestamptz, bigint, jsonb)
    to scholarsense_ingestion_quality_online;

create function ingestion_quality.iq_cleanup_expired(trusted_cutoff timestamptz)
returns bigint
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    expired_catalogs uuid[] := array[]::uuid[];
    deleted_idempotency bigint := 0;
    deleted_outbox bigint := 0;
    deleted_audit bigint := 0;
    deleted_evidence bigint := 0;
    deleted_catalogs bigint := 0;
begin
    if trusted_cutoff is null
       or trusted_cutoff < clock_timestamp() - interval '5 minutes'
       or trusted_cutoff > clock_timestamp() + interval '5 minutes' then
        raise exception using
            errcode = 'invalid_parameter_value',
            message = 'INGESTION_QUALITY_RETENTION_TRUSTED_CUTOFF_INVALID';
    end if;

    delete from ingestion_quality.iq_catalog_idempotency idempotency
     where idempotency.expires_at <= trusted_cutoff
       and not exists (
         select 1
           from ingestion_quality.iq_data_source_catalog held_catalog
          where held_catalog.catalog_id = idempotency.catalog_id
            and held_catalog.legal_hold);
    get diagnostics deleted_idempotency = row_count;

    delete from ingestion_quality.iq_local_audit_outbox outbox
     where exists (
       select 1
         from ingestion_quality.iq_local_audit_fact fact
        where fact.audit_id = outbox.audit_id
          and not fact.legal_hold
          and fact.expires_at <= trusted_cutoff
          and not exists (
            select 1
              from ingestion_quality.iq_data_source_catalog held_catalog
             where held_catalog.catalog_id = fact.catalog_id
               and held_catalog.legal_hold));
    get diagnostics deleted_outbox = row_count;

    delete from ingestion_quality.iq_local_audit_fact fact
     where not fact.legal_hold
       and fact.expires_at <= trusted_cutoff
       and not exists (
         select 1
           from ingestion_quality.iq_data_source_catalog held_catalog
          where held_catalog.catalog_id = fact.catalog_id
            and held_catalog.legal_hold);
    get diagnostics deleted_audit = row_count;

    delete from ingestion_quality.iq_catalog_evidence evidence
     where not evidence.legal_hold
       and evidence.expires_at <= trusted_cutoff
       and not exists (
         select 1
           from ingestion_quality.iq_data_source_catalog held_catalog
          where held_catalog.catalog_id = evidence.catalog_id
            and held_catalog.legal_hold);
    get diagnostics deleted_evidence = row_count;

    expired_catalogs := (
      select coalesce(array_agg(catalog.catalog_id), array[]::uuid[])
        from ingestion_quality.iq_data_source_catalog catalog
       where not catalog.legal_hold
         and catalog.expires_at <= trusted_cutoff
         and not exists (
           select 1
             from ingestion_quality.iq_catalog_current current_pointer
            where current_pointer.catalog_id = catalog.catalog_id)
         and not exists (
           select 1
             from ingestion_quality.iq_catalog_evidence evidence
            where evidence.catalog_id = catalog.catalog_id)
         and not exists (
           select 1
             from ingestion_quality.iq_local_audit_fact fact
            where fact.catalog_id = catalog.catalog_id)
         and not exists (
           select 1
             from ingestion_quality.iq_catalog_idempotency idempotency
            where idempotency.catalog_id = catalog.catalog_id));

    delete from ingestion_quality.iq_catalog_validation_attempt validation
     where validation.catalog_id = any(expired_catalogs);
    delete from ingestion_quality.iq_dependency_binding binding
     where binding.catalog_id = any(expired_catalogs);
    delete from ingestion_quality.iq_source_contract source_contract
     where source_contract.catalog_id = any(expired_catalogs);

    update ingestion_quality.iq_dependency_id_reservation reservation
       set first_catalog_id = (
         select binding.catalog_id
           from ingestion_quality.iq_dependency_binding binding
           join ingestion_quality.iq_data_source_catalog catalog
             on catalog.catalog_id = binding.catalog_id
          where binding.dependency_id = reservation.dependency_id
          order by catalog.created_at, catalog.catalog_id
          limit 1)
     where reservation.first_catalog_id = any(expired_catalogs)
    ;

    update ingestion_quality.iq_source_id_reservation reservation
       set first_catalog_id = (
         select source_contract.catalog_id
           from ingestion_quality.iq_source_contract source_contract
           join ingestion_quality.iq_data_source_catalog catalog
             on catalog.catalog_id = source_contract.catalog_id
          where source_contract.source_id = reservation.source_id
          order by catalog.created_at, catalog.catalog_id
          limit 1)
     where reservation.first_catalog_id = any(expired_catalogs)
    ;

    delete from ingestion_quality.iq_data_source_catalog catalog
     where catalog.catalog_id = any(expired_catalogs);
    get diagnostics deleted_catalogs = row_count;

    return deleted_idempotency + deleted_outbox + deleted_audit
        + deleted_evidence + deleted_catalogs;
end
$$;

revoke all on function ingestion_quality.iq_cleanup_expired(timestamptz)
    from public;
grant execute on function ingestion_quality.iq_cleanup_expired(timestamptz)
    to scholarsense_ingestion_quality_relay;

alter role scholarsense_ingestion_quality_online
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
alter role scholarsense_ingestion_quality_relay
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;

alter table ingestion_quality.iq_local_audit_outbox
    add constraint iq_local_audit_outbox_payload_digest_lower_hex
    check (payload_digest ~ '^[0-9a-f]{64}$');

revoke update on ingestion_quality.iq_local_audit_outbox
    from scholarsense_ingestion_quality_relay;
grant update (
    status, attempts, available_at, claimed_until, delivered_at, last_error_code)
    on ingestion_quality.iq_local_audit_outbox
    to scholarsense_ingestion_quality_relay;
