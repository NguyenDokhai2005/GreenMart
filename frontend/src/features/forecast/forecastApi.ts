import { request } from '../../api/client';
import type { components, operations } from '../../api/schema';

export type ForecastResponse = components['schemas']['ForecastResponse'];
export type ForecastQuery = NonNullable<operations['getForecast']['parameters']['query']>;

export function getForecast(query: ForecastQuery, signal?: AbortSignal) {
  return request('/forecasts', 'get', { query, signal });
}
