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
    SYNC_RECONCILED("identity.sync.reconciled");

    private final String code;

    IdentityAuditAction(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
