import { request } from '../../api/client';
import type { components, operations } from '../../api/schema';

export type Product = components['schemas']['Product'];
export type ProductInput = components['schemas']['ProductRequest'];
export type Customer = components['schemas']['Customer'];
export type CustomerInput = components['schemas']['CustomerRequest'];
export type ProductQuery = NonNullable<operations['listProducts']['parameters']['query']>;
export type CustomerQuery = NonNullable<operations['listCustomers']['parameters']['query']>;

// Feature adapters reuse #69 transport, refresh and generated contract types.
export const catalogApi = {
  products: (query: ProductQuery, signal: AbortSignal) =>
    request('/products', 'get', { query, signal }),
  categories: (signal: AbortSignal) => request('/categories', 'get', { signal }),
  product: (productId: number, signal: AbortSignal) =>
    request('/products/{productId}', 'get', { path: { productId }, signal }),
  saveProduct: (id: number | undefined, body: ProductInput, signal: AbortSignal) =>
    id === undefined
      ? request('/products', 'post', { body, signal })
      : request('/products/{productId}', 'put', { path: { productId: id }, body, signal }),
  customers: (query: CustomerQuery, signal: AbortSignal) =>
    request('/customers', 'get', { query, signal }),
  saveCustomer: (id: number | undefined, body: CustomerInput, signal: AbortSignal) =>
    id === undefined
      ? request('/customers', 'post', { body, signal })
      : request('/customers/{customerId}', 'put', { path: { customerId: id }, body, signal }),
};
