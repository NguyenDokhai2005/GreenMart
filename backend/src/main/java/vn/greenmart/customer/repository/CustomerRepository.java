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
        WHERE (:phone IS NOT NULL AND c.phone = :phone)
           OR (:phone IS NULL AND :q IS NOT NULL AND (
                LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%'))
                OR c.phone LIKE CONCAT('%', :q, '%')
           ))
           OR (:phone IS NULL AND :q IS NULL)
    """)
    Page<Customer> searchCustomers(@Param("q") String q, @Param("phone") String phone, Pageable pageable);
}
