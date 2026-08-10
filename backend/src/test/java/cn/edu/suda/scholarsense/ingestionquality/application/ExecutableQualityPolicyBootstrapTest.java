package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.adapters.outbound.FrozenExecutableQualityPolicyLoader;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ExecutableQualityPolicyBootstrapTest {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Path POLICY = Path.of(
            "contracts/ingestion-quality/batch-quality/executable-quality-policy-1.0.0.json");
    private static final Path POLICY_LOCK = Path.of(
            "contracts/ingestion-quality/batch-quality/executable-quality-contract-lock-1.0.0.json");
    private static final Path HASH_PROFILE = Path.of(
            "contracts/ingestion-quality/batch-quality/quality-snapshot-hash-profile-1.0.0.json");
    private static final Path HASH_LOCK = Path.of(
            "contracts/ingestion-quality/batch-quality/quality-snapshot-hash-contract-lock-1.0.0.json");
    private static final List<Path> RUNTIME_CONTRACTS = List.of(
            POLICY,
            POLICY_LOCK,
            HASH_PROFILE,
            HASH_LOCK);
    private static final List<Path> COMPLETE_CHAIN = completeChain();

    @TempDir
    Path temporary;

    @Test
    void startupLoadsAndReturnsTheVerifiedRuntimeContractInsteadOfADataCatalogPolicy() {
        VerifiedQualityContract expected = new FrozenExecutableQualityPolicyLoader(REPOSITORY)
                .loadVerified();
        CountingPort port = new CountingPort(expected);

        VerifiedQualityContract started = new ExecutableQualityPolicyBootstrap(port).start();

        assertSame(expected, started);
        assertEquals(1, port.loads.get());
        assertEquals(ExecutableQualityPolicyPort.class,
                ExecutableQualityPolicyBootstrap.class.getDeclaredConstructors()[0]
                        .getParameterTypes()[0]);
    }

    @Test
    void startupPropagatesFailClosedContractFailure() {
        IngestionQualityApplicationException expected = contractInvalid();
        ExecutableQualityPolicyPort failing = () -> {
            throw expected;
        };

        IngestionQualityApplicationException actual = assertThrows(
                IngestionQualityApplicationException.class,
                () -> new ExecutableQualityPolicyBootstrap(failing).start());

        assertSame(expected, actual);
    }

    @Test
    void captureAndFinalRevalidationReloadThePortAndRequireTheExactAttestation() {
        VerifiedQualityContract expected = new FrozenExecutableQualityPolicyLoader(REPOSITORY)
                .loadVerified();
        CountingPort port = new CountingPort(expected);
        ExecutableQualityPolicyGuard guard = new ExecutableQualityPolicyGuard(port);

        VerifiedQualityContract captured = guard.capture();
        VerifiedQualityContract revalidated = guard.revalidate(captured.attestation());

        assertSame(expected, captured);
        assertSame(expected, revalidated);
        assertEquals(2, port.loads.get());
    }

    @Test
    void everyAttestationComponentParticipatesInExactFinalCommitEquality() throws Exception {
        VerifiedQualityContract expected = new FrozenExecutableQualityPolicyLoader(REPOSITORY)
                .loadVerified();
        CountingPort port = new CountingPort(expected);
        ExecutableQualityPolicyGuard guard = new ExecutableQualityPolicyGuard(port);

        for (RecordComponent component : QualityContractAttestation.class.getRecordComponents()) {
            QualityContractAttestation drifted = drift(expected.attestation(), component);
            port.current = new VerifiedQualityContract(
                    expected.policy(), expected.hashProfile(), drifted);

            IngestionQualityApplicationException failure = assertThrows(
                    IngestionQualityApplicationException.class,
                    () -> guard.revalidate(expected.attestation()), component.getName());
            assertEquals("INGESTION_QUALITY_CONTRACT_INVALID", failure.code(),
                    component.getName());
        }
        assertEquals(QualityContractAttestation.class.getRecordComponents().length,
                port.loads.get());
    }

    @Test
    void captureThenFileDriftCannotPassViaACachedAttestationAndExactRestorationIsRechecked()
            throws Exception {
        Path repository = contractCopy("capture-drift");
        ExecutableQualityPolicyGuard guard = new ExecutableQualityPolicyGuard(
                new FrozenExecutableQualityPolicyLoader(repository));
        VerifiedQualityContract captured = guard.capture();
        byte[] original = Files.readAllBytes(repository.resolve(POLICY));
        Files.writeString(repository.resolve(POLICY), "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        IngestionQualityApplicationException drift = assertThrows(
                IngestionQualityApplicationException.class,
                () -> guard.revalidate(captured.attestation()));
        assertEquals("INGESTION_QUALITY_CONTRACT_INVALID", drift.code());

        Files.write(repository.resolve(POLICY), original);
        VerifiedQualityContract restored = guard.revalidate(captured.attestation());
        assertEquals(captured.attestation(), restored.attestation());
    }

    private Path contractCopy(String name) throws Exception {
        Path copy = temporary.resolve(name);
        for (Path relative : COMPLETE_CHAIN) {
            Path destination = copy.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(REPOSITORY.resolve(relative), destination,
                    StandardCopyOption.COPY_ATTRIBUTES);
        }
        return copy;
    }

    private static List<Path> completeChain() {
        try {
            ObjectMapper json = new ObjectMapper();
            LinkedHashSet<Path> result = new LinkedHashSet<>(RUNTIME_CONTRACTS);
            JsonNode policyLock = json.readTree(Files.readAllBytes(REPOSITORY.resolve(POLICY_LOCK)));
            for (String path : policyLock.required("digests").propertyNames()) {
                result.add(Path.of(path));
            }
            JsonNode hashLock = json.readTree(Files.readAllBytes(REPOSITORY.resolve(HASH_LOCK)));
            hashLock.required("digests").forEach(value ->
                    result.add(Path.of(value.required("path").textValue())));
            JsonNode hashProfile = json.readTree(
                    Files.readAllBytes(REPOSITORY.resolve(HASH_PROFILE)));
            hashProfile.required("controlledInputs").forEach(value ->
                    result.add(Path.of(value.required("path").textValue())));
            return List.copyOf(result);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static QualityContractAttestation drift(
            QualityContractAttestation original, RecordComponent target) throws Exception {
        RecordComponent[] components = QualityContractAttestation.class.getRecordComponents();
        Class<?>[] types = Arrays.stream(components)
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        Object[] values = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            values[index] = components[index].getAccessor().invoke(original);
            if (components[index].getName().equals(target.getName())) {
                values[index] = driftValue(values[index]);
            }
        }
        return (QualityContractAttestation) QualityContractAttestation.class
                .getDeclaredConstructor(types).newInstance(values);
    }

    private static Object driftValue(Object value) {
        if (value instanceof String text) return text + "-drift";
        if (value instanceof Instant instant) return instant.plusNanos(1_000);
        throw new AssertionError("Unhandled attestation component type: " + value.getClass());
    }

    private static IngestionQualityApplicationException contractInvalid() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_CONTRACT_INVALID");
    }

    private static final class CountingPort implements ExecutableQualityPolicyPort {
        private final AtomicInteger loads = new AtomicInteger();
        private VerifiedQualityContract current;

        private CountingPort(VerifiedQualityContract current) {
            this.current = current;
        }

        @Override
        public VerifiedQualityContract loadVerified() {
            loads.incrementAndGet();
            return current;
        }
    }
}
