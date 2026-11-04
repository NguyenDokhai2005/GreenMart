package vn.greenmart.inventory.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.greenmart.inventory.domain.StockDocument;

import java.util.Optional;

public interface StockDocumentRepository extends JpaRepository<StockDocument, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM StockDocument d WHERE d.id=:id")
    Optional<StockDocument> findForUpdate(@Param("id") Long id);
}
