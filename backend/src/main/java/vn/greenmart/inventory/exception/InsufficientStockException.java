package vn.greenmart.inventory.exception;

/**
 * Ném khi tồn khả dụng (on_hand - reserved) < số lượng yêu cầu.
 * Caller (SalesService) bắt và chuyển thành HTTP 409 INSUFFICIENT_STOCK.
 */
public class InsufficientStockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long branchId;
    private final long productId;
    private final int requested;
    private final int available;

    public InsufficientStockException(long branchId, long productId, int requested, int available) {
        super(String.format(
                "Không đủ tồn: branch=%d, product=%d, yêu cầu=%d, khả dụng=%d",
                branchId, productId, requested, available));
        this.branchId  = branchId;
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    public long getBranchId()  { return branchId; }
    public long getProductId() { return productId; }
    public int  getRequested() { return requested; }
    public int  getAvailable() { return available; }
}
