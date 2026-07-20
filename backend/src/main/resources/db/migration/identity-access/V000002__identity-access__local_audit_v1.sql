-- Forward-only expansion of the Story 1.2 audit table. V000001 remains immutable.
-- Legacy rows remain readable in this table and are not misrepresented as v1 facts.

alter table identity_access.ia_local_audit_fact
    add column schema_version varchar(64),
    add column producer_module varchar(64),
    add column actor_type varchar(32),
    add column actor_search_token varchar(160),
    add column role_ids jsonb,
    add column authorization_context jsonb,
    add column object_type varchar(64),
    add column object_search_token varchar(160),
    add column outcome varchar(32),
    add column reason_code varchar(128),
    add column purpose varchar(64),
    add column projection_scope varchar(64),
    add column recorded_at timestamptz,
    add column time_source_profile jsonb,
    add column source_ip_search_token varchar(160),
    add column tokenization_profile_version varchar(64),
    add column key_version varchar(32),
    add column aggregate_type varchar(64),
    add column aggregate_id_search_token varchar(160),
    add column aggregate_version bigint,
    add column idempotency_key_digest char(64),
    add column policy_versions jsonb,
    add column retention_schedule_version varchar(64);

update identity_access.ia_local_audit_fact
set schema_version = 'SESSION-AUDIT-LEGACY-1.2.0',
    producer_module = 'identity-access',
    actor_type = 'user',
    actor_search_token = null,
    role_ids = '[]'::jsonb,
    authorization_context = jsonb_build_object(
        'decision', 'not-applicable',
        'policyVersion', null,
        'scopeCodes', '[]'::jsonb,
        'grantSearchTokens', '[]'::jsonb,
        'notApplicableReason', 'NO_ROLE_MODEL'),
    object_type = 'identity-session',
    object_search_token = null,
    outcome = case when result = 'accepted' then 'accepted' else 'rejected' end,
    reason_code = 'LEGACY_SESSION_AUDIT',
    purpose = null,
    projection_scope = null,
    recorded_at = occurred_at,
    time_source_profile = jsonb_build_object(
        'sourceId', 'legacy-unverified',
        'profileVersion', 'SESSION-AUDIT-LEGACY-1.2.0',
        'offsetMs', 0,
        'observedAt', occurred_at,
        'freshUntil', occurred_at,
        'evidenceRef', 'legacy://unverified'),
    source_ip_search_token = null,
    tokenization_profile_version = 'SESSION-AUDIT-LEGACY-1.2.0',
    key_version = 'legacy',
    aggregate_type = null,
    aggregate_id_search_token = null,
    aggregate_version = null,
    idempotency_key_digest = null,
    policy_versions = jsonb_build_object('legacyProfileVersion', profile_version),
    retention_schedule_version = 'RS-1.0.0';

alter table identity_access.ia_local_audit_fact
    alter column schema_version set not null,
    alter column schema_version set default 'LOCAL-AUDIT-FACT-1.0.0',
    alter column producer_module set not null,
    alter column actor_type set not null,
    alter column role_ids set not null,
    alter column authorization_context set not null,
    alter column outcome set not null,
    alter column reason_code set not null,
    alter column recorded_at set not null,
    alter column time_source_profile set not null,
    alter column tokenization_profile_version set not null,
    alter column key_version set not null,
    alter column policy_versions set not null,
    alter column retention_schedule_version set not null,
    add constraint ia_local_audit_fact_schema_version_ck check (
        schema_version in ('SESSION-AUDIT-LEGACY-1.2.0', 'LOCAL-AUDIT-FACT-1.0.0')
    ),
    add constraint ia_local_audit_fact_v1_shape_ck check (
        schema_version <> 'LOCAL-AUDIT-FACT-1.0.0' or (
            producer_module = 'identity-access'
            and actor_type in ('user', 'anonymous', 'service')
            and jsonb_typeof(role_ids) = 'array'
            and jsonb_typeof(authorization_context) = 'object'
            and action ~ '^[a-z][a-z0-9.-]{2,127}$'
            and outcome in ('accepted', 'rejected')
            and reason_code ~ '^[A-Z][A-Z0-9_]{2,127}$'
            and jsonb_typeof(time_source_profile) = 'object'
            and tokenization_profile_version = 'AUDIT-TOKENIZATION-1.0.0'
            and key_version ~ '^k[0-9]+$'
            and trace_id ~ '^[0-9a-f]{32}$'
            and jsonb_typeof(policy_versions) = 'object'
            and retention_schedule_version = 'RS-1.0.0'
            and (aggregate_version is null or aggregate_version >= 1)
            and (idempotency_key_digest is null or idempotency_key_digest ~ '^[0-9a-f]{64}$')
            and (actor_type <> 'anonymous' or (
                actor_search_token is null
                and role_ids = '[]'::jsonb
                and object_type is null
                and object_search_token is null
                and aggregate_type is null
                and aggregate_id_search_token is null
                and authorization_context ->> 'decision' = 'not-applicable'
                and authorization_context -> 'policyVersion' = 'null'::jsonb
                and authorization_context -> 'scopeCodes' = '[]'::jsonb
                and authorization_context -> 'grantSearchTokens' = '[]'::jsonb
            ))
        )
    ),
    add constraint ia_local_audit_fact_recorded_order_ck check (recorded_at >= occurred_at),
    add constraint ia_local_audit_fact_uuid_v7_ck check (
        substring(audit_id::text, 15, 1) = '7'
        and substring(audit_id::text, 20, 1) ~ '^[89ab]$'
    );

