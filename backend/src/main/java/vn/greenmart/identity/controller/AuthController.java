package vn.greenmart.identity.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.identity.dto.*;
import vn.greenmart.identity.service.AuthService;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService service;
    @PostMapping("/login") public TokenResponse login(@Valid @RequestBody AuthRequests.Login request) { return service.login(request); }
    @PostMapping("/refresh") public TokenResponse refresh(@Valid @RequestBody AuthRequests.Refresh request) { return service.refresh(request.refreshToken()); }
    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody AuthRequests.Refresh request) { service.logout(SecurityUtils.getCurrentUserId(),request.refreshToken()); }
    @GetMapping("/me") public CurrentUser me() { return service.me(SecurityUtils.getCurrentUserId()); }
    @PostMapping("/change-password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody AuthRequests.ChangePassword request) {
        service.changePassword(SecurityUtils.getCurrentUserId(),request);
    }
}
