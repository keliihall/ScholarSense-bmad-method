package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import java.util.List;

public record IdentityErrorEnvelope(
        String code,
        String message,
        String traceId,
        List<FieldError> fieldErrors) {
    public record FieldError(String field, String code) {}
}
