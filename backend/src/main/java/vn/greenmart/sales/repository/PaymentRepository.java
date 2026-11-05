package vn.greenmart.sales.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.greenmart.sales.domain.Payment;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByOrderId(Long orderId);
}
