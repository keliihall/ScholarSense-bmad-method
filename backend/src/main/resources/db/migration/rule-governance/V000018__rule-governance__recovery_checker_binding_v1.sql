create schema if not exists rule_governance;

do $migration$
begin
    if not exists (select 1 from pg_catalog.pg_roles
                    where rolname='scholarsense_rule_governance_owner') then
        create role scholarsense_rule_governance_owner nologin;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles
                    where rolname='scholarsense_rule_governance_reader') then
        create role scholarsense_rule_governance_reader nologin;
    end if;
end
$migration$;

alter role scholarsense_rule_governance_owner set search_path=pg_catalog;
alter role scholarsense_rule_governance_reader set search_path=pg_catalog;
grant usage on schema rule_governance
    to scholarsense_rule_governance_owner,scholarsense_rule_governance_reader;
revoke create on schema rule_governance from scholarsense_rule_governance_reader;

create table rule_governance.rg_rule_version_business_owner_history (
    rule_version_digest char(71) not null,
    binding_version bigint not null check (binding_version between 1 and 9007199254740991),
    rule_id varchar(64) not null,
    rule_version varchar(32) not null,
    business_owner_key_digest char(71) not null,
    authority_version varchar(64) not null,
    authority_digest char(71) not null,
    approval_ref varchar(128) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    recorded_at timestamptz not null,
    legal_hold boolean not null default false,
    primary key (rule_version_digest,binding_version),
    constraint rg_rule_owner_history_shape_ck check (
        rule_version_digest ~ '^sha256:[0-9a-f]{64}$'
        and rule_id ~ '^[A-Z][A-Z0-9-]{2,63}$'
        and rule_version ~ '^[1-9][0-9]*\.[0-9]+\.[0-9]+$'
        and business_owner_key_digest ~ '^sha256:[0-9a-f]{64}$'
        and authority_version='RC-1.0.0'
        and authority_digest='sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a'
        and approval_ref='AUTH-2026-07-17-001'
        and (effective_to is null or effective_to>effective_from))
);

create table rule_governance.rg_rule_version_business_owner_current (
    rule_version_digest char(71) primary key,
    binding_version bigint not null check (binding_version between 1 and 9007199254740991),
    rule_id varchar(64) not null,
    rule_version varchar(32) not null,
    business_owner_key_digest char(71) not null,
    effective_from timestamptz not null,
    effective_to timestamptz,
    legal_hold boolean not null default false,
    unique (rule_id,rule_version),
    constraint rg_rule_owner_current_shape_ck check (
        rule_version_digest ~ '^sha256:[0-9a-f]{64}$'
        and rule_id ~ '^[A-Z][A-Z0-9-]{2,63}$'
        and rule_version ~ '^[1-9][0-9]*\.[0-9]+\.[0-9]+$'
        and business_owner_key_digest ~ '^sha256:[0-9a-f]{64}$'
        and (effective_to is null or effective_to>effective_from))
);

insert into rule_governance.rg_rule_version_business_owner_history values
('sha256:e56334738557db461718e437ea36c6be6963710661b830cddfbed68eb1b7c53c',1,
 'ACC-SAFE-001','1.0.0','sha256:bbd8062ac9da3d5c6f68b739c3af8225630cfbea4940cb53fb6f874206c6b2de',
 'RC-1.0.0','sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a',
 'AUTH-2026-07-17-001','2026-07-17T00:00:00+08:00',null,statement_timestamp(),false),
('sha256:0b3e2722ff8eecb86d08e6b421f6f03f072238bc91a36f6ebc0ca4ab1a435c57',1,
 'ACC-SAFE-002','1.0.0','sha256:bbd8062ac9da3d5c6f68b739c3af8225630cfbea4940cb53fb6f874206c6b2de',
 'RC-1.0.0','sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a',
 'AUTH-2026-07-17-001','2026-07-17T00:00:00+08:00',null,statement_timestamp(),false),
('sha256:235b3e0016969f8742aabe75c299ce03bc315fcecf61eea3ce0b546ae6fde321',1,
 'ECON-012','1.0.0','sha256:bbd8062ac9da3d5c6f68b739c3af8225630cfbea4940cb53fb6f874206c6b2de',
 'RC-1.0.0','sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a',
 'AUTH-2026-07-17-001','2026-07-17T00:00:00+08:00',null,statement_timestamp(),false),
('sha256:7b275f8d65012db43e235a28e78e14b0e12f64ad87f66f64fe1c837725d893e5',1,
 'NIGHT-001','1.0.0','sha256:bbd8062ac9da3d5c6f68b739c3af8225630cfbea4940cb53fb6f874206c6b2de',
 'RC-1.0.0','sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a',
 'AUTH-2026-07-17-001','2026-07-17T00:00:00+08:00',null,statement_timestamp(),false),
('sha256:97bbbcc29ec75ab8593fe60ed8fd09aee1b9db4cfc3a703cd3b5f80f27ac02d0',1,
 'ACADEMIC-001','1.0.0','sha256:bbd8062ac9da3d5c6f68b739c3af8225630cfbea4940cb53fb6f874206c6b2de',
 'RC-1.0.0','sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a',
 'AUTH-2026-07-17-001','2026-07-17T00:00:00+08:00',null,statement_timestamp(),false),
('sha256:472658ac5ab8a0914a22082414470e9e3be81e1b119c2553283d23b41f98573a',1,
 'CORROBORATE-001','1.0.0','sha256:bbd8062ac9da3d5c6f68b739c3af8225630cfbea4940cb53fb6f874206c6b2de',
 'RC-1.0.0','sha256:d4f2587e0bde7a37964bab7ce990560327e2e5960f68fba5b770f09b041fd21a',
 'AUTH-2026-07-17-001','2026-07-17T00:00:00+08:00',null,statement_timestamp(),false);

