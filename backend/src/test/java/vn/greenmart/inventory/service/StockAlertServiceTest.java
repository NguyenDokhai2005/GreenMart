package vn.greenmart.inventory.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vn.greenmart.inventory.dto.StockStatus;

import static org.assertj.core.api.Assertions.assertThat;

class StockAlertServiceTest {
    @ParameterizedTest
    @CsvSource(value = {"0,34,OUT", "0,NULL,OUT", "1,34,LOW", "34,34,LOW",
            "35,34,OK", "1,0,OK", "5,NULL,OK"}, nullValues = "NULL")
    void appliesReorderPointBoundaries(int available, Integer reorderPoint, StockStatus expected) {
        assertThat(StockAlertService.classify(available, reorderPoint)).isEqualTo(expected);
    }
}
