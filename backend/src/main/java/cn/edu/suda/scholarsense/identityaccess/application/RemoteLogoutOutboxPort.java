package cn.edu.suda.scholarsense.identityaccess.application;

public interface RemoteLogoutOutboxPort {
    void enqueue(RemoteLogoutRequest request);
}
