package cn.edu.suda.scholarsense.identityaccess.adapters;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.HmacIdentityAuditTokenAdapter;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Web identity audit-token binding backed by deployment-mounted HMAC material. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "scholarsense.identity.enabled", havingValue = "true")
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
}
