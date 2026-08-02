package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationAppliedFactObserver;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationBackfillProcessor;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.JdbcAccessInvalidationReconciler;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationExpiryWorker;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationImpactWorker;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationOutboxRelayPort;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.scheduling.annotation.Scheduled;

public final class AccessInvalidationScheduler {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AccessInvalidationOutboxRelayPort relay;
    private final AccessInvalidationImpactWorker impacts;
    private final AccessInvalidationExpiryWorker expiry;
    private final JdbcAccessInvalidationBackfillProcessor backfills;
    private final JdbcAccessInvalidationAppliedFactObserver appliedFacts;
    private final JdbcAccessInvalidationReconciler reconciliation;
    private final TrustedTimeSource time;
    private final String owner;

    public AccessInvalidationScheduler(
            AccessInvalidationOutboxRelayPort relay,
            AccessInvalidationImpactWorker impacts,
            AccessInvalidationExpiryWorker expiry,
            JdbcAccessInvalidationBackfillProcessor backfills,
            JdbcAccessInvalidationAppliedFactObserver appliedFacts,
            JdbcAccessInvalidationReconciler reconciliation,
            TrustedTimeSource time) {
        this.relay = relay;
        this.impacts = impacts;
        this.expiry = expiry;
        this.backfills = backfills;
        this.appliedFacts = appliedFacts;
        this.reconciliation = reconciliation;
        this.time = time;
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        this.owner =
                "access-invalidation-" + HexFormat.of().formatHex(random);
    }

    @Scheduled(
            initialDelayString =
                    "${scholarsense.access-invalidation.worker-poll:PT5S}",
            fixedDelayString =
                    "${scholarsense.access-invalidation.worker-poll:PT5S}")
    public void poll() {
        var now = time.now().instant();
        expiry.run(owner + "-expiry", 20, now);
        impacts.run(owner + "-impact", 10, 100, now);
        relay.relay(50, now);
        backfills.process(owner + "-backfill", 20, 25, now);
        appliedFacts.observe(100, now);
    }

    @Scheduled(
            fixedDelayString =
                    "${scholarsense.access-invalidation.reconciliation:PT15M}")
    public void incrementalReconciliation() {
        reconciliation.reconcileIncremental(
                time.now().instant(), 500);
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Shanghai")
    public void dailyReconciliation() {
        reconciliation.reconcileDaily(
                time.now().instant(), 1000);
    }
}
