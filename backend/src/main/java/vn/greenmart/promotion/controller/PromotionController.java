package vn.greenmart.promotion.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vn.greenmart.common.pagination.PageResponse;
import vn.greenmart.promotion.dto.PromotionRequest;
import vn.greenmart.promotion.dto.PromotionResponse;
import vn.greenmart.promotion.dto.PromotionStatusRequest;
import vn.greenmart.promotion.service.PromotionCrudService;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionCrudService promotionCrudService;

    @GetMapping
    public ResponseEntity<PageResponse<PromotionResponse>> listPromotions(
            @RequestParam(name = "status", required = false) List<String> status,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "activeAt", required = false) Instant activeAt,
            @RequestParam(name = "branchId", required = false) Long branchId,
            @RequestParam(name = "channel", required = false) String channel,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(
                promotionCrudService.listPromotions(status, level, activeAt, branchId, channel, q, page, size, request)
        );
    }

    @PostMapping
    public ResponseEntity<PromotionResponse> createPromotion(@Valid @RequestBody PromotionRequest request) {
        PromotionResponse created = promotionCrudService.createPromotion(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{promotionId}")
    public ResponseEntity<PromotionResponse> getPromotion(@PathVariable("promotionId") Long promotionId) {
        return ResponseEntity.ok(promotionCrudService.getPromotion(promotionId));
    }

    @PutMapping("/{promotionId}")
    public ResponseEntity<PromotionResponse> updatePromotion(
            @PathVariable("promotionId") Long promotionId,
            @Valid @RequestBody PromotionRequest request
    ) {
        return ResponseEntity.ok(promotionCrudService.updatePromotion(promotionId, request));
    }

    @PostMapping("/{promotionId}/status")
    public ResponseEntity<PromotionResponse> changeStatus(
            @PathVariable("promotionId") Long promotionId,
            @Valid @RequestBody PromotionStatusRequest request
    ) {
        return ResponseEntity.ok(promotionCrudService.changeStatus(promotionId, request));
    }
}
