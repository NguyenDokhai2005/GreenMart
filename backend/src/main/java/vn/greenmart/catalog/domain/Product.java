package vn.greenmart.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "product")
@Getter
@Setter
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String sku;

    @Column(length = 40)
    private String barcode;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String unit;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "sale_price", nullable = false)
    private Long salePrice;

    @Column(name = "cost_price", nullable = false)
    private Long costPrice;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
