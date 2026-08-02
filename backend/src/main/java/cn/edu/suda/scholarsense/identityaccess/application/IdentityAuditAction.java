package cn.edu.suda.scholarsense.identityaccess.application;

public enum IdentityAuditAction {
    SESSION_LOGIN("identity.session.login"),
    SESSION_REFRESH("identity.session.refresh"),
    SESSION_LOGOUT("identity.session.logout"),
    SESSION_ACCOUNT_SWITCH("identity.session.account-switch"),
    HOST_INPUT_REJECT("identity.host.input.reject"),
    SESSION_VIEW("identity.session.view"),
    SYNC_APPLIED("identity.sync.applied"),
    SYNC_REJECTED("identity.sync.rejected"),
    SYNC_FAILED("identity.sync.failed"),
    SYNC_RECONCILED("identity.sync.reconciled"),
    RESPONSIBILITY_SYNC_APPLIED("responsibility.sync.applied"),
    RESPONSIBILITY_SYNC_REJECTED("responsibility.sync.rejected"),
    RESPONSIBILITY_SYNC_RECONCILED("responsibility.sync.reconciled"),
    RESPONSIBILITY_V2_RECONCILED("responsibility.v2.reconciled"),
    RESPONSIBILITY_V2_CUTOVER_REQUESTED(
            "responsibility.v2.cutover.requested"),
    RESPONSIBILITY_V2_CUTOVER_DENIED(
            "responsibility.v2.cutover.denied"),
    RESPONSIBILITY_V2_CUTOVER_FAILED(
            "responsibility.v2.cutover.failed"),
    RESPONSIBILITY_V2_ACTIVATED("responsibility.v2.activated"),
    RESPONSIBILITY_EXCEPTION_OPENED("responsibility.exception.opened"),
    RESPONSIBILITY_EXCEPTION_RESOLVED("responsibility.exception.resolved"),
    ACCESS_INVALIDATION_PUBLISHED("access.invalidation.published");

    private final String code;

    IdentityAuditAction(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
