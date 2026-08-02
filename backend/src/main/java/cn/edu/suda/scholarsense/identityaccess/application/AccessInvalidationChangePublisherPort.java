package cn.edu.suda.scholarsense.identityaccess.application;

public interface AccessInvalidationChangePublisherPort {
    void publish(CommittedIdentityChangeSet changeSet);

    void publish(CommittedResponsibilityChangeSet changeSet);

    static AccessInvalidationChangePublisherPort noOp() {
        return new AccessInvalidationChangePublisherPort() {
            @Override
            public void publish(CommittedIdentityChangeSet changeSet) {}

            @Override
            public void publish(
                    CommittedResponsibilityChangeSet changeSet) {}
        };
    }
}
