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
import vn.greenmart.inventory.domain.ExportReason;
import vn.greenmart.inventory.exception.InsufficientStockException;
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
        branch(request.branchId()); // Return NOT_FOUND before a foreign-key failure.
        StockDocument document;
        if (request.type() == StockDocumentType.IMPORT) {
            requireImportFields(request.toBranchId(), request.exportReason());
            requireSupplier(request.supplier());
            document = StockDocument.importDraft(request.branchId(), actor, request.supplier().trim(),
                    request.note(), importedItems(request.items()));
        } else if (request.type() == StockDocumentType.EXPORT) {
            requireExportFields(request.toBranchId(), request.supplier());
            var reason = exportReason(request.exportReason(), request.note());
            document = StockDocument.exportDraft(request.branchId(), actor, reason, request.note(),
                    exportedItems(request.branchId(), request.items()));
        } else {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Hiện chỉ hỗ trợ IMPORT và EXPORT");
        }
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
        String note = request.note() == null ? document.getReason() : request.note();
        if (document.getType() == StockDocumentType.IMPORT) {
            requireImportFields(request.toBranchId(), request.exportReason());
            String supplier = request.supplier() == null ? document.getSupplier() : request.supplier().trim();
            requireSupplier(supplier);
            document.reviseImport(supplier, note, importedItems(request.items()));
        } else {
            requireExportFields(request.toBranchId(), request.supplier());
            String reason = request.exportReason() == null ? document.getExportReason().name() : request.exportReason();
            document.reviseExport(exportReason(reason, note), note, exportedItems(document.getBranchId(), request.items()));
        }
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
        if (document.getType() != StockDocumentType.IMPORT && document.getType() != StockDocumentType.EXPORT) {
            invalid("Hiện chỉ hỗ trợ IMPORT và EXPORT");
        }
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
        boolean isImport = document.getType() == StockDocumentType.IMPORT;
        if (lines.isEmpty()) invalid("Phiếu kho phải có ít nhất một dòng");
        if (lines.stream().anyMatch(line -> line.quantity() == null || line.quantity() <= 0
                || (isImport && (line.unitCost() == null || line.unitCost() < 0)))) {
            invalid("Phiếu kho thiếu số lượng hoặc đơn giá hợp lệ");
        }
        Long branchId = document.getBranchId();
        var exportCosts = new java.util.HashMap<Long, Long>();
        long exportTotal = 0;
        for (var line : lines) {
            if (isImport) {
                inventoryService.importStock(branchId, line.productId(), line.quantity(), line.unitCost(), id, actor);
            } else {
                long cost = inventoryService.exportStock(branchId, line.productId(), line.quantity(), id, actor);
                try { exportTotal = Math.addExact(exportTotal, Math.multiplyExact((long) line.quantity(), cost)); }
                catch (ArithmeticException ex) { invalid("Tổng giá trị hủy vượt giới hạn số tiền"); }
                exportCosts.put(line.productId(), cost);
            }
        }
        // Row lock is retained by the transaction even after EntityManager.clear().
        document = repository.findById(id).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!isImport) document.getItems().forEach(item -> item.recordExportCost(exportCosts.get(item.getProductId())));
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

    private void requireSupplier(String supplier) {
        if (supplier == null || supplier.isBlank()) invalid("Nhà cung cấp không được để trống");
    }

    private void requireExportFields(Long toBranchId, String supplier) {
        if (toBranchId != null || supplier != null) invalid("Phiếu xuất hủy không có chi nhánh đích hoặc nhà cung cấp");
    }

    private ExportReason exportReason(String value, String note) {
        ExportReason reason;
        try { reason = ExportReason.valueOf(value == null ? "" : value); }
        catch (IllegalArgumentException ex) { throw new AppException(ErrorCode.VALIDATION_ERROR, "Lý do xuất hủy không hợp lệ"); }
        if (reason == ExportReason.OTHER && (note == null || note.isBlank())) invalid("Lý do OTHER cần ghi chú");
        return reason;
    }

    private List<StockDocumentItem> exportedItems(Long branchId, List<StockDocumentRequests.Item> items) {
        var ids = items.stream().map(StockDocumentRequests.Item::productId).toList();
        if (ids.stream().distinct().count() != ids.size()) invalid("Mỗi sản phẩm chỉ được có một dòng");
        var refs = products(ids);
        Map<Long, Integer> available = namedJdbc.query("""
                SELECT product_id,on_hand-reserved AS available FROM inventory
                WHERE branch_id=:branch AND product_id IN (:ids)
                """, Map.of("branch", branchId, "ids", ids), (rs, n) -> Map.entry(rs.getLong("product_id"), rs.getInt("available")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        for (var item : items) {
            if (item.unitCost() != null || item.countedQuantity() != null) invalid("Phiếu xuất hủy không nhận giá nhập hoặc số kiểm kê");
            int remaining = available.getOrDefault(item.productId(), 0);
            if (item.quantity() > remaining) {
                throw new InsufficientStockException(branchId, item.productId(), refs.get(item.productId()).sku(), item.quantity(), remaining);
            }
        }
        return items.stream().map(item -> StockDocumentItem.exported(item.productId(), item.quantity())).toList();
    }

    private List<StockDocumentItem> importedItems(List<StockDocumentRequests.Item> items) {
        var ids = items.stream().map(StockDocumentRequests.Item::productId).toList();
        if (ids.stream().distinct().count() != ids.size()) invalid("Mỗi sản phẩm chỉ được có một dòng");
        products(ids); // Validate every product before replacing the draft's previous lines.
        long total = 0;
        for (var item : items) {
            if (item.unitCost() == null) invalid("Phiếu nhập bắt buộc có đơn giá");
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
        Long totalCost = null;
        if (SecurityUtils.canViewCostPrice()) {
            if (document.getType() == StockDocumentType.IMPORT) {
                totalCost = items.stream().mapToLong(item -> Math.multiplyExact((long) item.quantity(), item.unitCost())).reduce(0L, Math::addExact);
            } else if (document.getItems().stream().allMatch(item -> item.getExportCostPrice() != null)) {
                totalCost = document.getItems().stream().mapToLong(item -> Math.multiplyExact((long) item.getQuantity(), item.getExportCostPrice()))
                        .reduce(0L, Math::addExact);
            }
        }
        return new StockDocumentResponse(document.getId(), document.getCode(), document.getType(), branch(document.getBranchId()),
                null, document.getStatus(), items.size(), false, user(document.getCreatedBy()), document.getCreatedAt(),
                document.getApprovedAt(), document.getReceivedAt(), document.getSupplier(),
                document.getExportReason() == null ? null : document.getExportReason().name(), document.getReason(),
                user(document.getApprovedBy()), user(document.getReceivedBy()), totalCost, items);
    }

    private void invalid(String message) { throw new AppException(ErrorCode.VALIDATION_ERROR, message); }
}
