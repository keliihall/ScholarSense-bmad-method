package cn.edu.suda.scholarsense.subjectregistry.adapters.inbound;

final class SubjectMappingRequestException extends RuntimeException {
    SubjectMappingRequestException() {
        super("SUBJECT_REGISTRY_REQUEST_INVALID");
    }

    SubjectMappingRequestException(Throwable cause) {
        super("SUBJECT_REGISTRY_REQUEST_INVALID", cause);
    }
}
