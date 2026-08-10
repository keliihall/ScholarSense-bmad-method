package cn.edu.suda.scholarsense.identityaccess.adapters;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HmacIdentityAuditTokenAdapter;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.InternalAuditTokenizationAdapter;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditTokenPort;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Web identity audit-token binding backed by deployment-mounted HMAC material. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression(
        "'${scholarsense.identity.enabled:false}' == 'true' || "
                + "'${scholarsense.ingestion-quality.quality-worker-enabled:false}' == 'true'")
public class IdentityAuditTokenConfiguration {
    @Bean
    HmacIdentityAuditTokenAdapter identityAuditTokenAdapter(
            @Value("${scholarsense.identity.audit-token-key-path:}") String keyPath,
            @Value("${scholarsense.identity.audit-token-key-version:}") String keyVersion) {
        try {
            return HmacIdentityAuditTokenAdapter.fromMountedKey(
                    Path.of(keyPath), keyVersion);
        } catch (InvalidPathException invalidPath) {
            throw new IllegalStateException(
                    "IDENTITY_AUDIT_TOKEN_KEY_INVALID", invalidPath);
        }
    }

    @Bean
    @ConditionalOnMissingBean(AuditTokenizationPort.class)
    AuditTokenizationPort auditTokenizationPort(IdentityAuditTokenPort tokens) {
        return new InternalAuditTokenizationAdapter(tokens);
    }
}
