package vn.greenmart.catalog.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import vn.greenmart.catalog.domain.Category;
import vn.greenmart.catalog.dto.CategoryRequest;
import vn.greenmart.catalog.dto.CategoryResponse;
import vn.greenmart.catalog.repository.CategoryRepository;
import vn.greenmart.catalog.repository.ProductRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        SecurityContextHolder.clearContext();
        testInfo.getTestMethod().ifPresent(method -> {
            WithMockUser withMockUser = method.getAnnotation(WithMockUser.class);
            if (withMockUser != null) {
                String role = withMockUser.roles().length > 0 ? withMockUser.roles()[0] : "USER";
                String username = withMockUser.username().isEmpty() ? "1" : withMockUser.username();
                Authentication auth = new UsernamePasswordAuthenticationToken(
                    username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void testCreateCategory_Success() {
        CategoryRequest req = new CategoryRequest("Sữa và chế phẩm", null);
        Category saved = new Category();
        saved.setId(1L);
        saved.setName("Sữa và chế phẩm");

        when(categoryRepository.save(any(Category.class))).thenReturn(saved);

        CategoryResponse res = categoryService.createCategory(req);

        assertNotNull(res);
        assertEquals(1L, res.id());
        assertEquals("Sữa và chế phẩm", res.name());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void testUpdateCategory_SelfParent_ThrowsCategoryCycle() {
        Category cat = new Category();
        cat.setId(2L);
        cat.setName("Sữa chua");

        when(categoryRepository.findById(2L)).thenReturn(Optional.of(cat));

        CategoryRequest req = new CategoryRequest("Sữa chua", 2L);

        AppException ex = assertThrows(AppException.class, () -> categoryService.updateCategory(2L, req));
        assertEquals(ErrorCode.CATEGORY_CYCLE.name(), ex.getCode());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void testUpdateCategory_CircularCycle_ThrowsCategoryCycle() {
        Category catA = new Category();
        catA.setId(1L);
        catA.setName("A");

        Category catB = new Category();
        catB.setId(2L);
        catB.setName("B");
        catB.setParentId(1L);

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(catA));
        when(categoryRepository.existsById(2L)).thenReturn(true);
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(catB));

        CategoryRequest req = new CategoryRequest("A", 2L);

        AppException ex = assertThrows(AppException.class, () -> categoryService.updateCategory(1L, req));
        assertEquals(ErrorCode.CATEGORY_CYCLE.name(), ex.getCode());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void testDeleteCategory_HasChildren_ThrowsCategoryInUse() {
        when(categoryRepository.existsById(1L)).thenReturn(true);
        when(categoryRepository.existsByParentId(1L)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> categoryService.deleteCategory(1L));
        assertEquals(ErrorCode.CATEGORY_IN_USE.name(), ex.getCode());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void testDeleteCategory_HasProducts_ThrowsCategoryInUse() {
        when(categoryRepository.existsById(1L)).thenReturn(true);
        when(categoryRepository.existsByParentId(1L)).thenReturn(false);
        when(productRepository.existsByCategoryId(1L)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> categoryService.deleteCategory(1L));
        assertEquals(ErrorCode.CATEGORY_IN_USE.name(), ex.getCode());
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void testCreateCategory_NonOwner_ThrowsForbidden() {
        CategoryRequest req = new CategoryRequest("Rau củ", null);

        AppException ex = assertThrows(AppException.class, () -> categoryService.createCategory(req));
        assertEquals(ErrorCode.FORBIDDEN.name(), ex.getCode());
    }
}
