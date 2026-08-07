package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.UUID;

@FunctionalInterface
public interface MappingRecomputeIdPort {
    UUID nextId();
}
