package vn.greenmart.sales.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.greenmart.sales.domain.SalesOrder;

import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
    Optional<SalesOrder> findByCode(String code);
    Optional<SalesOrder> findByChannelIdAndExternalOrderId(Long channelId, String externalOrderId);
}
