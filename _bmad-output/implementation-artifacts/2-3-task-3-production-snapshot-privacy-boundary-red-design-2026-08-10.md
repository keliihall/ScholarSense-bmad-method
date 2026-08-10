# Story 2.3 Task 3 — production snapshot text privacy RED design

Date: 2026-08-10

Scope: production receive/seal/impact staging only; tests and design in this handoff

Authority: `DEC-019 / AUTH-2026-08-08-001` and `DEC-019 §4.9 / AUTH-2026-08-09-001`

## Contract reading

The approved QualitySnapshot projection says the object stores no student identifier, contact
data, sensitive body, or natural-language body. The Story also requires snapshot, event, log, and
trace evidence to contain no student plaintext. QSHM-1.0.0 separately approves a generic Unicode
scalar transport, exact canonical escaping, arbitrary `watermark` text up to 512 scalars, and
arbitrary unique `impactScopeCodes[]` values up to 64 scalars. Those QSHM bytes are a hash-material
transport contract, not approval for production callers to persist arbitrary subject text.

QMDP has no approved mapping that declares its `category` values (for example `privacy` or
`coverage`) to be `impactScopeCodes`. Inventing such an enum would therefore exceed the approved
contract. QMDP does approve exact `metricId` and `formulaId` identities. The embedded policy has 12
unique metric IDs with maximum length 34, while six approved formula IDs exceed QSHM's frozen
64-scalar impact-code maximum (the longest is 71). The minimum non-invented production binding is
therefore the exact `metricId` set applicable to the batch's source.

## Frozen production boundary

| Value | Accepted production forms | Rejected examples | Authority source |
|---|---|---|---|
| seal watermark | exact `lowercase(sourceId)@YYYY-MM-DD`, where the suffix is a real Gregorian date; or exact lowercase `sha256:<64 hex>` | `student-20260001`, free text, U+0000, another source prefix, uppercase/noncanonical digest, impossible date | Story no-plaintext requirement plus already approved source identity and digest form |
| impact scope | exact `metricId` returned by embedded `iq_qmdp_ordered_definitions(batch.sourceId)` | caller vocabulary such as `PRIMARY_KEY`/`PRIVACY`, another source's non-applicable metric, student text, free text, U+0000 | approved QMDP source applicability and metric identity |

The date suffix is intentionally not equated with `cutoffAt`: no approved contract defines that
relationship. The opaque SHA-256 alternative is intentionally not source-prefixed; its opacity is
the privacy property. Changing either semantic requires a new approved successor rather than an
implementation guess.

## Enforcement layers

1. Java rejects unsafe watermark values when a receiving `DataBatch` is sealed, where the batch's
   authoritative source is available. It rejects non-code-shaped impact values in
   `RecordQualityImpactScopeCommand`; PostgreSQL remains authoritative for exact source/QMDP
   membership.
2. PostgreSQL owner-only internal validators operate on the original `bytea` before conversion, so
   U+0000 and invalid/non-ASCII text fail closed. `iq_seal_data_batch` invokes the watermark
   validator before its batch update. `iq_record_batch_quality_impact_scope` invokes the impact
   validator before insert/conflict handling.
3. Invalid watermark uses the existing stable
   `INGESTION_QUALITY_SEAL_EVIDENCE_INVALID`. Invalid impact uses the precise new stable
   `INGESTION_QUALITY_IMPACT_SCOPE_INVALID`, which must be added to JDBC failure translation.
4. Neither helper is executable by `PUBLIC` or workload roles. They run only inside the existing
   owner-controlled `SECURITY DEFINER` commands (the migration owner may invoke them for evidence).

## Preservation boundary

Do not edit the QSHM schemas, profile, golden/negative fixtures, additive lock, Java
`QualityCanonicalJson`/`QualitySnapshotCanonicalizer`, or PostgreSQL `iq_qshm_canonical` semantics.
The Task 2.4 U+0000 distinction and arbitrary Unicode canonicalization remain valid generic
conformance evidence. Production rejects those values before materialization; it does not weaken or
rewrite the canonicalizer.

## RED evidence added

- `ProductionSnapshotPrivacyBoundaryTest`: rejects student/free-text/NUL/noncanonical watermarks and
  impact values, accepts both watermark forms and controlled metric-code shape, and pins the frozen
  QSHM vector digest plus generic U+0000 canonicalization.
- `QualitySnapshotProductionPrivacyPostgreSqlIT`: requires owner-only internal validators, proves
  their position before persistence, exercises both accepted watermark forms, exact source-QMDP
  metric membership, negative plaintext/NUL/cross-source cases, and confirms generic
  `iq_qshm_canonical` does not depend on the production validators.
- `run_audit_postgresql_tests.sh`: registers the new PostgreSQL test for clean/upgrade schema runs.

No production migration or Java implementation is changed by this RED handoff, and no Maven or
PostgreSQL execution is claimed.

## Java GREEN checkpoint

The production-only boundary is now implemented at `DataBatch.seal`: a watermark must be the
batch source ID lowercased plus an exact real `YYYY-MM-DD`, or an opaque lowercase SHA-256 value.
`RecordQualityImpactScopeCommand` now accepts only controlled-code shape; exact source/QMDP
membership remains the PostgreSQL owner's responsibility. JDBC failure translation includes the
exact `INGESTION_QUALITY_IMPACT_SCOPE_INVALID` stable code.

All Java fixtures that actually reach `DataBatch.seal` use a valid opaque SHA-256 watermark. The
remaining `wm-7` values are deliberately confined to generic `BatchManifest` constructor-negative
tests, and `wm-test-1` appears only as a production-seal rejection vector. Generic QSHM Unicode and
U+0000 vectors and their frozen digest remain unchanged.

The exact V14 helper/call/ACL candidate is recorded separately in
`2-3-task-3-production-snapshot-privacy-v14-candidate.sql`; it was integrated only after the
concurrent V14 work was explicitly released. No Maven or PostgreSQL execution is claimed at this
checkpoint. A narrow `javac` attempt was unavailable because the host exposes no Java runtime, so
verification is source-static only.
