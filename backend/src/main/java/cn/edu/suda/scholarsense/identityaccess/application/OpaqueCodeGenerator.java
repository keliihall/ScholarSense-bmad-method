package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface OpaqueCodeGenerator {
    String generate();
}
