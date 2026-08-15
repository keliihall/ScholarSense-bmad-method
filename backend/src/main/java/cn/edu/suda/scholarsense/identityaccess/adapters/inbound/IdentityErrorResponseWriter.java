package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import cn.edu.suda.scholarsense.shared.observability.HttpTraceContext;
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
            "IDENTITY_AUTHORIZATION_OBJECT_UNAVAILABLE",
            "IDENTITY_AUTHORIZATION_SURFACE_FORBIDDEN",
            "IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE",
            "IDENTITY_AUTHORIZATION_DECISION_STALE",
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
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
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
            case "IDENTITY_AUTHORIZATION_OBJECT_UNAVAILABLE" ->
                    "当前职责范围不包含此对象；本次访问已记录";
            case "IDENTITY_AUTHORIZATION_SURFACE_FORBIDDEN" ->
                    "当前身份没有可用入口";
            case "IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE" ->
                    "授权依赖暂时不可用，请重试";
            case "IDENTITY_AUTHORIZATION_DECISION_STALE" ->
                    "授权状态已变化，请重新操作";
            default -> "identity service is temporarily unavailable";
        };
    }

    private static String traceId(HttpServletRequest request) {
        return HttpTraceContext.traceId(request);
    }
}
