package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = DataSourceCatalogController.class)
public final class DataSourceCatalogExceptionHandler {
    @ExceptionHandler(IngestionQualityApplicationException.class)
    ResponseEntity<ErrorEnvelope> application(
            IngestionQualityApplicationException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "INGESTION_QUALITY_FORBIDDEN" -> HttpStatus.NOT_FOUND;
            case "INGESTION_QUALITY_VERSION_CONFLICT", "INGESTION_QUALITY_IDEMPOTENCY_MISMATCH",
                    "INGESTION_QUALITY_CATALOG_RELEASE_CONFLICT" ->
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
        HttpStatus status = "INGESTION_QUALITY_VERSION_CONFLICT".equals(failure.code())
                ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
        return error(status, failure.code(), -1, request);
    }

    @ExceptionHandler({CatalogRequestException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ErrorEnvelope> invalid(Exception ignored, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INGESTION_QUALITY_REQUEST_INVALID", -1, request);
    }

    private static ResponseEntity<ErrorEnvelope> error(
            HttpStatus status, String code, long currentVersion, HttpServletRequest request) {
        String traceId = DataSourceCatalogController.trace(request);
        Long version = currentVersion < 0 ? null : currentVersion;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(new ErrorEnvelope(code, traceId, version, List.of()));
    }

    public record ErrorEnvelope(String code, String traceId, Long currentVersion, List<FieldError> fieldErrors) {}
    public record FieldError(String field, String code) {}
}
