do $migration$
begin
    if not exists (select 1 from pg_catalog.pg_roles
                    where rolname='scholarsense_identity_high_risk_online') then
        create role scholarsense_identity_high_risk_online nologin;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles
                    where rolname='scholarsense_identity_high_risk_retention') then
        create role scholarsense_identity_high_risk_retention nologin;
    end if;
end
$migration$;

alter role scholarsense_identity_high_risk_online set search_path=pg_catalog;
alter role scholarsense_identity_high_risk_retention set search_path=pg_catalog;
grant scholarsense_identity_high_risk_online to scholarsense_identity_online;
grant usage on schema identity_access
    to scholarsense_identity_high_risk_online,scholarsense_identity_high_risk_retention;
revoke create on schema identity_access
    from scholarsense_identity_high_risk_online,scholarsense_identity_high_risk_retention;

create table identity_access.ia_high_risk_approval_history (
    approval_id uuid not null,
    approval_version bigint not null check (approval_version between 1 and 9007199254740991),
    status varchar(16) not null check (status in (
        'pending','approved','rejected','expired','cancelled')),
    aggregate jsonb not null,
    recorded_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (approval_id,approval_version),
    constraint ia_high_risk_approval_history_uuid_v7_ck check (
        substring(approval_id::text,15,1)='7'
        and substring(approval_id::text,20,1) ~ '^[89ab]$'),
    constraint ia_high_risk_approval_history_shape_ck check (
        jsonb_typeof(aggregate)='object'
        and aggregate->>'approvalId'=approval_id::text
        and (aggregate->>'approvalVersion')::bigint=approval_version
        and aggregate->>'status'=status
        and expires_at>recorded_at)
);

create table identity_access.ia_high_risk_approval_current (
    approval_id uuid primary key,
    approval_version bigint not null check (approval_version between 1 and 9007199254740991),
    idempotency_key_digest char(71) not null unique,
    request_id uuid not null unique,
    request_digest char(71) not null,
    action_type varchar(64) not null,
    maker_principal_digest char(71) not null,
    object_type varchar(64) not null,
    object_ref_digest char(71) not null,
    object_version bigint not null check (object_version between 1 and 9007199254740991),
    authorization_generation bigint not null check (
        authorization_generation between 0 and 9007199254740991),
    status varchar(16) not null check (status in (
        'pending','approved','rejected','expired','cancelled')),
    requested_at timestamptz not null,
    expires_at timestamptz not null,
    aggregate jsonb not null,
    updated_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint ia_high_risk_approval_current_uuid_v7_ck check (
        substring(approval_id::text,15,1)='7'
        and substring(approval_id::text,20,1) ~ '^[89ab]$'
        and substring(request_id::text,15,1)='7'
        and substring(request_id::text,20,1) ~ '^[89ab]$'),
    constraint ia_high_risk_approval_current_shape_ck check (
        idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'
        and request_digest ~ '^sha256:[0-9a-f]{64}$'
        and maker_principal_digest ~ '^sha256:[0-9a-f]{64}$'
        and object_ref_digest ~ '^sha256:[0-9a-f]{64}$'
        and action_type='quality-fuse.recover'
        and object_type='RECOVERY_TASK'
        and expires_at=requested_at+interval '4 hours'
        and jsonb_typeof(aggregate)='object')
);

create unique index ia_high_risk_approval_active_object_uk
    on identity_access.ia_high_risk_approval_current(
        action_type,object_ref_digest,object_version,maker_principal_digest)
    where status in ('pending','approved');
create index ia_high_risk_approval_expiry_idx
    on identity_access.ia_high_risk_approval_current(status,expires_at)
    where not legal_hold;

create table identity_access.ia_high_risk_approval_receipt (
    approval_id uuid not null,
    approval_version bigint not null,
    receipt_digest char(71) not null unique,
    status varchar(16) not null check (status in (
        'approved','rejected','expired','cancelled')),
    receipt jsonb not null,
    decided_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (approval_id,approval_version),
    foreign key (approval_id,approval_version)
        references identity_access.ia_high_risk_approval_history(
            approval_id,approval_version),
    constraint ia_high_risk_approval_receipt_shape_ck check (
        receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        and jsonb_typeof(receipt)='object'
        and receipt->>'approvalId'=approval_id::text
        and (receipt->>'approvalVersion')::bigint=approval_version
        and receipt->>'receiptDigest'=trim(receipt_digest))
);

create table identity_access.ia_high_risk_approval_decision_idempotency (
    idempotency_key_digest char(71) primary key,
    input_digest char(71) not null,
    approval_id uuid not null,
    approval_version bigint not null,
    receipt_digest char(71),
    response jsonb not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    foreign key (approval_id,approval_version)
        references identity_access.ia_high_risk_approval_history(
            approval_id,approval_version),
    constraint ia_high_risk_approval_decision_idempotency_shape_ck check (
        idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'
        and input_digest ~ '^sha256:[0-9a-f]{64}$'
        and (receipt_digest is null or receipt_digest ~ '^sha256:[0-9a-f]{64}$')
        and jsonb_typeof(response)='object'
        and response->>'approvalId'=approval_id::text
        and (response->>'approvalVersion')::bigint=approval_version
        and expires_at=created_at+interval '90 days')
);

