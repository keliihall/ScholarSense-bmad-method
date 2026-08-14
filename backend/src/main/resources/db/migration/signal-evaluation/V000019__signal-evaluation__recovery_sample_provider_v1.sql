create schema if not exists signal_evaluation;

do $migration$
begin
    if not exists (select 1 from pg_catalog.pg_roles
                    where rolname='scholarsense_signal_evaluation_owner') then
        create role scholarsense_signal_evaluation_owner nologin;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles
                    where rolname='scholarsense_signal_evaluation_recovery_worker') then
        create role scholarsense_signal_evaluation_recovery_worker nologin;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles
                    where rolname='scholarsense_signal_evaluation_input_authority') then
        create role scholarsense_signal_evaluation_input_authority nologin;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles
                    where rolname='scholarsense_signal_evaluation_retention') then
        create role scholarsense_signal_evaluation_retention nologin;
    end if;
end
$migration$;

alter role scholarsense_signal_evaluation_owner set search_path=pg_catalog;
alter role scholarsense_signal_evaluation_recovery_worker set search_path=pg_catalog;
alter role scholarsense_signal_evaluation_input_authority set search_path=pg_catalog;
alter role scholarsense_signal_evaluation_retention set search_path=pg_catalog;
grant scholarsense_signal_evaluation_input_authority
    to scholarsense_signal_evaluation_recovery_worker
    with inherit true,set false,admin false;
grant usage on schema signal_evaluation to scholarsense_signal_evaluation_owner,
    scholarsense_signal_evaluation_recovery_worker,
    scholarsense_signal_evaluation_input_authority,
    scholarsense_signal_evaluation_retention;
revoke create on schema signal_evaluation from
    scholarsense_signal_evaluation_recovery_worker,
    scholarsense_signal_evaluation_input_authority,
    scholarsense_signal_evaluation_retention;

create table signal_evaluation.se_recovery_sample_normalized_input (
    opaque_selection_ref varchar(80) primary key,
    input_version bigint not null check (input_version between 1 and 9007199254740991),
    rule_versions_digest char(71) not null,
    member_set_digest char(71) not null,
    watermarks_digest char(71) not null,
    quality_recovery_policy_digest char(71) not null,
    selection_seed char(71) not null,
    strata jsonb not null,
    strata_digest char(71) not null,
    sealed_at timestamptz not null,
    effective_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    constraint se_recovery_sample_input_shape_ck check (
        opaque_selection_ref ~ '^oswref:v1:[0-9a-f]{64}$'
        and rule_versions_digest ~ '^sha256:[0-9a-f]{64}$'
        and member_set_digest ~ '^sha256:[0-9a-f]{64}$'
        and watermarks_digest ~ '^sha256:[0-9a-f]{64}$'
        and quality_recovery_policy_digest=
            'sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366'
        and selection_seed ~ '^sha256:[0-9a-f]{64}$'
        and jsonb_typeof(strata)='array'
        and jsonb_array_length(strata) between 1 and 128
        and strata_digest ~ '^sha256:[0-9a-f]{64}$'
        and effective_at>=sealed_at and expires_at>effective_at)
);

create table signal_evaluation.se_recovery_sample_replay (
    provider_version varchar(64) not null,
    request_digest char(71) not null,
    canonical_body_digest char(71) not null,
    response jsonb not null,
    completed_at timestamptz not null,
    expires_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (provider_version,request_digest),
    constraint se_recovery_sample_replay_shape_ck check (
        provider_version='RECOVERY-SAMPLE-PROVIDER-1.0.0'
        and request_digest ~ '^sha256:[0-9a-f]{64}$'
        and canonical_body_digest ~ '^sha256:[0-9a-f]{64}$'
        and jsonb_typeof(response)='object'
        and response->>'traceId' ~ '^(?!0{32}$)[0-9a-f]{32}$'
        and expires_at=completed_at+interval '90 days')
);

