package vn.greenmart.sales.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.greenmart.sales.domain.OrderItem;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrderId(Long orderId);
}
