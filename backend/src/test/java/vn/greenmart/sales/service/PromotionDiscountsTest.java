package vn.greenmart.sales.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class PromotionDiscountsTest {
    @ParameterizedTest
    @CsvSource({"15000,1,100000,15000", "15000,3,300000,45000", "15000,3,30000,30000", "5000,3,54000,15000", "1000,3,0,0"})
    void amountScalesByUnitsAndCannotExceedSubtotal(long value,int quantity,long subtotal,long expected) {
        assertThat(PromotionDiscounts.amount(value,quantity,subtotal)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"15,6000,18000,2700", "15,6000,36000,5400", "15,6000,54000,6000", "33,,30003,9900", "100,,1,1", "1,,99,0"})
    void percentFloorsToDongAndCapAppliesOnce(long value,Long cap,long subtotal,long expected) {
        assertThat(PromotionDiscounts.percent(value,cap,subtotal)).isEqualTo(expected);
    }

    @Test void largeIntermediateValuesAreClampedBeforeConversion() {
        assertThat(PromotionDiscounts.amount(Long.MAX_VALUE,9999,9999)).isEqualTo(9999);
        assertThat(PromotionDiscounts.percent(100,null,Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(PromotionDiscounts.percent(100,5000L,Long.MAX_VALUE)).isEqualTo(5000);
    }
}
