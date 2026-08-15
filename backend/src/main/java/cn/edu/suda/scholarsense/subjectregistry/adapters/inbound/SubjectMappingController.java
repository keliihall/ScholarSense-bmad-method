package cn.edu.suda.scholarsense.subjectregistry.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.shared.observability.HttpTraceContext;
import cn.edu.suda.scholarsense.subjectregistry.application.ActorContext;
import cn.edu.suda.scholarsense.subjectregistry.application.RepairSubjectMappingCommand;
import cn.edu.suda.scholarsense.subjectregistry.application.RepairSubjectMappingResult;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingExceptionView;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryApplicationException;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryService;
import cn.edu.suda.scholarsense.subjectregistry.domain.CorrectionReason;
import cn.edu.suda.scholarsense.subjectregistry.domain.CorrectionType;
import cn.edu.suda.scholarsense.subjectregistry.domain.LinkType;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionCode;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionStatus;
import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRef;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectLink;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(SubjectRegistryService.class)
@Validated
@RequestMapping(value = "/api/v1/subject-mapping-exceptions",
        produces = MediaType.APPLICATION_JSON_VALUE)
public final class SubjectMappingController {
    private final SubjectRegistryService service;
    private final InternalSessionIdentityPort identities;
    private final String tenantId;

    public SubjectMappingController(
            SubjectRegistryService service, InternalSessionIdentityPort identities) {
        this(service, identities, "tenant-suda");
    }

