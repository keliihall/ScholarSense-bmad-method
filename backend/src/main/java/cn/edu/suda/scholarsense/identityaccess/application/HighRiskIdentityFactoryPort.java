package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.UUID;

public interface HighRiskIdentityFactoryPort {
    UUID nextUuidV7();
}
