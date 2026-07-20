package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface IdentityEstablishmentTransactionPort {
    void execute(Runnable work);
}
