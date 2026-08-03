package cn.edu.suda.scholarsense.identityaccess.api;

/** Scope anchors that an object-owning module may prove through the public boundary. */
public enum AuthorizationScopeAnchor {
    VALID_DELEGATION_GRANT,
    INITIATOR,
    COLLEGE_AGGREGATE,
    GOVERNANCE_WORK_ITEM,
    SCHOOL_GOVERNANCE,
    SCHOOL_AGGREGATE,
    CURRENT_TRANSFER_ASSIGNMENT,
    OWNED_SOURCE,
    TECHNICAL_OBJECT
}