create index se_recovery_sample_input_expiry_idx
    on signal_evaluation.se_recovery_sample_normalized_input(expires_at)
    where not legal_hold;
create index se_recovery_sample_replay_expiry_idx
    on signal_evaluation.se_recovery_sample_replay(expires_at)
    where not legal_hold;

create function signal_evaluation.se_stage_recovery_sample_normalized_input(
    requested_selection_ref varchar,requested_input_version bigint,
    requested_rule_versions_digest character,requested_member_set_digest character,
    requested_watermarks_digest character,requested_policy_digest character,
    requested_selection_seed character,requested_strata jsonb,
    requested_strata_digest character,requested_sealed_at timestamptz,
    requested_effective_at timestamptz,requested_expires_at timestamptz)
returns void language plpgsql security definer set search_path=pg_catalog as $$
declare se_stratum jsonb;
declare se_window jsonb;
declare se_total bigint:=0;
begin
    if not pg_catalog.pg_has_role(
            session_user,'scholarsense_signal_evaluation_input_authority','USAGE')
       or jsonb_typeof(requested_strata)<>'array'
       or jsonb_array_length(requested_strata) not between 1 and 128
       or octet_length(requested_strata::text)>65536 then
        raise exception using errcode='insufficient_privilege',
            message='SIGNAL_EVALUATION_RECOVERY_SAMPLE_INPUT_INVALID';
    end if;
    for se_stratum in select value from jsonb_array_elements(requested_strata)
    loop
      if jsonb_typeof(se_stratum)<>'object'
         or (select array_agg(key order by key) from jsonb_object_keys(se_stratum) key)
            <>array['code','populationCount','selectedWindows']
         or se_stratum->>'code' !~ '^[A-Z][A-Z0-9_]{1,63}$'
         or (se_stratum->>'populationCount')::bigint not between 0 and 9007199254740991
         or jsonb_typeof(se_stratum->'selectedWindows')<>'array'
         or jsonb_array_length(se_stratum->'selectedWindows')>10000
         or jsonb_array_length(se_stratum->'selectedWindows')>
            (se_stratum->>'populationCount')::bigint then
        raise exception using errcode='check_violation',
            message='SIGNAL_EVALUATION_RECOVERY_SAMPLE_INPUT_INVALID';
      end if;
      se_total:=se_total+jsonb_array_length(se_stratum->'selectedWindows');
      if se_total>10000 then
        raise exception using errcode='check_violation',
            message='SIGNAL_EVALUATION_RECOVERY_SAMPLE_INPUT_INVALID';
      end if;
      for se_window in select value
          from jsonb_array_elements(se_stratum->'selectedWindows')
      loop
        if jsonb_typeof(se_window)<>'object'
           or (select array_agg(key order by key) from jsonb_object_keys(se_window) key)
              <>array['expectedDigest','normalizedInputDigest','selectionRankDigest']
           or se_window->>'selectionRankDigest' !~ '^sha256:[0-9a-f]{64}$'
           or se_window->>'expectedDigest' !~ '^sha256:[0-9a-f]{64}$'
           or se_window->>'normalizedInputDigest' !~ '^sha256:[0-9a-f]{64}$' then
          raise exception using errcode='check_violation',
              message='SIGNAL_EVALUATION_RECOVERY_SAMPLE_INPUT_INVALID';
        end if;
      end loop;
    end loop;
    insert into signal_evaluation.se_recovery_sample_normalized_input as se_target values (
        requested_selection_ref,requested_input_version,requested_rule_versions_digest,
        requested_member_set_digest,requested_watermarks_digest,requested_policy_digest,
        requested_selection_seed,requested_strata,requested_strata_digest,
        requested_sealed_at,requested_effective_at,requested_expires_at,false)
    on conflict (opaque_selection_ref) do update set
        input_version=excluded.input_version,
        rule_versions_digest=excluded.rule_versions_digest,
        member_set_digest=excluded.member_set_digest,
        watermarks_digest=excluded.watermarks_digest,
        quality_recovery_policy_digest=excluded.quality_recovery_policy_digest,
        selection_seed=excluded.selection_seed,strata=excluded.strata,
        strata_digest=excluded.strata_digest,sealed_at=excluded.sealed_at,
        effective_at=excluded.effective_at,expires_at=excluded.expires_at
    where se_target.input_version<excluded.input_version;
