CREATE INDEX ON sr_subject (id);
CREATE INDEX CONCURRENTLY ia_lookup ON sr_student (id);
CREATE INDEX IF NOT EXISTS ia_lookup_2 ON sr_enrollment (id);
CREATE/* separator */INDEX ia_lookup_3 ON sr_profile (id);
