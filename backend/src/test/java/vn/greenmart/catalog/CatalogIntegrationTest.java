package vn.greenmart.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.greenmart.catalog.controller.CategoryController;
import vn.greenmart.catalog.controller.ProductController;
import vn.greenmart.catalog.dto.CategoryRequest;
import vn.greenmart.catalog.dto.ProductRequest;
import vn.greenmart.catalog.dto.ProductResponse;
import vn.greenmart.catalog.service.CategoryService;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.config.WebMvcConfig;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.GlobalExceptionHandler;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.security.SecurityConfig;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@ContextConfiguration(classes = {
    CategoryController.class,
    ProductController.class,
    GlobalExceptionHandler.class,
    SecurityConfig.class
})
@Import(WebMvcConfig.class)
class CatalogIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private ProductService productService;

    @Test
    @WithMockUser(roles = "OWNER")
    void testAC1_CreateProduct_OwnerRole_Returns201() throws Exception {
        ProductRequest req = new ProductRequest("SUA-TH-1L", "8935217400013", "Sữa TH", "hộp", 1L, 29000L, 23000L, true);
        ProductResponse res = new ProductResponse(1L, "SUA-TH-1L", "8935217400013", "Sữa TH", "hộp", 1L, "Sữa", 29000L, 23000L, true);

        when(productService.createProduct(any(ProductRequest.class))).thenReturn(res);

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sku").value("SUA-TH-1L"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void testAC2_CreateProduct_DuplicateSku_Returns409() throws Exception {
        ProductRequest req = new ProductRequest("SUA-TH-1L", null, "Sữa TH", "hộp", 1L, 29000L, 23000L, true);

        when(productService.createProduct(any(ProductRequest.class)))
            .thenThrow(new AppException(ErrorCode.DUPLICATE_SKU, "Mã SKU đã tồn tại"));

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void testAC3_UpdateProduct_CashierRole_Returns403() throws Exception {
        ProductRequest req = new ProductRequest("SUA-TH-1L", null, "Sữa TH", "hộp", 1L, 31000L, 23000L, true);

        when(productService.updateProduct(eq(1L), any(ProductRequest.class)))
            .thenThrow(new AppException(ErrorCode.FORBIDDEN, "Không có quyền"));

        mockMvc.perform(put("/api/v1/products/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void testAC5_UpdateCategory_Cycle_Returns422() throws Exception {
        CategoryRequest req = new CategoryRequest("A", 2L);

        when(categoryService.updateCategory(eq(1L), any(CategoryRequest.class)))
            .thenThrow(new AppException(ErrorCode.CATEGORY_CYCLE, "Vòng lặp chu trình"));

        mockMvc.perform(put("/api/v1/categories/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CATEGORY_CYCLE"));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void testAC6_DeleteCategory_InUse_Returns409() throws Exception {
        doThrow(new AppException(ErrorCode.CATEGORY_IN_USE, "Danh mục đang dùng"))
            .when(categoryService).deleteCategory(1L);

        mockMvc.perform(delete("/api/v1/categories/1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
    }

    @Test
    @WithMockUser(roles = "CASHIER")
    void testAC7_GetProducts_CashierRole_CostPriceNull() throws Exception {
        ProductResponse item = new ProductResponse(1L, "SUA-TH-1L", null, "Sữa TH", "hộp", 1L, "Sữa", 29000L, null, true);
        PageResponse<ProductResponse> page = new PageResponse<>(List.of(item), 0, 20, 1, 1);

        when(productService.listProducts(any(), any(), any(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].salePrice").value(29000))
            .andExpect(jsonPath("$.content[0].costPrice").doesNotExist());
    }
}
