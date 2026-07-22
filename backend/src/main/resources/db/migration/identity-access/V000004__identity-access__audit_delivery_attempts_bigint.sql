-- Story 1.4 forward extension: keep indefinite audit relay retries fenced beyond INTEGER capacity.

alter table identity_access.ia_local_audit_outbox
    alter column attempts type bigint;
