package vn.greenmart.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
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
    }

    public record TestRequest(@NotBlank(message = "Tên không được để trống") String name) {}

    @Test
    void testBodyValidationFails_Returns400() throws Exception {
        mockMvc.perform(post("/test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("name"));
    }

    @Test
    void testInvalidJson_Returns400() throws Exception {
        mockMvc.perform(post("/test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid_json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void testAppException_ReturnsCorrectStatusAndCode() throws Exception {
        mockMvc.perform(get("/test/app-exception"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy dữ liệu"));
    }

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
