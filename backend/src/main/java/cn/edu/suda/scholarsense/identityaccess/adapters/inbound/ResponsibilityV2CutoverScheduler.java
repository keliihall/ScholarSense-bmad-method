package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverService;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverCommand;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverCommandSignaturePort;
import cn.edu.suda.scholarsense.identityaccess.application.UuidV7;
import cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Controlled source-authenticated V2 reconciliation and one-way cutover entrypoint. */
public final class ResponsibilityV2CutoverScheduler {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ResponsibilityV2CutoverService cutover;
    private final ResponsibilitySyncRepository repository;
    private final TrustedTimeSource time;
    private final ResponsibilityAuthorityRuntimeProfile profile;
    private final CheckpointKey key;
    private final ResponsibilityV2CutoverCommandSignaturePort signatures;

    public ResponsibilityV2CutoverScheduler(
            ResponsibilityV2CutoverService cutover,
            ResponsibilitySyncRepository repository,
            TrustedTimeSource time,
            ResponsibilityAuthorityRuntimeProfile profile,
            CheckpointKey key,
            ResponsibilityV2CutoverCommandSignaturePort signatures) {
        this.cutover = Objects.requireNonNull(cutover);
        this.repository = Objects.requireNonNull(repository);
        this.time = Objects.requireNonNull(time);
        this.profile = Objects.requireNonNull(profile);
        this.key = Objects.requireNonNull(key);
        this.signatures = Objects.requireNonNull(signatures);
        if (!"responsibility".equals(key.consumerProjection())
                || !"0 */15 * * * *".equals(profile.cutoverCron())) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_CUTOVER_SCHEDULE_INVALID");
        }
    }

    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Shanghai")
    public void reconcileAndActivate() {
        var now = time.now().instant().truncatedTo(
                java.time.temporal.ChronoUnit.MICROS);
        if (!profile.cutoverEnabled()
                || now.isBefore(profile.effectiveAt())
                || repository.v2ShadowActive(key)) {
            return;
        }
        cutover.execute(
                ResponsibilityV2CutoverCommand.signed(
                        java.util.UUID.fromString(
                                UuidV7.generate(now)),
                        key,
                        now.atZone(ZONE).toLocalDate(),
                        ResponsibilityV2CutoverCommand.CONTROLLED_OPERATOR,
                        ResponsibilityV2CutoverCommand.APPROVAL,
                        profile.contractProfileDigest(),
                        now,
                        randomHex(16),
                        signatures));
    }

    private static String randomHex(int bytesCount) {
        byte[] bytes = new byte[bytesCount];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
