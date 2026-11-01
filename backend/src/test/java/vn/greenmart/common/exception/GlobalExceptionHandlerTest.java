package vn.greenmart.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(excludeAutoConfiguration = {SecurityAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {
    GlobalExceptionHandlerTest.TestController.class,
    GlobalExceptionHandler.class
})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @RestController
    public static class TestController {

        @PostMapping("/test/validation")
        public String testValidation(@Valid @RequestBody TestRequest request) {
            return "ok";
        }

        @GetMapping("/test/app-exception")
        public String testAppException() {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy dữ liệu");
        }

        @GetMapping("/test/server-error")
        public String testServerError() {
            throw new RuntimeException("Lỗi hệ thống bất ngờ");
        }

        @GetMapping("/test/type-mismatch")
        public String testTypeMismatch(@RequestParam("page") int page) {
            return "page: " + page;
        }

        @GetMapping("/test/required-param")
        public String testRequiredParam(@RequestParam("branchId") Long branchId) {
            return "branchId: " + branchId;
        }

        @GetMapping("/test/required-header")
        public String testRequiredHeader(@RequestHeader("Idempotency-Key") String key) {
            return "key: " + key;
        }
    }

    public record TestRequest(@NotBlank(message = "Tên không được để trống") String name) {}

    // sai validation -> 400
    @Test
    void testBodyValidationFails_Returns400() throws Exception {
        mockMvc.perform(post("/test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("name"));
    }

    // JSON sai cú pháp -> 400
    @Test
    void testInvalidJson_Returns400() throws Exception {
        mockMvc.perform(post("/test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid_json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // Sai kiểu dữ liệu params -> 400
    @Test
    void testTypeMismatch_Returns400() throws Exception {
        mockMvc.perform(get("/test/type-mismatch?page=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("page"));
    }

    // Thiếu tham số bắt buộc -> 400
    @Test
    void testMissingRequiredParameter_Returns400() throws Exception {
        mockMvc.perform(get("/test/required-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Thiếu tham số bắt buộc"))
                .andExpect(jsonPath("$.details[0].field").value("branchId"))
                .andExpect(jsonPath("$.details[0].message").value("Bắt buộc"));
    }

    // Thiếu header bắt buộc -> 400
    @Test
    void testMissingRequiredHeader_Returns400() throws Exception {
        mockMvc.perform(get("/test/required-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Thiếu header bắt buộc"))
                .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"))
                .andExpect(jsonPath("$.details[0].message").value("Bắt buộc"));
    }

    // URL không tồn tại -> 404
    @Test
    void testNotFound_Returns404() throws Exception {
        mockMvc.perform(get("/test/unmapped-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // Sai HTTP Method -> 405
    @Test
    void testMethodNotSupported_Returns405() throws Exception {
        mockMvc.perform(post("/test/app-exception"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    // AppException -> trả đúng HTTP status và code
    @Test
    void testAppException_ReturnsCorrectStatusAndCode() throws Exception {
        mockMvc.perform(get("/test/app-exception"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy dữ liệu"));
    }

    // 9. Exception bất ngờ (500) -> không lộ stack trace
    @Test
    void testUnexpectedException_Returns500WithoutStackTrace() throws Exception {
        mockMvc.perform(get("/test/server-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertFalse(
                        result.getResponse().getContentAsString().contains("java.lang.RuntimeException")));
    }
}
