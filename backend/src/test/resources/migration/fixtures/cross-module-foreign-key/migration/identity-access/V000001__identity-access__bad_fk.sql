CREATE TABLE identity_access.ia_grant (
    subject_id uuid REFERENCES subject_registry.sr_subject(id)
);
