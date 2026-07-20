package cn.edu.suda.scholarsense.shared.time;

/** Supplies time only after a previously collected synchronization observation is validated. */
@FunctionalInterface
public interface TrustedTimeSource {
    TrustedTime now();
}
