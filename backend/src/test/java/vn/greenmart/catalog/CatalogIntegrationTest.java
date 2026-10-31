package vn.greenmart.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import vn.greenmart.catalog.controller.CategoryController;
import vn.greenmart.catalog.controller.ProductController;
import vn.greenmart.catalog.dto.CategoryRequest;
import vn.greenmart.catalog.dto.ProductRequest;
import vn.greenmart.catalog.dto.ProductResponse;
import vn.greenmart.catalog.service.CategoryService;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.GlobalExceptionHandler;
import vn.greenmart.common.pagination.PageResponse;

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
    GlobalExceptionHandler.class
})
class CatalogIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private ProductService productService;

    // OWNER tạo sản phẩm mới thành công -> 201
    @Test
    void testAC1_CreateProduct_OwnerRole_Returns201() throws Exception {
        ProductRequest req = new ProductRequest("SUA-TH-1L", "8935217400013", "Sữa TH", "hộp", 1L, 29000L, 23000L, true);
        ProductResponse res = new ProductResponse(1L, "SUA-TH-1L", "8935217400013", "Sữa TH", "hộp", 1L, "Sữa", 29000L, 23000L, true);

        when(productService.createProduct(any(ProductRequest.class))).thenReturn(res);

        mockMvc.perform(post("/products")
                .header("X-User-Role", "OWNER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sku").value("SUA-TH-1L"));
    }

    // Trùng SKU -> 409 DUPLICATE_SKU
    @Test
    void testAC2_CreateProduct_DuplicateSku_Returns409() throws Exception {
        ProductRequest req = new ProductRequest("SUA-TH-1L", null, "Sữa TH", "hộp", 1L, 29000L, 23000L, true);

        when(productService.createProduct(any(ProductRequest.class)))
            .thenThrow(new AppException(ErrorCode.DUPLICATE_SKU, "Mã SKU đã tồn tại"));

        mockMvc.perform(post("/products")
                .header("X-User-Role", "OWNER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    }

    // CASHIER sửa sản phẩm -> 403 FORBIDDEN
    @Test
    void testAC3_UpdateProduct_CashierRole_Returns403() throws Exception {
        ProductRequest req = new ProductRequest("SUA-TH-1L", null, "Sữa TH", "hộp", 1L, 31000L, 23000L, true);

        when(productService.updateProduct(eq(1L), any(ProductRequest.class)))
            .thenThrow(new AppException(ErrorCode.FORBIDDEN, "Không có quyền"));

        mockMvc.perform(put("/products/1")
                .header("X-User-Role", "CASHIER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // Đặt cha tạo vòng lặp -> 422 CATEGORY_CYCLE
    @Test
    void testAC5_UpdateCategory_Cycle_Returns422() throws Exception {
        CategoryRequest req = new CategoryRequest("A", 2L);

        when(categoryService.updateCategory(eq(1L), any(CategoryRequest.class)))
            .thenThrow(new AppException(ErrorCode.CATEGORY_CYCLE, "Vòng lặp chu trình"));

        mockMvc.perform(put("/categories/1")
                .header("X-User-Role", "OWNER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("CATEGORY_CYCLE"));
    }

    // Xóa danh mục còn sản phẩm hoặc danh mục con -> 409 CATEGORY_IN_USE
    @Test
    void testAC6_DeleteCategory_InUse_Returns409() throws Exception {
        doThrow(new AppException(ErrorCode.CATEGORY_IN_USE, "Danh mục đang dùng"))
            .when(categoryService).deleteCategory(1L);

        mockMvc.perform(delete("/categories/1")
                .header("X-User-Role", "OWNER"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));
    }

    //  CASHIER gọi GET /products -> costPrice nhận null
    @Test
    void testAC7_GetProducts_CashierRole_CostPriceNull() throws Exception {
        ProductResponse item = new ProductResponse(1L, "SUA-TH-1L", null, "Sữa TH", "hộp", 1L, "Sữa", 29000L, null, true);
        PageResponse<ProductResponse> page = new PageResponse<>(List.of(item), 0, 20, 1, 1);

        when(productService.listProducts(any(), any(), any(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/products")
                .header("X-User-Role", "CASHIER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].salePrice").value(29000))
            .andExpect(jsonPath("$.content[0].costPrice").doesNotExist());
    }
}
