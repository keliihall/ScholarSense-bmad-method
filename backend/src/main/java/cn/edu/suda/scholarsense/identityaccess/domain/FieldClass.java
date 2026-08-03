package cn.edu.suda.scholarsense.identityaccess.domain;

public enum FieldClass {
    BASIC("B"),
    IDENTITY("I"),
    CONTACT("C"),
    SENSITIVE_CARE("S"),
    EVIDENCE("E"),
    NARRATIVE("N"),
    GOVERNANCE("G"),
    TECHNICAL("T");

    private final String code;

    FieldClass(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
