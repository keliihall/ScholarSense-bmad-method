-- Story 1.8 review follow-up: persist the audit 1.4 authorization-decision
-- evidence beside the frozen LOCAL-AUDIT-FACT-1.0.0 payload. Historical rows
-- remain null because their dropped successor fields cannot be reconstructed.

alter table identity_access.ia_local_audit_fact
    add column authorization_decision_context jsonb,
    add constraint ia_local_audit_fact_authorization_decision_context_ck check (
        authorization_decision_context is null
        or (
            jsonb_typeof(authorization_decision_context) = 'object'
            and authorization_decision_context ?& array[
                'actorPseudonym', 'rolePackages', 'actionId',
                'objectTokenDigest', 'policyVersion', 'scopeAnchorSummary',
                'objectVersion', 'fieldProjectionSummary', 'result',
                'trustedAt', 'traceId'
            ]
            and authorization_decision_context - array[
                'actorPseudonym', 'rolePackages', 'actionId',
                'objectTokenDigest', 'policyVersion', 'scopeAnchorSummary',
                'objectVersion', 'fieldProjectionSummary', 'result',
                'trustedAt', 'traceId'
            ] = '{}'::jsonb
            and authorization_decision_context ->> 'actorPseudonym'
                ~ '^ast_v1_k[0-9]+_[0-9a-f]{64}$'
            and jsonb_typeof(authorization_decision_context -> 'rolePackages') = 'array'
            and authorization_decision_context ->> 'actionId'
                ~ '^[a-z][a-z0-9.-]+$'
            and authorization_decision_context ->> 'objectTokenDigest'
                ~ '^[0-9a-f]{64}$'
            and authorization_decision_context ->> 'policyVersion' = 'RFP-1.0.0'
            and jsonb_typeof(authorization_decision_context -> 'scopeAnchorSummary') = 'array'
            and (
                authorization_decision_context -> 'objectVersion' = 'null'::jsonb
                or (
                    jsonb_typeof(authorization_decision_context -> 'objectVersion') = 'number'
                    and (authorization_decision_context ->> 'objectVersion')::bigint >= 1
                )
            )
            and jsonb_typeof(authorization_decision_context -> 'fieldProjectionSummary') = 'object'
            and authorization_decision_context -> 'fieldProjectionSummary' ?&
                array['B', 'I', 'C', 'S', 'E', 'N', 'G', 'T']
            and (authorization_decision_context -> 'fieldProjectionSummary')
                - array['B', 'I', 'C', 'S', 'E', 'N', 'G', 'T'] = '{}'::jsonb
            and authorization_decision_context -> 'fieldProjectionSummary' ->> 'B' in ('C', 'M', 'H')
            and authorization_decision_context -> 'fieldProjectionSummary' ->> 'I' in ('C', 'M', 'H')
            and authorization_decision_context -> 'fieldProjectionSummary' ->> 'C' in ('C', 'M', 'H')
            and authorization_decision_context -> 'fieldProjectionSummary' ->> 'S' in ('C', 'M', 'H')
            and authorization_decision_context -> 'fieldProjectionSummary' ->> 'E' in ('C', 'M', 'H')
            and authorization_decision_context -> 'fieldProjectionSummary' ->> 'N' in ('C', 'M', 'H')
            and authorization_decision_context -> 'fieldProjectionSummary' ->> 'G' in ('C', 'M', 'H')
            and authorization_decision_context -> 'fieldProjectionSummary' ->> 'T' in ('C', 'M', 'H')
            and authorization_decision_context ->> 'result'
                in ('ALLOW', 'DENY', 'DEPENDENCY_UNAVAILABLE')
            and authorization_decision_context ->> 'trustedAt'
                ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'
            and authorization_decision_context ->> 'traceId' ~ '^[0-9a-f]{32}$'
        )
    );

comment on column identity_access.ia_local_audit_fact.authorization_decision_context is
    'Audit 1.4 successor evidence for new authorization decisions; null on historical and non-authorization rows.';

grant insert (authorization_decision_context)
    on identity_access.ia_local_audit_fact to scholarsense_identity_online;
grant insert (authorization_decision_context)
    on identity_access.ia_local_audit_fact
    to scholarsense_identity_responsibility_v2_cutover;
