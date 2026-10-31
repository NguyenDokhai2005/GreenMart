package vn.greenmart.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

import java.util.Arrays;
import java.util.List;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static String getCurrentRole() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String headerRole = request.getHeader("X-User-Role");
            if (headerRole != null && !headerRole.trim().isEmpty()) {
                return headerRole.trim().toUpperCase();
            }
        }
        return "ANONYMOUS";
    }

    public static Long getCurrentUserId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String headerUserId = request.getHeader("X-User-Id");
            if (headerUserId != null && !headerUserId.trim().isEmpty()) {
                try {
                    return Long.parseLong(headerUserId.trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return 1L; 
    }

    public static void requireRole(String... allowedRoles) {
        String currentRole = getCurrentRole();
        boolean matched = Arrays.stream(allowedRoles).anyMatch(r -> r.equalsIgnoreCase(currentRole));
        if (!matched) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này");
        }
    }

    public static boolean canViewCostPrice() {
        String role = getCurrentRole();
        return List.of("ADMIN", "OWNER", "BRANCH_MANAGER").contains(role);
    }
}
