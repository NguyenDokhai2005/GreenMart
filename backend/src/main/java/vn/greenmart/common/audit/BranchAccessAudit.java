package vn.greenmart.common.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.ErrorCode;

import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class BranchAccessAudit {
    private final AuditLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long userId, Long branchId, String method, String path, String traceId) {
        var detail = new HashMap<String, Object>();
        detail.put("method", method);
        detail.put("path", path);
        detail.put("traceId", traceId);
        if (branchId != null) detail.put("branchId", branchId);

        var entry = new AuditLog();
        entry.setUserId(userId);
        entry.setAction(ErrorCode.BRANCH_FORBIDDEN.name());
        entry.setEntity("branch");
        entry.setEntityId(branchId == null ? null : branchId.toString());
        entry.setDetail(detail);
        repository.save(entry);
    }
}
