package cn.edu.suda.scholarsense.shared.outbox;

public enum ActorType {
    USER("user"),
    ANONYMOUS("anonymous"),
    SERVICE("service");

    private final String wireName;

    ActorType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
