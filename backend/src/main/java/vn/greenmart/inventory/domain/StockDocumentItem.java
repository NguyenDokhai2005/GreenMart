package vn.greenmart.inventory.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stock_document_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockDocumentItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private StockDocument document;
    @Column(name = "product_id", nullable = false)
    private Long productId;
    @Column(nullable = false)
    private int quantity;
    @Column(name = "unit_cost")
    private Long unitCost;
    @Column(name = "received_quantity")
    private Integer receivedQuantity;
    @Column(name = "counted_quantity")
    private Integer countedQuantity;

    public static StockDocumentItem imported(Long productId, int quantity, long unitCost) {
        var item = new StockDocumentItem();
        item.productId = productId;
        item.quantity = quantity;
        item.unitCost = unitCost;
        return item;
    }

    void attachTo(StockDocument document) { this.document = document; }
}
