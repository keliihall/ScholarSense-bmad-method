package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Current account projection; source identifiers and OIDC subject are stored only as keyed tokens. */
public record AuthoritativeAccount(
        UUID accountId,
        String sourceId,
        String externalRefDigest,
        String subjectBindingToken,
        List<String> subjectBindingReadTokens,
        String naturalPersonPrincipalDigest,
        String naturalPersonAuthorityEvidenceDigest,
        AuthoritativeStatus status,
        EffectiveInterval effectiveInterval,
        long sourceVersion,
        long aggregateVersion) {
    public AuthoritativeAccount {
        AuthorityValidation.uuidV7(accountId, "ACCOUNT_ID");
        AuthorityValidation.sourceId(sourceId);
        AuthorityValidation.digest(externalRefDigest, "ACCOUNT_EXTERNAL_REF");
        if (!validBindingToken(subjectBindingToken)) {
            throw new IllegalArgumentException("IDENTITY_SUBJECT_BINDING_TOKEN_INVALID");
        }
        subjectBindingReadTokens = List.copyOf(subjectBindingReadTokens);
        if (subjectBindingReadTokens.isEmpty()
                || !subjectBindingToken.equals(subjectBindingReadTokens.getFirst())
                || subjectBindingReadTokens.stream().anyMatch(
                        token -> !validBindingToken(token))
                || Set.copyOf(subjectBindingReadTokens).size()
                        != subjectBindingReadTokens.size()) {
            throw new IllegalArgumentException("IDENTITY_SUBJECT_BINDING_ROTATION_INVALID");
        }
        if ((naturalPersonPrincipalDigest == null)
                != (naturalPersonAuthorityEvidenceDigest == null)) {
            throw new IllegalArgumentException(
                    "IDENTITY_NATURAL_PERSON_AUTHORITY_EVIDENCE_INCOMPLETE");
        }
        if (naturalPersonPrincipalDigest != null) {
            AuthorityValidation.digest(
                    naturalPersonPrincipalDigest,
                    "NATURAL_PERSON_PRINCIPAL");
            AuthorityValidation.digest(
                    naturalPersonAuthorityEvidenceDigest,
                    "NATURAL_PERSON_AUTHORITY_EVIDENCE");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(effectiveInterval, "effectiveInterval");
        AuthorityValidation.positive(sourceVersion, "SOURCE");
        AuthorityValidation.positive(aggregateVersion, "AGGREGATE");
    }

    public AuthoritativeAccount(
            UUID accountId,
            String sourceId,
            String externalRefDigest,
            String subjectBindingToken,
            List<String> subjectBindingReadTokens,
            AuthoritativeStatus status,
            EffectiveInterval effectiveInterval,
            long sourceVersion,
            long aggregateVersion) {
        this(
                accountId,
                sourceId,
                externalRefDigest,
                subjectBindingToken,
                subjectBindingReadTokens,
                null,
                null,
                status,
                effectiveInterval,
                sourceVersion,
                aggregateVersion);
    }

    public AuthoritativeAccount(
            UUID accountId,
            String sourceId,
            String externalRefDigest,
            String subjectBindingToken,
            AuthoritativeStatus status,
            EffectiveInterval effectiveInterval,
            long sourceVersion,
            long aggregateVersion) {
        this(
                accountId,
                sourceId,
                externalRefDigest,
                subjectBindingToken,
                List.of(subjectBindingToken),
                null,
                null,
                status,
                effectiveInterval,
                sourceVersion,
                aggregateVersion);
    }

    private static boolean validBindingToken(String value) {
        return value != null
                && value.matches("[a-z]+_v1_k[0-9]+_[0-9a-f]{64}");
    }
}
