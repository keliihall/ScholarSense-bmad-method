package cn.edu.suda.scholarsense.ingestionquality.application;

public enum DataBatchCommandType {
    RECEIVE("data-batch.receive"),
    SEAL("data-batch.seal"),
    EVALUATE("data-batch.evaluate"),
    PUBLISH("data-batch.publish");

    private final String action;

    DataBatchCommandType(String action) {
        this.action = action;
    }

    public String action() {
        return action;
    }
}
