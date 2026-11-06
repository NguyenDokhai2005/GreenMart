package vn.greenmart.inventory.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.inventory.dto.InventoryTransactionFilter;
import vn.greenmart.inventory.repository.InventoryTransactionQueryRepository;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class InventoryTransactionQueryServiceTest {
    private final InventoryTransactionQueryRepository repository = mock(InventoryTransactionQueryRepository.class);
    private final InventoryTransactionQueryService service =
            new InventoryTransactionQueryServiceImpl(repository, mock(JdbcTemplate.class));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reversedDateRangeIsRejectedBeforeQueryingLedger() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("1", null, "ROLE_OWNER"));
        var from = LocalDate.of(2026, 11, 6);
        var filter = new InventoryTransactionFilter(null, null, null, null, null, from, from.minusDays(1));

        assertThatThrownBy(() -> service.list(filter, null, null))
                .isInstanceOfSatisfying(AppException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.name()));
        verifyNoInteractions(repository);
    }
}