create table identity_access.ia_high_risk_execution_lease_history (
    lease_id uuid not null,
    lease_version bigint not null check (lease_version between 1 and 9007199254740991),
    state varchar(16) not null check (state in (
        'issued','reserved','executed','expired','cancelled')),
    aggregate jsonb not null,
    recorded_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (lease_id,lease_version),
    constraint ia_high_risk_lease_history_uuid_v7_ck check (
        substring(lease_id::text,15,1)='7'
        and substring(lease_id::text,20,1) ~ '^[89ab]$'),
    constraint ia_high_risk_lease_history_shape_ck check (
        jsonb_typeof(aggregate)='object'
        and aggregate->>'leaseId'=lease_id::text
        and (aggregate->>'leaseVersion')::bigint=lease_version
        and aggregate->>'state'=state)
);

create table identity_access.ia_high_risk_execution_lease_current (
    lease_id uuid primary key,
    lease_version bigint not null check (lease_version between 1 and 9007199254740991),
    lease_digest char(71) not null unique,
    approval_id uuid not null unique,
    idempotency_key_digest char(71) not null unique,
    issuance_request_digest char(71) not null,
    execution_jti uuid not null unique,
    token_jti uuid not null unique,
    state varchar(16) not null check (state in (
        'issued','reserved','executed','expired','cancelled')),
    issued_at timestamptz not null,
    authorized_until timestamptz not null,
    owner_commit_id varchar(128),
    outbox_event_id uuid unique,
    aggregate jsonb not null,
    updated_at timestamptz not null,
    legal_hold boolean not null default false,
    foreign key (approval_id)
        references identity_access.ia_high_risk_approval_current(approval_id),
    constraint ia_high_risk_lease_current_uuid_v7_ck check (
        substring(lease_id::text,15,1)='7'
        and substring(lease_id::text,20,1) ~ '^[89ab]$'
        and substring(execution_jti::text,15,1)='7'
        and substring(execution_jti::text,20,1) ~ '^[89ab]$'
        and substring(token_jti::text,15,1)='7'
        and substring(token_jti::text,20,1) ~ '^[89ab]$'),
    constraint ia_high_risk_lease_current_shape_ck check (
        lease_digest ~ '^sha256:[0-9a-f]{64}$'
        and idempotency_key_digest ~ '^sha256:[0-9a-f]{64}$'
        and issuance_request_digest ~ '^sha256:[0-9a-f]{64}$'
        and authorized_until=issued_at+interval '15 minutes'
        and jsonb_typeof(aggregate)='object'
        and ((state='executed' and owner_commit_id is not null
              and outbox_event_id is not null)
             or (state<>'executed' and owner_commit_id is null
              and outbox_event_id is null)))
);

create index ia_high_risk_execution_expiry_idx
    on identity_access.ia_high_risk_execution_lease_current(state,authorized_until)
    where not legal_hold;

create table identity_access.ia_account_natural_person_history (
    account_id uuid not null,
    binding_version bigint not null check (binding_version between 1 and 9007199254740991),
    natural_person_principal_digest char(71) not null,
    authority_evidence_digest char(71) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    recorded_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (account_id,binding_version),
    foreign key (account_id)
        references identity_access.ia_authoritative_account_current(account_id),
    constraint ia_account_natural_person_history_shape_ck check (
        natural_person_principal_digest ~ '^sha256:[0-9a-f]{64}$'
        and authority_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        and (effective_to is null or effective_to>effective_from))
);

create table identity_access.ia_account_natural_person_current (
    account_id uuid primary key,
    binding_version bigint not null check (binding_version between 1 and 9007199254740991),
    natural_person_principal_digest char(71) not null,
    authority_evidence_digest char(71) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    legal_hold boolean not null default false,
    foreign key (account_id)
        references identity_access.ia_authoritative_account_current(account_id),
    constraint ia_account_natural_person_current_shape_ck check (
        natural_person_principal_digest ~ '^sha256:[0-9a-f]{64}$'
        and authority_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        and (effective_to is null or effective_to>effective_from))
);

create index ia_account_natural_person_principal_idx
    on identity_access.ia_account_natural_person_current(natural_person_principal_digest);

create table identity_access.ia_business_owner_natural_person_history (
    business_owner_key_digest char(71) not null,
    binding_version bigint not null check (binding_version between 1 and 9007199254740991),
    account_id uuid not null,
    natural_person_principal_digest char(71) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    authorization_generation bigint not null check (
        authorization_generation between 0 and 9007199254740991),
    recorded_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (business_owner_key_digest,binding_version),
    foreign key (account_id)
        references identity_access.ia_authoritative_account_current(account_id),
    constraint ia_business_owner_person_history_shape_ck check (
        business_owner_key_digest ~ '^sha256:[0-9a-f]{64}$'
        and natural_person_principal_digest ~ '^sha256:[0-9a-f]{64}$'
        and (effective_to is null or effective_to>effective_from))
);

create table identity_access.ia_business_owner_natural_person_current (
    business_owner_key_digest char(71) primary key,
    binding_version bigint not null check (binding_version between 1 and 9007199254740991),
    account_id uuid not null unique,
    natural_person_principal_digest char(71) not null unique,
    effective_from timestamptz not null,
    effective_to timestamptz,
    authorization_generation bigint not null check (
        authorization_generation between 0 and 9007199254740991),
    legal_hold boolean not null default false,
    foreign key (account_id)
        references identity_access.ia_authoritative_account_current(account_id),
    constraint ia_business_owner_person_current_shape_ck check (
        business_owner_key_digest ~ '^sha256:[0-9a-f]{64}$'
        and natural_person_principal_digest ~ '^sha256:[0-9a-f]{64}$'
        and (effective_to is null or effective_to>effective_from))
);

