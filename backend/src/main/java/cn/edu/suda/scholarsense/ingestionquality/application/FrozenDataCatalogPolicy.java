package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuntimeEvidenceClaim;
import java.util.ArrayList;
import java.util.List;

/** Runtime projection of the locked DCC/QG invariants; the Python checker remains the lock authority. */
public final class FrozenDataCatalogPolicy implements CatalogContractPolicyPort {
    @Override
    public List<CatalogContractViolation> validate(DataSourceCatalog catalog) {
        List<CatalogContractViolation> failures = new ArrayList<>();
        if (catalog.sources().size() != 17) failures.add(failure("DCC_SOURCE_SET_INVALID", "sources"));
        if (catalog.dependencies().size() != 11) failures.add(failure("DCC_DEPENDENCY_SET_INVALID", "dependencies"));
        for (int index = 0; index < catalog.sources().size(); index++) {
            var source = catalog.sources().get(index);
            String path = "sources[" + index + "]";
            if (source.metadata().ownerName().isBlank() || "未登记".equals(source.metadata().ownerName())) {
                failures.add(failure("DCC_OWNER_MISSING", path + ".metadata.ownerName"));
            }
            if (source.metadata().businessKeys().isEmpty() || source.metadata().contractTests().isEmpty()) {
                failures.add(failure("DCC_SOURCE_DESCRIPTOR_INVALID", path + ".metadata"));
            }
            if (source.runtimeEvidenceClaim() != RuntimeEvidenceClaim.TARGET_VERIFIED) {
                failures.add(failure("DCC_EVIDENCE_MISSING", path + ".evidenceUri"));
            }
        }
        return List.copyOf(failures);
    }

    private static CatalogContractViolation failure(String code, String path) {
        return new CatalogContractViolation(code, path);
    }
}
