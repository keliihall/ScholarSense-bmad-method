package cn.edu.suda.scholarsense.ingestionquality.application;

/** Producer-owned persistence boundary; finalizers are lease-generation fenced. */
public interface QualityTaskRelayWorkPort {
    QualityTaskRelayClaim claimNext();

    /**
     * Holds the task route fence from the final current-route check through the external send and
     * its local finalizer. The permit is not a database transaction.
     */
    SendPermit acquireSendPermit(QualityTaskRelayClaim claim);

    interface SendPermit extends AutoCloseable {
        boolean authorized();
        boolean confirm(String receiptId);
        boolean retry(String errorCode);
        boolean fail(String errorCode);

        @Override
        void close();
    }
}
