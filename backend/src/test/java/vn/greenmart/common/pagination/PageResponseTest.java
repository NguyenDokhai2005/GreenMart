package vn.greenmart.common.pagination;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageResponseTest {

    @Test
    void testFromSpringPage_MapsCorrectMetadata() {
        List<String> items = List.of("SP01", "SP02", "SP03");
        Page<String> springPage = new PageImpl<>(items, PageRequest.of(0, 20), 100);

        PageResponse<String> response = PageResponse.from(springPage);

        assertEquals(0, response.page());
        assertEquals(20, response.size());
        assertEquals(100L, response.totalElements());
        assertEquals(5, response.totalPages());
        assertEquals(3, response.content().size());
        assertEquals("SP01", response.content().get(0));
    }

    @Test
    void testOf_MapsMappedContentAndOriginalMetadata() {
        List<Integer> entities = List.of(1, 2);
        Page<Integer> originalPage = new PageImpl<>(entities, PageRequest.of(1, 10), 25);

        List<String> dtos = List.of("ID-1", "ID-2");
        PageResponse<String> response = PageResponse.of(dtos, originalPage);

        assertEquals(1, response.page());
        assertEquals(10, response.size());
        assertEquals(25L, response.totalElements());
        assertEquals(3, response.totalPages());
        assertEquals(List.of("ID-1", "ID-2"), response.content());
    }
}
