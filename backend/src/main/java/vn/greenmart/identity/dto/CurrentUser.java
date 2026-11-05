package vn.greenmart.identity.dto;

import java.util.List;

public record CurrentUser(Long id, String username, String fullName, String role, List<Branch> branches) {
    public record Branch(Long id, String code, String name) {}
}
