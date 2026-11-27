import { request } from '../../api/client';
import type { components, operations } from '../../api/schema';

export type Promotion = components['schemas']['Promotion'];
export type PromotionRequest = components['schemas']['PromotionRequest'];
export type PromotionQuery = NonNullable<operations['listPromotions']['parameters']['query']>;

export function listPromotions(query: PromotionQuery, signal?: AbortSignal) {
  return request('/promotions', 'get', { query, signal });
}

export function createPromotion(body: PromotionRequest) {
  return request('/promotions', 'post', { body });
}

export function updatePromotion(promotionId: number, body: PromotionRequest) {
  return request('/promotions/{promotionId}', 'put', {
    path: { promotionId },
    body,
  });
}

export function changePromotionStatus(promotionId: number, status: 'ACTIVE' | 'PAUSED' | 'ENDED') {
  return request('/promotions/{promotionId}/status', 'post', {
    path: { promotionId },
    body: { status },
  });
}
