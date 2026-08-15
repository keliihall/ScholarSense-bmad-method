package cn.edu.suda.scholarsense.shared.observability;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Objects;

/**
 * Explicit instrumentation boundary for custom {@link HttpClient} instances.
 * It preserves the original request and transport policy while governing trace headers.
 */
public final class TrustedHttpClient {
    public static final String LEGACY_TRACE_HEADER = "X-ScholarSense-Trace-Id";
    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";
    private static final String BAGGAGE = "baggage";

    private final HttpClient delegate;
    private final CurrentTraceSource currentTrace;
    private final W3cTraceContextCodec codec;
    private final TrustedTargetPolicy targets;
    private final ObservationPort observations;

    TrustedHttpClient(
            HttpClient delegate,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec codec,
            TrustedTargetPolicy targets,
            ObservationPort observations) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.currentTrace = Objects.requireNonNull(currentTrace, "currentTrace");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.observations = Objects.requireNonNull(observations, "observations");
        if (delegate.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException(
                    "automatic redirects cannot preserve the trusted trace-header boundary");
        }
    }

    /** Compatibility path for isolated predecessor tests; production wiring uses the full kernel. */
    public static TrustedHttpClient unobserved(HttpClient delegate) {
        return new TrustedHttpClient(Objects.requireNonNull(delegate, "delegate"));
    }

    private TrustedHttpClient(HttpClient delegate) {
        this.delegate = delegate;
        this.currentTrace = null;
        this.codec = null;
        this.targets = null;
        this.observations = null;
    }

    public HttpClient delegate() {
        return delegate;
    }

    public <T> HttpResponse<T> send(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseHandler,
            String module,
            String persistedTraceId) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(responseHandler, "responseHandler");
        if (observations == null) {
            return delegate.send(request, responseHandler);
        }
        W3cTraceContext parent = parent(persistedTraceId);
        ObservationPort.ObservationScope client = start(module, parent);
        W3cTraceContext clientContext = context(client, parent);
        HttpRequest governed = governed(request, clientContext);
        try {
            HttpResponse<T> response = delegate.send(governed, responseHandler);
            if (response.statusCode() >= 400) {
                outcome(client, httpOutcome(response.statusCode()));
                error(client, new ExternalHttpStatusException());
            } else {
                outcome(client, "success");
            }
            return response;
        } catch (IOException | InterruptedException failure) {
            outcome(client, failure instanceof HttpTimeoutException
                    ? "timeout"
                    : failure instanceof IOException ? "unavailable" : "failure");
            error(client, failure);
            throw failure;
        } finally {
            close(client);
        }
    }

    private W3cTraceContext parent(String persistedTraceId) {
        var current = currentTrace.current();
        if (current.isPresent()
                && (persistedTraceId == null
                        || persistedTraceId.equals(current.get().traceId()))) {
            return current.get();
        }
        if (persistedTraceId != null
                && persistedTraceId.matches("^(?!0{32}$)[0-9a-f]{32}$")) {
            return codec.resume(persistedTraceId, false);
        }
        return codec.newRoot(false);
    }

    private ObservationPort.ObservationScope start(
            String module, W3cTraceContext parent) {
        try {
            return observations.start(
                    "http.client",
                    ObservationPort.ObservationKind.CLIENT,
                    SafeObservationAttributes.create()
                            .low("module", module)
                            .low("operation", "http.client")
                            .low("outcome", "success"),
                    parent);
        } catch (RuntimeException telemetryUnavailable) {
            return null;
        }
    }

    private W3cTraceContext context(
            ObservationPort.ObservationScope scope, W3cTraceContext fallback) {
        if (scope == null) return fallback;
        try {
            return scope.context();
        } catch (RuntimeException telemetryUnavailable) {
            return fallback;
        }
    }

    private HttpRequest governed(HttpRequest request, W3cTraceContext context) {
        HttpRequest.Builder copy = HttpRequest.newBuilder(
                request,
                (name, value) -> !TRACEPARENT.equalsIgnoreCase(name)
                        && !TRACESTATE.equalsIgnoreCase(name)
                        && !LEGACY_TRACE_HEADER.equalsIgnoreCase(name)
                        && !BAGGAGE.equalsIgnoreCase(name));
        if (targets.allows(request.uri())) {
            copy.header(TRACEPARENT, codec.format(context));
            copy.header(LEGACY_TRACE_HEADER, context.traceId());
        }
        return copy.build();
    }

    private static void error(
            ObservationPort.ObservationScope scope, Throwable failure) {
        if (scope == null) return;
        try {
            scope.error(failure);
        } catch (RuntimeException ignoredTelemetryFailure) {
            // Telemetry is not allowed to change the external-call result.
        }
    }

    private static String httpOutcome(int status) {
        if (status == 408 || status == 504) return "timeout";
        if (status == 409) return "conflict";
        if (status >= 500) return "unavailable";
        return "denied";
    }

    private static void outcome(
            ObservationPort.ObservationScope scope, String value) {
        if (scope == null) return;
        try {
            scope.outcome(value);
        } catch (RuntimeException ignoredTelemetryFailure) {
            // Telemetry is not allowed to change the external-call result.
        }
    }

    private static void close(ObservationPort.ObservationScope scope) {
        if (scope == null) return;
        try {
            scope.close();
        } catch (RuntimeException ignoredTelemetryFailure) {
            // Exporter failure is an observability degradation, not a business failure.
        }
    }

    private static final class ExternalHttpStatusException extends RuntimeException {
        private ExternalHttpStatusException() {
            super("EXTERNAL_HTTP_STATUS_ERROR", null, false, false);
        }
    }
}
