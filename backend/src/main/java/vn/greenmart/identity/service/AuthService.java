package vn.greenmart.identity.service;

import vn.greenmart.identity.dto.*;

public interface AuthService {
    TokenResponse login(AuthRequests.Login request);
    TokenResponse refresh(String token);
    void logout(String token);
    CurrentUser me(long userId);
    String activeRole(long userId, String sessionId);
    void changePassword(long userId, AuthRequests.ChangePassword request);
    String getFullName(long userId);
}
