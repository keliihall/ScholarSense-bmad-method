package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.List;

/** Deployment-supplied keyed pseudonymization; raw identifiers must never be persisted or logged. */
@FunctionalInterface
public interface PseudonymizationPort {
    String pseudonymize(String purpose, String rawValue);

    /**
     * Returns the current write token first, followed by still-approved historical
     * read tokens during a bounded key rotation.
     */
    default List<String> pseudonymizeForRead(String purpose, String rawValue) {
        return List.of(pseudonymize(purpose, rawValue));
    }
}
