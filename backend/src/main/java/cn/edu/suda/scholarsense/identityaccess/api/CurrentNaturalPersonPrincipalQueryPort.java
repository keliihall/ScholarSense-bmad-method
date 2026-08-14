package cn.edu.suda.scholarsense.identityaccess.api;

@FunctionalInterface
public interface CurrentNaturalPersonPrincipalQueryPort {
    CurrentNaturalPersonPrincipalResult resolve(CurrentNaturalPersonPrincipalQuery query);
}
