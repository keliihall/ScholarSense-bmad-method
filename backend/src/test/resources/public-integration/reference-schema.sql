create table ${schema}.queued_delivery (
  aggregate_type text not null,
  aggregate_id text not null,
  channel_id text not null,
  contract_version text not null,
  generation_key text not null,
  operation text not null,
  priority integer not null default 0,
  accepted_at timestamptz not null,
  provenance_mode text not null check (provenance_mode in ('aggregate-stream','intent-command')),
  source_event_id text,
  source_fact_id text,
  source_fact_digest text,
  event_payload_digest text,
  route_sequence bigint,
  delivery_intent_id text,
  request_digest text,
  source_aggregate_version bigint not null check (source_aggregate_version between 1 and 9007199254740991),
  disposition text not null check (disposition in ('waiting','activated','superseded','terminal-cancelled')),
  primary key (aggregate_type, aggregate_id, channel_id, contract_version, generation_key),
  check ((provenance_mode='aggregate-stream'
      and source_event_id is not null and source_fact_id is not null
      and source_fact_digest is not null and event_payload_digest is not null
      and route_sequence between 1 and 9007199254740991
      and delivery_intent_id is null and request_digest is null)
    or (provenance_mode='intent-command'
      and source_event_id is null and source_fact_id is null
      and source_fact_digest is null and event_payload_digest is null
      and route_sequence is null
      and delivery_intent_id is not null and request_digest is not null))
);

create table ${schema}.current_delivery (
  aggregate_type text not null,
  aggregate_id text not null,
  channel_id text not null,
  contract_version text not null,
  current_generation_key text not null,
  current_delivery_sequence bigint not null check (current_delivery_sequence between 1 and 9007199254740991),
  status text not null check (status in ('pending','retrying','confirmed','failed')),
  fencing_token bigint not null check (fencing_token >= 1),
  last_confirmed_source_aggregate_version bigint,
  last_confirmed_delivery_sequence bigint,
  last_confirmed_generation_key text,
  current_external_task_ref text,
  current_provider_receipt_digest text,
  recoverable boolean not null default false,
  primary key (aggregate_type, aggregate_id, channel_id, contract_version)
);

create table ${schema}.generation_ledger (
  aggregate_type text not null,
  aggregate_id text not null,
  channel_id text not null,
  contract_version text not null,
  generation_key text not null,
  delivery_sequence bigint not null check (delivery_sequence between 1 and 9007199254740991),
  source_aggregate_version bigint not null,
  external_task_ref text,
  external_notification_id text,
  provider_receipt_digest text,
  confirmed_at timestamptz,
  sealed boolean not null default false,
  final_status text,
  retention_until timestamptz,
  tokenized_external_ref_digest text,
  terminal_tombstone boolean not null default false,
  primary key (aggregate_type, aggregate_id, channel_id, contract_version, generation_key),
  unique (aggregate_type, aggregate_id, channel_id, contract_version, delivery_sequence)
);

create table ${schema}.transition_ledger (
  transition_id bigint generated always as identity primary key,
  aggregate_type text not null,
  aggregate_id text not null,
  channel_id text not null,
  contract_version text not null,
  generation_key text not null,
  from_status text,
  to_status text not null,
  fencing_token bigint not null,
  reason_code text not null,
  occurred_at timestamptz not null
);

create table ${schema}.delivery_attempt (
  aggregate_type text not null,
  aggregate_id text not null,
  channel_id text not null,
  contract_version text not null,
  generation_key text not null,
  attempt_no bigint not null,
  worker_id text not null,
  fencing_token bigint not null,
  claimed_at timestamptz not null,
  lease_until timestamptz not null,
  outcome text,
  primary key (aggregate_type, aggregate_id, channel_id, contract_version, generation_key, attempt_no)
);

create table ${schema}.outbox (
  aggregate_type text not null,
  aggregate_id text not null,
  channel_id text not null,
  contract_version text not null,
  generation_key text not null,
  available_at timestamptz not null,
  claimed_by text,
  lease_until timestamptz,
  fencing_token bigint not null,
  delivered_at timestamptz,
  primary key (aggregate_type, aggregate_id, channel_id, contract_version, generation_key)
);

create table ${schema}.provider_lineage_map (
  tenant_token text not null,
  capability_family text not null,
  tokenized_work_item_key text not null,
  provider_lineage_key text not null,
  external_task_ref text,
  tokenized_external_ref_digest text,
  final_status text,
  retention_until timestamptz,
  terminal_tombstone boolean not null default false,
  primary key (tenant_token, capability_family, tokenized_work_item_key)
);

create table ${schema}.source_terminal_fence (
  tenant_token text not null,
  aggregate_type text not null,
  tokenized_aggregate_id text not null,
  tokenized_work_item_key text not null,
  pending_gap_version bigint,
  applied_terminal_version bigint,
  event_digest text,
  fence_epoch bigint not null,
  primary key (tenant_token, aggregate_type, tokenized_aggregate_id, tokenized_work_item_key)
);

create table ${schema}.lane_cutover_fence (
  provider_lineage_key text not null,
  capability_family text not null,
  contract_major integer not null,
  lane_state text not null check (lane_state in ('active','draining','switched','terminal-aborted')),
  lane_epoch bigint not null,
  active_contract_version text not null,
  target_contract_version text,
  primary key (provider_lineage_key, capability_family, contract_major)
);

create table ${schema}.cutover_holding_delivery (
  provider_lineage_key text not null,
  capability_family text not null,
  lane_epoch bigint not null,
  generation_key text not null,
  source_fact_id text,
  provider_effect_key text not null,
  accepted_at timestamptz not null,
  disposition text not null check (disposition in ('holding','released','terminal-cancelled')),
  primary key (provider_lineage_key, capability_family, lane_epoch, generation_key)
);

create table ${schema}.idempotency_result (
  scope_token text not null,
  idempotency_key text not null,
  request_digest text not null,
  receipt_bytes bytea not null,
  created_at timestamptz not null,
  retain_until timestamptz not null,
  primary key (scope_token, idempotency_key)
);

create table ${schema}.callback_inbox (
  source text not null,
  event_id text not null,
  payload_digest text not null,
  technical_result bytea not null,
  applied_at timestamptz not null,
  primary key (source, event_id)
);

create table ${schema}.callback_nonce (
  workload_sub text not null,
  key_id text not null,
  nonce_digest text not null,
  contract_version text not null,
  first_seen_at timestamptz not null,
  retain_until timestamptz not null,
  primary key (workload_sub, key_id, nonce_digest, contract_version)
);

create table ${schema}.reconciliation_state (
  job_kind text not null,
  route_key text not null,
  from_route_watermark bigint not null,
  to_route_watermark bigint not null,
  from_source_aggregate_version bigint,
  to_source_aggregate_version bigint,
  lease_owner text,
  lease_until timestamptz,
  fencing_token bigint not null,
  result_code text,
  trace_id text not null,
  primary key (job_kind, route_key)
);

create table ${schema}.route_watermark (
  tenant_token text not null,
  source text not null,
  aggregate_type text not null,
  aggregate_id text not null,
  channel_id text not null,
  contract_major integer not null,
  current_route_sequence bigint not null,
  last_source_aggregate_version bigint not null,
  last_event_id text not null,
  last_event_payload_digest text not null,
  primary key (tenant_token, source, aggregate_type, aggregate_id, channel_id, contract_major)
);
