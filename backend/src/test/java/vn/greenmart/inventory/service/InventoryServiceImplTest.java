package vn.greenmart.inventory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.greenmart.inventory.exception.InsufficientStockException;
import vn.greenmart.inventory.repository.InventoryRepository;
import vn.greenmart.inventory.repository.InventoryTransactionWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test cho InventoryServiceImpl.
 * <p>
 * Mock InventoryRepository → test logic service (validate qty, kiểm tra rows-affected, exception đúng loại).
 * Không cần DB — test đơn luồng, không chứng minh an toàn đồng thời.
 * Bằng chứng concurrent safety: phân tích SQL conditional UPDATE + test #51 (100 luồng).
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private InventoryTransactionWriter transactionWriter;

    @InjectMocks
    private InventoryServiceImpl inventoryService;

    private static final Long BRANCH_ID  = 1L;
    private static final Long PRODUCT_ID = 5L;
    private static final Long REF_ID     = 100L;

    // =========================================================================
    // reserve
    // =========================================================================

    @Nested
    @DisplayName("reserve")
    class ReserveTests {

        @Test
        @DisplayName("thành công khi repository trả về 1 dòng bị tác động")
        void reserve_success() {
            when(inventoryRepository.reserve(BRANCH_ID, PRODUCT_ID, 3)).thenReturn(1);

            inventoryService.reserve(BRANCH_ID, PRODUCT_ID, 3, REF_ID);   // không throw = pass

            verify(inventoryRepository).reserve(BRANCH_ID, PRODUCT_ID, 3);
        }

        @Test
        @DisplayName("ném InsufficientStockException khi repository trả về 0 dòng")
        void reserve_insufficientStock() {
            when(inventoryRepository.reserve(BRANCH_ID, PRODUCT_ID, 5)).thenReturn(0);

            assertThatThrownBy(() -> inventoryService.reserve(BRANCH_ID, PRODUCT_ID, 5, REF_ID))
                    .isInstanceOf(InsufficientStockException.class)
                    .satisfies(ex -> {
                        var e = (InsufficientStockException) ex;
                        assertThat(e.getBranchId()).isEqualTo(BRANCH_ID);
                        assertThat(e.getProductId()).isEqualTo(PRODUCT_ID);
                        assertThat(e.getRequested()).isEqualTo(5);
                    });
        }

        @Test
        @DisplayName("ném IllegalArgumentException khi qty = 0")
        void reserve_qtyZero() {
            assertThatThrownBy(() -> inventoryService.reserve(BRANCH_ID, PRODUCT_ID, 0, REF_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("qty");
        }

        @Test
        @DisplayName("ném IllegalArgumentException khi qty âm")
        void reserve_qtyNegative() {
            assertThatThrownBy(() -> inventoryService.reserve(BRANCH_ID, PRODUCT_ID, -1, REF_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // release
    // =========================================================================

    @Nested
    @DisplayName("release")
    class ReleaseTests {

        @Test
        @DisplayName("thành công khi repository trả về 1 dòng")
        void release_success() {
            when(inventoryRepository.release(BRANCH_ID, PRODUCT_ID, 3)).thenReturn(1);

            inventoryService.release(BRANCH_ID, PRODUCT_ID, 3, REF_ID);

            verify(inventoryRepository).release(BRANCH_ID, PRODUCT_ID, 3);
        }

        @Test
        @DisplayName("ném IllegalStateException khi repository trả về 0 (reserved < qty hoặc row không có)")
        void release_rowsZero_throwsIllegalState() {
            when(inventoryRepository.release(BRANCH_ID, PRODUCT_ID, 5)).thenReturn(0);

            assertThatThrownBy(() -> inventoryService.release(BRANCH_ID, PRODUCT_ID, 5, REF_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("release thất bại");
        }

        @Test
        @DisplayName("ném IllegalArgumentException khi qty = 0")
        void release_qtyZero() {
            assertThatThrownBy(() -> inventoryService.release(BRANCH_ID, PRODUCT_ID, 0, REF_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // commitSale
    // =========================================================================

    @Nested
    @DisplayName("commitSale")
    class CommitSaleTests {

        @Test
        @DisplayName("thành công khi repository trả về 1 dòng")
        void commitSale_success() {
            when(inventoryRepository.commitSale(BRANCH_ID, PRODUCT_ID, 3)).thenReturn(1);

            inventoryService.commitSale(BRANCH_ID, PRODUCT_ID, 3, REF_ID);

            verify(inventoryRepository).commitSale(BRANCH_ID, PRODUCT_ID, 3);
        }

        @Test
        @DisplayName("ném IllegalStateException khi repository trả về 0")
        void commitSale_rowsZero_throwsIllegalState() {
            when(inventoryRepository.commitSale(BRANCH_ID, PRODUCT_ID, 5)).thenReturn(0);

            assertThatThrownBy(() -> inventoryService.commitSale(BRANCH_ID, PRODUCT_ID, 5, REF_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("commitSale thất bại");
        }

        @Test
        @DisplayName("ném IllegalArgumentException khi qty = 0")
        void commitSale_qtyZero() {
            assertThatThrownBy(() -> inventoryService.commitSale(BRANCH_ID, PRODUCT_ID, 0, REF_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // sellDirect
    // =========================================================================

    @Nested
    @DisplayName("sellDirect")
    class SellDirectTests {

        @Test
        @DisplayName("thành công khi repository trả về 1 dòng")
        void sellDirect_success() {
            when(inventoryRepository.sellDirect(BRANCH_ID, PRODUCT_ID, 4)).thenReturn(1);

            inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, 4, REF_ID);

            verify(inventoryRepository).sellDirect(BRANCH_ID, PRODUCT_ID, 4);
        }

        @Test
        @DisplayName("ném InsufficientStockException khi repository trả về 0 dòng")
        void sellDirect_insufficientStock() {
            when(inventoryRepository.sellDirect(BRANCH_ID, PRODUCT_ID, 8)).thenReturn(0);

            assertThatThrownBy(() -> inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, 8, REF_ID))
                    .isInstanceOf(InsufficientStockException.class)
                    .satisfies(ex -> {
                        var e = (InsufficientStockException) ex;
                        assertThat(e.getRequested()).isEqualTo(8);
                    });
        }

        @Test
        @DisplayName("ném IllegalArgumentException khi qty = 0")
        void sellDirect_qtyZero() {
            assertThatThrownBy(() -> inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, 0, REF_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("sellDirect không ăn vào reserved — kiểm tra qua SQL WHERE available = on_hand-reserved >= qty")
        void sellDirect_checksAvailableNotOnHand() {
            // Kịch bản: on_hand=5, reserved=3, available=2
            // sellDirect(qty=2) → available đủ → repository.sellDirect trả 1 → success
            // Nếu service dùng on_hand (=5) thay vì available (=2) thì qty=4 sẽ sai cho vào.
            // Ở unit test này chỉ verify service gọi đúng method; SQL logic test ở RepositoryTest.
            when(inventoryRepository.sellDirect(BRANCH_ID, PRODUCT_ID, 2)).thenReturn(1);

            inventoryService.sellDirect(BRANCH_ID, PRODUCT_ID, 2, REF_ID);

            verify(inventoryRepository).sellDirect(BRANCH_ID, PRODUCT_ID, 2);
        }
    }
}