create table identity_access.ia_high_risk_audit (
    audit_id bigint generated always as identity primary key,
    aggregate_type varchar(32) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null,
    action varchar(64) not null,
    outcome varchar(32) not null,
    occurred_at timestamptz not null,
    trace_id char(32) not null,
    evidence_digest char(71) not null,
    legal_hold boolean not null default false,
    constraint ia_high_risk_audit_shape_ck check (
        aggregate_type in ('approval','execution-lease','account-person-binding',
            'business-owner-binding')
        and aggregate_version between 1 and 9007199254740991
        and trace_id ~ '^(?!0{32}$)[0-9a-f]{32}$'
        and evidence_digest ~ '^sha256:[0-9a-f]{64}$')
);

create function identity_access.ia_require_high_risk_workload(requested_role name)
returns void language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    if requested_role not in ('scholarsense_identity_high_risk_online',
            'scholarsense_identity_high_risk_retention')
       or not pg_catalog.pg_has_role(session_user,requested_role,'USAGE') then
        raise exception using errcode='insufficient_privilege',
            message='IDENTITY_HIGH_RISK_WORKLOAD_FORBIDDEN';
    end if;
end
$$;

create function identity_access.ia_find_high_risk_approval_by_id(requested_id uuid)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare ia_value jsonb;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select aggregate into ia_value
      from identity_access.ia_high_risk_approval_current
     where approval_id=requested_id;
    return ia_value;
end
$$;

create function identity_access.ia_find_high_risk_approval_by_key(requested_digest character)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare ia_value jsonb;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select aggregate into ia_value
      from identity_access.ia_high_risk_approval_current
     where idempotency_key_digest=requested_digest;
    return ia_value;
end
$$;

create function identity_access.ia_insert_high_risk_approval(
    requested_idempotency_digest character,requested_aggregate jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare ia_id uuid:=(requested_aggregate->>'approvalId')::uuid;
declare ia_now timestamptz:=statement_timestamp();
declare ia_value jsonb;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    if requested_idempotency_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_aggregate->>'status'<>'pending'
       or (requested_aggregate->>'approvalVersion')::bigint<>1
       or requested_aggregate->>'actionType'<>'quality-fuse.recover'
       or requested_aggregate->>'objectType'<>'RECOVERY_TASK' then
        raise exception using errcode='check_violation',
            message='IDENTITY_HIGH_RISK_APPROVAL_INVALID';
    end if;
    -- Serialize every request for the same protected object before touching an
    -- idempotency row.  A concurrent request with a different key must replay
    -- the already-created winner instead of returning NULL or leaking a unique
    -- constraint race.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'high-risk-approval-object:'||(requested_aggregate->>'actionType')||':'||
        (requested_aggregate->>'objectRefDigest')||':'||
        (requested_aggregate->>'objectVersion')||':'||
        (requested_aggregate->>'makerPrincipalDigest'),0));
    insert into identity_access.ia_high_risk_approval_current values (
        ia_id,1,requested_idempotency_digest,
        (requested_aggregate->>'requestId')::uuid,requested_aggregate->>'requestDigest',
        requested_aggregate->>'actionType',requested_aggregate->>'makerPrincipalDigest',
        requested_aggregate->>'objectType',requested_aggregate->>'objectRefDigest',
        (requested_aggregate->>'objectVersion')::bigint,
        (requested_aggregate->>'authorizationGeneration')::bigint,'pending',
        (requested_aggregate->>'requestedAt')::timestamptz,
        (requested_aggregate->>'expiresAt')::timestamptz,requested_aggregate,ia_now,false)
    on conflict do nothing;
    if found then
        insert into identity_access.ia_high_risk_approval_history values (
            ia_id,1,'pending',requested_aggregate,ia_now,
            ia_now+interval '2 years',false);
        insert into identity_access.ia_high_risk_audit(
            aggregate_type,aggregate_id,aggregate_version,action,outcome,
            occurred_at,trace_id,evidence_digest)
        values ('approval',ia_id,1,'quality-fuse.recover.request','pending',ia_now,
            requested_aggregate->>'traceId',requested_aggregate->>'requestDigest');
    end if;
    select aggregate into ia_value
      from identity_access.ia_high_risk_approval_current
     where idempotency_key_digest=requested_idempotency_digest
        or (action_type=requested_aggregate->>'actionType'
        and trim(object_ref_digest)=requested_aggregate->>'objectRefDigest'
        and object_version=(requested_aggregate->>'objectVersion')::bigint
        and trim(maker_principal_digest)=requested_aggregate->>'makerPrincipalDigest'
        and status in ('pending','approved'))
     order by (idempotency_key_digest=requested_idempotency_digest) desc,
              requested_at,approval_id
     limit 1;
    if ia_value is null then
        raise exception using errcode='serialization_failure',
            message='IDENTITY_HIGH_RISK_APPROVAL_WINNER_UNAVAILABLE';
    end if;
    return ia_value;
end
$$;