    public SubjectMappingController(
            SubjectRegistryService service, InternalSessionIdentityPort identities, String tenantId) {
        this.service = Objects.requireNonNull(service);
        this.identities = Objects.requireNonNull(identities);
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 128) {
            throw new IllegalArgumentException("tenantId");
        }
        this.tenantId = tenantId;
    }

    @GetMapping
    public ResponseEntity<ExceptionPage> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        String traceId = trace(request);
        ActorContext actor = actor(request, traceId);
        int offset;
        try {
            offset = Math.multiplyExact(page, size);
        } catch (ArithmeticException overflow) {
            throw new SubjectMappingRequestException(overflow);
        }
        MappingExceptionStatus filter = status == null ? null : parseStatus(status);
        List<SubjectMappingExceptionView> selected = filter == null
                ? service.listExceptions(offset, size, actor, traceId)
                : filtered(offset, size, filter, actor, traceId);
        boolean hasMore = selected.size() > size;
        List<ExceptionItem> items = selected.stream().limit(size)
                .map(SubjectMappingController::item).toList();
        return ok(new ExceptionPage(items, page, size, hasMore));
    }

    @GetMapping("/{exceptionId}")
    public ResponseEntity<ExceptionItem> detail(
            @PathVariable UUID exceptionId, HttpServletRequest request) {
        requireUuidV7(exceptionId);
        String traceId = trace(request);
        SubjectMappingExceptionView view =
                service.detailException(exceptionId, actor(request, traceId), traceId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .eTag("\"" + view.aggregateVersion() + "\"")
                .body(item(view));
    }

    @PostMapping(value = "/{exceptionId}/repair", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RepairResponse> repair(
            @PathVariable UUID exceptionId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128)
                    String idempotencyKey,
            @Valid @RequestBody RepairRequest body,
            HttpServletRequest request) {
        requireUuidV7(exceptionId);
        requireUuidV7(body.sourceStudentRef());
        body.targetStudentRefs().forEach(SubjectMappingController::requireUuidV7);
        RepairSemantics semantics = semantics(body.reasonCode(), body.relationType());
        String traceId = trace(request);
        SubjectLink link = SubjectLink.of(
                semantics.linkType(), StudentRef.of(body.sourceStudentRef()),
                body.targetStudentRefs().stream().map(StudentRef::of).toList());
        RepairSubjectMappingResult result = service.repair(new RepairSubjectMappingCommand(
                exceptionId, body.expectedAggregateVersion(), idempotencyKey,
                semantics.reason(), body.sourceWatermark(), semantics.correctionType(),
                link, actor(request, traceId), traceId));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(new RepairResponse(
                        result.exceptionId(), wireStatus(result.status()), result.aggregateVersion(),
                        result.correctionEventId(), List.of(result.recomputeRequestId()), traceId));
    }

    private List<SubjectMappingExceptionView> filtered(
            int offset, int size, MappingExceptionStatus filter,
            ActorContext actor, String traceId) {
        ArrayList<SubjectMappingExceptionView> matches = new ArrayList<>();
        int rawOffset = 0;
        int guard = 0;
        while (matches.size() < offset + size + 1 && guard++ < 1000) {
            List<SubjectMappingExceptionView> batch =
                    service.listExceptions(rawOffset, 100, actor, traceId);
            if (batch.isEmpty()) break;
            int usable = Math.min(100, batch.size());
            batch.subList(0, usable).stream().filter(item -> item.status() == filter)
                    .forEach(matches::add);
            rawOffset += usable;
            if (batch.size() <= 100) break;
        }
        if (offset >= matches.size()) return List.of();
        return List.copyOf(matches.subList(offset, Math.min(matches.size(), offset + size + 1)));
    }

    private ActorContext actor(HttpServletRequest request, String traceId) {
        HttpSession session;
        try {
            session = request.getSession(false);
        } catch (IllegalStateException invalidated) {
            throw forbidden();
        }
        if (session == null) throw forbidden();
        InternalSessionIdentity identity;
        try {
            identity = identities.current(session.getId(), request.getRemoteAddr(), traceId);
        } catch (InternalSessionIdentityException failure) {
            if (failure.reason() == InternalSessionIdentityException.Reason.DEPENDENCY_UNAVAILABLE) {
                throw new SubjectRegistryApplicationException(
                        "SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE");
            }
            throw forbidden();
        } catch (RuntimeException unavailable) {
            throw new SubjectRegistryApplicationException("SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE");
        }
        if (identity == null || !identity.authenticated()
                || identity.actorPseudonym() == null || identity.actorPseudonym().isBlank()) {
            throw forbidden();
        }
        return new ActorContext(
                tenantId, identity.actorPseudonym(), identity.actorPseudonym(), request.getRemoteAddr());
    }

    static String trace(HttpServletRequest request) {
        return HttpTraceContext.traceId(request);
    }

    private static ExceptionItem item(SubjectMappingExceptionView view) {
        return new ExceptionItem(
                view.exceptionId(), wireStatus(view.status()), view.subjectOfficialRef().orElse(null),
                wireExceptionCode(view.exceptionCode()), view.sourceSystem(), view.sourceOwner(),
                view.detectedAt());
    }

    private static MappingExceptionStatus parseStatus(String value) {
        return switch (value) {
            case "open" -> MappingExceptionStatus.OPEN;
            case "repairing" -> MappingExceptionStatus.IN_REVIEW;
            case "resolved" -> MappingExceptionStatus.RESOLVED;
            case "dismissed" -> MappingExceptionStatus.DISMISSED;
            default -> throw new SubjectMappingRequestException();
        };
    }

    private static String wireStatus(MappingExceptionStatus status) {
        return switch (status) {
            case OPEN -> "open";
            case IN_REVIEW -> "repairing";
            case RESOLVED -> "resolved";
            case DISMISSED -> "dismissed";
        };
    }

    private static String wireExceptionCode(MappingExceptionCode code) {
        return code == MappingExceptionCode.REISSUE_UNPROVEN
                ? "IDENTIFIER_REISSUE_UNPROVEN" : code.name();
    }

    private static RepairSemantics semantics(String reasonCode, String relationType) {
        return switch (reasonCode) {
            case "AUTHORITY_CORRECTION", "IDENTIFIER_REISSUED" -> requireRelation(
                    relationType, "alias", new RepairSemantics(
                            CorrectionReason.AUTHORITY_CORRECTION,
                            CorrectionType.CORRECT, LinkType.ALIAS));
            case "SUBJECT_MERGED" -> requireRelation(
                    relationType, "merged-into", new RepairSemantics(
                            CorrectionReason.AUTHORITY_MERGE,
                            CorrectionType.MERGE, LinkType.MERGED_INTO));
            case "SUBJECT_SPLIT" -> requireRelation(
                    relationType, "split-into", new RepairSemantics(
                            CorrectionReason.AUTHORITY_SPLIT,
                            CorrectionType.SPLIT, LinkType.SPLIT_INTO));
            case "MAPPING_REVOKED" -> requireRelation(
                    relationType, "alias", new RepairSemantics(
                            CorrectionReason.AUTHORITY_REVOCATION,
                            CorrectionType.REVOKE, LinkType.ALIAS));
            default -> throw new SubjectMappingRequestException();
        };
    }

    private static RepairSemantics requireRelation(
            String actual, String expected, RepairSemantics semantics) {
        if (!expected.equals(actual)) throw new SubjectMappingRequestException();
        return semantics;
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new SubjectMappingRequestException();
        }
    }

    private static SubjectRegistryApplicationException forbidden() {
        return new SubjectRegistryApplicationException("SUBJECT_REGISTRY_FORBIDDEN");
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").body(body);
    }

    public record RepairRequest(
            @Min(1) @Max(SubjectMapping.MAX_VERSION) long expectedAggregateVersion,
            @NotBlank String reasonCode,
            @NotBlank @Size(max = 128) String sourceWatermark,
            @NotBlank String relationType,
            @NotNull UUID sourceStudentRef,
            @NotNull @Size(max = 16) List<@NotNull UUID> targetStudentRefs) {
        public RepairRequest { targetStudentRefs = List.copyOf(targetStudentRefs); }
    }

    public record ExceptionItem(
            UUID exceptionId, String status, String subjectOfficialRef, String exceptionCode,
            String sourceSystem, String sourceOwner, java.time.Instant detectedAt) {}
    public record ExceptionPage(List<ExceptionItem> items, int page, int size, boolean hasMore) {
        public ExceptionPage { items = List.copyOf(items); }
    }
    public record RepairResponse(
            UUID exceptionId, String status, long aggregateVersion,
            UUID correctionId, List<UUID> jobIds, String traceId) {
        public RepairResponse { jobIds = List.copyOf(jobIds); }
    }
    private record RepairSemantics(
            CorrectionReason reason, CorrectionType correctionType, LinkType linkType) {}
}
