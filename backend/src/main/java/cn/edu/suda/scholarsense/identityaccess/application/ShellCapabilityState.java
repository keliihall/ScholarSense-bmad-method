package cn.edu.suda.scholarsense.identityaccess.application;

public enum ShellCapabilityState {
    AVAILABLE("available"),
    NOT_INSTALLED("not-installed"),
    UNAVAILABLE("unavailable");

    private final String wireName;

    ShellCapabilityState(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
