SELECT subject_registry.id
FROM identity_access.ia_grant AS subject_registry;

CREATE VIEW identity_access.ia_shadow AS
SELECT * FROM subject_registry.ia_shadow;

CREATE VIEW identity_access.ia_nested_shadow AS
SELECT outer_row.id
FROM (
    SELECT subject_registry.id
    FROM identity_access.ia_grant AS subject_registry
) AS outer_row
JOIN subject_registry.ia_shadow ON true;
