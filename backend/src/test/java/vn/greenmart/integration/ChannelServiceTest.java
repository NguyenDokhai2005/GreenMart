package vn.greenmart.integration;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.integration.channel.ChannelService;
import vn.greenmart.common.exception.AppException;
import static org.assertj.core.api.Assertions.*;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ChannelService.class)
@Testcontainers
@WithMockUser(roles = "ADMIN")
class ChannelServiceTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelService service;
    @BeforeEach void fixture() {
        jdbc.update("INSERT INTO branch(id,code,name,address) VALUES (901,'TEST901','CN1','A'),(902,'TEST902','CN2','B')");
        jdbc.update("INSERT INTO category(id,name) VALUES (901,'Test')");
        jdbc.update("INSERT INTO product(id,sku,name,unit,category_id,sale_price,cost_price) VALUES (205,'TEST205','Sữa','chai',901,29000,20000),(206,'TEST206','Gạo','gói',901,30000,20000)");
    }
    @Test void resolvesExternalSkuToProduct205AndKeepsChannelsSeparate() {
        service.mapProduct("WEB", 205, "W-SUA-TH-1L");
        service.mapProduct("MARKET", 206, "W-SUA-TH-1L");
        assertThat(service.resolveProductId("WEB", "W-SUA-TH-1L")).isEqualTo(205);
        assertThat(service.resolveProductId("MARKET", "W-SUA-TH-1L")).isEqualTo(206);
        service.mapProduct("WEB", 205, "W-SUA-NEW");
        assertThatThrownBy(() -> service.resolveProductId("WEB", "W-SUA-TH-1L"))
                .isInstanceOfSatisfying(AppException.class, ex -> assertThat(ex.getCode()).isEqualTo("UNMAPPED_SKU"));
    }
    @Test void postgresUniqueConstraintRejectsDuplicateSkuInSameChannel() {
        service.mapProduct("WEB", 205, "W-SUA-TH-1L");
        assertThatThrownBy(() -> service.mapProduct("WEB", 206, "W-SUA-TH-1L"))
                .isInstanceOfSatisfying(AppException.class, ex -> assertThat(ex.getCode()).isEqualTo("CONFLICT"));
    }
    @Test void channelConfigurationStoresDefaultBranchesAndSafetyStock() {
        service.configure("WEB", 901L, 2, List.of(901L, 902L));
        var channel = service.findChannel("WEB");
        assertThat(channel.defaultBranchId()).isEqualTo(901L);
        assertThat(channel.branchIds()).containsExactly(901L, 902L);
        assertThat(channel.safetyStock()).isEqualTo(2);
        assertThatThrownBy(() -> service.configure("WEB", 903L, 2, List.of(901L)))
                .isInstanceOf(AppException.class);
    }
    @Test @WithMockUser(roles = "CASHIER") void cashierCannotChangeMappings() {
        assertThatThrownBy(() -> service.mapProduct("WEB", 205, "W-X"))
                .isInstanceOfSatisfying(AppException.class, ex -> assertThat(ex.getCode()).isEqualTo("FORBIDDEN"));
    }
    @Test void unknownChannelAndUnmappedSkuHaveContractErrors() {
        assertThatThrownBy(() -> service.findChannel("OTHER")).isInstanceOfSatisfying(AppException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> service.resolveProductId("WEB", "W-UNKNOWN")).isInstanceOfSatisfying(AppException.class,
                ex -> { assertThat(ex.getCode()).isEqualTo("UNMAPPED_SKU"); assertThat(ex.getStatus().value()).isEqualTo(422); });
    }
}