end
$$;

create function signal_evaluation.se_resolve_recovery_sample_normalized_input(
    requested_selection_ref varchar,requested_at timestamptz)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare se_value jsonb;
begin
    if not pg_catalog.pg_has_role(
            session_user,'scholarsense_signal_evaluation_recovery_worker','USAGE') then
        raise exception using errcode='insufficient_privilege',
            message='SIGNAL_EVALUATION_RECOVERY_SAMPLE_WORKLOAD_FORBIDDEN';
    end if;
    select jsonb_build_object(
        'ruleVersionsDigest',trim(rule_versions_digest),
        'memberSetDigest',trim(member_set_digest),
        'watermarksDigest',trim(watermarks_digest),
        'qualityRecoveryPolicyDigest',trim(quality_recovery_policy_digest),
        'selectionSeed',trim(selection_seed),'strata',strata) into se_value
      from signal_evaluation.se_recovery_sample_normalized_input
     where opaque_selection_ref=requested_selection_ref
       and effective_at<=requested_at and requested_at<expires_at;
    return se_value;
end
$$;

create function signal_evaluation.se_find_recovery_sample_replay(
    requested_provider_version varchar,requested_request_digest character)
returns jsonb language plpgsql stable security definer set search_path=pg_catalog as $$
declare se_value jsonb;
begin
    if not pg_catalog.pg_has_role(
            session_user,'scholarsense_signal_evaluation_recovery_worker','USAGE') then
        raise exception using errcode='insufficient_privilege',
            message='SIGNAL_EVALUATION_RECOVERY_SAMPLE_WORKLOAD_FORBIDDEN';
    end if;
    select jsonb_build_object('canonicalBodyDigest',trim(canonical_body_digest),
        'response',response) into se_value
      from signal_evaluation.se_recovery_sample_replay
     where provider_version=requested_provider_version
       and request_digest=requested_request_digest;
    return se_value;
end
$$;

create function signal_evaluation.se_insert_recovery_sample_replay(
    requested_provider_version varchar,requested_request_digest character,
    requested_body_digest character,requested_response jsonb,
    requested_completed_at timestamptz)
returns jsonb language plpgsql security definer set search_path=pg_catalog as $$
declare se_value jsonb;
begin
    if not pg_catalog.pg_has_role(
            session_user,'scholarsense_signal_evaluation_recovery_worker','USAGE')
       or requested_provider_version<>'RECOVERY-SAMPLE-PROVIDER-1.0.0'
       or requested_request_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_body_digest !~ '^sha256:[0-9a-f]{64}$'
       or jsonb_typeof(requested_response)<>'object'
       or octet_length(requested_response::text)>65536 then
        raise exception using errcode='insufficient_privilege',
            message='SIGNAL_EVALUATION_RECOVERY_SAMPLE_REPLAY_INVALID';
    end if;
    insert into signal_evaluation.se_recovery_sample_replay values (
        requested_provider_version,requested_request_digest,requested_body_digest,
        requested_response,requested_completed_at,requested_completed_at+interval '90 days',false)
    on conflict (provider_version,request_digest) do nothing;
    select jsonb_build_object('canonicalBodyDigest',trim(canonical_body_digest),
        'response',response) into se_value
      from signal_evaluation.se_recovery_sample_replay
     where provider_version=requested_provider_version
       and request_digest=requested_request_digest;
    return se_value;
end
$$;

