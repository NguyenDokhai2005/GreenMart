package vn.greenmart.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.greenmart.catalog.domain.Category;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    boolean existsByParentId(Long parentId);
    List<Category> findByParentId(Long parentId);
}
