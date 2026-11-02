package vn.greenmart.catalog.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.greenmart.catalog.dto.CategoryRequest;
import vn.greenmart.catalog.dto.CategoryResponse;
import vn.greenmart.catalog.service.CategoryService;
import vn.greenmart.common.exception.GlobalExceptionHandler;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CategoryController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {CategoryController.class, GlobalExceptionHandler.class})
@DisplayName("CategoryController - WebMvc Tests")
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CategoryService categoryService;

    @Test
    @DisplayName("GET /categories: Trả về 200 OK kèm danh sách")
    void testListCategories_Returns200() throws Exception {
        when(categoryService.listCategories()).thenReturn(List.of(new CategoryResponse(1L, "Đồ gia dụng", null)));

        mockMvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Đồ gia dụng"));
    }

    @Test
    @DisplayName("POST /categories: Dữ liệu hợp lệ -> Trả về 201 Created")
    void testCreateCategory_Success_Returns201() throws Exception {
        CategoryRequest req = new CategoryRequest("Mì gói", null);
        when(categoryService.createCategory(any(CategoryRequest.class))).thenReturn(new CategoryResponse(10L, "Mì gói", null));

        mockMvc.perform(post("/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("Mì gói"));
    }

    @Test
    @DisplayName("POST /categories: Tên danh mục rỗng -> Trả về 400 VALIDATION_ERROR")
    void testCreateCategory_BlankName_Returns400() throws Exception {
        CategoryRequest req = new CategoryRequest("   ", null);

        mockMvc.perform(post("/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("name"));
    }

    @Test
    @DisplayName("PUT /categories/{id}: Cập nhật hợp lệ -> Trả về 200 OK")
    void testUpdateCategory_Success_Returns200() throws Exception {
        CategoryRequest req = new CategoryRequest("Bia - Nước ngọt", null);
        when(categoryService.updateCategory(eq(1L), any(CategoryRequest.class)))
                .thenReturn(new CategoryResponse(1L, "Bia - Nước ngọt", null));

        mockMvc.perform(put("/categories/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bia - Nước ngọt"));
    }

    @Test
    @DisplayName("DELETE /categories/{id}: Xóa thành công -> Trả về 204 No Content")
    void testDeleteCategory_Success_Returns204() throws Exception {
        doNothing().when(categoryService).deleteCategory(1L);

        mockMvc.perform(delete("/categories/1"))
                .andExpect(status().isNoContent());
    }
}
