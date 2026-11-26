package vn.greenmart.identity.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.identity.dto.UserRequests;
import vn.greenmart.identity.dto.UserResponse;
import vn.greenmart.identity.repository.AuthUserRepository;
import vn.greenmart.identity.repository.RefreshTokenStore;
import vn.greenmart.identity.repository.UserAdminRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class UserAdminService {
    private final UserAdminRepository admin;
    private final AuthUserRepository users;
    private final RefreshTokenStore sessions;
    private final PasswordEncoder passwords;
    private final AuditLogRepository audit;
    private final Clock clock;

    @Transactional(readOnly=true)
    public PageResponse<UserResponse> list(UserRequests.Role role, Long branchId, Boolean active, String q, int page, int size, String sort) {
        SecurityUtils.requireRole("ADMIN", "OWNER");
        if(page<0 || size<1 || size>100 || (branchId!=null && branchId<=0)) throw new AppException(ErrorCode.VALIDATION_ERROR);
        var found=admin.search(role==null?null:role.name(),branchId,active,q,page,size,sort);
        return new PageResponse<>(found.ids().stream().map(this::response).toList(),page,size,found.total(),
                (int)((found.total()+size-1)/size));
    }
    @Transactional(readOnly=true)
    public UserResponse get(long id) {
        SecurityUtils.requireRole("ADMIN", "OWNER");
        return response(id);
    }
    public UserResponse create(UserRequests.Create request) {
        authorizeWrite();
        // Administration writes share this transaction lock, including concurrent creates.
        if (admin.usernameExists(request.username())) throw new AppException(ErrorCode.DUPLICATE_USERNAME);
        var branches=validateBranches(request.role(),request.branchIds());
        String hash=encode(request.password());
        long id=admin.create(request.username(),request.fullName(),request.role().name(),hash);
        admin.replaceBranches(id,branches);
        log("CREATE_USER",id,Map.of("role",request.role().name(),"branchIds",branches));
        return response(id);
    }
    public UserResponse update(long id, UserRequests.Update request) {
        long actor=authorizeWrite();
        var existing=target(id,true);
        if(actor==id && (!request.active() || !existing.role().equals(request.role().name())))
            throw new AppException(ErrorCode.SELF_ACTION_FORBIDDEN);
        var branches=validateBranches(request.role(),request.branchIds());
        boolean accessChanged=existing.active()!=request.active() || !existing.role().equals(request.role().name())
                || !admin.branches(id).equals(branches);
        // Same account row lock as login/refresh; Redis failure aborts the DB change.
        // If a later DB operation fails, revocation remains safe (the user can log in again).
        if(accessChanged) sessions.revokeAll(id);
        admin.update(id,request.fullName(),request.role().name(),request.active());
        admin.replaceBranches(id,branches);
        log("UPDATE_USER",id,Map.of("role",request.role().name(),"active",request.active(),"branchIds",branches));
        return response(id);
    }
    public void unlock(long id) {
        authorizeWrite(); target(id,true);
        users.loginState(id,0,null);
        log("UNLOCK_USER",id,Map.of());
    }
    public void resetPassword(long id, String password) {
        authorizeWrite(); target(id,true);
        String hash=encode(password);
        sessions.revokeAll(id);
        users.password(id,hash);
        log("RESET_USER_PASSWORD",id,Map.of());
    }
    private long authorizeWrite() {
        SecurityUtils.requireRole("ADMIN");
        admin.lockAdministration();
        long actor=SecurityUtils.getCurrentUserId();
        // Serialize administrator writes and recheck the actor after waiting, including cross-demotion.
        // An active ADMIN cannot remove their own role/activity; at least one ADMIN therefore remains.
        if(!users.activeRole(actor).filter("ADMIN"::equals).isPresent()) throw new AppException(ErrorCode.FORBIDDEN);
        return actor;
    }
    private AuthUserRepository.User target(long id, boolean lock) {
        if(id<=0) throw new AppException(ErrorCode.VALIDATION_ERROR);
        return users.find(id,lock).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND));
    }
    private UserResponse response(long id) {
        var user=target(id,false);
        return new UserResponse(user.id(),user.username(),user.fullName(),user.role(),user.active(),users.current(user).branches(),
                user.lockedUntil()!=null && user.lockedUntil().isAfter(OffsetDateTime.now(clock)),user.lockedUntil());
    }
    private List<Long> validateBranches(UserRequests.Role role, List<Long> branchIds) {
        if(role==UserRequests.Role.ADMIN || role==UserRequests.Role.OWNER) return List.of();
        if(branchIds==null || branchIds.isEmpty() || branchIds.stream().anyMatch(id->id==null || id<=0))
            throw new AppException(ErrorCode.VALIDATION_ERROR,"Vai trò này phải có ít nhất một chi nhánh hợp lệ");
        var sorted=branchIds.stream().distinct().sorted().toList();
        if(!admin.lockBranches(sorted)) throw new AppException(ErrorCode.VALIDATION_ERROR,"Chi nhánh không tồn tại hoặc đã ngừng hoạt động");
        return sorted;
    }
    private String encode(String password) {
        if(password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new AppException(ErrorCode.VALIDATION_ERROR,"Mật khẩu BCrypt không được vượt quá 72 byte UTF-8");
        return passwords.encode(password);
    }
    private void log(String action, long id, Map<String,Object> detail) {
        var entry=new AuditLog();
        entry.setUserId(SecurityUtils.getCurrentUserId()); entry.setAction(action); entry.setEntity("app_user");
        entry.setEntityId(Long.toString(id)); entry.setDetail(detail); audit.save(entry);
    }
}
