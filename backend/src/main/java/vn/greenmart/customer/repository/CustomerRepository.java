package vn.greenmart.customer.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.greenmart.customer.domain.Customer;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByPhone(String phone);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);

    @Query("""
        SELECT c FROM Customer c
        WHERE c.isActive = :active
          AND (:phone IS NULL OR c.phone = :phone)
          AND (CAST(:q AS string) IS NULL OR (
                LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                OR c.phone LIKE CONCAT('%', CAST(:q AS string), '%')
          ))
    """)
    Page<Customer> searchCustomers(
        @Param("q") String q,
        @Param("phone") String phone,
        @Param("active") boolean active,
        Pageable pageable
    );
}
