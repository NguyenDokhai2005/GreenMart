package vn.greenmart.identity.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record UserResponse(long id, String username, String fullName, String role, boolean active,
                           List<CurrentUser.Branch> branches, boolean locked, OffsetDateTime lockedUntil) {}
