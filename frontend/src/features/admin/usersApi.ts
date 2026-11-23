import { request } from '../../api/client';
import type { components, operations } from '../../api/schema';

export type User = components['schemas']['User'];
export type UserPage = components['schemas']['UserPage'];
export type UserCreate = components['schemas']['UserCreateRequest'];
export type UserUpdate = components['schemas']['UserUpdateRequest'];
export type UserQuery = NonNullable<operations['listUsers']['parameters']['query']>;

export const listUsers = (query: UserQuery, signal?: AbortSignal) =>
  request('/users', 'get', { query, signal });
export const getUser = (userId: number) => request('/users/{userId}', 'get', { path: { userId } });
export const createUser = (body: UserCreate) => request('/users', 'post', { body });
export const updateUser = (userId: number, body: UserUpdate) =>
  request('/users/{userId}', 'put', { path: { userId }, body });
export const unlockUser = (userId: number) =>
  request('/users/{userId}/unlock', 'post', { path: { userId } });
export const resetUserPassword = (userId: number, newPassword: string) =>
  request('/users/{userId}/reset-password', 'post', { path: { userId }, body: { newPassword } });
