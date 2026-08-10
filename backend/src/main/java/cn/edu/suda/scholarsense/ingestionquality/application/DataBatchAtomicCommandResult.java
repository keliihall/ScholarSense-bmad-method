package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;

/** Closed result of an atomic owner command. */
public record DataBatchAtomicCommandResult(Status status, DataBatchView response) {
    public enum Status { ACCEPTED, REPLAY }

    public DataBatchAtomicCommandResult {
        Objects.requireNonNull(status);
        Objects.requireNonNull(response);
    }
}
