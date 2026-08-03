package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class IdentityExceptionHandler {
    @ExceptionHandler(IdentityAccessException.class)
    ResponseEntity<IdentityErrorEnvelope> identityFailure(
            IdentityAccessException failure, HttpServletRequest request) {
        return response(status(failure.code()), envelope(failure.code(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<IdentityErrorEnvelope> invalidRequest(HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                envelope("IDENTITY_REQUEST_INVALID", request));
    }

    private static IdentityErrorEnvelope envelope(String code, HttpServletRequest request) {
        return IdentityErrorResponseWriter.envelope(code, request);
    }

    private static HttpStatus status(String code) {
        if ("IDENTITY_AUTHORIZATION_OBJECT_UNAVAILABLE".equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        if ("IDENTITY_AUTHORIZATION_SURFACE_FORBIDDEN".equals(code)) {
            return HttpStatus.FORBIDDEN;
        }
        if ("IDENTITY_AUTHORIZATION_DECISION_STALE".equals(code)) {
            return HttpStatus.CONFLICT;
        }
        if ("IDENTITY_AUTHORIZATION_DEPENDENCY_UNAVAILABLE".equals(code)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (code.endsWith("VERSION_CONFLICT") || code.endsWith("IDEMPOTENCY_MISMATCH")) {
            return HttpStatus.CONFLICT;
        }
        if (code.endsWith("REQUIRED") || code.endsWith("EXPIRED")) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code.endsWith("UNAVAILABLE")) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.BAD_REQUEST;
    }

    private static ResponseEntity<IdentityErrorEnvelope> response(
            HttpStatus status, IdentityErrorEnvelope envelope) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store, no-cache")
                .header("Pragma", "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(envelope);
    }

}
