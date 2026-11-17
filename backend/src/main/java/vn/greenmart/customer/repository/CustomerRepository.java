package vn.greenmart.customer.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.greenmart.customer.domain.Customer;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByPhone(String phone);

    @Query("""
        SELECT c FROM Customer c
        WHERE (:phone IS NULL OR c.phone = :phone)
          AND (CAST(:q AS string) IS NULL OR (
                LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR c.phone LIKE CONCAT('%', CAST(:q AS string), '%')
          ))
    """)
    Page<Customer> searchCustomers(@Param("q") String q, @Param("phone") String phone, Pageable pageable);
}