create table identity_access.ia_local_audit_outbox (
    event_id uuid primary key,
    audit_id uuid not null unique,
    event_type varchar(128) not null,
    schema_version varchar(64) not null,
    envelope jsonb not null,
    created_at timestamptz not null,
    delivery_status varchar(32) not null default 'pending',
    attempts integer not null default 0,
    next_attempt_at timestamptz not null,
    last_error_code varchar(128),
    claimed_at timestamptz,
    foreign key (audit_id) references identity_access.ia_local_audit_fact(audit_id),
    constraint ia_local_audit_outbox_event_type_ck
        check (event_type = 'identity-access.local-audit-fact.recorded.v1'),
    constraint ia_local_audit_outbox_schema_version_ck
        check (schema_version = 'LOCAL-AUDIT-OUTBOX-1.0.0'),
    constraint ia_local_audit_outbox_delivery_status_ck
        check (delivery_status in ('pending', 'retrying', 'confirmed', 'failed')),
    constraint ia_local_audit_outbox_attempts_ck check (attempts >= 0),
    constraint ia_local_audit_outbox_envelope_ck check (jsonb_typeof(envelope) = 'object'),
    constraint ia_local_audit_outbox_uuid_v7_ck check (
        substring(event_id::text, 15, 1) = '7'
        and substring(event_id::text, 20, 1) ~ '^[89ab]$'
    )
);

create index ia_local_audit_fact_actor_lookup_idx
    on identity_access.ia_local_audit_fact (actor_search_token, occurred_at, audit_id);
create index ia_local_audit_fact_object_lookup_idx
    on identity_access.ia_local_audit_fact (object_search_token, occurred_at, audit_id);
create index ia_local_audit_fact_source_ip_lookup_idx
    on identity_access.ia_local_audit_fact (source_ip_search_token, occurred_at, audit_id);
create index ia_local_audit_outbox_delivery_idx
    on identity_access.ia_local_audit_outbox (delivery_status, next_attempt_at, event_id);

do $migration$
begin
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'scholarsense_identity_online') then
        create role scholarsense_identity_online nologin;
    end if;
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'scholarsense_identity_audit_delivery') then
        create role scholarsense_identity_audit_delivery nologin;
    end if;
end
$migration$;

revoke all privileges on identity_access.ia_local_audit_fact
    from public, scholarsense_identity_online, scholarsense_identity_audit_delivery;
revoke all privileges on identity_access.ia_local_audit_outbox
    from public, scholarsense_identity_online, scholarsense_identity_audit_delivery;
grant usage on schema identity_access
    to scholarsense_identity_online, scholarsense_identity_audit_delivery;
grant select on identity_access.ia_local_audit_fact to scholarsense_identity_online;
grant insert (
    audit_id, actor_pseudonym, session_pseudonym, action, result, occurred_at,
    source_ip_pseudonym, trace_id, profile_version, producer_module, actor_type,
    actor_search_token, role_ids, authorization_context, object_type, object_search_token,
    outcome, reason_code, purpose, projection_scope, recorded_at, time_source_profile,
    source_ip_search_token, tokenization_profile_version, key_version, aggregate_type,
    aggregate_id_search_token, aggregate_version, idempotency_key_digest, policy_versions,
    retention_schedule_version)
    on identity_access.ia_local_audit_fact to scholarsense_identity_online;
grant select, insert on identity_access.ia_local_audit_outbox
    to scholarsense_identity_online;
grant select on identity_access.ia_local_audit_outbox
    to scholarsense_identity_audit_delivery;
grant update (delivery_status, attempts, next_attempt_at, last_error_code, claimed_at)
    on identity_access.ia_local_audit_outbox to scholarsense_identity_audit_delivery;
