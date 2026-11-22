package vn.greenmart.sales.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class ChannelPaymentReferences {
    private final JdbcTemplate jdbc;

    @Transactional(propagation=Propagation.MANDATORY)
    public void claim(long orderId, String providerRef) {
        if (providerRef == null || providerRef.isBlank() || providerRef.length() > 60) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "PAID bắt buộc có providerRef từ 1 đến 60 ký tự");
        }
        String scope = jdbc.queryForObject("""
                SELECT c.payment_provider_scope FROM sales_order o JOIN sales_channel c ON c.id=o.channel_id WHERE o.id=?
                """, String.class, orderId);
        jdbc.update("""
                INSERT INTO channel_payment_reference(provider_scope,provider_ref,order_id) VALUES (?,?,?)
                ON CONFLICT DO NOTHING
                """, scope, providerRef, orderId);
        var owners = jdbc.queryForList("""
                SELECT order_id FROM channel_payment_reference WHERE provider_scope=? AND provider_ref=?
                """, Long.class, scope, providerRef);
        if (owners.isEmpty() || owners.getFirst() != orderId) {
            throw new AppException(ErrorCode.CONFLICT, "Mã thanh toán đã gắn với đơn khác hoặc đơn đã có mã thanh toán khác");
        }
    }
}