create function identity_access.ia_save_high_risk_approval(
    expected_version bigint,requested_aggregate jsonb,requested_receipt jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare ia_id uuid:=(requested_aggregate->>'approvalId')::uuid;
declare ia_current identity_access.ia_high_risk_approval_current%rowtype;
declare ia_now timestamptz:=statement_timestamp();
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select current_fact.* into ia_current
      from identity_access.ia_high_risk_approval_current current_fact
     where current_fact.approval_id=ia_id for update;
    if ia_current.approval_id is null or ia_current.approval_version<>expected_version
       or (requested_aggregate->>'approvalVersion')::bigint<>expected_version+1
       or requested_aggregate->>'requestDigest'<>trim(ia_current.request_digest)
       or requested_aggregate->>'actionType'<>ia_current.action_type
       or requested_aggregate->>'objectRefDigest'<>trim(ia_current.object_ref_digest)
       or (requested_aggregate->>'objectVersion')::bigint<>ia_current.object_version
       or requested_aggregate->>'status' not in (
            'pending','approved','rejected','expired','cancelled') then
        raise exception using errcode='serialization_failure',
            message='IDENTITY_HIGH_RISK_APPROVAL_VERSION_CONFLICT';
    end if;
    insert into identity_access.ia_high_risk_approval_history values (
        ia_id,expected_version+1,requested_aggregate->>'status',requested_aggregate,
        ia_now,ia_now+interval '2 years',ia_current.legal_hold);
    update identity_access.ia_high_risk_approval_current set
        approval_version=expected_version+1,status=requested_aggregate->>'status',
        aggregate=requested_aggregate,updated_at=ia_now
     where approval_id=ia_id;
    if requested_receipt is not null then
        if requested_receipt->>'approvalId'<>ia_id::text
           or (requested_receipt->>'approvalVersion')::bigint<>expected_version+1
           or requested_receipt->>'status'<>requested_aggregate->>'status' then
            raise exception using errcode='check_violation',
                message='IDENTITY_HIGH_RISK_RECEIPT_INVALID';
        end if;
        insert into identity_access.ia_high_risk_approval_receipt values (
            ia_id,expected_version+1,requested_receipt->>'receiptDigest',
            requested_receipt->>'status',requested_receipt,
            (requested_receipt->>'decidedAt')::timestamptz,
            ia_now+interval '2 years',ia_current.legal_hold);
    end if;
    insert into identity_access.ia_high_risk_audit(
        aggregate_type,aggregate_id,aggregate_version,action,outcome,
        occurred_at,trace_id,evidence_digest)
    values ('approval',ia_id,expected_version+1,'quality-fuse.recover.decision',
        requested_aggregate->>'status',ia_now,requested_aggregate->>'traceId',
        requested_aggregate->>'requestDigest');
    return requested_aggregate;
end
$$;

create function identity_access.ia_find_high_risk_approval_receipt(requested_id uuid)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare ia_value jsonb;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select receipt into ia_value
      from identity_access.ia_high_risk_approval_receipt
     where approval_id=requested_id order by approval_version desc limit 1;
    return ia_value;
end
$$;

create function identity_access.ia_find_high_risk_approval_decision_by_key(
    requested_digest character)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare ia_value jsonb;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select jsonb_build_object(
        'inputDigest',trim(decision.input_digest),
        'approval',decision.response,
        'receiptDigest',trim(decision.receipt_digest))
      into ia_value
      from identity_access.ia_high_risk_approval_decision_idempotency decision
     where decision.idempotency_key_digest=requested_digest;
    return ia_value;
end
$$;

create function identity_access.ia_save_high_risk_approval_decision(
    requested_idempotency_digest character,requested_input_digest character,
    expected_version bigint,requested_aggregate jsonb,requested_receipt jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare ia_existing identity_access.ia_high_risk_approval_decision_idempotency%rowtype;
declare ia_response jsonb;
declare ia_now timestamptz:=statement_timestamp();
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    if requested_idempotency_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_input_digest !~ '^sha256:[0-9a-f]{64}$' then
        raise exception using errcode='check_violation',
            message='IDENTITY_HIGH_RISK_APPROVAL_DECISION_INVALID';
    end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        'high-risk-approval-decision:'||requested_idempotency_digest,0));
    select decision.* into ia_existing
      from identity_access.ia_high_risk_approval_decision_idempotency decision
     where decision.idempotency_key_digest=requested_idempotency_digest;
    if ia_existing.idempotency_key_digest is not null then
        if trim(ia_existing.input_digest)<>trim(requested_input_digest) then
            raise exception using errcode='unique_violation',
                message='IDENTITY_HIGH_RISK_APPROVAL_IDEMPOTENCY_CONFLICT';
        end if;
        return jsonb_build_object(
            'inputDigest',trim(ia_existing.input_digest),
            'approval',ia_existing.response,
            'receiptDigest',trim(ia_existing.receipt_digest));
    end if;
    ia_response:=identity_access.ia_save_high_risk_approval(
        expected_version,requested_aggregate,requested_receipt);
    insert into identity_access.ia_high_risk_approval_decision_idempotency values (
        requested_idempotency_digest,requested_input_digest,
        (ia_response->>'approvalId')::uuid,
        (ia_response->>'approvalVersion')::bigint,
        requested_receipt->>'receiptDigest',ia_response,
        ia_now,ia_now+interval '90 days',false);
    return jsonb_build_object(
        'inputDigest',requested_input_digest,
        'approval',ia_response,
        'receiptDigest',requested_receipt->>'receiptDigest');
end
$$;

create function identity_access.ia_find_expirable_high_risk_approvals(
    requested_limit integer,requested_now timestamptz)
returns setof jsonb language plpgsql security definer set search_path=pg_catalog as $$
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    if requested_limit not between 1 and 100 then
        raise exception using errcode='invalid_parameter_value',
            message='IDENTITY_HIGH_RISK_EXPIRY_LIMIT_INVALID';
    end if;
    return query select approval.aggregate
      from identity_access.ia_high_risk_approval_current approval
     where approval.status in ('pending','approved')
       and approval.expires_at<=requested_now
     order by approval.expires_at,approval.approval_id
     for update skip locked limit requested_limit;
end
$$;

create function identity_access.ia_find_high_risk_execution_lease_by_id(requested_id uuid)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare ia_value jsonb;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select aggregate into ia_value
      from identity_access.ia_high_risk_execution_lease_current where lease_id=requested_id;
    return ia_value;
