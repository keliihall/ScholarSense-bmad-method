package cn.edu.suda.scholarsense.identityaccess.api;

/** Public token domains available to trusted bounded contexts. */
public enum AuditTokenizationDomain {
    ACTOR("ast"),
    OBJECT("ost"),
    SOURCE_IP("ipt"),
    AGGREGATE("agt");

    private final String prefix;

    AuditTokenizationDomain(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
