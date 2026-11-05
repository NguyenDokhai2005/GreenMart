package vn.greenmart.integration.channel;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;

@Service
@Transactional(readOnly = true)
public class ChannelService implements ChannelCatalog {
    private final JdbcTemplate jdbc;
    public ChannelService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public ChannelDetails findChannel(String code) {
        validateCode(code);
        var rows = jdbc.query("SELECT id, code, default_branch_id, safety_stock FROM sales_channel WHERE code=?",
                (rs, n) -> new ChannelDetails(rs.getLong("id"), rs.getString("code"),
                        rs.getObject("default_branch_id", Long.class), rs.getInt("safety_stock"), List.of()), code);
        if (rows.isEmpty()) throw new AppException(ErrorCode.NOT_FOUND, "Kênh bán không tồn tại");
        var channel = rows.getFirst();
        var branches = jdbc.queryForList("SELECT branch_id FROM channel_branch WHERE channel_id=? ORDER BY branch_id",
                Long.class, channel.id());
        return new ChannelDetails(channel.id(), channel.code(), channel.defaultBranchId(), channel.safetyStock(), branches);
    }

    @Override public long resolveProductId(String channelCode, String externalSku) {
        var channel = findChannel(channelCode);
        validateSku(externalSku);
        var ids = jdbc.queryForList("SELECT product_id FROM channel_product WHERE channel_id=? AND external_sku=?",
                Long.class, channel.id(), externalSku);
        if (ids.isEmpty()) throw new AppException(ErrorCode.UNMAPPED_SKU,
                "Mã hàng của kênh chưa được ánh xạ", Map.of("externalSkus", List.of(externalSku)));
        return ids.getFirst();
    }

    /** Existing PK and UNIQUE constraints arbitrate concurrent mapping changes. */
    @Transactional
    public void mapProduct(String channelCode, long productId, String externalSku) {
        SecurityUtils.requireRole("ADMIN");
        validateSku(externalSku);
        if (productId <= 0) throw new AppException(ErrorCode.VALIDATION_ERROR);
        long channelId = findChannel(channelCode).id();
        try {
            jdbc.update("""
                    INSERT INTO channel_product(channel_id, product_id, external_sku) VALUES (?, ?, ?)
                    ON CONFLICT(channel_id, product_id) DO UPDATE SET external_sku=excluded.external_sku
                    """, channelId, productId, externalSku);
        } catch (DataIntegrityViolationException ex) {
            throw new AppException(ErrorCode.CONFLICT, "SKU đã được ánh xạ hoặc sản phẩm không tồn tại");
        }
    }

    @Transactional
    public void configure(String code, Long defaultBranchId, int safetyStock, List<Long> branchIds) {
        SecurityUtils.requireRole("ADMIN");
        if (safetyStock < 0 || branchIds == null || branchIds.isEmpty()
                || branchIds.stream().anyMatch(id -> id == null || id <= 0)
                || branchIds.stream().distinct().count() != branchIds.size()
                || defaultBranchId == null || !branchIds.contains(defaultBranchId)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Chi nhánh mặc định phải thuộc phạm vi phục vụ kênh");
        }
        long id = findChannel(code).id();
        jdbc.queryForObject("SELECT id FROM sales_channel WHERE id=? FOR UPDATE", Long.class, id);
        try {
            jdbc.update("UPDATE sales_channel SET default_branch_id=?, safety_stock=? WHERE id=?", defaultBranchId, safetyStock, id);
            jdbc.update("DELETE FROM channel_branch WHERE channel_id=?", id);
            for (long branch : branchIds) jdbc.update("INSERT INTO channel_branch(channel_id,branch_id) VALUES (?,?)", id, branch);
        } catch (DataIntegrityViolationException ex) {
            throw new AppException(ErrorCode.CONFLICT, "Chi nhánh không tồn tại");
        }
    }

    private void validateCode(String code) {
        if (!List.of("WEB", "MARKET").contains(code == null ? "" : code))
            throw new AppException(ErrorCode.NOT_FOUND, "Kênh tích hợp không tồn tại");
    }
    private void validateSku(String sku) {
        if (sku == null || sku.isBlank() || sku.length() > 60) throw new AppException(ErrorCode.VALIDATION_ERROR);
    }
}
