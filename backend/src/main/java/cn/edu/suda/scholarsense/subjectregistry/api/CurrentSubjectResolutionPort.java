package cn.edu.suda.scholarsense.subjectregistry.api;

@FunctionalInterface
public interface CurrentSubjectResolutionPort {
    CurrentSubjectResolution resolve(CurrentSubjectResolutionQuery query);
}
