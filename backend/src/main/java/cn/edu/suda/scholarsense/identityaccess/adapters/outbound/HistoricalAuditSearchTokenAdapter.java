package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchQueryToken;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchTokenQuery;
import cn.edu.suda.scholarsense.identityaccess.api.AuditSearchTokenQueryPort;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditTokenPort;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Identity-owned historical key fan-out. It returns opaque tokens, never keys or raw references. */
public final class HistoricalAuditSearchTokenAdapter implements AuditSearchTokenQueryPort {
    private final String profileVersion;
    private final Map<String, IdentityAuditTokenPort> tokenizers;
    private final List<String> retainedKeyVersions;

    public HistoricalAuditSearchTokenAdapter(
            String profileVersion,
            Map<String, IdentityAuditTokenPort> tokenizers,
            List<String> retainedKeyVersions) {
        if (!"AUDIT-TOKENIZATION-1.0.0".equals(profileVersion)) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TOKEN_PROFILE_DRIFT");
        }
        this.profileVersion = profileVersion;
        this.tokenizers = Map.copyOf(tokenizers);
        this.retainedKeyVersions = List.copyOf(retainedKeyVersions);
        if (this.retainedKeyVersions.isEmpty()) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TOKEN_KEY_CATALOG_EMPTY");
        }
    }

    @Override
    public List<AuditSearchQueryToken> query(AuditSearchTokenQuery query) {
        String canonical = Normalizer.normalize(query.rawReference().strip(), Normalizer.Form.NFKC);
        AuditTokenDomain domain = query.domain() == AuditSearchTokenDomain.ACTOR
                ? AuditTokenDomain.ACTOR : AuditTokenDomain.OBJECT;
        List<AuditSearchQueryToken> result = new ArrayList<>();
        for (String keyVersion : retainedKeyVersions) {
            IdentityAuditTokenPort tokenizer = tokenizers.get(keyVersion);
            if (tokenizer == null) {
                throw new IllegalStateException("AUDIT_SEARCH_TOKEN_KEY_UNAVAILABLE");
            }
            try {
                var token = tokenizer.tokenize(domain, canonical);
                if (!profileVersion.equals(token.profileVersion()) || !keyVersion.equals(token.keyVersion())) {
                    throw new IllegalStateException("AUDIT_SEARCH_TOKEN_PROFILE_DRIFT");
                }
                result.add(new AuditSearchQueryToken(token.value(), token.profileVersion(), token.keyVersion()));
            } catch (IllegalStateException unavailable) {
                if ("AUDIT_SEARCH_TOKEN_PROFILE_DRIFT".equals(unavailable.getMessage())) {
                    throw unavailable;
                }
                throw new IllegalStateException("AUDIT_SEARCH_TOKENIZATION_UNAVAILABLE", unavailable);
            }
        }
        return List.copyOf(result);
    }
}
