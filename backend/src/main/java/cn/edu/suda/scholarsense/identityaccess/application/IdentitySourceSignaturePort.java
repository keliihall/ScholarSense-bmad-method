package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface IdentitySourceSignaturePort {
    boolean verify(byte[] payload, String detachedSignature, String signatureKeyReference);
}
