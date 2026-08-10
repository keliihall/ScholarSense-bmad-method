package cn.edu.suda.scholarsense.ingestionquality.application;

/** Database-linearized read-only view of database-owned command precedence. */
public interface DataBatchCommandReplayPort {
    DataBatchCommandPrecedence inspect(
            DataBatchIdempotencyScope scope, String requestDigest);
}