end
$$;

create function identity_access.ia_find_high_risk_execution_lease_by_approval(requested_id uuid)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare ia_value jsonb;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select aggregate into ia_value
      from identity_access.ia_high_risk_execution_lease_current where approval_id=requested_id;
    return ia_value;
end
$$;

create function identity_access.ia_find_expirable_high_risk_execution_leases(
    requested_limit integer,requested_now timestamptz)
returns setof jsonb language plpgsql security definer set search_path=pg_catalog as $$
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    if requested_limit not between 1 and 100 then
        raise exception using errcode='invalid_parameter_value',
            message='IDENTITY_HIGH_RISK_EXECUTION_EXPIRY_LIMIT_INVALID';
    end if;
    return query select lease.aggregate
      from identity_access.ia_high_risk_execution_lease_current lease
     where lease.state in ('issued','reserved')
       and lease.authorized_until<=requested_now
     order by lease.authorized_until,lease.lease_id
     for update skip locked limit requested_limit;
end
$$;

-- Terminal leases remain preserved in immutable history; removing the current
-- projection releases the approval/idempotency uniqueness fences so a later
-- command must obtain a newly signed token, lease and execution JTI.
create function identity_access.ia_retire_terminal_high_risk_execution_lease(
    requested_id uuid,requested_version bigint,requested_digest character)
returns boolean language plpgsql security definer set search_path=pg_catalog as $$
declare ia_current identity_access.ia_high_risk_execution_lease_current%rowtype;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select lease.* into ia_current
      from identity_access.ia_high_risk_execution_lease_current lease
     where lease.lease_id=requested_id for update;
    if ia_current.lease_id is null then return true; end if;
    if ia_current.lease_version<>requested_version
       or trim(ia_current.lease_digest)<>requested_digest
       or ia_current.state not in ('expired','cancelled') then
        return false;
    end if;
    delete from identity_access.ia_high_risk_execution_lease_current
     where lease_id=requested_id;
    return found;
end
$$;

create function identity_access.ia_find_high_risk_execution_lease_by_key(requested_digest character)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare ia_value jsonb;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select jsonb_build_object('issuanceRequestDigest',trim(issuance_request_digest),
        'lease',aggregate) into ia_value
      from identity_access.ia_high_risk_execution_lease_current
     where idempotency_key_digest=requested_digest;
    return ia_value;
end
$$;

