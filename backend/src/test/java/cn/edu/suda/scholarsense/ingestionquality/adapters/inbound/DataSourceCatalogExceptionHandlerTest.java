package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;

class DataSourceCatalogExceptionHandlerTest {
    @Test
    void requestFailuresAre400AndDependencyFailuresAre503() {
        var handler = new DataSourceCatalogExceptionHandler();
        HttpServletRequest request = request();

        assertEquals(HttpStatus.BAD_REQUEST,
                handler.invalid(new CatalogRequestException(), request).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                handler.application(new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE"), request).getStatusCode());
    }

    @Test
    void repositoryOrDomainIllegalArgumentsAreNotMisclassifiedAsRequest400() {
        boolean catchesIllegalArgument = Arrays.stream(
                        DataSourceCatalogExceptionHandler.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(ExceptionHandler.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(annotation -> Arrays.stream(annotation.value()))
                .anyMatch(type -> type == IllegalArgumentException.class
                        || type == ArithmeticException.class);

        assertFalse(catchesIllegalArgument);
    }

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/data-source-catalogs/test");
        return request;
    }
}
