package vn.greenmart.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class UserRequests {
    private UserRequests() {}
    public enum Role { ADMIN, OWNER, BRANCH_MANAGER, CASHIER, STOREKEEPER }
    public record Create(
            @NotBlank @Size(min=3,max=60) @Pattern(regexp="[a-z0-9._]+") String username,
            @NotBlank @Size(max=120) String fullName,
            @NotNull Role role, List<Long> branchIds,
            @NotBlank @Size(min=8,max=100) @Pattern(regexp="(?s)(?=.*[\\p{L}])(?=.*[0-9]).*") String password) {}
    public record Update(@NotBlank @Size(max=120) String fullName, @NotNull Role role,
                         List<Long> branchIds, @NotNull Boolean active) {}
    public record ResetPassword(@NotBlank @Size(min=8,max=100) String newPassword) {}
}
