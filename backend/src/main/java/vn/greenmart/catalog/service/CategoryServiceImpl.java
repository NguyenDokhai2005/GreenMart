package vn.greenmart.catalog.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.catalog.domain.Category;
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

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

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

        if (request.parentId() != null && !categoryRepository.existsById(request.parentId())) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy danh mục cha với id: " + request.parentId());
        }

        Category category = new Category();
        category.setName(request.name().trim());
        category.setParentId(request.parentId());

        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Override
    @Transactional
    public CategoryResponse updateCategory(Long id, CategoryRequest request) {
        SecurityUtils.requireRole("OWNER");

        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy danh mục với id: " + id));

        Long newParentId = request.parentId();
        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new AppException(ErrorCode.CATEGORY_CYCLE, "Không thể gán danh mục cha là chính nó");
            }
            if (!categoryRepository.existsById(newParentId)) {
                throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy danh mục cha với id: " + newParentId);
            }
            checkCycle(id, newParentId);
        }

        category.setName(request.name().trim());
        category.setParentId(newParentId);

        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        SecurityUtils.requireRole("OWNER");

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
