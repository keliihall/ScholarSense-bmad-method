package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHead;
import java.util.Optional;

public interface VerificationRunRepository {
    void save(VerificationRun run);

    Optional<LedgerHead> lastHealthyWatermark();
}
