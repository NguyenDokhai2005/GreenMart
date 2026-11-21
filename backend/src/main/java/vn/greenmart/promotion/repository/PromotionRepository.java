package vn.greenmart.promotion.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.greenmart.promotion.domain.Promotion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Promotion p where p.id=:id")
    Optional<Promotion> findForUpdate(@Param("id") Long id);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    @Query(
            value = """
                SELECT p.* FROM promotion p
                WHERE (:hasStatuses = false OR p.status IN (:statuses))
                  AND (CAST(:level AS varchar) IS NULL OR p.level = CAST(:level AS varchar))
                  AND (CAST(:q AS varchar) IS NULL OR LOWER(p.code) LIKE LOWER(CONCAT('%', CAST(:q AS varchar), '%')))
                  AND (CAST(:activeAt AS timestamptz) IS NULL OR (p.start_at <= CAST(:activeAt AS timestamptz) AND p.end_at > CAST(:activeAt AS timestamptz)))
                  AND (
                      CAST(:branchId AS bigint) IS NULL
                      OR NOT EXISTS (SELECT 1 FROM promotion_branch pb WHERE pb.promotion_id = p.id)
                      OR EXISTS (SELECT 1 FROM promotion_branch pb WHERE pb.promotion_id = p.id AND pb.branch_id = CAST(:branchId AS bigint))
                  )
                  AND (
                      CAST(:channel AS varchar) IS NULL
                      OR NOT EXISTS (SELECT 1 FROM promotion_channel pc WHERE pc.promotion_id = p.id)
                      OR EXISTS (
                          SELECT 1 FROM promotion_channel pc
                          JOIN sales_channel sc ON sc.id = pc.channel_id
                          WHERE pc.promotion_id = p.id AND sc.code = CAST(:channel AS varchar)
                      )
                  )
            """,
            countQuery = """
                SELECT COUNT(*) FROM promotion p
                WHERE (:hasStatuses = false OR p.status IN (:statuses))
                  AND (CAST(:level AS varchar) IS NULL OR p.level = CAST(:level AS varchar))
                  AND (CAST(:q AS varchar) IS NULL OR LOWER(p.code) LIKE LOWER(CONCAT('%', CAST(:q AS varchar), '%')))
                  AND (CAST(:activeAt AS timestamptz) IS NULL OR (p.start_at <= CAST(:activeAt AS timestamptz) AND p.end_at > CAST(:activeAt AS timestamptz)))
                  AND (
                      CAST(:branchId AS bigint) IS NULL
                      OR NOT EXISTS (SELECT 1 FROM promotion_branch pb WHERE pb.promotion_id = p.id)
                      OR EXISTS (SELECT 1 FROM promotion_branch pb WHERE pb.promotion_id = p.id AND pb.branch_id = CAST(:branchId AS bigint))
                  )
                  AND (
                      CAST(:channel AS varchar) IS NULL
                      OR NOT EXISTS (SELECT 1 FROM promotion_channel pc WHERE pc.promotion_id = p.id)
                      OR EXISTS (
                          SELECT 1 FROM promotion_channel pc
                          JOIN sales_channel sc ON sc.id = pc.channel_id
                          WHERE pc.promotion_id = p.id AND sc.code = CAST(:channel AS varchar)
                      )
                  )
            """,
            nativeQuery = true
    )
    Page<Promotion> searchPromotions(
            @Param("hasStatuses") boolean hasStatuses,
            @Param("statuses") List<String> statuses,
            @Param("level") String level,
            @Param("q") String q,
            @Param("activeAt") Instant activeAt,
            @Param("branchId") Long branchId,
            @Param("channel") String channel,
            Pageable pageable
    );
}
