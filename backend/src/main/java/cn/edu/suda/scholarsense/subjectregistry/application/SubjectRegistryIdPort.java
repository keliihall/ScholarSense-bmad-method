package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRef;
import java.util.UUID;

public interface SubjectRegistryIdPort {
    UUID nextUuid();
    StudentRef nextStudentRef();
}
