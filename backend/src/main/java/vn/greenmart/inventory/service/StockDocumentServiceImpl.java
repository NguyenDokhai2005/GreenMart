package vn.greenmart.inventory.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.greenmart.common.audit.AuditLog;
import vn.greenmart.common.audit.AuditLogRepository;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;
import vn.greenmart.common.security.SecurityUtils;
import vn.greenmart.inventory.domain.StockDocument;
import vn.greenmart.inventory.domain.StockDocumentItem;
import vn.greenmart.inventory.domain.StockDocumentType;
import vn.greenmart.inventory.dto.StockDocumentRequests;
import vn.greenmart.inventory.dto.StockDocumentResponse;
import vn.greenmart.inventory.repository.StockDocumentRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockDocumentServiceImpl implements StockDocumentService {
    private final StockDocumentRepository repository;
    private final AuditLogRepository audit;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final InventoryService inventoryService;

    @Override @Transactional
    public StockDocumentResponse create(StockDocumentRequests.Create request) {
        SecurityUtils.requireRole("STOREKEEPER", "BRANCH_MANAGER");
        Long actor = requireActor();
        requireBranch(request.branchId());
        if (request.type() != StockDocumentType.IMPORT) invalid("Hiện chỉ hỗ trợ phiếu nhập IMPORT");
        requireImportFields(request.toBranchId(), request.exportReason());
        branch(request.branchId()); // Return NOT_FOUND before a foreign-key failure.
        var document = StockDocument.importDraft(request.branchId(), actor, request.supplier().trim(),
                request.note(), importedItems(request.items()));
        return response(repository.saveAndFlush(document));
    }

    @Override @Transactional(readOnly = true)
    public StockDocumentResponse get(Long id) {
        SecurityUtils.requireRole("OWNER", "BRANCH_MANAGER", "STOREKEEPER");
        var document = load(id, false);
        return response(document);
    }

    @Override @Transactional
    public StockDocumentResponse update(Long id, StockDocumentRequests.Update request) {
        SecurityUtils.requireRole("STOREKEEPER", "BRANCH_MANAGER");
        requireActor();
        var document = load(id, true);
        document.requireDraft();
        requireImportFields(request.toBranchId(), request.exportReason());
        String supplier = request.supplier() == null ? document.getSupplier() : request.supplier().trim();
        if (supplier == null || supplier.isBlank()) invalid("Nhà cung cấp không được để trống");
        document.reviseImport(supplier, request.note() == null ? document.getReason() : request.note(), importedItems(request.items()));
        repository.flush();
        return response(document);
    }

    @Override @Transactional
    public StockDocumentResponse cancel(Long id, StockDocumentRequests.Cancel request) {
        SecurityUtils.requireRole("STOREKEEPER", "BRANCH_MANAGER");
        Long actor = requireActor();
        var document = load(id, true);
        if (request.reason().trim().length() < 3) invalid("Lý do hủy phải có ít nhất 3 ký tự");
        document.cancel();
        var entry = new AuditLog();
        entry.setUserId(actor);
        entry.setAction("CANCEL_STOCK_DOCUMENT");
        entry.setEntity("stock_document");
        entry.setEntityId(id.toString());
        entry.setDetail(Map.of("reason", request.reason().trim()));
        audit.save(entry);
        repository.flush();
        return response(document);
    }

    private StockDocument load(Long id, boolean lock) {
        var document = (lock ? repository.findForUpdate(id) : repository.findById(id))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy phiếu kho"));
        requireBranch(document.getBranchId());
        if (document.getType() != StockDocumentType.IMPORT) invalid("Hiện chỉ hỗ trợ phiếu nhập IMPORT");
        return document;
    }

    @Override @Transactional
    public StockDocumentResponse approve(Long id) {
        SecurityUtils.requireRole("STOREKEEPER", "BRANCH_MANAGER");
        Long actor = requireActor();
        var document = load(id, true);
        document.requireApprovableBy(actor);
        SecurityUtils.requireRole("BRANCH_MANAGER");
        // Copy immutable inputs before inventory bulk/native writes clear the persistence context.
        var lines = document.getItems().stream()
                .map(item -> new StockDocumentRequests.Item(item.getProductId(), item.getQuantity(), item.getUnitCost(), null))
                .sorted(java.util.Comparator.comparing(StockDocumentRequests.Item::productId)).toList();
        if (lines.isEmpty()) invalid("Phiếu nhập phải có ít nhất một dòng");
        if (lines.stream().anyMatch(line -> line.unitCost() == null || line.unitCost() < 0
                || line.quantity() == null || line.quantity() <= 0)) {
            invalid("Phiếu nhập thiếu số lượng hoặc đơn giá hợp lệ");
        }
        Long branchId = document.getBranchId();
        for (var line : lines) {
            inventoryService.importStock(branchId, line.productId(), line.quantity(), line.unitCost(), id, actor);
        }
        // Row lock is retained by the transaction even after EntityManager.clear().
        document = repository.findById(id).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        document.approve(actor);
        var entry = new AuditLog();
        entry.setUserId(actor);
        entry.setAction("APPROVE_STOCK_DOCUMENT");
        entry.setEntity("stock_document");
        entry.setEntityId(id.toString());
        entry.setDetail(Map.of("type", document.getType().name(), "status", document.getStatus().name()));
        audit.save(entry);
        repository.flush();
        return response(document);
    }

    private Long requireActor() {
        Long id = SecurityUtils.getCurrentUserId();
        if (id == null || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app_user WHERE id=? AND is_active)", Boolean.class, id))) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return id;
    }

    private void requireBranch(Long branchId) {
        var allowed = SecurityUtils.getAccessibleBranchIds(jdbc);
        if (allowed != null && !allowed.contains(branchId)) throw new AppException(ErrorCode.BRANCH_FORBIDDEN);
    }

    private void requireImportFields(Long toBranchId, String exportReason) {
        if (toBranchId != null || exportReason != null) invalid("Phiếu nhập không có chi nhánh đích hoặc lý do xuất hủy");
    }

    private List<StockDocumentItem> importedItems(List<StockDocumentRequests.Item> items) {
        var ids = items.stream().map(StockDocumentRequests.Item::productId).toList();
        if (ids.stream().distinct().count() != ids.size()) invalid("Mỗi sản phẩm chỉ được có một dòng");
        products(ids); // Validate every product before replacing the draft's previous lines.
        long total = 0;
        for (var item : items) {
            if (item.countedQuantity() != null) invalid("Phiếu nhập không có số lượng kiểm kê");
            try { total = Math.addExact(total, Math.multiplyExact((long) item.quantity(), item.unitCost())); }
            catch (ArithmeticException ex) { invalid("Tổng giá trị phiếu vượt giới hạn số tiền"); }
        }
        return items.stream().map(item -> StockDocumentItem.imported(item.productId(), item.quantity(), item.unitCost())).toList();
    }

    private record ProductRef(Long id, String sku, String name, String unit) {}

    private Map<Long, ProductRef> products(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        var rows = namedJdbc.query("SELECT id,sku,name,unit FROM product WHERE id IN (:ids)", Map.of("ids", ids),
                (rs, n) -> new ProductRef(rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getString("unit")));
        if (rows.size() != ids.size()) throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm trong phiếu");
        return rows.stream().collect(Collectors.toMap(ProductRef::id, Function.identity()));
    }

    private StockDocumentResponse.BranchRef branch(Long id) {
        return jdbc.query("SELECT id,code,name FROM branch WHERE id=?",
                (rs, n) -> new StockDocumentResponse.BranchRef(rs.getLong("id"), rs.getString("code"), rs.getString("name")), id)
                .stream().findFirst().orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chi nhánh"));
    }

    private StockDocumentResponse.UserRef user(Long id) {
        if (id == null) return null;
        return jdbc.queryForObject("SELECT id,full_name FROM app_user WHERE id=?",
                (rs, n) -> new StockDocumentResponse.UserRef(rs.getLong("id"), rs.getString("full_name")), id);
    }

    private StockDocumentResponse response(StockDocument document) {
        var productRefs = products(document.getItems().stream().map(StockDocumentItem::getProductId).toList());
        var items = document.getItems().stream().map(item -> {
            var product = productRefs.get(item.getProductId());
            return new StockDocumentResponse.Item(item.getId(), item.getProductId(), product.sku(), product.name(), product.unit(),
                    item.getQuantity(), item.getUnitCost(), item.getReceivedQuantity(), item.getCountedQuantity(), null);
        }).toList();
        Long totalCost = SecurityUtils.canViewCostPrice()
                ? items.stream().mapToLong(item -> Math.multiplyExact((long) item.quantity(), item.unitCost())).reduce(0L, Math::addExact) : null;
        return new StockDocumentResponse(document.getId(), document.getCode(), document.getType(), branch(document.getBranchId()),
                null, document.getStatus(), items.size(), false, user(document.getCreatedBy()), document.getCreatedAt(),
                document.getApprovedAt(), document.getReceivedAt(), document.getSupplier(), null, document.getReason(),
                user(document.getApprovedBy()), user(document.getReceivedBy()), totalCost, items);
    }

    private void invalid(String message) { throw new AppException(ErrorCode.VALIDATION_ERROR, message); }
}
