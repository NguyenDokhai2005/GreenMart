package vn.greenmart.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.inventory.PostgresTestImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ChannelMappingApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test @WithMockUser(roles = "ADMIN")
    void adminCreatesAndUpdatesMappingAndDuplicateSkuIsRejected() throws Exception {
        jdbc.update("INSERT INTO category(id,name) VALUES (951,'Channel fixture')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price)
                VALUES (951,'GM951','Product 1','box',951,100,50), (952,'GM952','Product 2','box',951,100,50)
                """);
        mvc.perform(mapping(951, "W-FIRST")).andExpect(status().isNoContent());
        mvc.perform(mapping(951, "W-UPDATED")).andExpect(status().isNoContent());
        mvc.perform(mapping(952, "W-UPDATED")).andExpect(status().isConflict());
        assertThat(jdbc.queryForList("""
                SELECT cp.external_sku FROM channel_product cp JOIN sales_channel c ON c.id=cp.channel_id
                WHERE c.code='WEB' AND cp.product_id IN (951,952)
                """, String.class)).containsExactly("W-UPDATED");
    }

    @Test @WithMockUser(roles = "CASHIER")
    void cashierCannotChangeChannelMappings() throws Exception {
        mvc.perform(mapping(951, "W-DENIED")).andExpect(status().isForbidden());
    }

    @Test void unauthenticatedCallerCannotChangeChannelMappings() throws Exception {
        mvc.perform(mapping(951, "W-DENIED")).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mapping(long product, String sku) {
        return put("/api/v1/integrations/WEB/products/{productId}", product)
                .contentType(MediaType.APPLICATION_JSON).content("{\"externalSku\":\"" + sku + "\"}");
    }
}
