package vn.greenmart.sales.service;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import vn.greenmart.branch.dto.BranchSaleInfo;
import vn.greenmart.branch.service.BranchService;
import vn.greenmart.sales.domain.SalesOrder;
import vn.greenmart.sales.dto.PromotionRef;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderResponseMapperTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BranchService branches = mock(BranchService.class);
    private final OrderResponseMapper mapper = new OrderResponseMapper(branches, null, null, null, jdbc);
    private SalesOrder order;

    @BeforeEach
    void fixture() {
        order = new SalesOrder();
        order.setBranchId(1L);
        order.setPromotionId(427L);
        when(branches.getSaleInfo(1L)).thenReturn(new BranchSaleInfo(1L, "GM-1", "Branch"));
    }

    @Test
    void databaseFailureIsNotDisguisedAsAFabricatedPromotionCode() {
        var failure = new DataAccessResourceFailureException("test-only database failure");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PromotionRef>>any(), eq(427L)))
                .thenThrow(failure);
        assertThatThrownBy(() -> mapper.toOrderResponse(order, List.of(), List.of()))
                .isSameAs(failure);
    }

    @Test
    void missingReferencedPromotionIsAnInternalDataError() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PromotionRef>>any(), eq(427L)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> mapper.toOrderResponse(order, List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("427");
    }
}
