package cn.edu.suda.scholarsense.identityaccess.api;

@FunctionalInterface
public interface CurrentNaturalPersonBindingQueryPort {
    CurrentNaturalPersonBindingResult resolve(CurrentNaturalPersonBindingQuery query);
}
