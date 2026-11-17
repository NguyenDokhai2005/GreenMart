package vn.greenmart.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "management.endpoint.health.group.readiness.include=readinessState,db"
})
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class InventoryQueryApiTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(71,'B71','Branch 71'),(72,'B72','Branch 72')");
        jdbc.update("INSERT INTO category(id,name) VALUES(91,'Milk'),(92,'Other')");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES(700,'cashier','hash','Cashier','CASHIER')");
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(700,71)");
        for (int id = 71; id <= 74; id++) {
            jdbc.update("INSERT INTO product(id,sku,barcode,name,unit,category_id,sale_price,cost_price) VALUES(?,?,?,?,?,?,100,50)",
                    id, "SKU-" + id, "BAR" + id, id == 71 ? "Sữa TH" : "Product " + id, "hộp", id == 71 ? 91 : 92);
        }
        jdbc.update("INSERT INTO inventory(branch_id,product_id,on_hand,reserved,reorder_point) VALUES "
                + "(71,71,35,5,34),(71,72,10,10,34),(71,73,34,0,34),(71,74,40,0,NULL),(72,71,999,0,34)");
    }

    @Test
    void returnsContractBalancesAndBranchForAssignedCashier() throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user("700").roles("CASHIER"))
                        .param("branchId", "71").param("productId", "71"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].branch.id").value(71))
                .andExpect(jsonPath("$.content[0].branch.code").value("B71"))
                .andExpect(jsonPath("$.content[0].productName").value("Sữa TH"))
                .andExpect(jsonPath("$.content[0].sku").value("SKU-71"))
                .andExpect(jsonPath("$.content[0].unit").value("hộp"))
                .andExpect(jsonPath("$.content[0].onHand").value(35))
                .andExpect(jsonPath("$.content[0].reserved").value(5))
                .andExpect(jsonPath("$.content[0].available").value(30))
                .andExpect(jsonPath("$.content[0].reorderPoint").value(34))
                .andExpect(jsonPath("$.content[0].stockStatus").value("LOW"))
                .andExpect(jsonPath("$.content[0].version").value(0))
                .andExpect(jsonPath("$.content[0].updatedAt").isNotEmpty());
    }

    @Test
    void forbidsAnotherBranch() throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user("700").roles("CASHIER")).param("branchId", "72"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
    }

    @Test
    void readsSingleStockItemWithBranchScope() throws Exception {
        mvc.perform(get("/api/v1/inventory/71/71").with(user("700").roles("CASHIER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(30))
                .andExpect(jsonPath("$.stockStatus").value("LOW"));
        mvc.perform(get("/api/v1/inventory/72/71").with(user("700").roles("CASHIER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        mvc.perform(get("/api/v1/inventory/71/999").with(user("700").roles("CASHIER")))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASHIER", "STOREKEEPER", "BRANCH_MANAGER"})
    void omittedBranchNeverLeaksOtherBranches(String role) throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user("700").roles(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[*].branch.id", everyItem(is(71))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"701", "unknown-principal"})
    void missingBranchAssignmentsReturnEmptyPage(String principal) throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user(principal).roles("CASHIER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "OWNER"})
    void privilegedRolesCanSeeAllBranches(String role) throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user("700").roles(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
    }

    @ParameterizedTest
    @CsvSource({"OUT,1", "LOW,2", "OK,1"})
    void filtersStatusIncludingZeroAndRopBoundary(String stockStatus, int count) throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user("700").roles("CASHIER"))
                        .param("stockStatus", stockStatus))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(count))
                .andExpect(jsonPath("$.content[*].stockStatus", everyItem(is(stockStatus))));
    }

    @Test
    void statusFilterRunsBeforePagingAndCountAndSupportsRawRepeatedSorts() throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user("700").roles("CASHIER"))
                        .param("stockStatus", "LOW").param("page", "1").param("size", "1")
                        .param("sort", "available,desc", "productId,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.content[0].productId").value(71));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sữa", "sku-71", "BAR71"})
    void searchesNameSkuAndBarcode(String q) throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user("700").roles("CASHIER")).param("q", q))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].productId").value(71));
    }

    @Test
    void combinesCategoryAndProductFilters() throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user("700").roles("CASHIER"))
                        .param("categoryId", "92").param("productId", "71"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "' OR 1=1 --"})
    void searchTreatsInputAsLiteralText(String q) throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user("700").roles("CASHIER")).param("q", q))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "stockStatus=BAD", "branchId=bad",
            "sort=costPrice", "sort=sku&sort=desc"})
    void invalidQueriesReturnStandard400(String query) throws Exception {
        mvc.perform(get("/api/v1/inventory?" + query).with(user("700").roles("CASHIER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void anonymousAccessIsRejected() throws Exception {
        mvc.perform(get("/api/v1/inventory"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void unrelatedRoleIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/inventory").with(user("700").roles("OTHER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
