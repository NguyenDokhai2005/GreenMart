package vn.greenmart.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

import java.util.Arrays;
import java.util.List;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static String getCurrentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }

        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse(null);
    }

    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Chưa đăng nhập");
        }

        Object principal = auth.getPrincipal();
        if (principal instanceof Number n) {
            return n.longValue();
        }

        try {
            return Long.parseLong(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void requireRole(String... allowedRoles) {
        String currentRole = getCurrentRole();
        if (currentRole == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Chưa đăng nhập");
        }

        boolean matched = Arrays.stream(allowedRoles).anyMatch(r -> r.equalsIgnoreCase(currentRole));
        if (!matched) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này");
        }
    }

    public static boolean canViewCostPrice() {
        String currentRole = getCurrentRole();
        if (currentRole == null) {
            return false;
        }
        return List.of("ADMIN", "OWNER", "BRANCH_MANAGER").contains(currentRole);
    }
}
