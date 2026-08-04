package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityException;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DataSourceCatalogController.class)
public final class DataSourceCatalogExceptionHandler {
    @ExceptionHandler(IngestionQualityApplicationException.class)
    ResponseEntity<ErrorEnvelope> application(
            IngestionQualityApplicationException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "INGESTION_QUALITY_FORBIDDEN" -> HttpStatus.NOT_FOUND;
            case "INGESTION_QUALITY_VERSION_CONFLICT", "INGESTION_QUALITY_IDEMPOTENCY_MISMATCH" ->
                    HttpStatus.CONFLICT;
            case "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "INGESTION_QUALITY_CONTRACT_INVALID", "INGESTION_QUALITY_EVIDENCE_INVALID",
                    "INGESTION_QUALITY_INVALID_STATE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return error(status, failure.code(), failure.currentVersion(), request);
    }

    @ExceptionHandler(IngestionQualityException.class)
    ResponseEntity<ErrorEnvelope> domain(IngestionQualityException failure, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, failure.code(), -1, request);
    }

    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class,
            MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorEnvelope> invalid(Exception ignored, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INGESTION_QUALITY_REQUEST_INVALID", -1, request);
    }

    private static ResponseEntity<ErrorEnvelope> error(
            HttpStatus status, String code, long currentVersion, HttpServletRequest request) {
        String traceId = W3cTraceId.from(
                request.getHeader("Traceparent"), request.getMethod() + ":" + request.getRequestURI());
        Long version = currentVersion < 0 ? null : currentVersion;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(new ErrorEnvelope(code, traceId, version, List.of()));
    }

    public record ErrorEnvelope(String code, String traceId, Long currentVersion, List<FieldError> fieldErrors) {}
    public record FieldError(String field, String code) {}
}
