package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import java.util.List;

public interface FindingRepository {
    void save(IntegrityFinding finding);

    default boolean saveIfAbsent(IntegrityFinding finding) {
        save(finding);
        return true;
    }

    boolean hasActivePermanentFinding();

    List<IntegrityFinding> activeFindings();
}
