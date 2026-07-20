package cn.edu.suda.scholarsense.identityaccess.application;

public enum AuditTokenDomain {
    ACTOR("actor", "ast"),
    OBJECT("object", "ost"),
    SOURCE_IP("source-ip", "ipt"),
    AGGREGATE("aggregate", "agt"),
    GRANT("grant", "gst");

    private final String wireName;
    private final String prefix;

    AuditTokenDomain(String wireName, String prefix) {
        this.wireName = wireName;
        this.prefix = prefix;
    }

    public String wireName() {
        return wireName;
    }

    public String prefix() {
        return prefix;
    }
}
