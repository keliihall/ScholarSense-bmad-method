package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public final class AuditTestSupport {
    static final Instant AUDIT_NOW = Instant.parse("2026-07-20T02:00:00Z");

    private AuditTestSupport() {}

    public static IdentityAuditFactFactory factory() {
        return new IdentityAuditFactFactory(
                () -> new TrustedTime(AUDIT_NOW, new TimeSourceProfile(
                        "campus-ntp-a", "AUDIT-CLOCK-BINDING-1.0.0", 0,
                        AUDIT_NOW.minusSeconds(30), AUDIT_NOW.plusSeconds(30),
                        "evidence://signed/clock/test-observation")),
                AuditTestSupport::token);
    }

    public static AuditSearchToken token(AuditTokenDomain domain, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (domain.wireName() + "\0" + value).getBytes(StandardCharsets.UTF_8));
            return new AuditSearchToken(
                    domain.prefix() + "_v1_k1_" + HexFormat.of().formatHex(digest),
                    "AUDIT-TOKENIZATION-1.0.0", "k1");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
