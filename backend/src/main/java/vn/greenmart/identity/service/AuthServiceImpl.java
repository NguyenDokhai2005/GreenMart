package vn.greenmart.identity.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.identity.dto.*;
import vn.greenmart.identity.repository.AuthUserRepository;
import vn.greenmart.identity.repository.AuthUserRepository.User;
import vn.greenmart.identity.repository.RefreshTokenStore;
import vn.greenmart.identity.security.JwtTokens;
import vn.greenmart.identity.security.AuthRateLimiter;
import vn.greenmart.common.exception.RateLimitException;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class AuthServiceImpl implements AuthService {
    private final AuthUserRepository users;
    private final RefreshTokenStore refresh;
    private final JwtTokens jwt;
    private final PasswordEncoder passwords;
    private final AuditLogRepository audit;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String dummyHash;
    private final AuthRateLimiter limits;

    public AuthServiceImpl(AuthUserRepository users, RefreshTokenStore refresh, JwtTokens jwt,
            PasswordEncoder passwords, AuditLogRepository audit, Clock clock, PlatformTransactionManager manager, AuthRateLimiter limits) {
        this.users=users; this.refresh=refresh; this.jwt=jwt; this.passwords=passwords; this.audit=audit;
        this.clock=clock; this.tx=new TransactionTemplate(manager);
        this.limits=limits;
        dummyHash=passwords.encode("dummy-password-for-unknown-user");
    }
    private record Attempt(TokenResponse token, AppException error) {}
    @Override public TokenResponse login(AuthRequests.Login request) {
        Attempt outcome=tx.execute(status -> {
            User user=users.lock(request.username()).orElse(null);
            if (user==null || !user.active()) {
                // Keep BCrypt work for unknown/inactive accounts without exposing their status.
                matches(request.password(),user==null?dummyHash:user.passwordHash());
                log(user,"LOGIN_FAILED",Map.of("reason","INVALID_CREDENTIALS"));
                return new Attempt(null,new AppException(ErrorCode.INVALID_CREDENTIALS));
            }
            OffsetDateTime now=OffsetDateTime.now(clock);
            boolean passwordMatches=matches(request.password(),user.passwordHash());
            if (user.lockedUntil()!=null && user.lockedUntil().isAfter(now)) {
                log(user,"LOGIN_FAILED",Map.of("reason","ACCOUNT_LOCKED"));
                return new Attempt(null,new AppException(ErrorCode.INVALID_CREDENTIALS));
            }
            if (!passwordMatches) {
                int failures=(user.lockedUntil()!=null?0:user.failures())+1;
                OffsetDateTime until=failures>=5?now.plusMinutes(15):null;
                users.loginState(user.id(),failures,until);
                log(user,"LOGIN_FAILED",Map.of("remainingAttempts",Math.max(0,5-failures)));
                if (until!=null) {
                    log(user,"ACCOUNT_LOCKED",Map.of("lockedUntil",until.toString()));
                    return new Attempt(null,new AppException(ErrorCode.INVALID_CREDENTIALS));
                }
                return new Attempt(null,new AppException(ErrorCode.INVALID_CREDENTIALS));
            }
            users.loginState(user.id(),0,null);
            return new Attempt(tokens(user,null),null);
        });
        // Throw only after commit: wrong-password counters and audit must survive HTTP 401.
        if (outcome.error()!=null) throw outcome.error();
        return outcome.token();
    }
    private User requireUser(long id, boolean lock, ErrorCode failure) {
        return users.find(id,lock).filter(User::active).orElseThrow(()->new AppException(failure));
    }
    private TokenResponse tokens(User user, String previous) {
        CurrentUser current=users.current(user);
        var session=previous==null?refresh.issue(user.id()):refresh.rotate(user.id(),previous);
        String access=jwt.issue(user.id(),user.role(),session.id());
        return new TokenResponse(access,session.token(),"Bearer",900,current);
    }
    @Override public TokenResponse refresh(String token) {
        long id=refresh.verify(token);
        return tx.execute(status -> {
            User user=requireUser(id,true,ErrorCode.INVALID_REFRESH_TOKEN);
            if (user.lockedUntil()!=null && user.lockedUntil().isAfter(OffsetDateTime.now(clock)))
                throw new AppException(ErrorCode.INVALID_REFRESH_TOKEN);
            return tokens(user,token);
        });
    }
    @Override public void logout(String token) {
        refresh.revoke(token);
    }
    @Override public CurrentUser me(long userId) {
        return tx.execute(status -> users.current(requireUser(userId,false,ErrorCode.UNAUTHORIZED)));
    }
    @Override public String activeRole(long userId, String sessionId) {
        if (!refresh.isSessionActive(userId,sessionId)) throw new AppException(ErrorCode.UNAUTHORIZED);
        return tx.execute(status -> users.activeRole(userId).orElseThrow(()->new AppException(ErrorCode.UNAUTHORIZED)));
    }
    @Override public void changePassword(long userId, AuthRequests.ChangePassword request) {
        try {
            long retryAfter=limits.retryAfterSeconds(AuthRateLimiter.Bucket.PASSWORD_USER,Long.toString(userId));
            if (retryAfter>0) throw new RateLimitException(ErrorCode.PASSWORD_RATE_LIMITED,retryAfter);
        } catch (DataAccessException exception) { throw new AppException(ErrorCode.AUTH_UNAVAILABLE); }
        AppException failure=tx.execute(status -> {
            User user=requireUser(userId,true,ErrorCode.UNAUTHORIZED);
            if (!matches(request.currentPassword(),user.passwordHash())) {
                log(user,"CHANGE_PASSWORD_FAILED",Map.of("reason","WRONG_PASSWORD"));
                return new AppException(ErrorCode.WRONG_PASSWORD);
            }
            if (request.newPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) {
                throw new AppException(ErrorCode.VALIDATION_ERROR,"Mật khẩu BCrypt không được vượt quá 72 byte UTF-8");
            }
            if (matches(request.newPassword(), user.passwordHash())) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "Mật khẩu mới phải khác mật khẩu hiện tại");
            }
            String encoded=passwords.encode(request.newPassword());
            // Same user row lock as login/refresh: no concurrent refresh can escape revokeAll.
            // Redis failure aborts password update. A later DB failure can revoke sessions, never restore them.
            refresh.revokeAll(userId);
            users.password(userId,encoded);
            log(user,"CHANGE_PASSWORD",Map.of());
            return null;
        });
        if (failure!=null) throw failure;
    }
    private void log(User user, String action, Map<String,Object> details) {
        var entry=new AuditLog();
        entry.setUserId(user==null?null:user.id()); entry.setAction(action); entry.setEntity("app_user");
        entry.setEntityId(user==null?null:Long.toString(user.id())); entry.setDetail(details); audit.save(entry);
    }
    private boolean matches(String raw, String encoded) {
        // BCrypt must not accept a different password merely because its first 72 bytes match.
        return raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72 && passwords.matches(raw,encoded);
    }

    @Override
    public String getFullName(long userId) {
        return tx.execute(status -> requireUser(userId, false, ErrorCode.UNAUTHORIZED).fullName());
    }
}
