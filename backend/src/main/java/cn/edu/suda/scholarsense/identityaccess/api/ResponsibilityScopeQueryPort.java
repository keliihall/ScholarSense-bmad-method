package cn.edu.suda.scholarsense.identityaccess.api;

/** Transport-neutral boundary consumed by later authorization and transfer stories. */
@FunctionalInterface
public interface ResponsibilityScopeQueryPort {
    ResponsibilityScopeView query(ResponsibilityScopeQuery query);
}
