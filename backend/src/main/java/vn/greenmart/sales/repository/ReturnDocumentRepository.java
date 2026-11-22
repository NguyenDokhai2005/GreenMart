package vn.greenmart.sales.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.greenmart.sales.domain.ReturnDocument;

import java.util.List;
import java.util.Optional;

public interface ReturnDocumentRepository extends JpaRepository<ReturnDocument, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReturnDocument r WHERE r.id = :id")
    Optional<ReturnDocument> findByIdForUpdate(@Param("id") Long id);

    List<ReturnDocument> findByOrderIdOrderByCreatedAtDesc(Long orderId);
}
