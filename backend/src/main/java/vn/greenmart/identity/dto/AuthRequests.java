package vn.greenmart.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthRequests {
    private AuthRequests() {}
    public record Login(@NotBlank @Size(max=60) String username, @NotBlank @Size(max=100) String password) {}
    public record Refresh(@NotBlank @Size(max=256) String refreshToken) {}
    public record ChangePassword(@NotBlank @Size(max=100) String currentPassword,
            @NotBlank @Size(min=8,max=100) @Pattern(regexp="(?s)(?=.*[\\p{L}])(?=.*[0-9]).*") String newPassword) {}
}
