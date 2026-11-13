package vn.greenmart.integration.webhook;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.branch.dto.BranchSaleInfo;
import vn.greenmart.branch.service.BranchService;
import vn.greenmart.catalog.service.ProductService;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.customer.service.CustomerService;
import vn.greenmart.integration.channel.ChannelCatalog;
import vn.greenmart.integration.outbox.OutboxService;
import vn.greenmart.inventory.service.ChannelStockAvailability;
import vn.greenmart.inventory.service.InventoryService;
import vn.greenmart.sales.service.ChannelOrderStore;

@Service
@RequiredArgsConstructor
public class ChannelOrderIntake {
    private final ChannelCatalog channels;
    private final ChannelOrderStore orders;
    private final CustomerService customers;
    private final ProductService products;
    private final BranchService branches;
    private final InventoryService inventory;
    private final ChannelStockAvailability stock;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;

    public record Ack(long orderId, String code, String externalOrderId, String status,
                      BranchSaleInfo branch, OffsetDateTime reservedUntil, String rejectReason) {}
    public record Result(Ack ack, boolean duplicate, ErrorCode error, Map<String, Object> details) {}

    @Transactional
    public Result receive(String channel, ChannelOrderRequest request) {
        var config = channels.findChannel(channel);
        var previous = orders.lockAndFind(config.id(), request.externalOrderId());
        if (previous.isPresent()) {
            var old = previous.get();
            String reason = "REJECTED".equals(old.status()) ? jdbc.query("""
                    SELECT payload->>'reason' FROM outbox_event
                    WHERE aggregate_type='order' AND aggregate_id=? AND event_type='order.rejected'
                    ORDER BY id DESC LIMIT 1
                    """, (rs, row) -> rs.getString(1), Long.toString(old.id())).stream().findFirst().orElse(null) : null;
            return new Result(ack(old, reason), true, null, Map.of());
        }
        if (config.branchIds().isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Kênh chưa có chi nhánh phục vụ");
        }
        Long preferred = config.defaultBranchId();
        long fallback = preferred != null && config.branchIds().contains(preferred)
                ? preferred : config.branchIds().getFirst();
        var unmapped = new ArrayList<String>();
        var lines = new ArrayList<ChannelOrderStore.Line>();
        var quantities = new TreeMap<Long, Integer>();
        long total = 0;
        for (var item : request.items()) {
            try {
                var mapping = channels.findMappedProduct(config.id(), item.externalSku());
                if (mapping.isEmpty()) {
                    unmapped.add(item.externalSku());
                    continue;
                }
                long productId = mapping.get();
                var product = products.getSaleInfo(productId);
                if (!product.active()) {
                    unmapped.add(item.externalSku());
                    continue;
                }
                long amount = Math.multiplyExact(item.unitPrice(), item.quantity().longValue());
                total = Math.addExact(total, amount);
                lines.add(new ChannelOrderStore.Line(productId, product.sku(), item.quantity(), item.unitPrice(), amount));
                quantities.merge(productId, item.quantity(), Integer::sum);
            } catch (ArithmeticException ex) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "Tổng tiền đơn vượt giới hạn");
            }
        }
        long customer = customers.resolveChannelCustomer(request.customer().phone(), request.customer().name());
        if (!unmapped.isEmpty()) {
            return reject(config.id(), channel, request, fallback, customer, ErrorCode.UNMAPPED_SKU,
                    "UNMAPPED_SKU", "externalSkus", unmapped.stream().distinct().toList());
        }

        var balances = stock.lockAvailable(config.branchIds(), quantities.keySet());
        var eligible = config.branchIds().stream().filter(id -> quantities.entrySet().stream()
                .allMatch(q -> available(balances, id, q.getKey()) >= q.getValue())).toList();
        if (eligible.isEmpty()) {
            var best = config.branchIds().stream().max(Comparator.comparingLong(id ->
                    quantities.keySet().stream().mapToLong(p -> available(balances, id, p)).sum())).orElseThrow();
            var shortages = lines.stream().filter(line ->
                            available(balances, best, line.productId()) < quantities.get(line.productId()))
                    .map(line -> Map.of("productId", line.productId(), "sku", line.sku(),
                            "requested", quantities.get(line.productId()),
                            "available", available(balances, best, line.productId())))
                    .distinct().toList();
            return reject(config.id(), channel, request, fallback, customer, ErrorCode.INSUFFICIENT_STOCK,
                    "OUT_OF_STOCK", "lines", shortages);
        }
        long branch = preferred != null && eligible.contains(preferred) ? preferred : eligible.stream().max(Comparator.comparingLong(id ->
                quantities.keySet().stream().mapToLong(p -> available(balances, id, p)).sum())).orElseThrow();
        boolean cod = request.paymentMethod() == ChannelOrderRequest.PaymentMethod.COD;
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String status = cod ? "CONFIRMED" : "RESERVED";
        var order = orders.insert(config.id(), channel, request.externalOrderId(), branch, customer, status,
                cod ? null : now.plusMinutes(15), request.orderedAt().toInstant(), total, lines);
        quantities.forEach((product, qty) -> inventory.reserve(branch, product, qty, order.id()));
        orderEvent(order, now, null);
        return new Result(ack(order, null), false, null, Map.of());
    }

    private Result reject(long channelId, String channel, ChannelOrderRequest request, long branch, long customer,
                          ErrorCode error, String reason, String detailKey, Object details) {
        var rejected = orders.insert(channelId, channel, request.externalOrderId(), branch, customer, "REJECTED",
                null, request.orderedAt().toInstant(), 0, List.of());
        orderEvent(rejected, OffsetDateTime.now(ZoneOffset.UTC), reason);
        return new Result(ack(rejected, reason), false, error,
                Map.of("orderId", rejected.id(), "status", "REJECTED", detailKey, details));
    }

    private void orderEvent(ChannelOrderStore.Stored order, OffsetDateTime occurredAt, String reason) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("orderId", order.id());
        payload.put("status", order.status());
        payload.put("occurredAt", occurredAt.toString());
        if (reason != null) payload.put("reason", reason);
        outbox.enqueue("order", Long.toString(order.id()),
                "order." + order.status().toLowerCase(java.util.Locale.ROOT), payload);
    }

    private Ack ack(ChannelOrderStore.Stored order, String reason) {
        return new Ack(order.id(), order.code(), order.externalOrderId(), order.status(),
                "REJECTED".equals(order.status()) ? null : branches.getSaleInfo(order.branchId()),
                order.reservedUntil(), reason);
    }

    private int available(Map<Long, Map<Long, Integer>> balances, long branch, long product) {
        return balances.getOrDefault(branch, Map.of()).getOrDefault(product, 0);
    }
}
