package cn.edu.suda.scholarsense.identityaccess.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FieldProjectionEvaluatorTest {
    private static final Instant START = Instant.parse("2026-07-17T08:00:00Z");
    private static final Instant END = Instant.parse("2026-07-18T08:00:00Z");
    private static final RoleFieldPolicyCatalog POLICY = RoleFieldPolicyCatalog.approved();
    private static final FieldProjectionCatalog CATALOG = FieldProjectionCatalog.approved();
    private final FieldProjectionEvaluator evaluator = new FieldProjectionEvaluator();

    @Test
    void qualitySnapshotUsesObjectScopedLeafClassesAndRequiresOwnedSource() {
        ProjectionObjectSchema qualitySnapshotSchema = CATALOG.objectSchema(
                ProjectionObjectClass.QUALITY_SNAPSHOT).orElseThrow();
        assertEquals(42, qualitySnapshotSchema.fieldNames().size());
        EnumMap<FieldClass, Integer> classCounts = new EnumMap<>(FieldClass.class);
        qualitySnapshotSchema.fieldNames().forEach(path -> classCounts.merge(
                CATALOG.field(ProjectionObjectClass.QUALITY_SNAPSHOT, path)
                        .orElseThrow().fieldClass(),
                1,
                Integer::sum));
        assertEquals(
                Map.of(
                        FieldClass.BASIC, 21,
                        FieldClass.EVIDENCE, 2,
                        FieldClass.GOVERNANCE, 4,
                        FieldClass.TECHNICAL, 15),
                classCounts);
        assertEquals(
                FieldClass.BASIC,
                CATALOG.field(
                        ProjectionObjectClass.AUDIT_SEARCH_RECORD,
                        "retentionScheduleVersion").orElseThrow().fieldClass());
        assertEquals(
                FieldClass.GOVERNANCE,
                CATALOG.field(
                        ProjectionObjectClass.QUALITY_SNAPSHOT,
                        "retentionScheduleVersion").orElseThrow().fieldClass());

        CompositeAuthorizationDecision authorization = authorization(Set.of(RolePackage.R6), Set.of());
        FieldProjectionDecision owned = evaluator.evaluate(
                authorization,
                FieldProjectionEvidence.qualitySnapshot(START, true),
                Set.of(
                        "snapshotId", "retentionScheduleVersion", "metricResults[].formulaId",
                        "studentOfficialRef"),
                CATALOG,
                POLICY);
        FieldProjectionDecision unowned = evaluator.evaluate(
                authorization,
                FieldProjectionEvidence.qualitySnapshot(START, false),
                Set.of("snapshotId"),
                CATALOG,
                POLICY);

        assertTrue(owned.allowed());
        assertEquals(Visibility.CLEAR, owned.visibilityFor("snapshotId"));
        assertEquals(Visibility.CLEAR, owned.visibilityFor("retentionScheduleVersion"));
        assertEquals(Visibility.CLEAR, owned.visibilityFor("metricResults[].formulaId"));
        assertEquals(Visibility.HIDDEN, owned.visibilityFor("studentOfficialRef"));
        assertFalse(unowned.allowed());
        assertEquals("OWNED_SOURCE_REQUIRED", unowned.reasonCode());
    }

    @Test
    void applies_the_strictest_role_and_keeps_catalog_order() {
        CompositeAuthorizationDecision authorization = authorization(
                Set.of(RolePackage.R1, RolePackage.R7), Set.of());
        FieldProjectionDecision result = evaluator.evaluate(
                authorization,
                FieldProjectionEvidence.auditSearch(
                        "audit.search-technical-metadata", START),
                new LinkedHashSet<>(Set.of("traceId", "actorDisplayRef", "recordId", "unknownSecret")),
                CATALOG,
                POLICY);

        assertTrue(result.allowed());
        assertEquals(Visibility.CLEAR, result.visibilityFor("recordId"));
        assertEquals(Visibility.HIDDEN, result.visibilityFor("actorDisplayRef"));
        assertEquals(Visibility.MASKED, result.visibilityFor("traceId"));
        assertEquals(Visibility.HIDDEN, result.visibilityFor("unknownSecret"));
        assertEquals(Set.of("recordId", "traceId"), result.serializableFieldNames());
        assertEquals(
                List.of("recordId", "actorDisplayRef", "traceId"),
                result.fieldDecisions().stream().map(ProjectedFieldDecision::fieldName).toList());
        assertEquals("[MASKED-TECHNICAL]", result.decisionFor("traceId").orElseThrow().maskedValue().orElseThrow());
    }

    @Test
    void r5_intersects_closed_universe_owner_allowlist_authorization_and_grant() {
        Set<String> authorizedConditional = Set.of(
                "studentContactPhone", "requestedServiceCode", "referralSummary", "resultSummary");
        CompositeAuthorizationDecision authorization = authorization(Set.of(RolePackage.R5), authorizedConditional);
        FieldProjectionEvidence evidence = FieldProjectionEvidence.transfer(
                "transfer.process",
                START,
                Optional.of(new EffectiveWindow(START, END)),
                Set.of("studentContactPhone", "requestedServiceCode", "referralSummary", "resultSummary"),
                Optional.of(Set.of("studentContactPhone", "requestedServiceCode", "resultSummary")));

        FieldProjectionDecision result = evaluator.evaluate(
                authorization,
                evidence,
                Set.of(
                        "transferOrderId", "studentContactPhone", "studentContactEmail",
                        "requestedServiceCode", "referralSummary", "resultSummary", "diagnosisText"),
                CATALOG,
                POLICY);

        assertTrue(result.allowed());
        assertEquals(Visibility.CLEAR, result.visibilityFor("transferOrderId"));
        assertEquals(Visibility.CLEAR, result.visibilityFor("studentContactPhone"));
        assertEquals(Visibility.CLEAR, result.visibilityFor("requestedServiceCode"));
        assertEquals(Visibility.HIDDEN, result.visibilityFor("studentContactEmail"));
        assertEquals(Visibility.HIDDEN, result.visibilityFor("referralSummary"));
        assertEquals(Visibility.CLEAR, result.visibilityFor("resultSummary"));
        assertEquals(Visibility.HIDDEN, result.visibilityFor("diagnosisText"));

        FieldProjectionDecision atEnd = evaluator.evaluate(
                authorization,
                evidence.withServerNow(END),
                Set.of("studentContactPhone"),
                CATALOG,
                POLICY);
        assertFalse(atEnd.allowed());
        assertEquals("TASK_WINDOW_INACTIVE", atEnd.reasonCode());
        assertTrue(atEnd.serializableFieldNames().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("transferWindowBoundaries")
    void r5_uses_an_exact_half_open_task_window(Instant serverNow, boolean expectedAllowed) {
        CompositeAuthorizationDecision authorization = authorization(
                Set.of(RolePackage.R5), Set.of("studentContactPhone"));
        FieldProjectionEvidence evidence = FieldProjectionEvidence.transfer(
                "transfer.process",
                serverNow,
                Optional.of(new EffectiveWindow(START, END)),
                Set.of("studentContactPhone"),
                Optional.of(Set.of("studentContactPhone")));

        FieldProjectionDecision result = evaluator.evaluate(
                authorization,
                evidence,
                Set.of("studentContactPhone"),
                CATALOG,
                POLICY);

        assertEquals(expectedAllowed, result.allowed());
        assertEquals(
                expectedAllowed ? Visibility.CLEAR : Visibility.HIDDEN,
                result.visibilityFor("studentContactPhone"));
        if (!expectedAllowed) {
            assertEquals("TASK_WINDOW_INACTIVE", result.reasonCode());
            assertTrue(result.fieldDecisions().isEmpty());
        }
    }

    @Test
    void invalid_unicode_overlong_nested_null_and_duplicate_keys_never_change_schema_order() {
        List<String> expectedOrder = List.of("recordId", "actorDisplayRef", "traceId");
        CompositeAuthorizationDecision authorization = authorization(
                Set.of(RolePackage.R1, RolePackage.R7), Set.of());

        FieldProjectionDecision forward = evaluator.evaluate(
                authorization,
                FieldProjectionEvidence.auditSearch("audit.search-technical-metadata", START),
                requestedFieldsWithInvalidKeys(false),
                CATALOG,
                POLICY);
        FieldProjectionDecision reversed = evaluator.evaluate(
                authorization,
                FieldProjectionEvidence.auditSearch("audit.search-technical-metadata", START),
                requestedFieldsWithInvalidKeys(true),
                CATALOG,
                POLICY);

        assertEquals(expectedOrder,
                forward.fieldDecisions().stream().map(ProjectedFieldDecision::fieldName).toList());
        assertEquals(forward.fieldDecisions(), reversed.fieldDecisions());
        assertEquals(Set.of("recordId", "traceId"), forward.serializableFieldNames());
        assertEquals(Visibility.HIDDEN, forward.visibilityFor(null));
        assertEquals(Visibility.HIDDEN, forward.visibilityFor("记录编号"));
        assertEquals(Visibility.HIDDEN, forward.visibilityFor("x".repeat(10_000)));
    }

    @Test
    void delegation_unknown_and_case_variant_fields_never_expand_r5_clear_access() {
        CompositeAuthorizationDecision authorization = authorization(
                Set.of(RolePackage.R5), Set.of("studentContactPhone"));
        FieldProjectionEvidence evidence = FieldProjectionEvidence.transfer(
                "transfer.process",
                START,
                Optional.of(new EffectiveWindow(START, END)),
                Set.of("studentContactPhone"),
                Optional.of(Set.of("StudentContactPhone", "unknownSecret", "学生联系电话")));

        FieldProjectionDecision result = evaluator.evaluate(
                authorization,
                evidence,
                Set.of("studentContactPhone"),
                CATALOG,
                POLICY);

        assertTrue(result.allowed());
        assertEquals(Visibility.HIDDEN, result.visibilityFor("studentContactPhone"));
        assertTrue(result.serializableFieldNames().isEmpty());
    }

    @Test
    void projection_performs_one_requested_membership_check_per_schema_field() {
        ProjectionObjectSchema schema = CATALOG.objectSchema(ProjectionObjectClass.AUDIT_SEARCH_RECORD)
                .orElseThrow();
        CountingRequestedFields requestedFields = new CountingRequestedFields(
                new LinkedHashSet<>(schema.fieldNames()));

        FieldProjectionDecision result = evaluator.evaluate(
                authorization(Set.of(RolePackage.R1), Set.of()),
                FieldProjectionEvidence.auditSearch("audit.search-business-metadata", START),
                requestedFields,
                CATALOG,
                POLICY);

        assertTrue(result.allowed());
        assertEquals(schema.fieldNames().size(), requestedFields.containsCalls());
        assertEquals(schema.fieldNames(),
                result.fieldDecisions().stream().map(ProjectedFieldDecision::fieldName).toList());
    }

    @Test
    void r2_requires_current_work_item_and_r6_owned_source_can_clear_identity() {
        FieldProjectionDecision r2 = evaluator.evaluate(
                authorization(Set.of(RolePackage.R2), Set.of()),
                FieldProjectionEvidence.auditSearch("audit.search-business-metadata", START)
                        .withCurrentWorkItem(false),
                Set.of("recordId"),
                CATALOG,
                POLICY);
        assertFalse(r2.allowed());
        assertEquals("CURRENT_WORK_ITEM_REQUIRED", r2.reasonCode());

        CompositeAuthorizationDecision r6Authorization = authorization(
                Set.of(RolePackage.R6), Set.of("subjectOfficialRef"));
        FieldProjectionDecision owned = evaluator.evaluate(
                r6Authorization,
                FieldProjectionEvidence.subjectMappingRepair(START, true),
                Set.of("subjectOfficialRef", "evidenceBody"),
                CATALOG,
                POLICY);
        assertEquals(Visibility.CLEAR, owned.visibilityFor("subjectOfficialRef"));
        assertEquals(Visibility.HIDDEN, owned.visibilityFor("evidenceBody"));

        FieldProjectionDecision notOwned = evaluator.evaluate(
                r6Authorization,
                FieldProjectionEvidence.subjectMappingRepair(START, false),
                Set.of("subjectOfficialRef"),
                CATALOG,
                POLICY);
        assertEquals(Visibility.MASKED, notOwned.visibilityFor("subjectOfficialRef"));
    }

    @Test
    void denied_authorization_and_unknown_object_fail_closed() {
        CompositeAuthorizationDecision denied = new CompositeAuthorizationDecision(
                CompositeAuthorizationOutcome.DENY,
                "SCOPE_NOT_PROVEN",
                Set.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                RoleFieldPolicyCatalog.POLICY_VERSION,
                versions(),
                7,
                START,
                new AuthorizationDecisionToken(versions(), 7, RoleFieldPolicyCatalog.POLICY_VERSION));
        assertFalse(evaluator.evaluate(
                denied,
                FieldProjectionEvidence.auditSearch("audit.search-business-metadata", START),
                Set.of("recordId"),
                CATALOG,
                POLICY).allowed());

        FieldProjectionDecision unknown = evaluator.evaluate(
                authorization(Set.of(RolePackage.R3), Set.of()),
                FieldProjectionEvidence.unknown(START),
                Set.of("recordId"),
                CATALOG,
                POLICY);
        assertFalse(unknown.allowed());
        assertEquals("PROJECTION_OBJECT_UNKNOWN", unknown.reasonCode());
    }

    private static CompositeAuthorizationDecision authorization(
            Set<RolePackage> roles, Set<String> conditionalFields) {
        EnumMap<FieldClass, Visibility> summary = new EnumMap<>(FieldClass.class);
        for (RolePackage role : roles) {
            for (Map.Entry<FieldClass, Visibility> entry : POLICY.fieldVisibility(role).entrySet()) {
                summary.merge(entry.getKey(), entry.getValue(), Visibility::strictest);
            }
        }
        return new CompositeAuthorizationDecision(
                CompositeAuthorizationOutcome.ALLOW,
                "ROLE_SCOPE_MATCHED",
                roles,
                Set.of(ScopeAnchor.TECHNICAL_OBJECT),
                summary,
                conditionalFields,
                RoleFieldPolicyCatalog.POLICY_VERSION,
                versions(),
                7,
                START,
                new AuthorizationDecisionToken(versions(), 7, RoleFieldPolicyCatalog.POLICY_VERSION));
    }

    private static AuthorizationEvidenceVersions versions() {
        return new AuthorizationEvidenceVersions(1, 2, 3, 4, 5);
    }

    private static Stream<Arguments> transferWindowBoundaries() {
        return Stream.of(
                Arguments.of(START.minusNanos(1), false),
                Arguments.of(START, true),
                Arguments.of(END.minusNanos(1), true),
                Arguments.of(END, false));
    }

    private static Set<String> requestedFieldsWithInvalidKeys(boolean reverse) {
        List<String> values = new ArrayList<>(List.of(
                "traceId",
                "recordId",
                "actorDisplayRef",
                "recordId",
                "",
                "记录编号",
                "reco\u0301rdId",
                "ＡctorDisplayRef",
                "🙂",
                "x".repeat(10_000),
                "{\"recordId\":\"injected\"}",
                "recordId[0]",
                "unknownSecret"));
        values.add(null);
        if (reverse) {
            Collections.reverse(values);
        }
        return new LinkedHashSet<>(values);
    }

    private static final class CountingRequestedFields extends AbstractSet<String> {
        private final Set<String> delegate;
        private int containsCalls;

        private CountingRequestedFields(Set<String> delegate) {
            this.delegate = Set.copyOf(delegate);
        }

        @Override
        public Iterator<String> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean contains(Object value) {
            containsCalls++;
            return delegate.contains(value);
        }

        private int containsCalls() {
            return containsCalls;
        }
    }
}
