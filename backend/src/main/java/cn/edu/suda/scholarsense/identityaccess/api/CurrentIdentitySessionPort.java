package cn.edu.suda.scholarsense.identityaccess.api;

/** Transport-neutral public identity surface for other bounded contexts. */
@FunctionalInterface
public interface CurrentIdentitySessionPort {
    CurrentIdentitySession current(String sessionPseudonym);
}
