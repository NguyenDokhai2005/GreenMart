package vn.greenmart.catalog.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.greenmart.catalog.domain.Product;

import java.util.Collection;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    boolean existsBySku(String sku);
    boolean existsBySkuAndIdNot(String sku, Long id);

    boolean existsByBarcode(String barcode);
    boolean existsByBarcodeAndIdNot(String barcode, Long id);

    boolean existsByCategoryId(Long categoryId);

    Optional<Product> findByBarcode(String barcode);
    Optional<Product> findBySku(String sku);

    @Query("""
        SELECT p FROM Product p
        WHERE (:categoryIds IS NULL OR p.categoryId IN :categoryIds)
          AND (:active IS NULL OR p.active = :active)
          AND (:q IS NULL OR :q = '' OR (
              LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(COALESCE(p.barcode, '')) LIKE LOWER(CONCAT('%', :q, '%'))
          ))
    """)
    Page<Product> searchProducts(
        @Param("categoryIds") Collection<Long> categoryIds,
        @Param("active") Boolean active,
        @Param("q") String q,
        Pageable pageable
    );
}
