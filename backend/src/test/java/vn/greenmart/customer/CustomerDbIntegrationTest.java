package vn.greenmart.customer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.customer.domain.Customer;
import vn.greenmart.customer.dto.CustomerRequest;
import vn.greenmart.customer.dto.CustomerResponse;
import vn.greenmart.customer.repository.CustomerRepository;
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.sales.dto.OrderSummaryResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
@Transactional
class CustomerDbIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            vn.greenmart.inventory.PostgresTestImage.IMAGE
    );

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long branch1Id;
    private Long branch2Id;
    private Long posChannelId;
    private Long webChannelId;

    private final List<Long> insertedOrderIds = new ArrayList<>();
    private final List<Long> insertedCustomerIds = new ArrayList<>();
    private static final long TEST_USER_ID = 8888L;

    @BeforeEach
    void setupData() {
        jdbcTemplate.execute("""
            INSERT INTO branch (code, name, address, is_active)
            VALUES ('GM-CUS-1', 'GreenMart Test CN1', '123 Lê Lợi', true)
            ON CONFLICT (code) DO NOTHING
        """);
        jdbcTemplate.execute("""
            INSERT INTO branch (code, name, address, is_active)
            VALUES ('GM-CUS-2', 'GreenMart Test CN2', '456 Võ Văn Tần', true)
            ON CONFLICT (code) DO NOTHING
        """);

        branch1Id = jdbcTemplate.queryForObject("SELECT id FROM branch WHERE code = 'GM-CUS-1'", Long.class);
        branch2Id = jdbcTemplate.queryForObject("SELECT id FROM branch WHERE code = 'GM-CUS-2'", Long.class);

        posChannelId = jdbcTemplate.queryForObject("SELECT id FROM sales_channel WHERE code = 'POS'", Long.class);
        webChannelId = jdbcTemplate.queryForObject("SELECT id FROM sales_channel WHERE code = 'WEB'", Long.class);

        // Tạo user và phân quyền vào branch 1
        jdbcTemplate.execute(String.format("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (%d, 'cashier_test_88', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Thu Ngân Test', 'CASHIER', true)
            ON CONFLICT (id) DO NOTHING
        """, TEST_USER_ID));
        jdbcTemplate.execute(String.format("INSERT INTO user_branch (user_id, branch_id) VALUES (%d, %d) ON CONFLICT DO NOTHING", TEST_USER_ID, branch1Id));
    }

    @AfterEach
    void tearDownData() {
        // Dọn dẹp chính xác theo ID, không xóa toàn bảng
        if (!insertedOrderIds.isEmpty()) {
            for (Long orderId : insertedOrderIds) {
                jdbcTemplate.update("DELETE FROM sales_order WHERE id = ?", orderId);
            }
            insertedOrderIds.clear();
        }
        if (!insertedCustomerIds.isEmpty()) {
            customerRepository.deleteAllById(insertedCustomerIds);
            insertedCustomerIds.clear();
        }
        jdbcTemplate.update("DELETE FROM user_branch WHERE user_id = ?", TEST_USER_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", TEST_USER_ID);
    }

    @Test
    @WithMockUser(username = "8888", roles = "CASHIER")
    void testAC1_CreateCustomer_ThenSearchFound() {
        CustomerRequest req = new CustomerRequest("0901234567", "Nguyễn Văn A", "a@gmail.com");
        CustomerResponse created = customerService.createCustomer(req);
        insertedCustomerIds.add(created.id());

        assertNotNull(created.id());
        assertEquals("0901234567", created.phone());

        PageResponse<CustomerResponse> searchResult = customerService.listCustomers("0901234567", null, PageRequest.of(0, 20));
        assertEquals(1, searchResult.totalElements());
        assertEquals("0901234567", searchResult.content().get(0).phone());
    }

    @Test
    @WithMockUser(username = "8888", roles = "CASHIER")
    void testAC2_DuplicatePhone_ThrowsConflict() {
        Customer c1 = customerRepository.save(new Customer("0901234567", "Khách 1", null));
        Customer c2 = customerRepository.save(new Customer("0908888888", "Khách 2", null));
        insertedCustomerIds.add(c1.getId());
        insertedCustomerIds.add(c2.getId());

        CustomerRequest createReq = new CustomerRequest("0901234567", "Khách Mới", null);
        AppException exCreate = assertThrows(AppException.class, () -> customerService.createCustomer(createReq));
        assertEquals(ErrorCode.DUPLICATE_PHONE.name(), exCreate.getCode());

        CustomerRequest updateReq = new CustomerRequest("0901234567", "Khách 2 Đổi SĐT", null);
        AppException exUpdate = assertThrows(AppException.class, () -> customerService.updateCustomer(c2.getId(), updateReq));
        assertEquals(ErrorCode.DUPLICATE_PHONE.name(), exUpdate.getCode());
    }

    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testAC4_CustomerOrders_ReturnsPosAndWeb_SortedLatestFirst() {
        Customer customer = customerRepository.save(new Customer("0909999999", "Khách Thân Thiết", null));
        insertedCustomerIds.add(customer.getId());

        Long order1Id = jdbcTemplate.queryForObject(String.format("""
            INSERT INTO sales_order (code, channel_id, branch_id, customer_id, status, subtotal, discount, total, created_at)
            VALUES ('POS-001', %d, %d, %d, 'COMPLETED', 100000, 0, 100000, '2026-11-01 10:00:00+07')
            RETURNING id
        """, posChannelId, branch1Id, customer.getId()), Long.class);
        insertedOrderIds.add(order1Id);

        Long order2Id = jdbcTemplate.queryForObject(String.format("""
            INSERT INTO sales_order (code, channel_id, external_order_id, branch_id, customer_id, status, subtotal, discount, total, created_at)
            VALUES ('WEB-001', %d, 'EXT-WEB-1', %d, %d, 'COMPLETED', 200000, 0, 200000, '2026-11-01 11:30:00+07')
            RETURNING id
        """, webChannelId, branch1Id, customer.getId()), Long.class);
        insertedOrderIds.add(order2Id);

        PageResponse<OrderSummaryResponse> orders = customerService.listCustomerOrders(customer.getId(), PageRequest.of(0, 20));

        assertEquals(2, orders.totalElements());
        assertEquals("WEB", orders.content().get(0).channel());
        assertEquals("POS", orders.content().get(1).channel());
    }

    @Test
    @WithMockUser(username = "8888", roles = "CASHIER")
    void testCustomerOrders_CashierRestrictedToAssignedBranch() {
        Customer customer = customerRepository.save(new Customer("0909999999", "Khách B", null));
        insertedCustomerIds.add(customer.getId());

        Long orderCn1 = jdbcTemplate.queryForObject(String.format("""
            INSERT INTO sales_order (code, channel_id, branch_id, customer_id, status, subtotal, discount, total, created_at)
            VALUES ('POS-CN1', %d, %d, %d, 'COMPLETED', 100000, 0, 100000, '2026-11-01 10:00:00+07')
            RETURNING id
        """, posChannelId, branch1Id, customer.getId()), Long.class);
        insertedOrderIds.add(orderCn1);

        Long orderCn2 = jdbcTemplate.queryForObject(String.format("""
            INSERT INTO sales_order (code, channel_id, branch_id, customer_id, status, subtotal, discount, total, created_at)
            VALUES ('POS-CN2', %d, %d, %d, 'COMPLETED', 150000, 0, 150000, '2026-11-01 11:00:00+07')
            RETURNING id
        """, posChannelId, branch2Id, customer.getId()), Long.class);
        insertedOrderIds.add(orderCn2);

        PageResponse<OrderSummaryResponse> orders = customerService.listCustomerOrders(customer.getId(), PageRequest.of(0, 20));

        assertEquals(1, orders.totalElements());
        assertEquals("POS-CN1", orders.content().get(0).code());
    }
}
