package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import org.springframework.http.MediaType;

/** Writes the frozen identity error envelope from security filters and MVC handlers alike. */
final class IdentityErrorResponseWriter {
    private static final Set<String> CONTRACT_CODES = Set.of(
            "IDENTITY_SESSION_REQUIRED",
            "IDENTITY_SESSION_EXPIRED",
            "IDENTITY_REAUTHENTICATION_REQUIRED",
            "IDENTITY_DEPENDENCY_UNAVAILABLE",
            "IDENTITY_SESSION_VERSION_CONFLICT",
            "IDENTITY_IDEMPOTENCY_MISMATCH",
            "HOST_ORIGIN_FORBIDDEN",
            "HOST_SOURCE_FORBIDDEN",
            "HOST_MESSAGE_INVALID",
            "HOST_MESSAGE_REPLAYED",
            "HOST_BOOTSTRAP_EXPIRED",
            "HOST_BOOTSTRAP_ALREADY_USED",
            "CONTINUATION_INVALID_OR_EXPIRED");

    private IdentityErrorResponseWriter() {}

    static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code) throws IOException {
        IdentityErrorEnvelope envelope = envelope(contractCode(code), request);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + envelope.code()
                + "\",\"message\":\"" + envelope.message()
                + "\",\"traceId\":\"" + envelope.traceId()
                + "\",\"fieldErrors\":[]}");
    }

    static IdentityErrorEnvelope envelope(String code, HttpServletRequest request) {
        String safeCode = contractCode(code);
        return new IdentityErrorEnvelope(safeCode, safeMessage(safeCode), traceId(request), List.of());
    }

    static String contractCode(String code) {
        if (CONTRACT_CODES.contains(code)) {
            return code;
        }
        return "IDENTITY_DEPENDENCY_UNAVAILABLE";
    }

    private static String safeMessage(String code) {
        return switch (code) {
            case "IDENTITY_SESSION_REQUIRED", "IDENTITY_SESSION_EXPIRED",
                    "IDENTITY_REAUTHENTICATION_REQUIRED" ->
                    "authentication is required";
            case "IDENTITY_SESSION_VERSION_CONFLICT", "IDENTITY_IDEMPOTENCY_MISMATCH" ->
                    "session changed; refresh before retrying";
            case "HOST_ORIGIN_FORBIDDEN", "HOST_SOURCE_FORBIDDEN", "HOST_MESSAGE_INVALID",
                    "HOST_MESSAGE_REPLAYED", "HOST_BOOTSTRAP_EXPIRED",
                    "HOST_BOOTSTRAP_ALREADY_USED" -> "host request is unavailable";
            case "CONTINUATION_INVALID_OR_EXPIRED" ->
                    "the requested destination is unavailable";
            default -> "identity service is temporarily unavailable";
        };
    }

    private static String traceId(HttpServletRequest request) {
        return W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
    }
}
