package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationEvidenceAvailability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceProvider;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationObjectEvidenceQuery;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeAnchor;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizationScopeEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.FrozenDataCatalogPolicy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** SOURCE/DEPENDENCY owner facts loaded from a digest-bound deployment mount. */
public final class CatalogOwnerEvidenceProvider implements AuthorizationObjectEvidenceProvider {
    static final String PROFILE_VERSION = "INGESTION-QUALITY-OWNER-BINDINGS-1.0.0";
    private static final int MAX_PROFILE_BYTES = 256 * 1024;
    private static final Set<String> OBJECT_CLASSES = Set.of(
            "SOURCE", "DEPENDENCY", "SUBJECT_MAPPING_EXCEPTION", "JOB");
    private static final Set<String> SUBJECT_MAPPING_FIELDS = Set.of(
            "exceptionId", "status", "subjectOfficialRef", "exceptionCode",
            "sourceSystem", "sourceOwner", "detectedAt");
    private final Map<BindingKey, OwnerBinding> bindings;

    private CatalogOwnerEvidenceProvider(Map<BindingKey, OwnerBinding> bindings) {
        this.bindings = Map.copyOf(bindings);
    }

    public static CatalogOwnerEvidenceProvider load(
            Path path, String expectedDigest, ObjectMapper json) {
        return load(path, expectedDigest, json, () -> {});
    }

