package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.function.Supplier;

@FunctionalInterface
public interface SessionTransactionPort {
    SessionCommandResult execute(Supplier<SessionCommandResult> work);
}
