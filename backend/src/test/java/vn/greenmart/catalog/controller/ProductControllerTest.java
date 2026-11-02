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
import vn.greenmart.catalog.dto.ProductLookupResponse;
import vn.greenmart.catalog.dto.ProductRequest;
import vn.greenmart.catalog.dto.ProductResponse;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.exception.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProductController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {ProductController.class, GlobalExceptionHandler.class})
@DisplayName("ProductController - WebMvc Tests")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    @Test
    @DisplayName("POST /products: SKU sai regex (chứa chữ thường hoặc ký tự lạ) -> 400 VALIDATION_ERROR")
    void testCreateProduct_InvalidSkuRegex_Returns400() throws Exception {
        // Chứa chữ thường 'sku' -> vi phạm regex ^[A-Z0-9-]+$
        ProductRequest req = new ProductRequest("sku-lower_case", "8931234567890", "Nước Suối", "Chai", 1L, 10000L, 7000L, true);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("sku"));
    }

    @Test
    @DisplayName("POST /products: salePrice âm -> 400 VALIDATION_ERROR")
    void testCreateProduct_NegativeSalePrice_Returns400() throws Exception {
        ProductRequest req = new ProductRequest("SKU-VALID", "8931234567890", "Nước Suối", "Chai", 1L, -5000L, 7000L, true);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("salePrice"));
    }

    @Test
    @DisplayName("POST /products: Dữ liệu hợp lệ -> 201 Created")
    void testCreateProduct_Success_Returns201() throws Exception {
        ProductRequest req = new ProductRequest("SKU-COCA", "893000000001", "Coca Cola 330ml", "Lon", 1L, 12000L, 9000L, true);
        ProductResponse res = new ProductResponse(5L, "SKU-COCA", "893000000001", "Coca Cola 330ml", "Lon", 1L, "Nước ngọt", 12000L, 9000L, true);

        when(productService.createProduct(any(ProductRequest.class))).thenReturn(res);

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.sku").value("SKU-COCA"));
    }

    @Test
    @DisplayName("GET /products/lookup: Tra cứu mã tồn tại -> 200 OK")
    void testLookupProduct_Success_Returns200() throws Exception {
        ProductResponse p = new ProductResponse(5L, "SKU-COCA", "893000000001", "Coca Cola 330ml", "Lon", 1L, "Nước ngọt", 12000L, null, true);
        ProductLookupResponse lookupRes = new ProductLookupResponse(p, 1L, 50);

        when(productService.lookupProduct("893000000001", 1L)).thenReturn(lookupRes);

        mockMvc.perform(get("/products/lookup")
                        .param("code", "893000000001")
                        .param("branchId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(50))
                .andExpect(jsonPath("$.product.sku").value("SKU-COCA"));
    }

    @Test
    @DisplayName("GET /products/{id}: Lấy sản phẩm -> 200 OK")
    void testGetProduct_Success_Returns200() throws Exception {
        ProductResponse res = new ProductResponse(5L, "SKU-COCA", "893000000001", "Coca Cola 330ml", "Lon", 1L, "Nước ngọt", 12000L, null, true);
        when(productService.getProduct(5L)).thenReturn(res);

        mockMvc.perform(get("/products/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
    }
}
