package vn.greenmart.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.greenmart.common.exception.GlobalExceptionHandler;
import vn.greenmart.inventory.exception.InsufficientStockException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InsufficientStockApiTest {
    @RestController
    static class TestController {
        @GetMapping("/test/stock")
        void stock() {
            throw new InsufficientStockException(1L, 5L, "SKU-5", 3, 2);
        }
    }

    @Test
    void mapsStockExceptionTo409AndContractDetails() throws Exception {
        MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/test/stock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.lines[0].productId").value(5))
                .andExpect(jsonPath("$.details.lines[0].sku").value("SKU-5"))
                .andExpect(jsonPath("$.details.lines[0].requested").value(3))
                .andExpect(jsonPath("$.details.lines[0].available").value(2))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
