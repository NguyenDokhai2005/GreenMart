package vn.greenmart.catalog.service;

/** Product cost ownership and the shared product-before-inventory lock order. */
public interface ProductCostService {
    void lockForStockChange(Long productId);
    long lockForImport(Long productId);
    long lockForExport(Long productId);
    void updateCostPrice(Long productId, long costPrice);
}
