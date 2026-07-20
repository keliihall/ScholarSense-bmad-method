create schema if not exists identity_access;

create table identity_access.ia_identity_session (
    session_id varchar(128) primary key,
    session_pseudonym varchar(128) not null unique,
    actor_pseudonym varchar(128) not null,
    browser_binding_hash varchar(128) not null,
    origin varchar(255) not null,
    created_at timestamptz not null,
    last_activity_at timestamptz not null,
    access_expires_at timestamptz not null,
    idle_expires_at timestamptz not null,
    absolute_expires_at timestamptz not null,
    warning_at timestamptz not null,
    session_version bigint not null check (session_version >= 1),
    refresh_family_id varchar(128) not null,
    current_refresh_digest char(64) not null,
    used_refresh_digests text not null default '',
    status varchar(32) not null,
    updated_at timestamptz not null
);

create table identity_access.ia_refresh_secret (
    session_id varchar(128) not null,
    registration_id varchar(128) not null,
    access_ciphertext bytea not null,
    access_wrapped_dek bytea not null,
    access_nonce bytea not null,
    refresh_ciphertext bytea not null,
    refresh_wrapped_dek bytea not null,
    refresh_nonce bytea not null,
    key_ref varchar(255) not null,
    key_version varchar(128) not null,
    access_expires_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (session_id, registration_id),
    foreign key (session_id) references identity_access.ia_identity_session(session_id)
);

create table identity_access.ia_idempotency_result (
    session_id varchar(128) not null,
    command_type varchar(32) not null,
    idempotency_key varchar(128) not null,
    request_digest char(64) not null,
    result_session_version bigint not null,
    result_completed_at timestamptz not null,
    result_session_pseudonym varchar(128) not null,
    result_next_action varchar(255) not null,
    result_remote_pending boolean not null,
    expires_at timestamptz not null,
    primary key (session_id, command_type, idempotency_key),
    unique (command_type, idempotency_key),
    foreign key (session_id) references identity_access.ia_identity_session(session_id)
);

create table identity_access.ia_oidc_attempt (
    state_digest char(64) primary key,
    nonce_digest char(64) not null,
    browser_binding_hash varchar(128) not null,
    origin varchar(255) not null,
    pkce_ciphertext bytea not null,
    pkce_wrapped_dek bytea not null,
    pkce_nonce bytea not null,
    key_ref varchar(255) not null,
    key_version varchar(128) not null,
    continuation_digest char(64),
    expires_at timestamptz not null,
    consumed_at timestamptz
);

create table identity_access.ia_continuation (
    code_digest char(64) primary key,
    browser_session_id varchar(128) not null,
    origin varchar(255) not null,
    route_id varchar(64) not null check (route_id in ('shell.home', 'shell.session')),
    opaque_context varchar(128),
    expires_at timestamptz not null,
    consumed_at timestamptz
);

create table identity_access.ia_local_audit_fact (
    audit_id uuid primary key,
    actor_pseudonym varchar(128) not null,
    session_pseudonym varchar(128) not null,
    action varchar(128) not null,
    result varchar(32) not null,
    occurred_at timestamptz not null,
    source_ip_pseudonym varchar(128) not null,
    trace_id varchar(128) not null,
    profile_version varchar(64) not null
);

create table identity_access.ia_remote_logout_outbox (
    request_id uuid primary key,
    session_id varchar(128) not null,
    registration_id varchar(128) not null,
    command_type varchar(32) not null,
    idempotency_key varchar(128) not null,
    state varchar(32) not null check (state in ('pending', 'retrying', 'confirmed', 'failed')),
    attempts integer not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz not null,
    last_error_code varchar(128),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (session_id, command_type, idempotency_key),
    foreign key (session_id) references identity_access.ia_identity_session(session_id)
);

create table identity_access.ia_host_bootstrap (
    code_digest char(64) primary key,
    audience varchar(128) not null,
    origin varchar(255) not null,
    browser_binding_hash varchar(128) not null,
    expires_at timestamptz not null,
    consumed_at timestamptz
);

create table identity_access.ia_host_replay (
    origin varchar(255) not null,
    nonce_digest char(64) not null,
    message_id uuid not null,
    result_code varchar(128) not null,
    response_payload text not null,
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    primary key (origin, nonce_digest),
    unique (origin, message_id)
);

create table identity_access.ia_spring_session (
    primary_id char(36) not null,
    session_id char(36) not null,
    creation_time bigint not null,
    last_access_time bigint not null,
    max_inactive_interval integer not null,
    expiry_time bigint not null,
    principal_name varchar(100),
    constraint ia_spring_session_pk primary key (primary_id),
    constraint ia_spring_session_uk unique (session_id)
);

create table identity_access.ia_spring_session_attributes (
    session_primary_id char(36) not null,
    attribute_name varchar(200) not null,
    attribute_bytes bytea not null,
    constraint ia_spring_session_attributes_pk primary key (session_primary_id, attribute_name),
    constraint ia_spring_session_attributes_fk foreign key (session_primary_id)
        references identity_access.ia_spring_session(primary_id) on delete cascade
);

create index ia_identity_session_expiry_idx
    on identity_access.ia_identity_session (status, idle_expires_at, absolute_expires_at);
create index ia_idempotency_expiry_idx
    on identity_access.ia_idempotency_result (expires_at);
create index ia_continuation_expiry_idx
    on identity_access.ia_continuation (expires_at, consumed_at);
create index ia_remote_logout_due_idx
    on identity_access.ia_remote_logout_outbox (state, next_attempt_at);
create index ia_host_replay_expiry_idx
    on identity_access.ia_host_replay (expires_at);
create index ia_spring_session_expiry_idx
    on identity_access.ia_spring_session (expiry_time);
create index ia_spring_session_principal_idx
    on identity_access.ia_spring_session (principal_name);
