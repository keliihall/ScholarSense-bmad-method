package cn.edu.suda.scholarsense.identityaccess.application;

/** Deployment-supplied keyed pseudonymization; raw identifiers must never be persisted or logged. */
@FunctionalInterface
public interface PseudonymizationPort {
    String pseudonymize(String purpose, String rawValue);
}
