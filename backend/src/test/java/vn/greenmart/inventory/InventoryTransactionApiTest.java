package vn.greenmart.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.identity.security.JwtTokens;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
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
class InventoryTransactionApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.forecast_user", () -> "forecast_ro");
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokens tokens;

    @BeforeEach void fixtures() {
        jdbc.execute("TRUNCATE branch,category,app_user CASCADE");
        jdbc.update("INSERT INTO branch(id,code,name) VALUES(71,'B71','Branch 71'),(72,'B72','Branch 72')");
        jdbc.update("INSERT INTO category(id,name) VALUES(81,'Milk')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES "
                + "(81,'MILK','Milk','hop',81,20000,10000),(82,'OTHER','Other','hop',81,10000,5000)");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,full_name,role) VALUES "
                + "(710,'manager','hash','Manager','BRANCH_MANAGER'),(711,'keeper','hash','Keeper','STOREKEEPER'),"
                + "(712,'owner','hash','Owner','OWNER'),(713,'cashier','hash','Cashier','CASHIER'),"
                + "(714,'admin','hash','Admin','ADMIN'),(715,'unassigned','hash','Unassigned','BRANCH_MANAGER'),"
                + "(716,'other-manager','hash','Other Manager','BRANCH_MANAGER')");
        jdbc.update("INSERT INTO user_branch(user_id,branch_id) VALUES(710,71),(711,71),(713,71),(716,72)");
        jdbc.update("INSERT INTO sales_channel(code,name) VALUES('POS','POS')");
        jdbc.update("INSERT INTO sales_order(id,code,channel_id,branch_id,status,subtotal,total) "
                + "SELECT 100,'ORDER-71',id,71,'COMPLETED',0,0 FROM sales_channel WHERE code='POS'");
        jdbc.update("INSERT INTO sales_order(id,code,channel_id,branch_id,status,subtotal,total) "
                + "SELECT 101,'ORDER-72',id,72,'COMPLETED',0,0 FROM sales_channel WHERE code='POS'");
        jdbc.update("INSERT INTO stock_document(id,code,type,branch_id,created_by,supplier) VALUES(100,'IMPORT-71','IMPORT',71,711,'Supplier')");
        jdbc.update("INSERT INTO stock_document(id,code,type,branch_id,to_branch_id,created_by) VALUES(101,'TRANSFER-71-72','TRANSFER',71,72,711)");
        jdbc.update("INSERT INTO stock_document(id,code,type,branch_id,created_by,export_reason) VALUES(102,'EXPORT-71','EXPORT',71,711,'DAMAGED')");
        jdbc.update("INSERT INTO return_document(id,code,order_id,branch_id,reason,refund_amount,created_by) "
                + "VALUES(100,'RETURN-71',100,71,'Quality',0,711),(101,'RETURN-72',101,72,'Quality',0,716)");
        entry(1,71,81,"SALE","ORDER",100,null,"2026-11-04T23:59:59.999999+07:00");
        entry(2,71,81,"RESERVE","ORDER",100,710L,"2026-11-05T00:00:00+07:00");
        entry(3,71,81,"IMPORT","STOCK_DOCUMENT",100,710L,"2026-11-05T12:00:00+07:00");
        entry(4,71,82,"EXPORT","STOCK_DOCUMENT",102,null,"2026-11-05T12:00:00+07:00");
        entry(5,71,81,"RETURN","RETURN_DOCUMENT",100,710L,"2026-11-05T23:59:59.999999+07:00");
        entry(6,71,81,"SALE","ORDER",999,null,"2026-11-06T00:00:00+07:00");
        entry(7,72,81,"TRANSFER_IN","STOCK_DOCUMENT",101,716L,"2026-11-05T12:00:00+07:00");
        entry(8,72,81,"RETURN","RETURN_DOCUMENT",101,716L,"2026-11-05T12:00:00+07:00");
    }
    private void entry(long id,long branch,long product,String type,String refType,long ref,Long actor,String time) {
        int deltaOnHand = switch (type) {
            case "IMPORT", "RETURN", "TRANSFER_IN" -> 2;
            case "SALE", "EXPORT" -> -2;
            default -> 0;
        };
        int deltaReserved = "RESERVE".equals(type) ? 2 : 0;
        jdbc.update("""
                INSERT INTO inventory_transaction(id,branch_id,product_id,type,delta_on_hand,on_hand_after,
                    delta_reserved,reserved_after,ref_type,ref_id,created_by,created_at)
                VALUES(?,?,?,?,?,10,?,2,?,?,?,?)
                """,id,branch,product,type,deltaOnHand,deltaReserved,refType,ref,actor,OffsetDateTime.parse(time));
    }
    private MockHttpServletRequestBuilder manager() {
        return get("/api/v1/inventory/transactions").with(user("710").roles("BRANCH_MANAGER"));
    }
    private MockHttpServletRequestBuilder owner() {
        return get("/api/v1/inventory/transactions").with(user("712").roles("OWNER"));
    }
    @Test void defaultsToNewestFirstAndStableIdTieBreakerWithinAssignedBranch() throws Exception {
        mvc.perform(manager()).andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[*].id").value(contains(6,5,4,3,2,1)))
                .andExpect(jsonPath("$.content[*].branchId").value(everyItem(is(71))));
    }
    @Test void combinedFiltersReturnOrderReferenceAndLedgerBalances() throws Exception {
        mvc.perform(manager().param("branchId","71").param("productId","81").param("type","RESERVE")
                        .param("refType","ORDER").param("refId","100").param("from","2026-11-05").param("to","2026-11-05"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(2)).andExpect(jsonPath("$.content[0].sku").value("MILK"))
                .andExpect(jsonPath("$.content[0].type").value("RESERVE"))
                .andExpect(jsonPath("$.content[0].deltaOnHand").value(0)).andExpect(jsonPath("$.content[0].onHandAfter").value(10))
                .andExpect(jsonPath("$.content[0].deltaReserved").value(2)).andExpect(jsonPath("$.content[0].reservedAfter").value(2))
                .andExpect(jsonPath("$.content[0].refType").value("ORDER")).andExpect(jsonPath("$.content[0].refId").value(100))
                .andExpect(jsonPath("$.content[0].refCode").value("ORDER-71"))
                .andExpect(jsonPath("$.content[0].createdBy.id").value(710))
                .andExpect(jsonPath("$.content[0].createdBy.fullName").value("Manager"))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty());
    }
    @Test void datesIncludeBothVietnamCalendarDaysAndExcludeNextMidnight() throws Exception {
        mvc.perform(manager().param("from","2026-11-05").param("to","2026-11-05"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[*].id").value(contains(5,4,3,2)))
                .andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(manager().param("to","2026-11-04")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(1)));
        mvc.perform(manager().param("from","2026-11-06")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(contains(6)));
    }
    @Test void referenceTypesWithSameIdNeverMixAndMissingReferenceRemainsVisible() throws Exception {
        mvc.perform(manager().param("refId","100")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].refCode").value(contains("RETURN-71","IMPORT-71","ORDER-71","ORDER-71")));
        mvc.perform(manager().param("refId","999")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].refCode",nullValue())).andExpect(jsonPath("$.content[0].createdBy",nullValue()));
        mvc.perform(owner().param("branchId","72").param("type","TRANSFER_IN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].refCode").value("TRANSFER-71-72"));
    }
    @Test void referenceCodeDoesNotLeakUnrelatedBranchThroughMalformedReference() throws Exception {
        entry(9,71,81,"SALE","ORDER",101,null,"2026-11-05T12:00:00+07:00");
        mvc.perform(manager().param("refType","ORDER").param("refId","101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].refCode",nullValue()));
    }
    @Test void paginationAndCountUseSameBranchScope() throws Exception {
        mvc.perform(manager().param("page","1").param("size","2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.totalElements").value(6)).andExpect(jsonPath("$.content[*].id").value(contains(4,3)));
        mvc.perform(manager().param("page","100").param("size","2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.totalElements").value(6));
    }
    @Test void ownerSeesAllBranchesAndCanFilterOne() throws Exception {
        mvc.perform(owner()).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(8));
        mvc.perform(owner().param("branchId","72")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content[*].branchId").value(everyItem(is(72))));
    }
    @Test void assignedKeeperCanReadButCannotSelectOtherBranch() throws Exception {
        mvc.perform(get("/api/v1/inventory/transactions").with(user("711").roles("STOREKEEPER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(6));
        mvc.perform(manager().param("branchId","72")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
    }
    @ParameterizedTest @ValueSource(strings={"CASHIER","ADMIN"})
    void rolesOutsideOpenApiAreForbidden(String role) throws Exception {
        mvc.perform(get("/api/v1/inventory/transactions").with(user("713").roles(role)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/inventory/transactions").with(user("713").roles(role)).param("productId", "abc"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
    @ParameterizedTest @ValueSource(strings={"715","unknown-principal"})
    void unassignedPrincipalGetsEmptyPageAndNoForeignCount(String actor) throws Exception {
        mvc.perform(get("/api/v1/inventory/transactions").with(user(actor).roles("BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.content").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"type=UNKNOWN","refType=OTHER","refId=0","productId=-1","branchId=0",
            "productId=abc","page=-1","size=0","size=101","from=2026-02-30","from=2026-11-06&to=2026-11-05",
            "from=+10000-01-01"})
    void rejectsInvalidFilters(String query) throws Exception {
        var request=owner();
        for(String pair:query.split("&")) {
            String[] parts=pair.split("=",2); request.param(parts[0],parts[1]);
        }
        mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void nonexistentFilterIsEmptyAndInputCannotInjectSql() throws Exception {
        mvc.perform(manager().param("productId","999")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(manager().param("refId","100 OR 1=1")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_transaction",Integer.class)).isEqualTo(8);
    }
    @Test void realBearerContextIsAcceptedAndAnonymousRequestIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/inventory/transactions").header("Authorization","Bearer "+tokens.issue(710,"BRANCH_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(6));
        mvc.perform(get("/api/v1/inventory/transactions").header("Authorization","Bearer "+tokens.issue(713,"CASHIER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/inventory/transactions")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED")).andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
