# Edge Case Hunter Review Prompt

Invoke the `bmad-review-edge-case-hunter` skill on the planning-document change described below. Review without relying on prior conversation.

## Specification

`_bmad-output/implementation-artifacts/spec-update-planning-docs-approved-course-correction.md`

## Approved source

`_bmad-output/planning-artifacts/sprint-change-proposal-2026-07-16.md`

## Changed artifacts (NO_VCS best-effort diff scope)

- `_bmad-output/planning-artifacts/prds/prd-ScholarSense-bmad-method-2026-07-16/prd.md`
- `_bmad-output/planning-artifacts/architecture/architecture-ScholarSense-bmad-method-2026-07-16/ARCHITECTURE-SPINE.md`
- `_bmad-output/planning-artifacts/ux-designs/ux-ScholarSense-bmad-method-2026-07-16/DESIGN.md`
- `_bmad-output/planning-artifacts/ux-designs/ux-ScholarSense-bmad-method-2026-07-16/EXPERIENCE.md`
- `_bmad-output/planning-artifacts/epics.md`
- `_bmad-output/planning-artifacts/rule-catalog.md`
- `_bmad-output/planning-artifacts/high-risk-action-matrix.md`
- `_bmad-output/planning-artifacts/open-decisions.md`

Walk every dependency boundary, Story split, FR mapping, rule lifecycle, risk-gate branch, unresolved-decision state and report/export timing path. Report only unhandled edge cases, each with artifact path, triggering state, missing behavior, consequence, and proposed correction.
