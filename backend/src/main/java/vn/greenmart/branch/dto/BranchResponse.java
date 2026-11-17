package vn.greenmart.branch.dto;

public record BranchResponse(long id, String code, String name, String address, boolean active) {
}
