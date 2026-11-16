package vn.greenmart.promotion.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.greenmart.promotion.domain.Promotion;
import vn.greenmart.promotion.domain.PromotionLevel;
import vn.greenmart.promotion.domain.PromotionStatus;

import java.time.Instant;
import java.util.List;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    @Query("""
        SELECT p FROM Promotion p
        WHERE (:statuses IS NULL OR p.status IN :statuses)
          AND (:level IS NULL OR p.level = :level)
          AND (CAST(:q AS string) IS NULL OR LOWER(p.code) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
          AND (CAST(:activeAt AS timestamp) IS NULL OR (p.startAt <= :activeAt AND p.endAt >= :activeAt))
    """)
    Page<Promotion> searchPromotions(
            @Param("statuses") List<PromotionStatus> statuses,
            @Param("level") PromotionLevel level,
            @Param("q") String q,
            @Param("activeAt") Instant activeAt,
            Pageable pageable
    );
}
