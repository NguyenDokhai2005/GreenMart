package vn.greenmart.catalog.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.catalog.domain.Category;
import vn.greenmart.catalog.domain.CategoryNames;
import vn.greenmart.catalog.dto.CategoryRequest;
import vn.greenmart.catalog.dto.CategoryResponse;
import vn.greenmart.catalog.repository.CategoryRepository;
import vn.greenmart.catalog.repository.ProductRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private static final long CATEGORY_TREE_LOCK_KEY = 3060386L;

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories() {
        return categoryRepository.findAll().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public CategoryResponse createCategory(CategoryRequest request) {
        SecurityUtils.requireRole("OWNER");
        acquireCategoryTreeLock();
        String name = validatedName(request.name());

        if (request.parentId() != null && !categoryRepository.existsById(request.parentId())) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy danh mục cha với id: " + request.parentId());
        }

        Category category = new Category();
        ensureUniqueName(name, request.parentId(), null);
        category.setName(name);
        category.setParentId(request.parentId());

        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Override
    @Transactional
    public CategoryResponse updateCategory(Long id, CategoryRequest request) {
        SecurityUtils.requireRole("OWNER");

        Long newParentId = request.parentId();
        // Serialize all tree/name changes, including moves to the root.
        acquireCategoryTreeLock();

        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy danh mục với id: " + id));

        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new AppException(ErrorCode.CATEGORY_CYCLE, "Không thể gán danh mục cha là chính nó");
            }
            if (!categoryRepository.existsById(newParentId)) {
                throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy danh mục cha với id: " + newParentId);
            }
            checkCycle(id, newParentId);
        }

        String name = validatedName(request.name());
        ensureUniqueName(name, newParentId, id);
        category.setName(name);
        category.setParentId(newParentId);

        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        SecurityUtils.requireRole("OWNER");
        acquireCategoryTreeLock();

        if (!categoryRepository.existsById(id)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy danh mục với id: " + id);
        }

        if (categoryRepository.existsByParentId(id)) {
            throw new AppException(ErrorCode.CATEGORY_IN_USE, "Không thể xóa danh mục đang có danh mục con");
        }

        if (productRepository.existsByCategoryId(id)) {
            throw new AppException(ErrorCode.CATEGORY_IN_USE, "Không thể xóa danh mục đang chứa sản phẩm");
        }

        categoryRepository.deleteById(id);
    }

    private String validatedName(String name) {
        String normalized = CategoryNames.normalize(name);
        if (normalized == null || normalized.isEmpty() || normalized.length() > 120) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Tên danh mục phải có 1–120 ký tự sau chuẩn hóa");
        }
        return normalized;
    }

    private void ensureUniqueName(String name, Long parentId, Long excludedId) {
        if (categoryRepository.existsByNormalizedName(name, parentId, excludedId)) {
            throw new AppException(ErrorCode.CONFLICT, "Tên danh mục đã tồn tại trong cùng danh mục cha");
        }
    }

    private void acquireCategoryTreeLock() {
        if (jdbcTemplate != null) {
            jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, CATEGORY_TREE_LOCK_KEY);
        }
        if (entityManager != null) {
            // Xóa cache cấp 1 của Hibernate sau khi nhận lock để đảm bảo đọc snapshot mới nhất đã commit
            entityManager.clear();
        }
    }

    private void checkCycle(Long categoryId, Long targetParentId) {
        Set<Long> visited = new HashSet<>();
        Long currentParentId = targetParentId;

        while (currentParentId != null) {
            if (currentParentId.equals(categoryId)) {
                throw new AppException(ErrorCode.CATEGORY_CYCLE, "Gán danh mục cha tạo thành vòng lặp khép kín");
            }
            if (!visited.add(currentParentId)) {
                break;
            }
            currentParentId = categoryRepository.findById(currentParentId)
                    .map(Category::getParentId)
                    .orElse(null);
        }
    }
}
