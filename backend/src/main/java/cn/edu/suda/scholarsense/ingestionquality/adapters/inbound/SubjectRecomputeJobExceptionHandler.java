package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = SubjectRecomputeJobController.class)
public final class SubjectRecomputeJobExceptionHandler {
    @ExceptionHandler(IngestionQualityApplicationException.class)
    ResponseEntity<ErrorEnvelope> application(
            IngestionQualityApplicationException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "INGESTION_QUALITY_FORBIDDEN" -> HttpStatus.NOT_FOUND;
            case "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return error(status, failure.code(), request);
    }

    @ExceptionHandler({CatalogRequestException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorEnvelope> invalid(Exception ignored, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INGESTION_QUALITY_REQUEST_INVALID", request);
    }

    private static ResponseEntity<ErrorEnvelope> error(
            HttpStatus status, String code, HttpServletRequest request) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(new ErrorEnvelope(
                        code, "Request could not be completed",
                        DataSourceCatalogController.trace(request), List.of()));
    }

    public record ErrorEnvelope(
            String code, String message, String traceId, List<FieldError> fieldErrors) {}
    public record FieldError(String field, String code) {}
}
