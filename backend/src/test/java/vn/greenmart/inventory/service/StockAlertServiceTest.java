package vn.greenmart.inventory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import vn.greenmart.integration.outbox.OutboxService;
import vn.greenmart.inventory.dto.StockStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockAlertServiceTest {
    @ParameterizedTest
    @CsvSource(value = {"0,34,OUT", "0,NULL,OUT", "1,34,LOW", "34,34,LOW",
            "35,34,OK", "1,0,OK", "5,NULL,OK"}, nullValues = "NULL")
    void appliesReorderPointBoundaries(int available, Integer reorderPoint, StockStatus expected) {
        assertThat(StockAlertService.classify(available, reorderPoint)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = StockStatus.class, names = {"LOW", "OUT"})
    void emitsStockLowForNewAlerts(StockStatus severity) {
        var jdbc = mock(JdbcTemplate.class);
        var outbox = mock(OutboxService.class);
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<StockStatus>>any(),
                eq(1L), eq(2L))).thenReturn(severity);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Long>>any(),
                eq(1L), eq(2L), eq(severity.name()))).thenReturn(List.of(10L));

        new StockAlertService(jdbc, outbox).reconcileAfterStockUpdate(1L, 2L);

        verify(outbox).enqueue("stock_alert", "10", "stock.low",
                Map.of("alertId", 10L, "branchId", 1L, "productId", 2L, "severity", severity.name()));
    }

    @Test
    void doesNotEmitWhenAlertIsAlreadyOpen() {
        var jdbc = mock(JdbcTemplate.class);
        var outbox = mock(OutboxService.class);
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<StockStatus>>any(),
                eq(1L), eq(2L))).thenReturn(StockStatus.OUT);
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Long>>any(),
                eq(1L), eq(2L), eq("OUT"))).thenReturn(List.of());

        new StockAlertService(jdbc, outbox).reconcileAfterStockUpdate(1L, 2L);

        verify(jdbc).update(anyString(), eq("OUT"), eq(1L), eq(2L), eq("OUT"));
        verifyNoInteractions(outbox);
    }
}
