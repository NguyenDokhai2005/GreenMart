package vn.greenmart.customer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import vn.greenmart.customer.dto.OrderSummaryResponse;
import vn.greenmart.customer.repository.CustomerRepository;
import vn.greenmart.customer.service.CustomerService;

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

    @BeforeEach
    void setupData() {
        jdbcTemplate.execute("DELETE FROM order_item");
        jdbcTemplate.execute("DELETE FROM sales_order");
        jdbcTemplate.execute("DELETE FROM user_branch");
        customerRepository.deleteAll();

        // Tạo chi nhánh
        jdbcTemplate.execute("""
            INSERT INTO branch (code, name, address, is_active)
            VALUES ('GM-001', 'GreenMart Quận 1', '123 Lê Lợi', true)
            ON CONFLICT (code) DO NOTHING
        """);
        jdbcTemplate.execute("""
            INSERT INTO branch (code, name, address, is_active)
            VALUES ('GM-002', 'GreenMart Quận 3', '456 Võ Văn Tần', true)
            ON CONFLICT (code) DO NOTHING
        """);

        branch1Id = jdbcTemplate.queryForObject("SELECT id FROM branch WHERE code = 'GM-001'", Long.class);
        branch2Id = jdbcTemplate.queryForObject("SELECT id FROM branch WHERE code = 'GM-002'", Long.class);

        // Kênh bán từ V3 seed
        posChannelId = jdbcTemplate.queryForObject("SELECT id FROM sales_channel WHERE code = 'POS'", Long.class);
        webChannelId = jdbcTemplate.queryForObject("SELECT id FROM sales_channel WHERE code = 'WEB'", Long.class);

        // User & phân quyền chi nhánh (User 10 gán chi nhánh 1)
        jdbcTemplate.execute("""
            INSERT INTO app_user (id, username, password_hash, full_name, role, is_active)
            VALUES (10, 'cashier_cn1', '$2a$10$abcdefghijklmnopqrstuvwxyz123456', 'Thu Ngân CN1', 'CASHIER', true)
            ON CONFLICT (id) DO NOTHING
        """);
        jdbcTemplate.execute("INSERT INTO user_branch (user_id, branch_id) VALUES (10, " + branch1Id + ") ON CONFLICT DO NOTHING");
    }

    //Tạo khách 0901234567 thành công -> tìm GET /customers?q=0901234567 ra đúng 1 khách
    @Test
    @WithMockUser(username = "10", roles = "CASHIER")
    void testAC1_CreateCustomer_ThenSearchFound() {
        CustomerRequest req = new CustomerRequest("0901234567", "Nguyễn Văn A", "a@gmail.com");
        CustomerResponse created = customerService.createCustomer(req);

        assertNotNull(created.id());
        assertEquals("0901234567", created.phone());

        PageResponse<CustomerResponse> searchResult = customerService.listCustomers("0901234567", null, 0, 20, null);
        assertEquals(1, searchResult.totalElements());
        assertEquals("0901234567", searchResult.content().get(0).phone());
    }

    // Trùng số điện thoại khi tạo hoặc sửa -> 409 DUPLICATE_PHONE
    @Test
    @WithMockUser(username = "10", roles = "CASHIER")
    void testAC2_DuplicatePhone_ThrowsConflict() {
        customerRepository.save(new Customer("0901234567", "Khách 1", null));
        Customer c2 = customerRepository.save(new Customer("0908888888", "Khách 2", null));

        // Tạo mới trùng SĐT khách 1
        CustomerRequest createReq = new CustomerRequest("0901234567", "Khách Mới", null);
        AppException exCreate = assertThrows(AppException.class, () -> customerService.createCustomer(createReq));
        assertEquals(ErrorCode.DUPLICATE_PHONE.name(), exCreate.getCode());

        // Sửa khách 2 đổi sang SĐT của khách 1
        CustomerRequest updateReq = new CustomerRequest("0901234567", "Khách 2 Đổi Tên", null);
        AppException exUpdate = assertThrows(AppException.class, () -> customerService.updateCustomer(c2.getId(), updateReq));
        assertEquals(ErrorCode.DUPLICATE_PHONE.name(), exUpdate.getCode());
    }

    // Khách có đơn POS và đơn WEB, gọi GET /customers/{id}/orders trả đủ cả 2 kênh, mới nhất trước
    @Test
    @WithMockUser(username = "1", roles = "OWNER")
    void testAC4_CustomerOrders_ReturnsPosAndWeb_SortedLatestFirst() {
        Customer customer = customerRepository.save(new Customer("0909999999", "Khách Thân Thiết", null));

        // Đơn 1: POS tạo lúc 10:00
        jdbcTemplate.execute(String.format("""
            INSERT INTO sales_order (code, channel_id, branch_id, customer_id, status, subtotal, discount, total, created_at)
            VALUES ('POS-001', %d, %d, %d, 'COMPLETED', 100000, 0, 100000, '2026-11-01 10:00:00+07')
        """, posChannelId, branch1Id, customer.getId()));

        // Đơn 2: WEB tạo lúc 11:30 (mới hơn)
        jdbcTemplate.execute(String.format("""
            INSERT INTO sales_order (code, channel_id, external_order_id, branch_id, customer_id, status, subtotal, discount, total, created_at)
            VALUES ('WEB-001', %d, 'EXT-WEB-1', %d, %d, 'COMPLETED', 200000, 0, 200000, '2026-11-01 11:30:00+07')
        """, webChannelId, branch1Id, customer.getId()));

        PageResponse<OrderSummaryResponse> orders = customerService.listCustomerOrders(customer.getId(), 0, 20);

        assertEquals(2, orders.totalElements());
        assertEquals("WEB", orders.content().get(0).channel());
        assertEquals("POS", orders.content().get(1).channel());
    }

    // CASHIER chi nhánh 1 chỉ xem được đơn tại chi nhánh 1
    @Test
    @WithMockUser(username = "10", roles = "CASHIER")
    void testCustomerOrders_CashierRestrictedToAssignedBranch() {
        Customer customer = customerRepository.save(new Customer("0909999999", "Khách A", null));

        // Đơn tại CN1
        jdbcTemplate.execute(String.format("""
            INSERT INTO sales_order (code, channel_id, branch_id, customer_id, status, subtotal, discount, total, created_at)
            VALUES ('POS-CN1', %d, %d, %d, 'COMPLETED', 100000, 0, 100000, '2026-11-01 10:00:00+07')
        """, posChannelId, branch1Id, customer.getId()));

        // Đơn tại CN2
        jdbcTemplate.execute(String.format("""
            INSERT INTO sales_order (code, channel_id, branch_id, customer_id, status, subtotal, discount, total, created_at)
            VALUES ('POS-CN2', %d, %d, %d, 'COMPLETED', 150000, 0, 150000, '2026-11-01 11:00:00+07')
        """, posChannelId, branch2Id, customer.getId()));

        PageResponse<OrderSummaryResponse> orders = customerService.listCustomerOrders(customer.getId(), 0, 20);

        // Chỉ thấy 1 đơn của CN1
        assertEquals(1, orders.totalElements());
        assertEquals("POS-CN1", orders.content().get(0).code());
    }
}
