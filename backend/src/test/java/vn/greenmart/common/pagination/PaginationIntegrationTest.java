package vn.greenmart.common.pagination;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.greenmart.common.exception.GlobalExceptionHandler;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@ContextConfiguration(classes = {
    PaginationIntegrationTest.DummyListController.class,
    GlobalExceptionHandler.class
})
class PaginationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @RestController
    public static class DummyListController {

        @GetMapping("/test/pagination")
        public PageResponse<String> getPagedItems(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "sort", required = false) String sort
        ) {
            Pageable pageable = PageUtils.createPageable(page, size, sort);
            List<String> items = List.of("Item A", "Item B");
            Page<String> springPage = new PageImpl<>(items, pageable, 50);
            return PageResponse.from(springPage);
        }
    }

    @Test
    void testGetPagination_ReturnsCorrectFlatJsonStructure() throws Exception {
        mockMvc.perform(get("/test/pagination?page=0&size=20&sort=name,asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(50))
            .andExpect(jsonPath("$.totalPages").value(3))
            .andExpect(jsonPath("$.content[0]").value("Item A"))
            .andExpect(jsonPath("$.content[1]").value("Item B"));
    }

    @Test
    void testGetPagination_SizeExceedsMax_Returns400ValidationError() throws Exception {
        mockMvc.perform(get("/test/pagination?page=0&size=150"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("Kích thước trang (size) phải từ 1 đến 100"));
    }

    @Test
    void testGetPagination_PageNegative_Returns400ValidationError() throws Exception {
        mockMvc.perform(get("/test/pagination?page=-1&size=20"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value("Số trang (page) phải bắt đầu từ 0"));
    }
}
