package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.HistoricalWindow;
import java.util.List;
import java.util.Set;

public interface HistoricalWindowPort {
    List<HistoricalWindow> findBySubjectRefs(Set<String> subjectRefs);
}
