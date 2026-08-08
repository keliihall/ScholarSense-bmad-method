package cn.edu.suda.scholarsense.subjectregistry.adapters.outbound;

import cn.edu.suda.scholarsense.subjectregistry.application.NormalizationProfile;
import cn.edu.suda.scholarsense.subjectregistry.application.SourceIdentifierPolicyPort;
import cn.edu.suda.scholarsense.subjectregistry.application.SourceIdentifierRule;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryApplicationException;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierType;
import java.util.Map;

/** Compile-time projection of the digest-locked SMP-1.0.0 adapter set. */
public final class FrozenSourceIdentifierPolicy implements SourceIdentifierPolicyPort {
    public static final String INPUT_CONTRACT = "SUBJECT-IDENTIFIER-ADAPTER-1.0.0";
    private static final Map<Key, SourceIdentifierRule> RULES = Map.ofEntries(
            rule("SRC-P0-STUDENT-001", IdentifierType.STUDENT_NUMBER,
                    "学籍主数据 owner", NormalizationProfile.UPPERCASE_PRESERVE_LEADING_ZERO_V1, true),
            rule("SRC-P0-CARD-001", IdentifierType.CARD_NUMBER,
                    "一卡通数据 owner", NormalizationProfile.UPPERCASE_PRESERVE_LEADING_ZERO_V1, false),
            rule("SRC-P1-NETWORK-001", IdentifierType.CAMPUS_NETWORK_ACCOUNT,
                    "网络数据 owner", NormalizationProfile.LOWERCASE_PRESERVE_LEADING_ZERO_V1, false),
            rule("SRC-P0-ACCOMMODATION-001", IdentifierType.ACCOMMODATION_STUDENT_NUMBER,
                    "宿管数据 owner + 学工住宿备案 owner",
                    NormalizationProfile.UPPERCASE_PRESERVE_LEADING_ZERO_V1, false),
            rule("SRC-P0-CAMPUS-ACCESS-001", IdentifierType.CAMPUS_ACCESS_CREDENTIAL,
                    "保卫校门数据 owner", NormalizationProfile.UPPERCASE_PRESERVE_LEADING_ZERO_V1, false),
            rule("SRC-P0-DORM-ACCESS-001", IdentifierType.DORM_ACCESS_CREDENTIAL,
                    "宿舍门禁数据 owner", NormalizationProfile.UPPERCASE_PRESERVE_LEADING_ZERO_V1, false));

    @Override
    public SourceIdentifierRule requireApproved(
            String sourceId, String sourceContractVersion, IdentifierType identifierType) {
        if (!INPUT_CONTRACT.equals(sourceContractVersion)) {
            throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_POLICY_VERSION_UNKNOWN");
        }
        SourceIdentifierRule rule = RULES.get(new Key(sourceId, identifierType));
        if (rule == null) {
            throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_POLICY_VERSION_UNKNOWN");
        }
        return rule;
    }

    private static Map.Entry<Key, SourceIdentifierRule> rule(
            String sourceId, IdentifierType type, String owner,
            NormalizationProfile profile, boolean mayIssue) {
        return Map.entry(new Key(sourceId, type),
                SourceIdentifierRule.approved(sourceId, type, owner, profile, mayIssue));
    }

    private record Key(String sourceId, IdentifierType type) {}
}
