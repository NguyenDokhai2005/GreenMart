package vn.greenmart.sales.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.greenmart.sales.domain.ReturnItem;
import vn.greenmart.sales.domain.ReturnStatus;

import java.util.Collection;
import java.util.List;

public interface ReturnItemRepository extends JpaRepository<ReturnItem, Long> {

    interface ReturnedQtyProjection {
        Long getOrderItemId();
        Long getTotalQuantity();
    }

    @Query("""
        SELECT ri.orderItemId AS orderItemId, COALESCE(SUM(ri.quantity), 0) AS totalQuantity
        FROM ReturnItem ri
        JOIN ri.returnDocument rd
        WHERE rd.orderId = :orderId
          AND rd.status IN :statuses
          AND (:excludeReturnId IS NULL OR rd.id <> :excludeReturnId)
        GROUP BY ri.orderItemId
    """)
    List<ReturnedQtyProjection> sumReturnedQuantitiesByOrder(
        @Param("orderId") Long orderId,
        @Param("statuses") Collection<ReturnStatus> statuses,
        @Param("excludeReturnId") Long excludeReturnId
    );
}
