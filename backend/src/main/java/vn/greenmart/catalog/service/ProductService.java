package vn.greenmart.catalog.service;

import jakarta.servlet.http.HttpServletRequest;
import vn.greenmart.catalog.dto.ProductLookupResponse;
import vn.greenmart.catalog.dto.ProductRequest;
import vn.greenmart.catalog.dto.ProductResponse;
import vn.greenmart.common.pagination.PageResponse;

public interface ProductService {
    PageResponse<ProductResponse> listProducts(String q, Long categoryId, Boolean active, Integer page, Integer size, HttpServletRequest request);
    ProductResponse createProduct(ProductRequest request);
    ProductResponse getProduct(Long id);
    ProductResponse updateProduct(Long id, ProductRequest request);
    ProductLookupResponse lookupProduct(String code, Long branchId);
}
