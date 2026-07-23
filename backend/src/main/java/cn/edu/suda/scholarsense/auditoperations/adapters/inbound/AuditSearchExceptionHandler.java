package cn.edu.suda.scholarsense.auditoperations.adapters.inbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchException;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {AuditSearchController.class, AuditRetentionExecutionController.class})
public final class AuditSearchExceptionHandler {
    @ExceptionHandler(AuditSearchException.class)
    ResponseEntity<ErrorEnvelope> searchFailure(AuditSearchException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "AUDIT_SEARCH_FORBIDDEN", "AUDIT_EVIDENCE_NOT_AVAILABLE" -> HttpStatus.FORBIDDEN;
            case "AUDIT_SEARCH_PROJECTION_NOT_CAUGHT_UP" -> HttpStatus.CONFLICT;
            case "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", "AUDIT_SEARCH_AUDIT_COMMIT_FAILED" ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return error(status, failure.code(), request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ErrorEnvelope> invalidRequest(Exception ignored, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "AUDIT_SEARCH_INVALID_REQUEST", request);
    }

    private static ResponseEntity<ErrorEnvelope> error(
            HttpStatus status, String code, HttpServletRequest request) {
        String traceId = W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(new ErrorEnvelope(code, "Audit search request could not be completed", traceId, List.of()));
    }

    public record ErrorEnvelope(
            String code,
            String message,
            String traceId,
            List<FieldError> fieldErrors) {}

    public record FieldError(String field, String code, String message) {}
}
