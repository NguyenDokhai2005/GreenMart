package vn.greenmart.catalog.dto;

public record ProductLookupResponse(
    ProductResponse product,
    Long branchId,
    int available
) {}
