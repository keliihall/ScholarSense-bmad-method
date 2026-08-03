package cn.edu.suda.scholarsense.identityaccess.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FieldProjectionApiContractTest {
    @Test
    void request_composes_the_current_authorization_decision_and_owner_evidence() {
        Set<String> ownerAllowlist = new java.util.HashSet<>(Set.of("studentContactPhone"));
        FieldProjectionRequest request = new FieldProjectionRequest(
                authorizationRequest(),
                FieldProjectionObjectClass.TRANSFER_ORDER,
                new FieldProjectionObjectEvidence(
                        "transfer.process",
                        Instant.parse("2026-07-17T08:00:00Z"),
                        Optional.of(new FieldProjectionTaskWindow(
                                Instant.parse("2026-07-17T08:00:00Z"),
                                Instant.parse("2026-07-18T08:00:00Z"))),
                        true,
                        true,
                        false,
                        ownerAllowlist,
                        Optional.of(Set.of("studentContactPhone")),
                        "key-state-v1"),
                List.of(new FieldProjectionValueReference(
                        "studentContactPhone",
                        "C",
                        "string",
                        new SensitiveValueReference(
                                "VALUE-REF-0001", "CONTACT", "kms/contact", "key-v4"))));

        ownerAllowlist.clear();
        assertEquals(Set.of("studentContactPhone"), request.objectEvidence().fieldAllowlist());
        assertEquals("TRANSFER_ORDER", request.authorizationRequest().objectClass());
        assertTrue(FieldProjectionPort.class.isInterface());
    }

    @Test
    void public_values_and_results_fail_closed_on_invalid_shape() {
        assertThrows(IllegalArgumentException.class, () -> new SensitiveValueReference(
                "raw student value", "CONTACT", "kms/contact", "key-v4"));
        assertThrows(IllegalArgumentException.class, () -> new FieldProjectionValueReference(
                "clientInjected", "UNKNOWN", "string",
                new SensitiveValueReference("VALUE-REF-0001", "CONTACT", "kms/contact", "key-v4")));
        assertThrows(IllegalArgumentException.class, () -> new FieldProjectionFieldResult(
                "studentContactPhone",
                FieldVisibility.HIDDEN,
                Optional.of(new SensitiveValueReference(
                        "VALUE-REF-0001", "CONTACT", "kms/contact", "key-v4")),
                Optional.empty()));
    }

    @Test
    void requestRejectsAuthorizationForAnotherObjectClassOrPurpose() {
        FieldProjectionObjectEvidence transferEvidence = new FieldProjectionObjectEvidence(
                "transfer.process",
                Instant.parse("2026-07-17T08:00:00Z"),
                Optional.empty(), true, true, false, Set.of(), Optional.empty(),
                "key-state-v1");
        List<FieldProjectionValueReference> values = List.of(new FieldProjectionValueReference(
                "studentContactPhone", "C", "string",
                new SensitiveValueReference(
                        "VALUE-REF-0001", "CONTACT", "kms/contact", "key-v4")));

        IllegalArgumentException objectMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> new FieldProjectionRequest(
                        authorizationRequest(), FieldProjectionObjectClass.AUDIT_SEARCH_RECORD,
                        transferEvidence, values));
        assertEquals("FIELD_PROJECTION_AUTHORIZATION_OBJECT_MISMATCH", objectMismatch.getMessage());

        CompositeAuthorizationRequest wrongAction = new CompositeAuthorizationRequest(
                "actor-pseudonym-0001", "TRANSFER_ORDER", "transfer.read", "a".repeat(64),
                7, Optional.empty(), Optional.empty(),
                "0123456789abcdef0123456789abcdef");
        IllegalArgumentException purposeMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> new FieldProjectionRequest(
                        wrongAction, FieldProjectionObjectClass.TRANSFER_ORDER,
                        transferEvidence, values));
        assertEquals("FIELD_PROJECTION_AUTHORIZATION_PURPOSE_MISMATCH", purposeMismatch.getMessage());
    }

    @Test
    void null_empty_unicode_overlong_and_nested_field_keys_are_rejected() {
        List<String> invalidFieldNames = new ArrayList<>(List.of(
                "",
                "a",
                "记录编号",
                "reco\u0301rdId",
                "ＡctorDisplayRef",
                "🙂",
                "x".repeat(65),
                "{\"recordId\":\"injected\"}",
                "recordId[0]",
                "recordId\nforged"));
        invalidFieldNames.add(null);

        for (String fieldName : invalidFieldNames) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> new FieldProjectionValueReference(
                            fieldName,
                            "B",
                            "string",
                            valueReference("VALUE-REF-0001")),
                    String.valueOf(fieldName));
            assertEquals("FIELD_PROJECTION_FIELD_NAME_INVALID", failure.getMessage());
        }
    }

    @Test
    void duplicate_output_fields_fail_before_a_safe_document_can_be_created() {
        FieldProjectionResult duplicate = new FieldProjectionResult(
                true,
                "FIELD_PROJECTION_ALLOWED",
                List.of(
                        new FieldProjectionFieldResult(
                                "recordId", FieldVisibility.CLEAR,
                                Optional.of("AUDIT-0001"), Optional.empty()),
                        new FieldProjectionFieldResult(
                                "recordId", FieldVisibility.CLEAR,
                                Optional.of("AUDIT-0002"), Optional.empty())));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> FieldProjectionSafeDocument.from(duplicate));
        assertEquals("FIELD_PROJECTION_DOCUMENT_FIELD_DUPLICATE", failure.getMessage());
    }

    @Test
    void safe_document_omits_hidden_fields_and_preserves_the_projector_order_for_both_sinks() {
        FieldProjectionResult projected = new FieldProjectionResult(
                true,
                "FIELD_PROJECTION_ALLOWED",
                List.of(
                        new FieldProjectionFieldResult(
                                "recordId", FieldVisibility.CLEAR,
                                Optional.of("审计-🙂"), Optional.empty()),
                        new FieldProjectionFieldResult(
                                "actorDisplayRef", FieldVisibility.HIDDEN,
                                Optional.empty(), Optional.empty()),
                        new FieldProjectionFieldResult(
                                "traceId", FieldVisibility.MASKED,
                                Optional.empty(), Optional.of("[MASKED-TECHNICAL]"))));

        FieldProjectionSafeDocument document = FieldProjectionSafeDocument.from(projected);

        assertEquals(List.of("recordId", "traceId"), List.copyOf(document.jsonValues().keySet()));
        assertEquals("审计-🙂", document.jsonValues().get("recordId"));
        assertEquals("[MASKED-TECHNICAL]", document.jsonValues().get("traceId"));
        assertEquals(document.jsonValues(), document.exportValues());
    }

    private static CompositeAuthorizationRequest authorizationRequest() {
        return new CompositeAuthorizationRequest(
                "actor-pseudonym-0001",
                "TRANSFER_ORDER",
                "transfer.process",
                "a".repeat(64),
                7,
                Optional.empty(),
                Optional.empty(),
                "0123456789abcdef0123456789abcdef");
    }

    private static SensitiveValueReference valueReference(String token) {
        return new SensitiveValueReference(token, "BASIC", "kms/audit", "key-v4");
    }
}
