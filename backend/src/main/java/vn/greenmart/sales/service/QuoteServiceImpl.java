package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.catalog.domain.Product;
import vn.greenmart.catalog.repository.ProductRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.customer.repository.CustomerRepository;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.dto.*;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuoteServiceImpl implements QuoteService {

    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final InventoryService inventoryService;
    private final PromotionCalculatorService promotionCalculatorService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Quote calculateQuote(QuoteRequest request) {
        // Phân quyền: chỉ CASHIER, BRANCH_MANAGER, OWNER, ADMIN (AUTH-02)
        SecurityUtils.requireRole("BRANCH_MANAGER", "CASHIER", "OWNER", "ADMIN");

        // Kiểm tra quyền chi nhánh theo BR-11
        List<Long> accessibleBranches = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);
        if (accessibleBranches != null && !accessibleBranches.contains(request.branchId())) {
            throw new AppException(ErrorCode.BRANCH_FORBIDDEN, "Bạn không có quyền thao tác trên chi nhánh này");
        }

        // Kiểm tra giảm giá thủ công (BR-19)
        ManualDiscount manualDiscountReq = request.manualDiscount();
        long manualDiscountAmount = 0L;
        if (manualDiscountReq != null && manualDiscountReq.amount() != null && manualDiscountReq.amount() > 0) {
            String role = SecurityUtils.getCurrentRole();
            if (!"BRANCH_MANAGER".equalsIgnoreCase(role) && !"OWNER".equalsIgnoreCase(role) && !"ADMIN".equalsIgnoreCase(role)) {
                throw new AppException(ErrorCode.MANUAL_DISCOUNT_FORBIDDEN, "Thu ngân không được quyền áp dụng giảm giá ngoài chương trình");
            }
            if (manualDiscountReq.reason() == null || manualDiscountReq.reason().trim().length() < 5) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "Lý do giảm giá ngoài chương trình phải từ 5 ký tự trở lên");
            }
            manualDiscountAmount = manualDiscountReq.amount();
        }

        // Kiểm tra khách hàng nếu có
        if (request.customerId() != null && !customerRepository.existsById(request.customerId())) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khách hàng với id: " + request.customerId());
        }

        // Kiểm tra danh sách sản phẩm trùng lặp trong request
        Set<Long> seenProductIds = new HashSet<>();
        for (OrderLineRequest item : request.items()) {
            if (!seenProductIds.add(item.productId())) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "Danh mục sản phẩm trong giỏ bị trùng mã productId: " + item.productId());
            }
        }

        // Duyệt từng dòng, tính tồn và khuyến mại dòng
        List<QuoteLine> quoteLines = new ArrayList<>();
        long subtotal = 0L;
        long totalItemDiscount = 0L;
        boolean canComplete = true;

        for (OrderLineRequest itemReq : request.items()) {
            Product product = productRepository.findById(itemReq.productId())
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm với id: " + itemReq.productId()));

            int available = inventoryService.getAvailable(request.branchId(), product.getId());
            boolean sufficient = (itemReq.quantity() <= available);

            if (!sufficient || !product.isActive()) {
                canComplete = false;
            }

            long unitPrice = product.getSalePrice();
            int qty = itemReq.quantity();
            long lineRawTotal = unitPrice * qty;

            PromotionRef itemPromo = promotionCalculatorService.evaluateItemPromotion(product, qty);
            long lineDiscount = 0L;
            if (itemPromo != null) {
                lineDiscount = Math.min(itemPromo.discount(), lineRawTotal);
            }

            long lineTotal = lineRawTotal - lineDiscount;

            subtotal += lineRawTotal;
            totalItemDiscount += lineDiscount;

            quoteLines.add(new QuoteLine(
                    product.getId(),
                    product.getSku(),
                    product.getName(),
                    product.getUnit(),
                    qty,
                    unitPrice,
                    lineDiscount,
                    lineTotal,
                    itemPromo,
                    available,
                    sufficient
            ));
        }

        // Tính khuyến mại cấp đơn
        long subtotalAfterLineDiscounts = subtotal - totalItemDiscount;
        PromotionRef orderPromo = promotionCalculatorService.evaluateOrderPromotion(subtotalAfterLineDiscounts);
        long orderDiscount = (orderPromo != null) ? orderPromo.discount() : 0L;

        // Tổng hợp chiết khấu và tổng thanh toán
        long totalDiscount = totalItemDiscount + orderDiscount + manualDiscountAmount;
        long finalTotal = Math.max(0L, subtotal - totalDiscount);

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
