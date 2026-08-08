package cn.edu.suda.scholarsense.ingestionquality.application;

public record SubjectWindowRecomputeWorkerResult(
        int claimed, int succeeded, int failed, int fenced) {}
