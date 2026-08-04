package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuntimeEvidenceClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContract;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class CatalogFixtures {
    private CatalogFixtures() {}

    static DataSourceCatalog draft(UUID id, Instant now) {
        return DataSourceCatalog.draft(
                id, "DCC-1.0.0",
                List.of(new SourceContract(
                        "SRC-P0-CALENDAR-001", "business-calendar-control", "BC-1.0.0",
                        "QG-1.0.0", "evidence+sha256://" + "a".repeat(64),
                        RuntimeEvidenceClaim.TARGET_VERIFIED)),
                List.of(new DependencyBinding(
                        "SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001",
                        DependencyRequirement.REQUIRED, DependencyOperator.ALL_OF)),
                "sha256:" + "a".repeat(64), now);
    }
}
