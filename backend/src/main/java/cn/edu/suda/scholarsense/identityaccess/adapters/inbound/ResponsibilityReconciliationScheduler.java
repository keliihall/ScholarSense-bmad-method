package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationJobPort;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import org.springframework.scheduling.annotation.Scheduled;

/** Asia/Shanghai 06:00 trigger plus bounded restart catch-up; it only persists jobs. */
public final class ResponsibilityReconciliationScheduler {
    static final ZoneId SCHEDULE_ZONE =
            ZoneId.of("Asia/Shanghai");
    static final LocalTime SCHEDULE_TIME =
            LocalTime.of(6, 0);
    static final Duration CATCH_UP_WINDOW =
            Duration.ofHours(24);
    static final LocalDate FIRST_BUSINESS_DATE =
            LocalDate.of(2026, 7, 30);
    private static final SecureRandom RANDOM =
            new SecureRandom();

    private final ResponsibilityReconciliationJobPort jobs;
    private final CheckpointKey key;
    private final TrustedTimeSource time;
    private final LocalDate firstBusinessDate;

    public ResponsibilityReconciliationScheduler(
            ResponsibilityReconciliationJobPort jobs,
            CheckpointKey key,
            TrustedTimeSource time) {
        this(jobs, key, time, FIRST_BUSINESS_DATE);
    }

    ResponsibilityReconciliationScheduler(
            ResponsibilityReconciliationJobPort jobs,
            CheckpointKey key,
            TrustedTimeSource time,
            LocalDate firstBusinessDate) {
        this.jobs = java.util.Objects.requireNonNull(jobs);
        this.time = java.util.Objects.requireNonNull(time);
        if (key == null
                || !"responsibility".equals(
                        key.consumerProjection())) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_RECONCILIATION_ROUTE_INVALID");
        }
        this.key = key;
        this.firstBusinessDate =
                java.util.Objects.requireNonNull(firstBusinessDate);
    }

    @Scheduled(
            cron = "0 0 6 * * *",
            zone = "Asia/Shanghai")
    public void scheduledTrigger() {
        catchUp(time.now().instant());
    }

    @Scheduled(
            initialDelayString =
                    "${scholarsense.responsibility-reconciliation.catch-up-poll:PT30S}",
            fixedDelayString =
                    "${scholarsense.responsibility-reconciliation.catch-up-poll:PT5M}")
    public void restartCatchUp() {
        catchUp(time.now().instant());
    }

    public void catchUp(Instant now) {
        ZonedDateTime local = now.atZone(SCHEDULE_ZONE);
        LocalDate today = local.toLocalDate();
        LocalDate newestDue = local.toLocalTime()
                        .isBefore(SCHEDULE_TIME)
                ? today.minusDays(1)
                : today;
        for (LocalDate businessDate = firstBusinessDate;
                !businessDate.isAfter(newestDue);
                businessDate = businessDate.plusDays(1)) {
            enqueueOrMiss(businessDate, now);
        }
    }

    private void enqueueOrMiss(
            LocalDate businessDate, Instant now) {
        if (jobs.terminalRunExists(key, businessDate)) {
            return;
        }
        Instant scheduledAt = businessDate
                .atTime(SCHEDULE_TIME)
                .atZone(SCHEDULE_ZONE)
                .toInstant();
        String traceId = randomHex(16);
        if (Duration.between(scheduledAt, now)
                        .compareTo(CATCH_UP_WINDOW)
                > 0) {
            jobs.miss(
                    key,
                    businessDate,
                    traceId,
                    "RESPONSIBILITY_SNAPSHOT_CATCH_UP_EXPIRED",
                    now);
        } else {
            jobs.enqueue(key, businessDate, traceId);
        }
    }

    private static String randomHex(int bytesCount) {
        byte[] bytes = new byte[bytesCount];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