    static CatalogOwnerEvidenceProvider load(
            Path path,
            String expectedDigest,
            ObjectMapper json,
            Runnable afterInitialValidation) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(json);
        if (!path.isAbsolute()) {
            throw invalid();
        }
        path = path.normalize();
        Snapshot before = inspect(path);
        afterInitialValidation.run();
        byte[] bytes;
        try {
            Snapshot after;
            try (SeekableByteChannel channel = Files.newByteChannel(
                    path,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                bytes = readBounded(channel);
                after = inspect(path);
            }
            if (!before.sameFile(after)) {
                throw invalid();
            }
        } catch (NoSuchFileException missing) {
            throw invalid();
        } catch (IOException unavailable) {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid();
            }
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_OWNER_BINDINGS_UNAVAILABLE", unavailable);
        }
        if (bytes.length == 0 || bytes.length > MAX_PROFILE_BYTES) {
            throw invalid();
        }
        if (expectedDigest == null
                || !expectedDigest.matches("sha256:[0-9a-f]{64}")
                || !expectedDigest.equals("sha256:" + sha256(bytes))) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_OWNER_BINDINGS_DIGEST_MISMATCH");
        }
        try {
            return parse(json.readTree(bytes));
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalArgumentException argument) {
                throw argument;
            }
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_OWNER_BINDINGS_INVALID", invalid);
        }
    }

    private static Snapshot inspect(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.fileKey() == null) {
                throw invalid();
            }
            Set<PosixFilePermission> permissions = permissions(path);
            return new Snapshot(
                    attributes.fileKey(),
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    permissions);
        } catch (NoSuchFileException missing) {
            throw invalid();
        } catch (IOException unavailable) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_OWNER_BINDINGS_UNAVAILABLE", unavailable);
        }
    }

    private static Set<PosixFilePermission> permissions(Path path) throws IOException {
        try {
            return Set.copyOf(
                    Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
        } catch (UnsupportedOperationException ignoredNonPosixFileSystem) {
            return Set.of();
        }
    }

    private static byte[] readBounded(SeekableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(MAX_PROFILE_BYTES + 1);
        while (buffer.hasRemaining() && channel.read(buffer) != -1) {
            // Read from the one no-follow channel so validation and use cannot diverge.
        }
        return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }

    @Override
    public Set<String> supportedObjectClasses() {
        return OBJECT_CLASSES;
    }

    @Override
    public AuthorizationObjectEvidence resolve(AuthorizationObjectEvidenceQuery query) {
        Objects.requireNonNull(query);
        if (!OBJECT_CLASSES.contains(query.objectClass())) {
            return AuthorizationObjectEvidence.notInstalled();
        }
        String bindingClass = switch (query.objectClass()) {
            case "SUBJECT_MAPPING_EXCEPTION", "JOB" -> "SOURCE";
            default -> query.objectClass();
        };
        OwnerBinding binding = bindings.get(
                new BindingKey(bindingClass, query.objectTokenDigest()));
        if (binding == null) {
            return AuthorizationObjectEvidence.unavailable();
        }
        Set<AuthorizationScopeEvidence> scopes = new HashSet<>();
        if ("JOB".equals(query.objectClass())) {
            scopes.add(new AuthorizationScopeEvidence(
                    AuthorizationScopeAnchor.TECHNICAL_OBJECT, null, null));
        } else {
            binding.ownerAccountIds().forEach(account -> scopes.add(
                    new AuthorizationScopeEvidence(
                            AuthorizationScopeAnchor.OWNED_SOURCE, account, null)));
            binding.ownerOrganizationIds().forEach(organization -> scopes.add(
                    new AuthorizationScopeEvidence(
                            AuthorizationScopeAnchor.OWNED_SOURCE, null, organization)));
        }
        String purpose = "SUBJECT_MAPPING_EXCEPTION".equals(query.objectClass())
                ? "SUBJECT_MAPPING_EXCEPTION_REPAIR" : query.actionId();
        Set<String> fieldAllowlist = "SUBJECT_MAPPING_EXCEPTION".equals(query.objectClass())
                ? SUBJECT_MAPPING_FIELDS : Set.of();
        return new AuthorizationObjectEvidence(
                AuthorizationEvidenceAvailability.AVAILABLE,
                scopes,
                purpose,
                fieldAllowlist,
                null,
                null,
                Set.of(),
                false,
                binding.relationVersion(),
                0,
                0,
                query.expectedObjectVersion(),
                Optional.empty());
    }

    private static CatalogOwnerEvidenceProvider parse(JsonNode root) {
        requireExact(root, Set.of("version", "bindings"));
        if (!PROFILE_VERSION.equals(text(root, "version"))) {
            throw invalid();
        }
        JsonNode values = root.required("bindings");
        if (!values.isArray() || values.isEmpty()) {
            throw invalid();
        }
        Map<BindingKey, OwnerBinding> bindings = new HashMap<>();
        values.forEach(value -> {
            requireExact(value, Set.of(
                    "objectClass",
                    "objectId",
                    "ownerAccountIds",
                    "ownerOrganizationIds",
                    "relationVersion"));
            String objectClass = text(value, "objectClass");
            String objectId = text(value, "objectId");
            if (!OBJECT_CLASSES.contains(objectClass)
                    || !objectId.matches(objectClass.equals("SOURCE")
                            ? "^SRC-P[01]-[A-Z-]+-[0-9]{3}$"
                            : "^DEP-P[01]-[A-Z-]+-[0-9]{3}$")) {
                throw invalid();
            }
            Set<UUID> accounts = uuids(value, "ownerAccountIds");
            Set<UUID> organizations = uuids(value, "ownerOrganizationIds");
            if (accounts.isEmpty() && organizations.isEmpty()) {
                throw invalid();
            }
            JsonNode relationVersion = value.required("relationVersion");
            if (!relationVersion.isIntegralNumber() || relationVersion.asLong() < 1) {
                throw invalid();
            }
            BindingKey key = new BindingKey(
                    objectClass,
                    sha256(objectId.getBytes(StandardCharsets.UTF_8)));
            OwnerBinding previous = bindings.put(
                    key,
                    new OwnerBinding(accounts, organizations, relationVersion.asLong()));
            if (previous != null) {
                throw invalid();
            }
        });
        Set<BindingKey> expected = new HashSet<>();
        FrozenDataCatalogPolicy.expectedSourceIds().forEach(sourceId -> expected.add(
                new BindingKey(
                        "SOURCE", sha256(sourceId.getBytes(StandardCharsets.UTF_8)))));
        FrozenDataCatalogPolicy.expectedDependencies().values().forEach(dependencyId -> expected.add(
                new BindingKey(
                        "DEPENDENCY", sha256(dependencyId.getBytes(StandardCharsets.UTF_8)))));
        if (!bindings.keySet().equals(expected)) {
            throw invalid();
        }
        return new CatalogOwnerEvidenceProvider(bindings);
    }

    private static Set<UUID> uuids(JsonNode parent, String field) {
        JsonNode values = parent.required(field);
        if (!values.isArray()) {
            throw invalid();
        }
        Set<UUID> result = new HashSet<>();
        values.forEach(value -> {
            if (!value.isTextual()) {
                throw invalid();
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(value.asText());
            } catch (IllegalArgumentException malformed) {
                throw invalid();
            }
            if (uuid.version() != 7 || uuid.variant() != 2 || !result.add(uuid)) {
                throw invalid();
            }
        });
        return Set.copyOf(result);
    }

    private static void requireExact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        Set<String> actual = new HashSet<>();
        node.forEachEntry((name, ignored) -> actual.add(name));
        if (!actual.equals(expected)) {
            throw invalid();
        }
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid();
        }
        return value.asText();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_OWNER_BINDINGS_INVALID");
    }

    private record BindingKey(String objectClass, String objectTokenDigest) {}

    private record OwnerBinding(
            Set<UUID> ownerAccountIds,
            Set<UUID> ownerOrganizationIds,
            long relationVersion) {
        private OwnerBinding {
            ownerAccountIds = Set.copyOf(ownerAccountIds);
            ownerOrganizationIds = Set.copyOf(ownerOrganizationIds);
        }
    }

    private record Snapshot(
            Object fileKey,
            long size,
            FileTime lastModified,
            Set<PosixFilePermission> permissions) {
        private boolean sameFile(Snapshot other) {
            return fileKey.equals(other.fileKey)
                    && size == other.size
                    && lastModified.equals(other.lastModified)
                    && permissions.equals(other.permissions);
        }
    }
}
