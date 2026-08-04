# ingestion-quality migrations

This owner uses schema `ingestion_quality`, table prefix `iq_`, and independent online/relay roles.
`V000010__ingestion-quality__data_source_catalog_v1.sql` is the first forward migration. It owns
catalog/source/dependency history, validation/evidence, the atomic current pointer, 90-day
idempotency state, module-local audit/outbox and RS-1.0.0 lifecycle fields. It creates no cross-schema
foreign key or query. Published catalog rows and stable identifier reservations are append-only.

The global sequence and clean/upgrade inventory are discovered from the production migration tree;
this document is explanatory and is not runtime evidence.
