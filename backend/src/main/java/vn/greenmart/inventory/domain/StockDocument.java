package vn.greenmart.inventory.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vn.greenmart.common.exception.AppException;
import vn.greenmart.common.exception.ErrorCode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "stock_document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 30)
    private String code;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private StockDocumentType type;
    @Column(name = "branch_id", nullable = false)
    private Long branchId;
    @Column(name = "to_branch_id")
    private Long toBranchId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 15)
    private StockDocumentStatus status;
    @Column(length = 120)
    private String supplier;
    @Column(length = 255)
    private String reason;
    @Enumerated(EnumType.STRING) @Column(name = "export_reason", length = 10)
    private ExportReason exportReason;
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
    @Column(name = "approved_by")
    private Long approvedBy;
    @Column(name = "received_by")
    private Long receivedBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;
    @Column(name = "received_at")
    private OffsetDateTime receivedAt;
    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<StockDocumentItem> items = new ArrayList<>();

    public static StockDocument importDraft(Long branchId, Long actor, String supplier,
            String note, List<StockDocumentItem> items) {
        var document = new StockDocument();
        document.code = "PN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        document.type = StockDocumentType.IMPORT;
        document.branchId = branchId;
        document.status = StockDocumentStatus.DRAFT;
        document.createdBy = actor;
        document.createdAt = OffsetDateTime.now();
        document.reviseImport(supplier, note, items);
        return document;
    }

    public void reviseImport(String supplier, String note, List<StockDocumentItem> replacements) {
        requireDraft();
        this.supplier = supplier;
        this.reason = note;
        items.clear();
        replacements.forEach(item -> { item.attachTo(this); items.add(item); });
    }

    public static StockDocument exportDraft(Long branchId, Long actor, ExportReason reason,
            String note, List<StockDocumentItem> items) {
        var document = new StockDocument();
        document.code = "PX-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        document.type = StockDocumentType.EXPORT;
        document.branchId = branchId;
        document.status = StockDocumentStatus.DRAFT;
        document.createdBy = actor;
        document.createdAt = OffsetDateTime.now();
        document.reviseExport(reason, note, items);
        return document;
    }

    public void reviseExport(ExportReason exportReason, String note, List<StockDocumentItem> replacements) {
        requireDraft();
        this.exportReason = Objects.requireNonNull(exportReason);
        this.reason = note;
        items.clear();
        replacements.forEach(item -> { item.attachTo(this); items.add(item); });
    }

    public void cancel() {
        requireDraft();
        status = StockDocumentStatus.CANCELLED;
    }

    public static StockDocument transferDraft(Long branchId, Long toBranchId, Long actor,
            String note, List<StockDocumentItem> items) {
        var document = new StockDocument();
        document.code = "PC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        document.type = StockDocumentType.TRANSFER;
        document.branchId = branchId;
        document.status = StockDocumentStatus.DRAFT;
        document.createdBy = actor;
        document.createdAt = OffsetDateTime.now();
        document.reviseTransfer(toBranchId, note, items);
        return document;
    }

    public void reviseTransfer(Long destination, String note, List<StockDocumentItem> replacements) {
        requireDraft();
        if (destination == null || Objects.equals(branchId, destination)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "Chi nhánh đích phải khác chi nhánh nguồn");
        }
        toBranchId = destination;
        reason = note;
        items.clear();
        replacements.forEach(item -> { item.attachTo(this); items.add(item); });
    }

    public void requireReceivable() {
        if (type != StockDocumentType.TRANSFER || status != StockDocumentStatus.IN_TRANSIT) {
            throw new AppException(ErrorCode.INVALID_STATUS, "Chỉ nhận được phiếu chuyển đang vận chuyển");
        }
    }

    public void receive(Long actor, Map<Long, Integer> quantities) {
        requireReceivable();
        items.forEach(item -> item.recordReceivedQuantity(quantities.get(item.getId())));
        status = StockDocumentStatus.RECEIVED;
        receivedBy = actor;
        receivedAt = OffsetDateTime.now();
    }

    public void requireDraft() {
        if (status != StockDocumentStatus.DRAFT) {
            throw new AppException(ErrorCode.INVALID_STATUS, "Chỉ thao tác được với phiếu nháp",
                    Map.of("currentStatus", status, "allowed", List.of(StockDocumentStatus.DRAFT)));
        }
    }

    /** Checked before stock writes and again when recording the approval. */
    public void requireApprovableBy(Long actor) {
        requireDraft();
        if (actor == null) throw new AppException(ErrorCode.UNAUTHORIZED);
        if (Objects.equals(createdBy, actor)) throw new AppException(ErrorCode.SELF_APPROVAL_FORBIDDEN);
    }

    public void approve(Long actor) {
        requireApprovableBy(actor);
        status = type == StockDocumentType.TRANSFER ? StockDocumentStatus.IN_TRANSIT : StockDocumentStatus.APPROVED;
        approvedBy = actor;
        approvedAt = OffsetDateTime.now();
    }
}