create function identity_access.ia_issue_high_risk_execution_lease(
    requested_idempotency_digest character,requested_issuance_digest character,
    requested_aggregate jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare ia_now timestamptz:=statement_timestamp();
declare ia_value jsonb;
declare ia_approval identity_access.ia_high_risk_approval_current%rowtype;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select approval.* into ia_approval
      from identity_access.ia_high_risk_approval_current approval
     where approval.approval_id=(requested_aggregate->>'approvalId')::uuid for update;
    if ia_approval.status<>'approved'
       or requested_aggregate->>'state'<>'issued'
       or (requested_aggregate->>'leaseVersion')::bigint<>1
       or (requested_aggregate->>'approvalVersion')::bigint<>ia_approval.approval_version
       or requested_aggregate->>'requestDigest'<>trim(ia_approval.request_digest)
       or (requested_aggregate->>'issuedAt')::timestamptz>=ia_approval.expires_at
       or requested_idempotency_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_issuance_digest !~ '^sha256:[0-9a-f]{64}$' then
        raise exception using errcode='check_violation',
            message='IDENTITY_HIGH_RISK_EXECUTION_INVALID';
    end if;
    insert into identity_access.ia_high_risk_execution_lease_current values (
        (requested_aggregate->>'leaseId')::uuid,1,requested_aggregate->>'leaseDigest',
        ia_approval.approval_id,requested_idempotency_digest,requested_issuance_digest,
        (requested_aggregate->>'executionJti')::uuid,
        (requested_aggregate->>'tokenJti')::uuid,'issued',
        (requested_aggregate->>'issuedAt')::timestamptz,
        (requested_aggregate->>'authorizedUntil')::timestamptz,null,null,
        requested_aggregate,ia_now,false)
    on conflict (idempotency_key_digest) do nothing;
    if found then
        insert into identity_access.ia_high_risk_execution_lease_history values (
            (requested_aggregate->>'leaseId')::uuid,1,'issued',requested_aggregate,
            ia_now,ia_now+interval '2 years',false);
        insert into identity_access.ia_high_risk_audit(
            aggregate_type,aggregate_id,aggregate_version,action,outcome,
            occurred_at,trace_id,evidence_digest)
        values ('execution-lease',(requested_aggregate->>'leaseId')::uuid,1,
            'quality-fuse.recover.authorize','issued',ia_now,
            requested_aggregate->>'traceId',requested_aggregate->>'leaseDigest');
    end if;
    select jsonb_build_object('issuanceRequestDigest',trim(issuance_request_digest),
        'lease',aggregate) into ia_value
      from identity_access.ia_high_risk_execution_lease_current
     where idempotency_key_digest=requested_idempotency_digest;
    return ia_value;
end
$$;

create function identity_access.ia_save_high_risk_execution_lease(
    expected_version bigint,requested_aggregate jsonb)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare ia_id uuid:=(requested_aggregate->>'leaseId')::uuid;
declare ia_current identity_access.ia_high_risk_execution_lease_current%rowtype;
declare ia_now timestamptz:=statement_timestamp();
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select lease.* into ia_current
      from identity_access.ia_high_risk_execution_lease_current lease
     where lease.lease_id=ia_id for update;
    if ia_current.lease_id is null or ia_current.lease_version<>expected_version
       or (requested_aggregate->>'leaseVersion')::bigint<>expected_version+1
       or requested_aggregate->>'leaseDigest'<>trim(ia_current.lease_digest)
       or requested_aggregate->>'executionJti'<>ia_current.execution_jti::text
       or requested_aggregate->>'state' not in (
            'reserved','executed','expired','cancelled') then
        raise exception using errcode='serialization_failure',
            message='IDENTITY_HIGH_RISK_LEASE_VERSION_CONFLICT';
    end if;
    insert into identity_access.ia_high_risk_execution_lease_history values (
        ia_id,expected_version+1,requested_aggregate->>'state',requested_aggregate,
        ia_now,ia_now+interval '2 years',ia_current.legal_hold);
    update identity_access.ia_high_risk_execution_lease_current set
        lease_version=expected_version+1,state=requested_aggregate->>'state',
        owner_commit_id=requested_aggregate->>'ownerCommitId',
        outbox_event_id=(requested_aggregate->>'outboxEventId')::uuid,
        aggregate=requested_aggregate,updated_at=ia_now
     where lease_id=ia_id;
    insert into identity_access.ia_high_risk_audit(
        aggregate_type,aggregate_id,aggregate_version,action,outcome,
        occurred_at,trace_id,evidence_digest)
    values ('execution-lease',ia_id,expected_version+1,
        'quality-fuse.recover.lease',requested_aggregate->>'state',ia_now,
        requested_aggregate->>'traceId',requested_aggregate->>'leaseDigest');
    return requested_aggregate;
end
$$;

create function identity_access.ia_find_current_high_risk_approval_evidence(
    requested_action varchar,requested_object_type varchar,
    requested_object_ref_digest character,requested_object_version bigint,
    requested_actor_account_id uuid,requested_now timestamptz)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare ia_value jsonb;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    select jsonb_build_object('status',approval.status,
        'approvalVersion',approval.approval_version,
        'receiptDigest',trim(receipt.receipt_digest),
        'authorizationGeneration',approval.authorization_generation)
      into ia_value
      from identity_access.ia_high_risk_approval_current approval
      join identity_access.ia_high_risk_approval_receipt receipt
        on receipt.approval_id=approval.approval_id
       and receipt.approval_version=approval.approval_version
     where approval.action_type=requested_action
       and approval.object_type=requested_object_type
       and approval.object_ref_digest=requested_object_ref_digest
       and approval.object_version=requested_object_version
       and approval.maker_principal_digest=(select person.natural_person_principal_digest
            from identity_access.ia_account_natural_person_current person
           where person.account_id=requested_actor_account_id
             and person.effective_from<=requested_now
             and (person.effective_to is null or requested_now<person.effective_to))
       and approval.status='approved' and requested_now<approval.expires_at
     order by approval.approval_version desc limit 1;
    return ia_value;
end
$$;

create function identity_access.ia_bind_account_natural_person(
    requested_account_id uuid,requested_person_digest character,
    requested_authority_evidence_digest character,
    requested_effective_from timestamptz,requested_trace_id character)
returns bigint language plpgsql security definer set search_path=pg_catalog as $$
declare ia_current identity_access.ia_account_natural_person_current%rowtype;
declare ia_version bigint;
begin
    if not pg_catalog.pg_has_role(session_user,'scholarsense_identity_sync_worker','USAGE')
       or requested_person_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_authority_evidence_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_trace_id !~ '^(?!0{32}$)[0-9a-f]{32}$'
       or not exists (select 1 from identity_access.ia_authoritative_account_current account
           where account.account_id=requested_account_id and account.status='active') then
        raise exception using errcode='insufficient_privilege',
            message='IDENTITY_ACCOUNT_PERSON_BINDING_INVALID';
    end if;
    select binding.* into ia_current
      from identity_access.ia_account_natural_person_current binding
     where binding.account_id=requested_account_id for update;
    ia_version:=coalesce(ia_current.binding_version,0)+1;
    if ia_current.account_id is not null then
        update identity_access.ia_account_natural_person_history set
            effective_to=requested_effective_from
         where account_id=requested_account_id
           and binding_version=ia_current.binding_version and effective_to is null;
    end if;
    insert into identity_access.ia_account_natural_person_history values (
        requested_account_id,ia_version,requested_person_digest,
        requested_authority_evidence_digest,requested_effective_from,null,
        statement_timestamp(),false);
    insert into identity_access.ia_account_natural_person_current values (
        requested_account_id,ia_version,requested_person_digest,
        requested_authority_evidence_digest,requested_effective_from,null,false)
    on conflict (account_id) do update set
        binding_version=excluded.binding_version,
        natural_person_principal_digest=excluded.natural_person_principal_digest,
        authority_evidence_digest=excluded.authority_evidence_digest,
        effective_from=excluded.effective_from,effective_to=null;
    insert into identity_access.ia_high_risk_audit(
        aggregate_type,aggregate_id,aggregate_version,action,outcome,
        occurred_at,trace_id,evidence_digest)
    values ('account-person-binding',requested_account_id,ia_version,
        'natural-person.bind','applied',statement_timestamp(),requested_trace_id,
        requested_authority_evidence_digest);
    return ia_version;
end
$$;

create function identity_access.ia_resolve_current_natural_person_principal(
    requested_account_id uuid)
returns table(natural_person_principal_digest text,binding_version bigint)
language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    return query select trim(binding.natural_person_principal_digest),
        binding.binding_version
      from identity_access.ia_account_natural_person_current binding
      join identity_access.ia_authoritative_account_current account
        on account.account_id=binding.account_id and account.status='active'
     where binding.account_id=requested_account_id
       and binding.effective_from<=statement_timestamp()
       and (binding.effective_to is null or statement_timestamp()<binding.effective_to);
end
$$;

create function identity_access.ia_bind_business_owner_natural_person(
    requested_owner_key_digest character,requested_account_id uuid,
    requested_authorization_generation bigint,requested_effective_from timestamptz,
    requested_trace_id character)
returns bigint language plpgsql security definer set search_path=pg_catalog as $$
declare ia_current identity_access.ia_business_owner_natural_person_current%rowtype;
declare ia_version bigint;
declare ia_principal char(71);
begin
    if not pg_catalog.pg_has_role(session_user,'scholarsense_identity_sync_worker','USAGE')
       or requested_owner_key_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_authorization_generation not between 0 and 9007199254740991
       or requested_trace_id !~ '^(?!0{32}$)[0-9a-f]{32}$'
       or not exists (select 1 from identity_access.ia_authoritative_account_current account
           where account.account_id=requested_account_id and account.status='active') then
        raise exception using errcode='insufficient_privilege',
            message='IDENTITY_BUSINESS_OWNER_BINDING_INVALID';
    end if;
    select person.natural_person_principal_digest into ia_principal
      from identity_access.ia_account_natural_person_current person
     where person.account_id=requested_account_id
       and person.effective_from<=requested_effective_from
       and (person.effective_to is null or requested_effective_from<person.effective_to);
    if ia_principal is null then
        raise exception using errcode='insufficient_privilege',
            message='IDENTITY_ACCOUNT_PERSON_BINDING_REQUIRED';
    end if;
    select binding.* into ia_current
      from identity_access.ia_business_owner_natural_person_current binding
     where binding.business_owner_key_digest=requested_owner_key_digest for update;
    ia_version:=coalesce(ia_current.binding_version,0)+1;
    if ia_current.business_owner_key_digest is not null then
        update identity_access.ia_business_owner_natural_person_history set
            effective_to=requested_effective_from
         where business_owner_key_digest=requested_owner_key_digest
           and binding_version=ia_current.binding_version and effective_to is null;
    end if;
    insert into identity_access.ia_business_owner_natural_person_history values (
        requested_owner_key_digest,ia_version,requested_account_id,ia_principal,
        requested_effective_from,null,requested_authorization_generation,
        statement_timestamp(),false);
    insert into identity_access.ia_business_owner_natural_person_current values (
        requested_owner_key_digest,ia_version,requested_account_id,ia_principal,
        requested_effective_from,null,requested_authorization_generation,false)
    on conflict (business_owner_key_digest) do update set
        binding_version=excluded.binding_version,account_id=excluded.account_id,
        natural_person_principal_digest=excluded.natural_person_principal_digest,
        effective_from=excluded.effective_from,effective_to=null,
        authorization_generation=excluded.authorization_generation;
    insert into identity_access.ia_high_risk_audit(
        aggregate_type,aggregate_id,aggregate_version,action,outcome,
        occurred_at,trace_id,evidence_digest)
    values ('business-owner-binding',requested_account_id,ia_version,
        'business-owner.bind','applied',statement_timestamp(),requested_trace_id,
        requested_owner_key_digest);
    return ia_version;
end
$$;

create function identity_access.ia_resolve_current_natural_person_bindings(
    requested_owner_keys jsonb,requested_at timestamptz)
returns table(business_owner_key_digest text,natural_person_principal_digest text,
    binding_version bigint,effective_from timestamptz,effective_to timestamptz)
language plpgsql stable security definer set search_path=pg_catalog as $$
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_online');
    if jsonb_typeof(requested_owner_keys)<>'array'
       or jsonb_array_length(requested_owner_keys) not between 1 and 128 then
        raise exception using errcode='invalid_parameter_value',
            message='IDENTITY_BUSINESS_OWNER_QUERY_INVALID';
    end if;
    return query select trim(binding.business_owner_key_digest),
        trim(binding.natural_person_principal_digest),binding.binding_version,
        binding.effective_from,binding.effective_to
      from identity_access.ia_business_owner_natural_person_current binding
      join identity_access.ia_authoritative_account_current account
        on account.account_id=binding.account_id and account.status='active'
     where trim(binding.business_owner_key_digest) in (
        select jsonb_array_elements_text(requested_owner_keys))
       and binding.effective_from<=requested_at
       and (binding.effective_to is null or requested_at<binding.effective_to)
     order by binding.business_owner_key_digest;
end
$$;

create function identity_access.ia_cleanup_high_risk_expired(requested_now timestamptz)
returns bigint language plpgsql security definer set search_path=pg_catalog as $$
declare ia_count bigint;
declare ia_step bigint;
begin
    perform identity_access.ia_require_high_risk_workload(
        'scholarsense_identity_high_risk_retention');
    ia_count:=0;
    delete from identity_access.ia_high_risk_approval_decision_idempotency decision
     where not decision.legal_hold and decision.expires_at<=requested_now;
    get diagnostics ia_step=row_count;
    ia_count:=ia_count+ia_step;
    delete from identity_access.ia_high_risk_execution_lease_current lease
     where not lease.legal_hold
       and lease.updated_at+interval '2 years'<=requested_now;
    get diagnostics ia_step=row_count;
    ia_count:=ia_count+ia_step;
    delete from identity_access.ia_high_risk_execution_lease_history history
     where not history.legal_hold and history.expires_at<=requested_now;
    get diagnostics ia_step=row_count;
    ia_count:=ia_count+ia_step;
    delete from identity_access.ia_high_risk_approval_receipt receipt
     where not receipt.legal_hold and receipt.expires_at<=requested_now
       and not exists (select 1
         from identity_access.ia_high_risk_execution_lease_current lease
        where lease.approval_id=receipt.approval_id);
    get diagnostics ia_step=row_count;
    ia_count:=ia_count+ia_step;
    delete from identity_access.ia_high_risk_approval_current approval
     where not approval.legal_hold
       and approval.updated_at+interval '2 years'<=requested_now
       and not exists (select 1
         from identity_access.ia_high_risk_execution_lease_current lease
        where lease.approval_id=approval.approval_id)
       and not exists (select 1
         from identity_access.ia_high_risk_approval_receipt receipt
        where receipt.approval_id=approval.approval_id);
    get diagnostics ia_step=row_count;
    ia_count:=ia_count+ia_step;
    delete from identity_access.ia_high_risk_approval_history history
     where not history.legal_hold and history.expires_at<=requested_now
       and not exists (select 1
         from identity_access.ia_high_risk_approval_receipt receipt
        where receipt.approval_id=history.approval_id
          and receipt.approval_version=history.approval_version);
    get diagnostics ia_step=row_count;
    ia_count:=ia_count+ia_step;
    return ia_count;
end
$$;

alter table identity_access.ia_high_risk_approval_history owner to scholarsense_identity_online;
alter table identity_access.ia_high_risk_approval_current owner to scholarsense_identity_online;
alter table identity_access.ia_high_risk_approval_receipt owner to scholarsense_identity_online;
alter table identity_access.ia_high_risk_approval_decision_idempotency
    owner to scholarsense_identity_online;
alter table identity_access.ia_high_risk_execution_lease_history owner to scholarsense_identity_online;
alter table identity_access.ia_high_risk_execution_lease_current owner to scholarsense_identity_online;
alter table identity_access.ia_account_natural_person_history owner to scholarsense_identity_online;
alter table identity_access.ia_account_natural_person_current owner to scholarsense_identity_online;
alter table identity_access.ia_business_owner_natural_person_history owner to scholarsense_identity_online;
alter table identity_access.ia_business_owner_natural_person_current owner to scholarsense_identity_online;
alter table identity_access.ia_high_risk_audit owner to scholarsense_identity_online;

revoke all privileges on table
    identity_access.ia_high_risk_approval_history,
    identity_access.ia_high_risk_approval_current,
    identity_access.ia_high_risk_approval_receipt,
    identity_access.ia_high_risk_approval_decision_idempotency,
    identity_access.ia_high_risk_execution_lease_history,
    identity_access.ia_high_risk_execution_lease_current,
    identity_access.ia_account_natural_person_history,
    identity_access.ia_account_natural_person_current,
    identity_access.ia_business_owner_natural_person_history,
    identity_access.ia_business_owner_natural_person_current,
    identity_access.ia_high_risk_audit
from public,scholarsense_identity_high_risk_online,
    scholarsense_identity_high_risk_retention;

do $migration$
declare ia_function regprocedure;
begin
  for ia_function in select procedure.oid::regprocedure
    from pg_catalog.pg_proc procedure
   where procedure.pronamespace='identity_access'::regnamespace
     and procedure.proname like 'ia_%high_risk%'
  loop
    execute format('revoke all on function %s from public',ia_function);
    execute format('alter function %s owner to scholarsense_identity_online',ia_function);
  end loop;
end
$migration$;

revoke all on function identity_access.ia_bind_business_owner_natural_person(
    character,uuid,bigint,timestamptz,character) from public;
revoke all on function identity_access.ia_resolve_current_natural_person_bindings(
    jsonb,timestamptz) from public;
revoke all on function identity_access.ia_bind_account_natural_person(
    uuid,character,character,timestamptz,character) from public;
revoke all on function identity_access.ia_resolve_current_natural_person_principal(uuid)
from public;
grant execute on function identity_access.ia_find_high_risk_approval_by_id(uuid)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_find_high_risk_approval_by_key(character)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_insert_high_risk_approval(character,jsonb)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_save_high_risk_approval(bigint,jsonb,jsonb)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_find_high_risk_approval_receipt(uuid)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_find_high_risk_approval_decision_by_key(character)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_save_high_risk_approval_decision(
    character,character,bigint,jsonb,jsonb)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_find_expirable_high_risk_approvals(integer,timestamptz)
    to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_find_expirable_high_risk_execution_leases(
    integer,timestamptz) to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_retire_terminal_high_risk_execution_lease(
    uuid,bigint,character) to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_find_high_risk_execution_lease_by_id(uuid)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_find_high_risk_execution_lease_by_approval(uuid)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_find_high_risk_execution_lease_by_key(character)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_issue_high_risk_execution_lease(
    character,character,jsonb) to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_save_high_risk_execution_lease(bigint,jsonb)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_find_current_high_risk_approval_evidence(
    varchar,varchar,character,bigint,uuid,timestamptz)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_resolve_current_natural_person_bindings(
    jsonb,timestamptz) to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_resolve_current_natural_person_principal(uuid)
to scholarsense_identity_high_risk_online;
grant execute on function identity_access.ia_bind_account_natural_person(
    uuid,character,character,timestamptz,character)
to scholarsense_identity_sync_worker;
grant execute on function identity_access.ia_bind_business_owner_natural_person(
    character,uuid,bigint,timestamptz,character)
to scholarsense_identity_sync_worker;
grant execute on function identity_access.ia_cleanup_high_risk_expired(timestamptz)
to scholarsense_identity_high_risk_retention;
