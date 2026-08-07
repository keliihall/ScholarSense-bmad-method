package cn.edu.suda.scholarsense.subjectregistry.domain;

public enum IdentifierType {
    STUDENT_NUMBER("student-number"),
    CARD_NUMBER("card-number"),
    CAMPUS_NETWORK_ACCOUNT("campus-network-account"),
    ACCOMMODATION_STUDENT_NUMBER("accommodation-student-number"),
    CAMPUS_ACCESS_CREDENTIAL("campus-access-credential"),
    DORM_ACCESS_CREDENTIAL("dorm-access-credential");

    private final String wireValue;

    IdentifierType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
