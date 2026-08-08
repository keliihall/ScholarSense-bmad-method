package cn.edu.suda.scholarsense.subjectregistry.adapters.inbound;

import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryApplicationException;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectRegistryException;
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

@RestControllerAdvice(assignableTypes = SubjectMappingController.class)
public final class SubjectMappingExceptionHandler {
    @ExceptionHandler(SubjectRegistryApplicationException.class)
    ResponseEntity<ErrorEnvelope> application(
            SubjectRegistryApplicationException failure, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(failure.httpStatus());
        return error(status, failure.code(), failure.currentVersion(), request);
    }

    @ExceptionHandler(SubjectRegistryException.class)
    ResponseEntity<ErrorEnvelope> domain(
            SubjectRegistryException failure, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, failure.code().name(), null, request);
    }

    @ExceptionHandler({SubjectMappingRequestException.class,
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ErrorEnvelope> invalid(Exception ignored, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "SUBJECT_REGISTRY_REQUEST_INVALID", null, request);
    }

    private static ResponseEntity<ErrorEnvelope> error(
            HttpStatus status, String code, Long currentVersion, HttpServletRequest request) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(new ErrorEnvelope(
                        code, "Request could not be completed", SubjectMappingController.trace(request),
                        List.of(), currentVersion));
    }

    public record ErrorEnvelope(
            String code, String message, String traceId,
            List<FieldError> fieldErrors, Long currentAggregateVersion) {}
    public record FieldError(String field, String code) {}
}
