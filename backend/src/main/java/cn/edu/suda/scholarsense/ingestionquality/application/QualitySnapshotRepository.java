package cn.edu.suda.scholarsense.ingestionquality.application;

/** Owner write/read boundary for immutable snapshots; Task 3 supplies the PostgreSQL adapter. */
public interface QualitySnapshotRepository extends QualitySnapshotReadPort {
    void insert(VerifiedQualitySnapshot snapshot);
}