insert into rule_governance.rg_rule_version_business_owner_current
select rule_version_digest,binding_version,rule_id,rule_version,
       business_owner_key_digest,effective_from,effective_to,legal_hold
  from rule_governance.rg_rule_version_business_owner_history;

create function rule_governance.rg_resolve_rule_version_business_owners(
    requested_rule_digests jsonb,requested_binding_set_digest character,
    requested_at timestamptz)
returns table(rule_version_digest text,business_owner_key_digest text,
    binding_version bigint,effective_from timestamptz,effective_to timestamptz)
language plpgsql stable security definer set search_path=pg_catalog as $$
declare rg_actual_digest text;
begin
    if not pg_catalog.pg_has_role(
            session_user,'scholarsense_rule_governance_reader','USAGE')
       or jsonb_typeof(requested_rule_digests)<>'array'
       or jsonb_array_length(requested_rule_digests) not between 1 and 128
       or requested_binding_set_digest !~ '^sha256:[0-9a-f]{64}$' then
        raise exception using errcode='insufficient_privilege',
            message='RULE_GOVERNANCE_CHECKER_BINDING_QUERY_INVALID';
    end if;
    select 'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
          trim(binding.rule_version_digest)||chr(31)||
          trim(binding.business_owner_key_digest)||chr(31)||
          binding.binding_version::text,chr(30)
          order by binding.rule_version_digest),'UTF8')),'hex') into rg_actual_digest
      from rule_governance.rg_rule_version_business_owner_current binding
     where trim(binding.rule_version_digest) in (
          select jsonb_array_elements_text(requested_rule_digests))
       and binding.effective_from<=requested_at
       and (binding.effective_to is null or requested_at<binding.effective_to);
    if rg_actual_digest is distinct from trim(requested_binding_set_digest) then
        return;
    end if;
    return query select trim(binding.rule_version_digest),
        trim(binding.business_owner_key_digest),binding.binding_version,
        binding.effective_from,binding.effective_to
      from rule_governance.rg_rule_version_business_owner_current binding
     where trim(binding.rule_version_digest) in (
          select jsonb_array_elements_text(requested_rule_digests))
       and binding.effective_from<=requested_at
       and (binding.effective_to is null or requested_at<binding.effective_to)
     order by binding.rule_version_digest;
end
$$;

create function rule_governance.rg_read_current_rule_version_business_owners(
    requested_rule_digests jsonb,requested_at timestamptz)
returns table(rule_version_digest text,business_owner_key_digest text,
    binding_version bigint,effective_from timestamptz,effective_to timestamptz,
    binding_set_digest text)
language plpgsql stable security definer set search_path=pg_catalog as $$
declare rg_digest text;
begin
    if not pg_catalog.pg_has_role(
            session_user,'scholarsense_rule_governance_reader','USAGE')
       or jsonb_typeof(requested_rule_digests)<>'array'
       or jsonb_array_length(requested_rule_digests) not between 1 and 128 then
        raise exception using errcode='insufficient_privilege',
            message='RULE_GOVERNANCE_CHECKER_BINDING_QUERY_INVALID';
    end if;
    select 'sha256:'||encode(pg_catalog.sha256(pg_catalog.convert_to(string_agg(
          trim(binding.rule_version_digest)||chr(31)||
          trim(binding.business_owner_key_digest)||chr(31)||
          binding.binding_version::text,chr(30)
          order by binding.rule_version_digest),'UTF8')),'hex') into rg_digest
      from rule_governance.rg_rule_version_business_owner_current binding
     where trim(binding.rule_version_digest) in (
          select jsonb_array_elements_text(requested_rule_digests))
       and binding.effective_from<=requested_at
       and (binding.effective_to is null or requested_at<binding.effective_to);
    return query select trim(binding.rule_version_digest),
        trim(binding.business_owner_key_digest),binding.binding_version,
        binding.effective_from,binding.effective_to,rg_digest
      from rule_governance.rg_rule_version_business_owner_current binding
     where trim(binding.rule_version_digest) in (
          select jsonb_array_elements_text(requested_rule_digests))
       and binding.effective_from<=requested_at
       and (binding.effective_to is null or requested_at<binding.effective_to)
     order by binding.rule_version_digest;
end
$$;

alter table rule_governance.rg_rule_version_business_owner_history
    owner to scholarsense_rule_governance_owner;
alter table rule_governance.rg_rule_version_business_owner_current
    owner to scholarsense_rule_governance_owner;
revoke all privileges on table
    rule_governance.rg_rule_version_business_owner_history,
    rule_governance.rg_rule_version_business_owner_current
from public,scholarsense_rule_governance_reader;
revoke all on function rule_governance.rg_resolve_rule_version_business_owners(
    jsonb,character,timestamptz) from public;
revoke all on function rule_governance.rg_read_current_rule_version_business_owners(
    jsonb,timestamptz) from public;
alter function rule_governance.rg_resolve_rule_version_business_owners(
    jsonb,character,timestamptz) owner to scholarsense_rule_governance_owner;
alter function rule_governance.rg_read_current_rule_version_business_owners(
    jsonb,timestamptz) owner to scholarsense_rule_governance_owner;
grant execute on function rule_governance.rg_resolve_rule_version_business_owners(
    jsonb,character,timestamptz) to scholarsense_rule_governance_reader;
grant execute on function rule_governance.rg_read_current_rule_version_business_owners(
    jsonb,timestamptz) to scholarsense_rule_governance_reader;
