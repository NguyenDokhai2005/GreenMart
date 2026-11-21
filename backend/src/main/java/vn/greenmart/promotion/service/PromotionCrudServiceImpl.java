package vn.greenmart.promotion.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.exception.ValidationErrorDetail;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.common.pagination.PageUtils;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.promotion.domain.Promotion;
import vn.greenmart.promotion.domain.PromotionLevel;
import vn.greenmart.promotion.domain.PromotionStatus;
import vn.greenmart.promotion.dto.PromotionRequest;
import vn.greenmart.promotion.dto.PromotionResponse;
import vn.greenmart.promotion.dto.PromotionStatusRequest;
import vn.greenmart.promotion.repository.PromotionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PromotionCrudServiceImpl implements PromotionCrudService {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "code", "startAt", "endAt", "usedCount", "status"
    );
    private static final Set<String> ALLOWED_CHANNELS = Set.of("POS", "WEB", "MARKET");

    private final PromotionRepository promotionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PromotionResponse> listPromotions(
            List<String> status,
            String level,
            Instant activeAt,
            Long branchId,
            String channel,
            String q,
            Integer page,
            Integer size,
            HttpServletRequest request
    ) {
        ensureAuthenticated();

        List<PromotionStatus> normalizedStatuses = parseStatuses(status);
        PromotionLevel normalizedLevel = parseLevel(level);
        String normalizedChannel = parseChannelFilter(channel);
        String trimmedQ = (q != null && !q.isBlank()) ? q.trim() : null;

        String[] sortParams = (request != null) ? request.getParameterValues("sort") : null;
        Pageable rawPageable = PageUtils.createPageable(
                page, size, sortParams, Sort.by(Sort.Direction.DESC, "id"), ALLOWED_SORT_FIELDS
        );
        Sort nativeSort = Sort.by(rawPageable.getSort().stream()
                .map(order -> new Sort.Order(order.getDirection(), toSnakeCaseColumn(order.getProperty())))
                .toList());
        Pageable pageable = org.springframework.data.domain.PageRequest.of(
                rawPageable.getPageNumber(), rawPageable.getPageSize(), nativeSort
        );

        boolean hasStatuses = normalizedStatuses != null && !normalizedStatuses.isEmpty();
        List<String> statusNames = hasStatuses
                ? normalizedStatuses.stream().map(Enum::name).toList()
                : List.of("");

        Page<Promotion> promotionPage = promotionRepository.searchPromotions(
                hasStatuses,
                statusNames,
                normalizedLevel != null ? normalizedLevel.name() : null,
                trimmedQ,
                activeAt,
                branchId,
                normalizedChannel,
                pageable
        );

        List<PromotionResponse> dtos = promotionPage.getContent().stream()
                .map(this::toResponse)
                .toList();

        return PageResponse.of(dtos, promotionPage);
    }

    private String toSnakeCaseColumn(String property) {
        return switch (property) {
            case "startAt" -> "start_at";
            case "endAt" -> "end_at";
            case "usedCount" -> "used_count";
            default -> property;
        };
    }

    @Override
    @Transactional(readOnly = true)
    public PromotionResponse getPromotion(Long promotionId) {
        ensureAuthenticated();
        Promotion promotion = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chương trình khuyến mại", Map.of("promotionId", promotionId)));
        return toResponse(promotion);
    }

    @Override
    @Transactional
    public PromotionResponse createPromotion(PromotionRequest request) {
        SecurityUtils.requireRole("OWNER");

        String code = request.code().trim();
        if (promotionRepository.existsByCode(code)) {
            throw new AppException(ErrorCode.DUPLICATE_PROMOTION_CODE, "Mã khuyến mại đã tồn tại: " + code);
        }

        ScopeData scope = validatePromotionBusinessRules(request, null);

        Promotion promotion = new Promotion();
        promotion.setCode(code);
        promotion.setType(request.type());
        promotion.setLevel(request.level());
        promotion.setValue(request.value());
        promotion.setMaxDiscount(request.maxDiscount());
        promotion.setMinOrder(request.minOrder());
        promotion.setStartAt(request.startAt());
        promotion.setEndAt(request.endAt());
        promotion.setUsageLimit(request.usageLimit());
        promotion.setUsedCount(0);
        promotion.setStatus(PromotionStatus.DRAFT);

        Promotion saved = promotionRepository.save(promotion);
        saveScopes(saved.getId(), scope);
        saveAudit("CREATE_PROMOTION", saved.getId(), Map.of("code", saved.getCode(), "status", saved.getStatus().name()));

        return PromotionResponse.of(saved, scope.productIds(), scope.branchIds(), scope.channels());
    }

    @Override
    @Transactional
    public PromotionResponse updatePromotion(Long promotionId, PromotionRequest request) {
        SecurityUtils.requireRole("OWNER");

        Promotion promotion = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chương trình khuyến mại", Map.of("promotionId", promotionId)));

        if (promotion.getStatus() != PromotionStatus.DRAFT && promotion.getStatus() != PromotionStatus.PAUSED) {
            throw new AppException(
                    ErrorCode.INVALID_STATUS,
                    "Chỉ được sửa khuyến mại ở trạng thái DRAFT hoặc PAUSED",
                    Map.of("currentStatus", promotion.getStatus().name(), "allowed", List.of("DRAFT", "PAUSED"))
            );
        }

        String code = request.code().trim();
        if (promotionRepository.existsByCodeAndIdNot(code, promotionId)) {
            throw new AppException(ErrorCode.DUPLICATE_PROMOTION_CODE, "Mã khuyến mại đã thuộc chương trình khác: " + code);
        }

        ScopeData scope = validatePromotionBusinessRules(request, promotion.getUsedCount());

        promotion.setCode(code);
        promotion.setType(request.type());
        promotion.setLevel(request.level());
        promotion.setValue(request.value());
        promotion.setMaxDiscount(request.maxDiscount());
        promotion.setMinOrder(request.minOrder());
        promotion.setStartAt(request.startAt());
        promotion.setEndAt(request.endAt());
        promotion.setUsageLimit(request.usageLimit());

        Promotion updated = promotionRepository.save(promotion);
        saveScopes(updated.getId(), scope);
        saveAudit("UPDATE_PROMOTION", updated.getId(), Map.of("code", updated.getCode(), "status", updated.getStatus().name()));

        return PromotionResponse.of(updated, scope.productIds(), scope.branchIds(), scope.channels());
    }

    @Override
    @Transactional
    public PromotionResponse changeStatus(Long promotionId, PromotionStatusRequest request) {
        SecurityUtils.requireRole("OWNER");

        Promotion promotion = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chương trình khuyến mại", Map.of("promotionId", promotionId)));

        PromotionStatus current = promotion.getStatus();
        PromotionStatus target = request.status();

        List<PromotionStatus> allowedTargets = switch (current) {
            case DRAFT -> List.of(PromotionStatus.ACTIVE);
            case ACTIVE -> List.of(PromotionStatus.PAUSED, PromotionStatus.ENDED);
            case PAUSED -> List.of(PromotionStatus.ACTIVE, PromotionStatus.ENDED);
            case ENDED -> List.of();
        };

        if (!allowedTargets.contains(target)) {
            throw new AppException(
                    ErrorCode.INVALID_STATUS,
                    "Không thể chuyển trạng thái khuyến mại từ " + current + " sang " + target,
                    Map.of("currentStatus", current.name(), "allowed", allowedTargets.stream().map(Enum::name).toList())
            );
        }

        promotion.setStatus(target);
        Promotion updated = promotionRepository.save(promotion);
        saveAudit("CHANGE_PROMOTION_STATUS", updated.getId(), Map.of("from", current.name(), "to", target.name()));

        return toResponse(updated);
    }

    private ScopeData validatePromotionBusinessRules(PromotionRequest request, Integer currentUsedCount) {
        List<ValidationErrorDetail> errors = new ArrayList<>();

        if (request.productIds() != null && request.productIds().stream().anyMatch(java.util.Objects::isNull)) {
            errors.add(new ValidationErrorDetail("productIds", "Danh sách productIds không được chứa phần tử null"));
        }
        if (request.branchIds() != null && request.branchIds().stream().anyMatch(java.util.Objects::isNull)) {
            errors.add(new ValidationErrorDetail("branchIds", "Danh sách branchIds không được chứa phần tử null"));
        }
        if (request.channels() != null && request.channels().stream().anyMatch(c -> c == null || c.isBlank())) {
            errors.add(new ValidationErrorDetail("channels", "Danh sách channels không được chứa phần tử null hoặc rỗng"));
        }

        if (!errors.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Dữ liệu khuyến mại không hợp lệ", errors);
        }

        List<Long> productIds = request.productIds() != null
                ? new ArrayList<>(new LinkedHashSet<>(request.productIds()))
                : List.of();
        List<Long> branchIds = request.branchIds() != null
                ? new ArrayList<>(new LinkedHashSet<>(request.branchIds()))
                : List.of();
        List<String> channels = request.channels() != null
                ? request.channels().stream().map(c -> c.trim().toUpperCase()).distinct().toList()
                : List.of();

        if (request.level() == PromotionLevel.ITEM) {
            if (productIds.isEmpty()) {
                errors.add(new ValidationErrorDetail("productIds", "Khuyến mại cấp sản phẩm (ITEM) bắt buộc có ít nhất 1 sản phẩm"));
            }
            if (request.minOrder() != null) {
                errors.add(new ValidationErrorDetail("minOrder", "Khuyến mại cấp sản phẩm (ITEM) không dùng minOrder"));
            }
        } else if (request.level() == PromotionLevel.ORDER && !productIds.isEmpty()) {
            errors.add(new ValidationErrorDetail("productIds", "Khuyến mại cấp đơn (ORDER) phải để trống productIds"));
        }

        for (String ch : channels) {
            if (!ALLOWED_CHANNELS.contains(ch)) {
                errors.add(new ValidationErrorDetail("channels", "Kênh bán không hợp lệ: " + ch));
            }
        }

        if (!errors.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Dữ liệu khuyến mại không hợp lệ", errors);
        }

        for (Long productId : productIds) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product WHERE id = ?", Integer.class, productId);
            if (count == null || count == 0) {
                throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm với id: " + productId, Map.of("productId", productId));
            }
        }
        for (Long branchId : branchIds) {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM branch WHERE id = ?", Integer.class, branchId);
            if (count == null || count == 0) {
                throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chi nhánh với id: " + branchId, Map.of("branchId", branchId));
            }
        }

        return new ScopeData(productIds, branchIds, channels);
    }

    private void saveScopes(Long promotionId, ScopeData scope) {
        jdbcTemplate.update("DELETE FROM promotion_product WHERE promotion_id = ?", promotionId);
        jdbcTemplate.update("DELETE FROM promotion_branch WHERE promotion_id = ?", promotionId);
        jdbcTemplate.update("DELETE FROM promotion_channel WHERE promotion_id = ?", promotionId);

        for (Long productId : scope.productIds()) {
            jdbcTemplate.update("INSERT INTO promotion_product (promotion_id, product_id) VALUES (?, ?)", promotionId, productId);
        }
        for (Long branchId : scope.branchIds()) {
            jdbcTemplate.update("INSERT INTO promotion_branch (promotion_id, branch_id) VALUES (?, ?)", promotionId, branchId);
        }
        for (String channelCode : scope.channels()) {
            Long channelId = jdbcTemplate.queryForObject("SELECT id FROM sales_channel WHERE code = ?", Long.class, channelCode);
            jdbcTemplate.update("INSERT INTO promotion_channel (promotion_id, channel_id) VALUES (?, ?)", promotionId, channelId);
        }
    }

    private PromotionResponse toResponse(Promotion p) {
        List<Long> productIds = jdbcTemplate.queryForList(
                "SELECT product_id FROM promotion_product WHERE promotion_id = ? ORDER BY product_id ASC",
                Long.class, p.getId()
        );
        List<Long> branchIds = jdbcTemplate.queryForList(
                "SELECT branch_id FROM promotion_branch WHERE promotion_id = ? ORDER BY branch_id ASC",
                Long.class, p.getId()
        );
        List<String> channels = jdbcTemplate.queryForList(
                """
                SELECT sc.code FROM promotion_channel pc
                JOIN sales_channel sc ON sc.id = pc.channel_id
                WHERE pc.promotion_id = ?
                ORDER BY sc.id ASC
                """,
                String.class, p.getId()
        );
        return PromotionResponse.of(p, productIds, branchIds, channels);
    }

    private List<PromotionStatus> parseStatuses(List<String> rawStatuses) {
        if (rawStatuses == null || rawStatuses.isEmpty()) {
            return null;
        }
        List<PromotionStatus> result = new ArrayList<>();
        for (String raw : rawStatuses) {
            if (raw == null || raw.isBlank()) continue;
            for (String token : raw.split(",")) {
                String s = token.trim().toUpperCase();
                if (s.isEmpty()) continue;
                try {
                    result.add(PromotionStatus.valueOf(s));
                } catch (IllegalArgumentException e) {
                    throw new AppException(ErrorCode.VALIDATION_ERROR, "Trạng thái khuyến mại không hợp lệ: " + token);
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    private PromotionLevel parseLevel(String rawLevel) {
        if (rawLevel == null || rawLevel.isBlank()) {
            return null;
        }
        try {
            return PromotionLevel.valueOf(rawLevel.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Cấp khuyến mại không hợp lệ: " + rawLevel);
        }
    }

    private String parseChannelFilter(String rawChannel) {
        if (rawChannel == null || rawChannel.isBlank()) {
            return null;
        }
        String normalized = rawChannel.trim().toUpperCase();
        if (!ALLOWED_CHANNELS.contains(normalized)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Kênh bán không hợp lệ: " + rawChannel);
        }
        return normalized;
    }

    private void ensureAuthenticated() {
        if (SecurityUtils.getCurrentRole() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Chưa đăng nhập");
        }
    }

    private void saveAudit(String action, Long promotionId, Map<String, Object> detail) {
        AuditLog log = new AuditLog();
        log.setUserId(SecurityUtils.getCurrentUserId());
        log.setAction(action);
        log.setEntity("promotion");
        log.setEntityId(String.valueOf(promotionId));
        log.setDetail(detail);
        auditLogRepository.save(log);
    }

    private record ScopeData(List<Long> productIds, List<Long> branchIds, List<String> channels) {}
}
