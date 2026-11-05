package vn.greenmart.identity.dto;

public record TokenResponse(String accessToken, String refreshToken, String tokenType, int expiresIn, CurrentUser user) {}
