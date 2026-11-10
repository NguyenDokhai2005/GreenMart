package vn.greenmart.inventory.domain;

import org.junit.jupiter.api.Test;
import vn.greenmart.common.exception.AppException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockTransferDocumentTest {
    private final StockDocument transfer = StockDocument.transferDraft(81L, 82L, 810L, null, List.of());

    @Test
    void approvingTransferRecordsDispatchAndPreventsAnotherApproval() {
        transfer.approve(811L);

        assertThat(transfer.getStatus()).isEqualTo(StockDocumentStatus.IN_TRANSIT);
        assertThat(transfer.getApprovedBy()).isEqualTo(811L);
        assertThat(transfer.getApprovedAt()).isAfterOrEqualTo(transfer.getCreatedAt());
        assertThatThrownBy(() -> transfer.approve(811L))
                .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getCode()).isEqualTo("INVALID_STATUS"));
    }

    @Test
    void receivingRequiresDispatchAndCannotBeRepeated() {
        assertThatThrownBy(() -> transfer.receive(812L, Map.of()))
                .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getCode()).isEqualTo("INVALID_STATUS"));

        transfer.approve(811L);
        transfer.receive(812L, Map.of());

        assertThat(transfer.getStatus()).isEqualTo(StockDocumentStatus.RECEIVED);
        assertThat(transfer.getReceivedBy()).isEqualTo(812L);
        assertThat(transfer.getReceivedAt()).isAfterOrEqualTo(transfer.getApprovedAt());
        assertThatThrownBy(() -> transfer.receive(813L, Map.of()))
                .isInstanceOfSatisfying(AppException.class, error -> assertThat(error.getCode()).isEqualTo("INVALID_STATUS"));
        assertThat(transfer.getReceivedBy()).isEqualTo(812L);
    }
}
