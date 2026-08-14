package cn.edu.suda.scholarsense.ingestionquality.domain;

/**
 * Declares why a composition is being materialized. Bootstrap/import state is
 * never inferred from the absence of a prior eligibility fact.
 */
public enum QualityFuseEvaluationProvenance {
    REALTIME_ASSESSMENT,
    BOOTSTRAP_MATERIALIZATION
}
