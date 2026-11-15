package vn.greenmart.sales.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.greenmart.branch.service.BranchService;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ValidationErrorDetail;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.integration.channel.ChannelCatalog;
import vn.greenmart.integration.outbox.OutboxService;
import vn.greenmart.integration.webhook.ChannelOrderIntake;
import vn.greenmart.inventory.exception.InsufficientStockException;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.domain.OrderItem;
import vn.greenmart.sales.domain.OrderStatus;
import vn.greenmart.sales.domain.Payment;
import vn.greenmart.sales.domain.PaymentMethod;
import vn.greenmart.sales.domain.PaymentStatus;
import vn.greenmart.sales.domain.SalesOrder;
import vn.greenmart.sales.dto.CancelOrderRequest;
import vn.greenmart.sales.dto.CancelReason;
import vn.greenmart.sales.dto.ChannelOrderEventRequest;
import vn.greenmart.sales.dto.OrderResponse;
import vn.greenmart.sales.repository.OrderItemRepository;
import vn.greenmart.sales.repository.PaymentRepository;
import vn.greenmart.sales.repository.SalesOrderRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineOrderServiceImpl implements OnlineOrderService {

    private final SalesOrderRepository salesOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final InventoryService inventoryService;
    private final PromotionService promotionService;
    private final OutboxService outboxService;
    private final AuditLogRepository auditLogRepository;
    private final OrderResponseMapper orderResponseMapper;
    private final ChannelCatalog channelCatalog;
    private final ChannelOrderStore channelOrderStore;
    private final BranchService branchService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse completeOrder(Long orderId) {
        // 1. Phân quyền vai trò & chi nhánh (openapi.yaml: BRANCH_MANAGER, STOREKEEPER)
        SecurityUtils.requireRole("BRANCH_MANAGER", "STOREKEEPER");

        // Khóa dòng đơn hàng trước khi đọc trạng thái để tránh race condition
        SalesOrder order = lockOrderForUpdate(orderId);

        List<Long> accessibleBranches = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);
        if (accessibleBranches != null && !accessibleBranches.contains(order.getBranchId())) {
            throw new AppException(ErrorCode.BRANCH_FORBIDDEN, "Bạn không có quyền thao tác trên chi nhánh này");
        }

        // Chặn đơn kênh POS 
        String channelCode = jdbcTemplate.queryForObject(
                "SELECT code FROM sales_channel WHERE id = ?", String.class, order.getChannelId()
        );
        if ("POS".equalsIgnoreCase(channelCode) || order.getStatus() != OrderStatus.CONFIRMED) {
            throw new AppException(
                    ErrorCode.INVALID_STATUS,
                    "Chỉ hoàn tất được đơn hàng online ở trạng thái CONFIRMED",
                    Map.of("currentStatus", order.getStatus().name(), "allowed", List.of("CONFIRMED"))
            );
        }

        Long actorId = SecurityUtils.getCurrentUserId();
        Instant now = Instant.now();

        // Chốt giảm tồn thực và giữ chỗ từng dòng theo productId ASC (tránh deadlock, BR-01)
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<OrderItem> sortedItems = items.stream()
                .sorted(Comparator.comparing(OrderItem::getProductId))
                .toList();

        for (OrderItem item : sortedItems) {
            inventoryService.commitSale(order.getBranchId(), item.getProductId(), item.getQuantity(), order.getId(), actorId);
        }

        // Cập nhật đơn -> COMPLETED và xác nhận thanh toán COD nếu đang PENDING
        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(now);
        order = salesOrderRepository.save(order);

        List<Payment> payments = paymentRepository.findByOrderId(order.getId());
        if (payments.isEmpty() && order.getTotal() > 0) {
            Payment codPayment = new Payment();
            codPayment.setOrderId(order.getId());
            codPayment.setMethod(PaymentMethod.CASH);
            codPayment.setAmount(order.getTotal());
            codPayment.setStatus(PaymentStatus.PAID);
            codPayment.setPaidAt(now);
            payments = List.of(paymentRepository.save(codPayment));
        } else {
            for (Payment p : payments) {
                if (p.getStatus() == PaymentStatus.PENDING) {
                    p.setStatus(PaymentStatus.PAID);
                    p.setPaidAt(now);
                    paymentRepository.save(p);
                }
            }
        }

        // Ghi outbox order.completed -> ChannelOrderStatusPublisher tự báo về kênh
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.getId());
        payload.put("code", order.getCode());
        payload.put("branchId", order.getBranchId());
        payload.put("status", OrderStatus.COMPLETED.name());
        payload.put("total", order.getTotal());
        payload.put("completedAt", now.toString());
        payload.put("occurredAt", now.toString());
        outboxService.enqueue("order", order.getId().toString(), "order.completed", payload);

        return enrichStatusMetadata(order, orderResponseMapper.toOrderResponseFromEntities(order, items, payments));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderResponse cancelOrder(Long orderId, CancelOrderRequest request) {
        // Phân quyền vai trò & chi nhánh (openapi.yaml: BRANCH_MANAGER)
        SecurityUtils.requireRole("BRANCH_MANAGER");

        if (request == null || request.reason() == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Lý do hủy đơn không được để trống");
        }
        if (request.reason() == CancelReason.OTHER && (request.note() == null || request.note().trim().isEmpty())) {
            throw new AppException(
                    ErrorCode.VALIDATION_ERROR,
                    "Bắt buộc nhập ghi chú khi chọn lý do OTHER",
                    List.of(new ValidationErrorDetail("note", "Bắt buộc nhập ghi chú khi lý do là OTHER"))
            );
        }

        SalesOrder order = lockOrderForUpdate(orderId);

        List<Long> accessibleBranches = SecurityUtils.getAccessibleBranchIds(jdbcTemplate);
        if (accessibleBranches != null && !accessibleBranches.contains(order.getBranchId())) {
            throw new AppException(ErrorCode.BRANCH_FORBIDDEN, "Bạn không có quyền thao tác trên chi nhánh này");
        }

        // Chỉ hủy được đơn online ở trạng thái RESERVED hoặc CONFIRMED (không hủy đơn POS)
        String channelCode = jdbcTemplate.queryForObject(
                "SELECT code FROM sales_channel WHERE id = ?", String.class, order.getChannelId()
        );
        if ("POS".equalsIgnoreCase(channelCode)
                || (order.getStatus() != OrderStatus.RESERVED && order.getStatus() != OrderStatus.CONFIRMED)) {
            throw new AppException(
                    ErrorCode.INVALID_STATUS,
                    "Chỉ hủy được đơn online đang giữ hàng (RESERVED) hoặc đã xác nhận (CONFIRMED)",
                    Map.of("currentStatus", order.getStatus().name(), "allowed", List.of("RESERVED", "CONFIRMED"))
            );
        }

        Long actorId = SecurityUtils.getCurrentUserId();
        String trimmedNote = (request.note() != null && !request.note().trim().isEmpty()) ? request.note().trim() : null;

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<Payment> payments = cancelAndReleaseInternal(
                order, items, actorId, request.reason().name(), trimmedNote, true
        );

        return enrichStatusMetadata(order, orderResponseMapper.toOrderResponseFromEntities(order, items, payments));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelOrderIntake.Ack handleChannelEvent(
            String channelCode,
            String externalOrderId,
            ChannelOrderEventRequest event
    ) {
        var config = channelCatalog.findChannel(channelCode);
        var locked = channelOrderStore.lockAndFind(config.id(), externalOrderId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng của kênh"));

        // Khóa dòng sales_order (FOR UPDATE) để loại trừ hoàn toàn với job hết hạn (FOR UPDATE SKIP LOCKED)
        SalesOrder order = lockOrderForUpdate(locked.id());

        Instant occurredAt = event.occurredAt().toInstant();

        if (event.type() == ChannelOrderEventRequest.EventType.PAID) {
            return handlePaidEvent(channelCode, order, occurredAt, event.providerRef());
        } else if (event.type() == ChannelOrderEventRequest.EventType.CANCELLED) {
            return handleChannelCancelledEvent(channelCode, order, occurredAt);
        }

        throw new AppException(ErrorCode.VALIDATION_ERROR, "Loại sự kiện không hợp lệ");
    }

    private ChannelOrderIntake.Ack handlePaidEvent(
            String channelCode,
            SalesOrder order,
            Instant occurredAt,
            String providerRef
    ) {
        // Idempotent: đơn đã CONFIRMED hoặc COMPLETED -> trả 200
        if (order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.COMPLETED) {
            return buildChannelAck(order, null);
        }

        if (order.getStatus() == OrderStatus.RESERVED) {
            order.setStatus(OrderStatus.CONFIRMED);
            order.setReservedUntil(null);
            salesOrderRepository.save(order);

            recordPaidPayment(order, occurredAt, providerRef);
            enqueueOrderLifecycleEvent(order.getId(), OrderStatus.CONFIRMED.name(), occurredAt, null);
            return buildChannelAck(order, null);
        }

        // Ngoại lệ BA E2 / EX-06: Thanh toán đến muộn sau khi đơn đã EXPIRED
        if (order.getStatus() == OrderStatus.EXPIRED) {
            List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
            List<OrderItem> sortedItems = items.stream()
                    .sorted(Comparator.comparing(OrderItem::getProductId))
                    .toList();

            boolean reReserved = tryReReserveInNewTx(order.getBranchId(), order.getId(), sortedItems);
            if (reReserved) {
                order.setStatus(OrderStatus.CONFIRMED);
                order.setReservedUntil(null);
                salesOrderRepository.save(order);

                recordPaidPayment(order, occurredAt, providerRef);
                enqueueOrderLifecycleEvent(order.getId(), OrderStatus.CONFIRMED.name(), occurredAt, null);
                return buildChannelAck(order, null);
            } else {
                // Hết hàng khi giữ chỗ lại -> CANCELLED và hoàn tiền giả lập (REFUNDED)
                order.setStatus(OrderStatus.CANCELLED);
                order.setReservedUntil(null);
                salesOrderRepository.save(order);

                Payment refundPayment = new Payment();
                refundPayment.setOrderId(order.getId());
                refundPayment.setMethod(PaymentMethod.QR);
                refundPayment.setAmount(order.getTotal());
                refundPayment.setStatus(PaymentStatus.REFUNDED);
                refundPayment.setProviderRef(providerRef);
                refundPayment.setPaidAt(occurredAt);
                paymentRepository.save(refundPayment);

                writeAuditLog(null, "CANCEL_ORDER", order.getId(), Map.of(
                        "reason", "OUT_OF_STOCK",
                        "source", "LATE_PAID_AFTER_EXPIRED"
                ));
                enqueueOrderLifecycleEvent(order.getId(), OrderStatus.CANCELLED.name(), occurredAt, "OUT_OF_STOCK");
                return buildChannelAck(order, "OUT_OF_STOCK");
            }
        }

        throw new AppException(
                ErrorCode.INVALID_STATUS,
                "Không thể ghi nhận thanh toán cho đơn ở trạng thái " + order.getStatus().name(),
                Map.of("currentStatus", order.getStatus().name(), "allowed", List.of("RESERVED", "EXPIRED"))
        );
    }

    private boolean tryReReserveInNewTx(Long branchId, Long orderId, List<OrderItem> sortedItems) {
        TransactionTemplate newTx = new TransactionTemplate(transactionManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            newTx.executeWithoutResult(status -> {
                for (OrderItem item : sortedItems) {
                    inventoryService.reserve(branchId, item.getProductId(), item.getQuantity(), orderId);
                }
            });
            return true;
        } catch (InsufficientStockException ex) {
            log.info("Giữ chỗ lại thất bại cho đơn quá hạn orderId={}: thiếu tồn productId={}", orderId, ex.getProductId());
            return false;
        }
    }

    private ChannelOrderIntake.Ack handleChannelCancelledEvent(
            String channelCode,
            SalesOrder order,
            Instant occurredAt
    ) {
        // Idempotent: đơn đã CANCELLED -> trả 200
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return buildChannelAck(order, "CHANNEL_CANCELLED");
        }

        if (order.getStatus() != OrderStatus.RESERVED && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new AppException(
                    ErrorCode.INVALID_STATUS,
                    "Không thể hủy đơn ở trạng thái " + order.getStatus().name(),
                    Map.of("currentStatus", order.getStatus().name(), "allowed", List.of("RESERVED", "CONFIRMED"))
            );
        }

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        cancelAndReleaseInternal(order, items, null, "CHANNEL_CANCELLED", null, true);
        return buildChannelAck(order, "CHANNEL_CANCELLED");
    }

    private List<Payment> cancelAndReleaseInternal(
            SalesOrder order,
            List<OrderItem> items,
            Long actorId,
            String reason,
            String note,
            boolean writeAudit
    ) {
        // Nhả toàn bộ giữ chỗ theo productId ASC
        List<OrderItem> sortedItems = items.stream()
                .sorted(Comparator.comparing(OrderItem::getProductId))
                .toList();
        for (OrderItem item : sortedItems) {
            inventoryService.release(order.getBranchId(), item.getProductId(), item.getQuantity(), order.getId(), actorId);
        }

        // Cập nhật trạng thái đơn -> CANCELLED
        order.setStatus(OrderStatus.CANCELLED);
        order.setReservedUntil(null);
        salesOrderRepository.save(order);

        // Hoàn tiền giả lập nếu đã thanh toán (PAID -> REFUNDED)
        List<Payment> payments = paymentRepository.findByOrderId(order.getId());
        for (Payment p : payments) {
            if (p.getStatus() == PaymentStatus.PAID) {
                p.setStatus(PaymentStatus.REFUNDED);
                paymentRepository.save(p);
            } else if (p.getStatus() == PaymentStatus.PENDING) {
                p.setStatus(PaymentStatus.FAILED);
                paymentRepository.save(p);
            }
        }

        // Hoàn lượt khuyến mại (BR-04)
        promotionService.releaseUsage(order.getPromotionId());
        for (OrderItem item : items) {
            promotionService.releaseUsage(item.getPromotionId());
        }

        // Ghi audit_log (AUTH-03)
        if (writeAudit) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", reason);
            if (note != null) {
                detail.put("note", note);
            }
            writeAuditLog(actorId, "CANCEL_ORDER", order.getId(), detail);
        }

        // Phát outbox order.cancelled
        enqueueOrderLifecycleEvent(order.getId(), OrderStatus.CANCELLED.name(), Instant.now(), reason);

        return payments;
    }

    @Override
    public int expireOverdueOrders() {
        List<Long> overdueIds = jdbcTemplate.queryForList("""
                SELECT id FROM sales_order
                WHERE status = 'RESERVED' AND reserved_until IS NOT NULL AND reserved_until <= now()
                ORDER BY reserved_until ASC
                LIMIT 50
                """, Long.class);

        int expiredCount = 0;
        for (Long id : overdueIds) {
            if (expireSingleOrderInNewTx(id)) {
                expiredCount++;
            }
        }
        return expiredCount;
    }

    private boolean expireSingleOrderInNewTx(Long orderId) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Boolean.TRUE.equals(requiresNew.execute(status -> {
            List<Long> locked = jdbcTemplate.queryForList("""
                    SELECT id FROM sales_order
                    WHERE id = ? AND status = 'RESERVED' AND reserved_until <= now()
                    FOR UPDATE SKIP LOCKED
                    """, Long.class, orderId);
            if (locked.isEmpty()) {
                return false;
            }

            SalesOrder order = salesOrderRepository.findById(orderId).orElse(null);
            if (order == null || order.getStatus() != OrderStatus.RESERVED) {
                return false;
            }

            List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
            List<OrderItem> sortedItems = items.stream()
                    .sorted(Comparator.comparing(OrderItem::getProductId))
                    .toList();
            for (OrderItem item : sortedItems) {
                inventoryService.release(order.getBranchId(), item.getProductId(), item.getQuantity(), order.getId());
            }

            order.setStatus(OrderStatus.EXPIRED);
            salesOrderRepository.save(order);

            // Hoàn lượt khuyến mại (BR-04)
            promotionService.releaseUsage(order.getPromotionId());
            for (OrderItem item : items) {
                promotionService.releaseUsage(item.getPromotionId());
            }

            enqueueOrderLifecycleEvent(order.getId(), OrderStatus.EXPIRED.name(), Instant.now(), null);
            log.info("Đã hết hạn đơn giữ chỗ orderId={} code={}", order.getId(), order.getCode());
            return true;
        }));
    }

    private void recordPaidPayment(SalesOrder order, Instant paidAt, String providerRef) {
        List<Payment> existing = paymentRepository.findByOrderId(order.getId());
        if (!existing.isEmpty()) {
            for (Payment p : existing) {
                if (p.getStatus() != PaymentStatus.PAID) {
                    p.setStatus(PaymentStatus.PAID);
                    p.setProviderRef(providerRef != null ? providerRef : p.getProviderRef());
                    p.setPaidAt(paidAt);
                    paymentRepository.save(p);
                }
            }
            return;
        }
        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setMethod(PaymentMethod.QR);
        payment.setAmount(order.getTotal());
        payment.setStatus(PaymentStatus.PAID);
        payment.setProviderRef(providerRef);
        payment.setPaidAt(paidAt);
        paymentRepository.save(payment);
    }

    private void enqueueOrderLifecycleEvent(Long orderId, String status, Instant occurredAt, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId);
        payload.put("status", status);
        payload.put("occurredAt", occurredAt.toString());
        if (reason != null && !reason.isBlank()) {
            payload.put("reason", reason);
        }
        outboxService.enqueue("order", orderId.toString(), "order." + status.toLowerCase(), payload);
    }

    private void writeAuditLog(Long userId, String action, Long orderId, Map<String, Object> detail) {
        AuditLog logEntry = new AuditLog();
        logEntry.setUserId(userId);
        logEntry.setAction(action);
        logEntry.setEntity("order");
        logEntry.setEntityId(orderId.toString());
        logEntry.setDetail(detail);
        auditLogRepository.save(logEntry);
    }

    private ChannelOrderIntake.Ack buildChannelAck(SalesOrder order, String rejectReason) {
        OffsetDateTime reservedUntilOffset = order.getReservedUntil() != null
                ? order.getReservedUntil().atOffset(ZoneOffset.UTC)
                : null;
        return new ChannelOrderIntake.Ack(
                order.getId(),
                order.getCode(),
                order.getExternalOrderId(),
                order.getStatus().name(),
                order.getStatus() == OrderStatus.REJECTED ? null : branchService.getSaleInfo(order.getBranchId()),
                reservedUntilOffset,
                rejectReason
        );
    }

    private OrderResponse enrichStatusMetadata(SalesOrder order, OrderResponse base) {
        if (order.getStatus() != OrderStatus.CANCELLED && order.getStatus() != OrderStatus.REJECTED) {
            return base;
        }
        String statusReason = null;
        String statusNote = null;

        List<String> details = jdbcTemplate.query(
                "SELECT detail::text FROM audit_log WHERE entity = 'order' AND entity_id = ? AND action = 'CANCEL_ORDER' ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1),
                order.getId().toString()
        );
        if (!details.isEmpty() && details.get(0) != null) {
            try {
                Map<String, Object> map = objectMapper.readValue(details.get(0), new TypeReference<>() {});
                Object r = map.get("reason");
                Object n = map.get("note");
                if (r != null) statusReason = r.toString();
                if (n != null) statusNote = n.toString();
            } catch (Exception e) {
                log.warn("Không đọc được chi tiết audit_log cho orderId={}: {}", order.getId(), e.getMessage());
            }
        }

        return new OrderResponse(
                base.id(), base.code(), base.channel(), base.externalOrderId(),
                base.branch(), base.customer(), base.status(), base.itemCount(), base.total(),
                base.createdAt(), base.completedAt(), base.createdBy(), base.items(),
                base.orderPromotion(), base.manualDiscountReason(), base.subtotal(),
                base.itemDiscount(), base.orderDiscount(), base.manualDiscount(), base.discount(),
                base.payments(), base.reservedUntil(), statusReason, statusNote
        );
    }

    private SalesOrder lockOrderForUpdate(Long orderId) {
        List<Long> lockedIds = jdbcTemplate.queryForList(
                "SELECT id FROM sales_order WHERE id = ? FOR UPDATE",
                Long.class,
                orderId
        );
        if (lockedIds.isEmpty()) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng");
        }
        return salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng"));
    }
}
