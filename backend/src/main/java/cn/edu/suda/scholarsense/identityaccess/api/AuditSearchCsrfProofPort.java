package cn.edu.suda.scholarsense.identityaccess.api;

/**
 * Atomically consumes an audit-search CSRF proof across every application node.
 *
 * <p>Both arguments are SHA-256 digests; raw session ids and proof values must not cross this port.
 */
@FunctionalInterface
public interface AuditSearchCsrfProofPort {
    boolean consume(String browserSessionDigest, String proofDigest);
}
