package vn.greenmart.catalog.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.catalog.domain.Category;
import vn.greenmart.catalog.domain.Product;
import vn.greenmart.catalog.dto.ProductLookupResponse;
import vn.greenmart.catalog.dto.ProductRequest;
import vn.greenmart.catalog.dto.ProductResponse;
import vn.greenmart.catalog.repository.CategoryRepository;
import vn.greenmart.catalog.repository.ProductRepository;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.pagination.PageUtils;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.inventory.service.InventoryService;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "sku", "name", "salePrice", "costPrice", "active");

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final AuditLogRepository auditLogRepository;
    private final InventoryService inventoryService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> listProducts(String q, Long categoryId, Boolean active, Integer page, Integer size, HttpServletRequest request) {
        String[] sortParams = (request != null) ? request.getParameterValues("sort") : null;
        Pageable pageable = PageUtils.createPageable(page, size, sortParams, Sort.by(Sort.Direction.ASC, "id"), ALLOWED_SORT_FIELDS);

        Set<Long> categoryIds = null;
        if (categoryId != null) {
            categoryIds = getAllSubCategoryIds(categoryId);
        }

        Page<Product> productPage = productRepository.searchProducts(categoryIds, active, q, pageable);
        boolean canViewCost = SecurityUtils.canViewCostPrice();

        Set<Long> catIds = productPage.getContent().stream()
                .map(Product::getCategoryId)
                .collect(Collectors.toSet());

        Map<Long, String> categoryNameMap = categoryRepository.findAllById(catIds).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));

        List<ProductResponse> dtos = productPage.getContent().stream()
                .map(p -> ProductResponse.of(p, categoryNameMap.getOrDefault(p.getCategoryId(), ""), canViewCost))
                .toList();

        return PageResponse.of(dtos, productPage);
    }

    @Override
    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        SecurityUtils.requireRole("OWNER");

        String sku = request.sku().trim();
        if (productRepository.existsBySku(sku)) {
            throw new AppException(ErrorCode.DUPLICATE_SKU, "Mã SKU đã tồn tại trên hệ thống");
        }

        String barcode = (request.barcode() != null && !request.barcode().trim().isEmpty()) ? request.barcode().trim() : null;
        if (barcode != null && productRepository.existsByBarcode(barcode)) {
            throw new AppException(ErrorCode.DUPLICATE_BARCODE, "Mã vạch đã tồn tại trên hệ thống");
        }

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy danh mục với id: " + request.categoryId()));

        Product p = new Product();
        p.setSku(sku);
        p.setBarcode(barcode);
        p.setName(request.name().trim());
        p.setUnit(request.unit().trim());
        p.setCategoryId(category.getId());
        p.setSalePrice(request.salePrice());
        p.setCostPrice(request.costPrice());
        p.setActive(request.active() != null ? request.active() : true);

        Product saved = productRepository.save(p);
        return ProductResponse.of(saved, category.getName(), SecurityUtils.canViewCostPrice());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long id) {
        Product p = productRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm với id: " + id));
        String categoryName = categoryRepository.findById(p.getCategoryId()).map(Category::getName).orElse("");
        return ProductResponse.of(p, categoryName, SecurityUtils.canViewCostPrice());
    }

    @Override
    @Transactional
    public ProductResponse updateProduct(Long id, ProductRequest request) {
        SecurityUtils.requireRole("OWNER");

        Product p = productRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm với id: " + id));

        String sku = request.sku().trim();
        if (productRepository.existsBySkuAndIdNot(sku, id)) {
            throw new AppException(ErrorCode.DUPLICATE_SKU, "Mã SKU đã thuộc về sản phẩm khác");
        }

        String barcode = (request.barcode() != null && !request.barcode().trim().isEmpty()) ? request.barcode().trim() : null;
        if (barcode != null && productRepository.existsByBarcodeAndIdNot(barcode, id)) {
            throw new AppException(ErrorCode.DUPLICATE_BARCODE, "Mã vạch đã thuộc về sản phẩm khác");
        }

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy danh mục với id: " + request.categoryId()));

        // nhật ký đổi giá bán
        if (!Objects.equals(p.getSalePrice(), request.salePrice())) {
            AuditLog log = new AuditLog();
            log.setUserId(SecurityUtils.getCurrentUserId());
            log.setAction("CHANGE_PRICE");
            log.setEntity("product");
            log.setEntityId(String.valueOf(p.getId()));
            log.setDetail(Map.of("oldPrice", p.getSalePrice(), "newPrice", request.salePrice()));
            auditLogRepository.save(log);
        }

        p.setSku(sku);
        p.setBarcode(barcode);
        p.setName(request.name().trim());
        p.setUnit(request.unit().trim());
        p.setCategoryId(category.getId());
        p.setSalePrice(request.salePrice());
        p.setCostPrice(request.costPrice());
        if (request.active() != null) {
            p.setActive(request.active());
        }

        Product updated = productRepository.save(p);
        return ProductResponse.of(updated, category.getName(), SecurityUtils.canViewCostPrice());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductLookupResponse lookupProduct(String code, Long branchId) {
        if (code == null || code.trim().isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Mã tra cứu không được để trống");
        }
        String searchCode = code.trim();

        Product p = productRepository.findByBarcode(searchCode)
                .or(() -> productRepository.findBySku(searchCode))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm với mã: " + searchCode));

        // Gọi qua interface InventoryService
        int available = inventoryService.getAvailable(branchId, p.getId());

        String categoryName = categoryRepository.findById(p.getCategoryId()).map(Category::getName).orElse("");
        ProductResponse productDto = ProductResponse.of(p, categoryName, SecurityUtils.canViewCostPrice());
        return new ProductLookupResponse(productDto, branchId, available);
    }

    private Set<Long> getAllSubCategoryIds(Long rootCategoryId) {
        Set<Long> result = new HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        queue.add(rootCategoryId);
        result.add(rootCategoryId);

        while (!queue.isEmpty()) {
            Long parent = queue.poll();
            List<Category> children = categoryRepository.findByParentId(parent);
            for (Category child : children) {
                if (result.add(child.getId())) {
                    queue.add(child.getId());
                }
            }
        }
        return result;
    }
}
