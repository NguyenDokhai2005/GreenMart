package vn.greenmart.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.greenmart.catalog.domain.Category;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    @Query(value = """
            SELECT EXISTS(SELECT 1 FROM category
                WHERE parent_id IS NOT DISTINCT FROM CAST(:parentId AS bigint)
                  AND greenmart_category_key(name)=greenmart_category_key(:name)
                  AND (CAST(:excludedId AS bigint) IS NULL OR id<>CAST(:excludedId AS bigint)))
            """, nativeQuery = true)
    boolean existsByNormalizedName(@Param("name") String name, @Param("parentId") Long parentId,
                                   @Param("excludedId") Long excludedId);

    boolean existsByParentId(Long parentId);
    List<Category> findByParentId(Long parentId);
}
