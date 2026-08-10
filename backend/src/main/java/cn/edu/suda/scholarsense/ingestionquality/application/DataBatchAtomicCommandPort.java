package cn.edu.suda.scholarsense.ingestionquality.application;

/** The sole owner-write boundary for the four data-batch lifecycle transitions. */
public interface DataBatchAtomicCommandPort {
    DataBatchAtomicCommandResult receive(ReceiveDataBatchAtomicCommand command);

    DataBatchAtomicCommandResult seal(SealDataBatchAtomicCommand command);

    DataBatchAtomicCommandResult commitQualityEvaluation(
            CommitDataBatchQualityEvaluationCommand command);

    DataBatchAtomicCommandResult publish(PublishDataBatchAtomicCommand command);
}