create function signal_evaluation.se_cleanup_recovery_sample_expired(
    requested_now timestamptz)
returns bigint language plpgsql security definer set search_path=pg_catalog as $$
declare se_count bigint;
begin
    if not pg_catalog.pg_has_role(
            session_user,'scholarsense_signal_evaluation_retention','USAGE') then
        raise exception using errcode='insufficient_privilege',
            message='SIGNAL_EVALUATION_RECOVERY_SAMPLE_RETENTION_FORBIDDEN';
    end if;
    with replay as (delete from signal_evaluation.se_recovery_sample_replay
      where not legal_hold and expires_at<=requested_now returning 1),
    input as (delete from signal_evaluation.se_recovery_sample_normalized_input
      where not legal_hold and expires_at<=requested_now returning 1)
    select count(*) into se_count from (
      select * from replay union all select * from input) deleted;
    return se_count;
end
$$;

alter table signal_evaluation.se_recovery_sample_normalized_input
    owner to scholarsense_signal_evaluation_owner;
alter table signal_evaluation.se_recovery_sample_replay
    owner to scholarsense_signal_evaluation_owner;
revoke all privileges on table
    signal_evaluation.se_recovery_sample_normalized_input,
    signal_evaluation.se_recovery_sample_replay
from public,scholarsense_signal_evaluation_recovery_worker,
    scholarsense_signal_evaluation_input_authority,
    scholarsense_signal_evaluation_retention;
revoke all on function signal_evaluation.se_stage_recovery_sample_normalized_input(
    varchar,bigint,character,character,character,character,character,
    jsonb,character,timestamptz,timestamptz,timestamptz) from public;
revoke all on function signal_evaluation.se_resolve_recovery_sample_normalized_input(
    varchar,timestamptz) from public;
revoke all on function signal_evaluation.se_find_recovery_sample_replay(
    varchar,character) from public;
revoke all on function signal_evaluation.se_insert_recovery_sample_replay(
    varchar,character,character,jsonb,timestamptz) from public;
revoke all on function signal_evaluation.se_cleanup_recovery_sample_expired(timestamptz)
    from public;
alter function signal_evaluation.se_stage_recovery_sample_normalized_input(
    varchar,bigint,character,character,character,character,character,
    jsonb,character,timestamptz,timestamptz,timestamptz)
    owner to scholarsense_signal_evaluation_owner;
alter function signal_evaluation.se_resolve_recovery_sample_normalized_input(
    varchar,timestamptz) owner to scholarsense_signal_evaluation_owner;
alter function signal_evaluation.se_find_recovery_sample_replay(
    varchar,character) owner to scholarsense_signal_evaluation_owner;
alter function signal_evaluation.se_insert_recovery_sample_replay(
    varchar,character,character,jsonb,timestamptz)
    owner to scholarsense_signal_evaluation_owner;
alter function signal_evaluation.se_cleanup_recovery_sample_expired(timestamptz)
    owner to scholarsense_signal_evaluation_owner;
grant execute on function signal_evaluation.se_stage_recovery_sample_normalized_input(
    varchar,bigint,character,character,character,character,character,
    jsonb,character,timestamptz,timestamptz,timestamptz)
    to scholarsense_signal_evaluation_input_authority;
grant execute on function signal_evaluation.se_resolve_recovery_sample_normalized_input(
    varchar,timestamptz) to scholarsense_signal_evaluation_recovery_worker;
grant execute on function signal_evaluation.se_find_recovery_sample_replay(
    varchar,character) to scholarsense_signal_evaluation_recovery_worker;
grant execute on function signal_evaluation.se_insert_recovery_sample_replay(
    varchar,character,character,jsonb,timestamptz)
    to scholarsense_signal_evaluation_recovery_worker;
grant execute on function signal_evaluation.se_cleanup_recovery_sample_expired(timestamptz)
    to scholarsense_signal_evaluation_retention;
