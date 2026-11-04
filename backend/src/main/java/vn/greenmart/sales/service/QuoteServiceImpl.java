package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.catalog.dto.ProductSaleInfo;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.dto.*;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuoteServiceImpl implements QuoteService {

    private final ProductService productService;
    private final CustomerService customerService;
    private final InventoryService inventoryService;
    private final PromotionCalculator promotionCalculator;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Quote calculateQuote(QuoteRequest request) {
        // 1. Phân quyền: theo openapi.yaml x-roles chỉ gồm BRANCH_MANAGER và CASHIER
        SecurityUtils.requireRole("BRANCH_MANAGER", "CASHIER");

        // Kiểm tra chi nhánh được gán theo BR-11
        List<Long> accessibleBranches = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);
        if (accessibleBranches != null && !accessibleBranches.contains(request.branchId())) {
            throw new AppException(ErrorCode.BRANCH_FORBIDDEN, "Bạn không có quyền thao tác trên chi nhánh này");
        }

        // 2. Kiểm tra quyền áp dụng giảm giá thủ công ngay từ đầu (BR-19 fail-fast)
        ManualDiscount manualDiscountReq = request.manualDiscount();
        if (manualDiscountReq != null && manualDiscountReq.amount() != null && manualDiscountReq.amount() > 0) {
            String role = SecurityUtils.getCurrentRole();
            if (!"BRANCH_MANAGER".equalsIgnoreCase(role)) {
                throw new AppException(ErrorCode.MANUAL_DISCOUNT_FORBIDDEN, "Thu ngân không được quyền áp dụng giảm giá ngoài chương trình");
            }
            if (manualDiscountReq.reason() == null || manualDiscountReq.reason().trim().length() < 5) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "Lý do giảm giá ngoài chương trình phải từ 5 ký tự trở lên");
            }
        }

        // 3. Kiểm tra khách hàng nếu có
        if (request.customerId() != null && !customerService.exists(request.customerId())) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khách hàng với id: " + request.customerId());
        }

        // 4. Kiểm tra trùng lặp productId trong giỏ hàng
        Set<Long> seenProductIds = new HashSet<>();
        for (OrderLineRequest item : request.items()) {
            if (!seenProductIds.add(item.productId())) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "Danh mục sản phẩm trong giỏ bị trùng mã productId: " + item.productId());
            }
        }

        // 5. Duyệt từng dòng, tính tồn và khuyến mại cấp dòng
        List<QuoteLine> quoteLines = new ArrayList<>();
        long subtotal = 0L;
        long totalItemDiscount = 0L;
        boolean canComplete = true;

        for (OrderLineRequest itemReq : request.items()) {
            ProductSaleInfo product = productService.getSaleInfo(itemReq.productId());

            int available = inventoryService.getAvailable(request.branchId(), product.id());
            boolean sufficient = (itemReq.quantity() <= available);

            if (!sufficient || !product.active()) {
                canComplete = false;
            }

            long unitPrice = product.salePrice();
            int qty = itemReq.quantity();
            long lineRawTotal = unitPrice * qty;

            PromotionRef itemPromo = promotionCalculator.evaluateItemPromotion(
                    product.id(), product.sku(), unitPrice, qty, request.branchId()
            );
            long lineDiscount = 0L;
            if (itemPromo != null) {
                lineDiscount = Math.min(itemPromo.discount(), lineRawTotal);
            }

            long lineTotal = lineRawTotal - lineDiscount;

            subtotal += lineRawTotal;
            totalItemDiscount += lineDiscount;

            quoteLines.add(new QuoteLine(
                    product.id(),
                    product.sku(),
                    product.name(),
                    product.unit(),
                    qty,
                    unitPrice,
                    lineDiscount,
                    lineTotal,
                    itemPromo,
                    available,
                    sufficient
            ));
        }

        // 6. Tính khuyến mại cấp đơn
        long subtotalAfterLineDiscounts = subtotal - totalItemDiscount;
        PromotionRef orderPromo = promotionCalculator.evaluateOrderPromotion(subtotalAfterLineDiscounts, request.branchId());
        long orderDiscount = (orderPromo != null) ? orderPromo.discount() : 0L;

        // 7. Kiểm tra giá trị giảm giá thủ công không vượt quá số tiền phải trả
        long manualDiscountAmount = 0L;
        if (manualDiscountReq != null && manualDiscountReq.amount() != null && manualDiscountReq.amount() > 0) {
            long payableBeforeManual = subtotalAfterLineDiscounts - orderDiscount;
            if (manualDiscountReq.amount() > payableBeforeManual) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "Số tiền giảm giá thủ công không được vượt quá số tiền phải trả");
            }
            manualDiscountAmount = manualDiscountReq.amount();
        }

        // 8. Tổng kết giá trị báo giá
        long totalDiscount = totalItemDiscount + orderDiscount + manualDiscountAmount;
        long finalTotal = subtotal - totalDiscount;

        return new Quote(
                request.branchId(),
                request.customerId(),
                quoteLines,
                orderPromo,
                subtotal,
                totalItemDiscount,
                orderDiscount,
                manualDiscountAmount,
                totalDiscount,
                finalTotal,
                canComplete
        );
    }
}
