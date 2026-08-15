-- The V23 reader-first validator accepted V2 envelopes, but the existing
-- owner outbox table still allowed only event names ending in .v1. Evolve the
-- storage constraint additively so the owner transaction can persist either
-- predecessor or successor events while all payload semantics remain checked
-- by the V23 validator functions.

alter table ingestion_quality.iq_batch_quality_outbox
    drop constraint iq_batch_quality_outbox_event_type_check;

alter table ingestion_quality.iq_batch_quality_outbox
    add constraint iq_batch_quality_outbox_event_type_check check (
        event_type ~ '^scholarsense[.][a-z0-9.-]+[.]v(1|2)$');
