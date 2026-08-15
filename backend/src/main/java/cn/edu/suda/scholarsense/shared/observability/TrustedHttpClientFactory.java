package cn.edu.suda.scholarsense.shared.observability;

import cn.edu.suda.scholarsense.runtime.RuntimeEnvironment;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.Objects;

/** Creates governed wrappers without taking ownership of adapter-specific transport settings. */
public final class TrustedHttpClientFactory {
    private final CurrentTraceSource currentTrace;
    private final W3cTraceContextCodec codec;
    private final RuntimeEnvironment environment;
    private final ObservationPort observations;

    public TrustedHttpClientFactory(
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec codec,
            RuntimeEnvironment environment,
            ObservationPort observations) {
        this.currentTrace = Objects.requireNonNull(currentTrace);
        this.codec = Objects.requireNonNull(codec);
        this.environment = Objects.requireNonNull(environment);
        this.observations = Objects.requireNonNull(observations);
    }

    public TrustedHttpClient wrap(
            HttpClient client, IdentityAuthorityRuntimeProfile approvedProfile) {
        TrustedTargetPolicy targets = TrustedTargetPolicy.fromIdentityAuthorityProfile(
                environment, approvedProfile);
        return new TrustedHttpClient(client, currentTrace, codec, targets, observations);
    }

    public TrustedHttpClient wrap(
            HttpClient client, ResponsibilityAuthorityRuntimeProfile approvedProfile) {
        TrustedTargetPolicy targets = TrustedTargetPolicy.fromResponsibilityAuthorityProfile(
                environment, approvedProfile);
        return new TrustedHttpClient(client, currentTrace, codec, targets, observations);
    }

    /**
     * Compatibility for test/dev providers that do not yet have a frozen authority profile.
     * Stage and production must fail closed instead of promoting arbitrary configuration URIs.
     */
    public TrustedHttpClient wrapSandboxIdentityProvider(
            HttpClient client, URI... sandboxEndpoints) {
        TrustedTargetPolicy targets = TrustedTargetPolicy.fromSandboxProfile(
                environment,
                "IDENTITY-RUNTIME-PROFILE-1.0.0",
                Arrays.asList(sandboxEndpoints));
        return new TrustedHttpClient(client, currentTrace, codec, targets, observations);
    }

    /** See {@link #wrapSandboxIdentityProvider(HttpClient, URI...)}. */
    public TrustedHttpClient wrapSandboxQualityWorker(
            HttpClient client, URI... sandboxEndpoints) {
        TrustedTargetPolicy targets = TrustedTargetPolicy.fromSandboxProfile(
                environment,
                "QUALITY-WORKER-PROVIDER-PROFILE-1.0.0",
                Arrays.asList(sandboxEndpoints));
        return new TrustedHttpClient(client, currentTrace, codec, targets, observations);
    }
}
