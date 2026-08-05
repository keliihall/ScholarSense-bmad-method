package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizedValue;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditTokenPort;
import java.util.Objects;

/** Adapts the identity-owned keyed tokenizer without exposing its application types. */
public final class InternalAuditTokenizationAdapter implements AuditTokenizationPort {
    private final IdentityAuditTokenPort tokens;

    public InternalAuditTokenizationAdapter(IdentityAuditTokenPort tokens) {
        this.tokens = Objects.requireNonNull(tokens);
    }

    @Override
    public AuditTokenizedValue tokenize(
            AuditTokenizationDomain domain, String normalizedValue) {
        Objects.requireNonNull(domain, "domain");
        if (normalizedValue == null || normalizedValue.isBlank()) {
            throw new IllegalArgumentException("AUDIT_TOKENIZATION_INPUT_INVALID");
        }
        var value = tokens.tokenize(AuditTokenDomain.valueOf(domain.name()), normalizedValue);
        if (!value.value().startsWith(domain.prefix() + "_")) {
            throw new IllegalArgumentException("AUDIT_TOKEN_DOMAIN_MISMATCH");
        }
        return new AuditTokenizedValue(
                value.value(), value.profileVersion(), value.keyVersion());
    }
}
