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

    @Query(
        value = """
            SELECT p.* FROM product p
            WHERE (:hasCategoryIds = false OR p.category_id IN (:categoryIds))
              AND (CAST(:active AS boolean) IS NULL OR p.is_active = CAST(:active AS boolean))
              AND (
                  CAST(:q AS text) IS NULL
                  OR btrim(CAST(:q AS text)) = ''
                  OR strpos(
                      regexp_replace(translate(lower(normalize(p.name, NFD)), 'đ', 'd'), U&'[\\0300-\\036f]', '', 'g'),
                      NULLIF(regexp_replace(translate(lower(normalize(btrim(CAST(:q AS text)), NFD)), 'đ', 'd'), U&'[\\0300-\\036f]', '', 'g'), '')
                  ) > 0
                  OR strpos(lower(p.sku), lower(btrim(CAST(:q AS text)))) > 0
                  OR strpos(lower(COALESCE(p.barcode, '')), lower(btrim(CAST(:q AS text)))) > 0
              )
        """,
        countQuery = """
            SELECT COUNT(*) FROM product p
            WHERE (:hasCategoryIds = false OR p.category_id IN (:categoryIds))
              AND (CAST(:active AS boolean) IS NULL OR p.is_active = CAST(:active AS boolean))
              AND (
                  CAST(:q AS text) IS NULL
                  OR btrim(CAST(:q AS text)) = ''
                  OR strpos(
                      regexp_replace(translate(lower(normalize(p.name, NFD)), 'đ', 'd'), U&'[\\0300-\\036f]', '', 'g'),
                      NULLIF(regexp_replace(translate(lower(normalize(btrim(CAST(:q AS text)), NFD)), 'đ', 'd'), U&'[\\0300-\\036f]', '', 'g'), '')
                  ) > 0
                  OR strpos(lower(p.sku), lower(btrim(CAST(:q AS text)))) > 0
                  OR strpos(lower(COALESCE(p.barcode, '')), lower(btrim(CAST(:q AS text)))) > 0
              )
        """,
        nativeQuery = true
    )
    Page<Product> searchProducts(
        @Param("hasCategoryIds") boolean hasCategoryIds,
        @Param("categoryIds") Collection<Long> categoryIds,
        @Param("active") Boolean active,
        @Param("q") String q,
        Pageable pageable
    );
}
