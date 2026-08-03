package cn.edu.suda.scholarsense.identityaccess.api;

import cn.edu.suda.scholarsense.identityaccess.application.FieldCiphertextEnvelope;

/** Resolves an opaque server-owned value reference without exposing plaintext. */
@FunctionalInterface
public interface SensitiveFieldValueResolverPort {
    FieldCiphertextEnvelope resolve(SensitiveValueReference reference);
}
