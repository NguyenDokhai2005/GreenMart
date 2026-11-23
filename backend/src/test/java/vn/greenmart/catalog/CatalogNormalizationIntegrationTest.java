package vn.greenmart.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import vn.greenmart.integration.channel.ChannelService;
import vn.greenmart.integration.channel.ChannelSignatures;
import vn.greenmart.inventory.PostgresTestImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@WithMockUser(roles = "OWNER")
class CatalogNormalizationIntegrationTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(PostgresTestImage.IMAGE);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelService channels;
    @Autowired ChannelSignatures signatures;

    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE category RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO category(id,name) VALUES(43001,'Fixture')");
        jdbc.update("""
                INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price)
                VALUES(43001,'GM43001','Product','box',43001,100,50)
                """);
    }

    @Test void duplicateRootNameIsRejected() throws Exception {
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sữa Tươi\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  SỮA   TƯƠI  \"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category", Integer.class)).isEqualTo(2);
    }

    @Test @WithMockUser(roles = "ADMIN")
    void invalidChannelSkuIsRejected() throws Exception {
        mvc.perform(put("/api/v1/integrations/WEB/products/43001").contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalSku\":\"<bad>\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_product", Integer.class)).isZero();
    }

    @Test void categoryNamesNormalizeUnicodeAndWhitespaceButKeepAccents() throws Exception {
        String decomposed = Normalizer.normalize("Sữa", Normalizer.Form.NFD);
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  " + decomposed + "   Tươi  \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Sữa Tươi"));
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"SỮA TƯƠI\"}")) .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sua Tuoi\"}")) .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(ints = {0x1c, 0x1d, 0x1e, 0x1f, 0x09, 0x0d, 0x20, 0x85, 0xa0, 0x1680,
            0x2000, 0x2007, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000})
    void javaAndPostgresAgreeOnUnicodeWhitespaceAndControlSeparators(int codePoint) {
        String mark = Character.toString(codePoint);
        String raw = mark + "SỮA" + mark + mark + "TƯƠI" + mark;
        String display = vn.greenmart.catalog.domain.CategoryNames.normalize(raw);
        assertThat(jdbc.queryForObject("SELECT greenmart_category_key(?)=greenmart_category_key(?)", Boolean.class,
                raw, display)).isTrue();
        if (codePoint >= 0x1c && codePoint <= 0x1f) assertThat(display).isEqualTo(raw);
        else assertThat(display).isEqualTo("SỮA TƯƠI");
    }

    @Test void namesAreUniqueWithinParentAndMovesCannotCreateDuplicates() throws Exception {
        jdbc.update("INSERT INTO category(id,name,parent_id) VALUES(43002,'Other',NULL),(43003,'Sữa',43001),(43004,'SỮA',43002)");
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"sữa\",\"parentId\":43001}")) .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/categories/43004").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sữa\",\"parentId\":43001}")) .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT parent_id FROM category WHERE id=43004", Long.class)).isEqualTo(43002);
        mvc.perform(put("/api/v1/categories/43003").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  SỮA  \",\"parentId\":43001}")) .andExpect(status().isOk());
        mvc.perform(put("/api/v1/categories/43003").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" fixture \"}")) .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/categories/43001").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Fixture\",\"parentId\":43003}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CATEGORY_CYCLE"));
        mvc.perform(delete("/api/v1/categories/43001")) .andExpect(status().isConflict());
    }

    @ParameterizedTest @ValueSource(strings = {"", "   ", " ", "\t\n"})
    void blankNormalizedNamesAreRejected(String name) throws Exception {
        String encoded = name.replace("\t", "\\t").replace("\n", "\\n");
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + encoded + "\"}")) .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category", Integer.class)).isEqualTo(1);
    }

    @Test void categoryNameBoundaryIsCheckedAfterNormalization() throws Exception {
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  " + "A".repeat(120) + "  \"}")) .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "B".repeat(121) + "\"}")) .andExpect(status().isBadRequest());
    }

    @Test void databaseAlsoRejectsNormalizedDuplicateAndBlankNames() {
        jdbc.update("INSERT INTO category(id,name) VALUES(43002,'Sữa Tươi')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO category(name) VALUES(?)", " SỮA TƯƠI "))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO category(name) VALUES(?)", "   "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentEquivalentCreatesHaveOneWinner(boolean root) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results = List.of("  Sữa   Tươi ", "SỮA TƯƠI").stream().map(name -> workers.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/categories").with(user("43001").roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\""
                                + (root ? "" : ",\"parentId\":43001") + "}"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category", Integer.class)).isEqualTo(2);
    }

    @Test @WithMockUser(roles = "CASHIER")
    void cashierCannotCreateCategory() throws Exception {
        mvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Denied\"}")) .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"A", "aB-12._:z", " W-Case ", " W-Case "})
    @WithMockUser(roles = "ADMIN")
    void mappingAndBothLookupPathsUseSameNormalization(String sku) throws Exception {
        mvc.perform(put("/api/v1/integrations/WEB/products/43001").contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalSku\":\"" + sku + "\"}")) .andExpect(status().isNoContent());
        assertThat(channels.resolveProductId("WEB", sku)).isEqualTo(43001);
        long channel = channels.findChannel("WEB").id();
        assertThat(channels.findMappedProduct(channel, sku)).contains(43001L);
        assertThat(jdbc.queryForObject("SELECT external_sku FROM channel_product", String.class))
                .isEqualTo(sku.replace(" ", "").replace(" ", "").strip());
        assertThat(channels.findMappedProduct(channel, "different-case")).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"<bad>", "W BAD", "W/1", "é", "-BAD", " ", "W😀"})
    @WithMockUser(roles = "ADMIN")
    void invalidMappingDoesNotMutateExistingMapping(String sku) throws Exception {
        channels.mapProduct("WEB", 43001, "Keep-Me");
        mvc.perform(put("/api/v1/integrations/WEB/products/43001").contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalSku\":\"" + sku + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT external_sku FROM channel_product", String.class)).isEqualTo("Keep-Me");
        assertThatThrownBy(() -> channels.findMappedProduct(channels.findChannel("WEB").id(), sku))
                .isInstanceOf(vn.greenmart.common.exception.AppException.class);
    }

    @Test @WithMockUser(roles = "ADMIN")
    void skuBoundaryCaseAndChannelIsolationArePreserved() throws Exception {
        String max = "A".repeat(60);
        mvc.perform(put("/api/v1/integrations/WEB/products/43001").contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalSku\":\" " + max + " \"}")) .andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/integrations/WEB/products/43001").contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalSku\":\"" + max + "A\"}")) .andExpect(status().isBadRequest());
        assertThat(channels.findMappedProduct(channels.findChannel("WEB").id(), max.toLowerCase())).isEmpty();
        assertThat(channels.findMappedProduct(channels.findChannel("MARKET").id(), max)).isEmpty();
        assertThatThrownBy(() -> jdbc.update("UPDATE channel_product SET external_sku='<invalid>'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void signedWebhookRejectsInvalidSkuBeforeCreatingCustomerOrOrder() throws Exception {
        jdbc.update("INSERT INTO branch(id,code,name,address) VALUES(43001,'GM43001','Branch','A')");
        jdbc.update("UPDATE sales_channel SET webhook_secret='fixture-only',default_branch_id=43001 WHERE code='WEB'");
        jdbc.update("INSERT INTO channel_branch(channel_id,branch_id) SELECT id,43001 FROM sales_channel WHERE code='WEB'");
        byte[] body = ("{\"externalOrderId\":\"invalid-430\",\"orderedAt\":\"" + OffsetDateTime.now()
                + "\",\"customer\":{\"name\":\"Test\",\"phone\":\"0900000430\"},"
                + "\"items\":[{\"externalSku\":\"<invalid>\",\"quantity\":1,\"unitPrice\":100}],\"paymentMethod\":\"COD\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(post("/api/v1/integrations/WEB/orders").header("X-Signature", signatures.sign("WEB", body))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE phone='0900000430'", Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"externalSku\":null}", "{\"externalSku\":\"\"}"})
    @WithMockUser(roles = "ADMIN")
    void invalidMappingBodyUsesExistingValidationEnvelope(String body) throws Exception {
        mvc.perform(put("/api/v1/integrations/WEB/products/43001").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channel_product", Integer.class)).isZero();
    }

    @Test @WithMockUser(roles = "ADMIN")
    void invalidMappingPathUsesExistingConstraintViolationHandler() throws Exception {
        mvc.perform(put("/api/v1/integrations/WEB/products/0").contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalSku\":\"Valid\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
