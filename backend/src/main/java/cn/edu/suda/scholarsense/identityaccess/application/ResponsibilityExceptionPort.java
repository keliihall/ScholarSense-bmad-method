package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientReason;

public interface ResponsibilityExceptionPort {
    void openOrUpdate(
            String collegeOrganizationRefDigest,
            String studentSourceRefDigest,
            ResponsibilityRecipientReason reason,
            long sourceVersion,
            long sourceWatermark,
            String traceId);

    void resolve(
            String studentSourceRefDigest,
            ResponsibilityRecipientReason reason,
            long sourceVersion,
            long sourceWatermark,
            String traceId);
}
