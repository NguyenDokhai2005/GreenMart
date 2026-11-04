import { describe, expect, it } from 'vitest';
import { ApiError } from '../../../src/api/client';
import {
  formatLockedUntil,
  getLoginFieldErrors,
  getLoginNotice,
  getLockedUntil,
  getRemainingAttempts,
  parseLockedUntil,
  validateLoginRequest,
} from '../../../src/features/auth/loginForm';

describe('validateLoginRequest', () => {
  it('requires both contract fields without adding password rules', () => {
    expect(validateLoginRequest('', '')).toEqual({
      username: 'Vui lòng nhập tên đăng nhập.',
      password: 'Vui lòng nhập mật khẩu.',
    });
    expect(validateLoginRequest('a', 'x')).toEqual({});
  });

  it('enforces only LoginRequest maximum lengths', () => {
    expect(validateLoginRequest('u'.repeat(60), 'p'.repeat(100))).toEqual({});
    expect(validateLoginRequest('u'.repeat(61), 'p'.repeat(101))).toEqual({
      username: 'Tên đăng nhập không được vượt quá 60 ký tự.',
      password: 'Mật khẩu không được vượt quá 100 ký tự.',
    });
  });
});

describe('login error details', () => {
  it('shows remaining attempts only when a valid value is present', () => {
    const provided = new ApiError('Sai thông tin', 401, 'INVALID_CREDENTIALS', undefined, {
      remainingAttempts: 4,
    });
    const missing = new ApiError('Sai thông tin', 401, 'INVALID_CREDENTIALS');
    const malformed = new ApiError('Sai thông tin', 401, 'INVALID_CREDENTIALS', undefined, {
      remainingAttempts: '4',
    });

    expect(getRemainingAttempts(provided)).toBe(4);
    expect(getRemainingAttempts(missing)).toBeUndefined();
    expect(getRemainingAttempts(malformed)).toBeUndefined();
    expect(getLoginNotice(missing).description).toBe('Sai tên đăng nhập hoặc mật khẩu.');
  });

  it('keeps invalid-credential feedback generic and never echoes the username', () => {
    const error = new ApiError('Unknown account: secret-name', 401, 'INVALID_CREDENTIALS');
    expect(getLoginNotice(error)).toEqual({
      title: 'Đăng nhập không thành công',
      description: 'Sai tên đăng nhập hoặc mật khẩu.',
    });
  });

  it('parses and formats a server supplied lock deadline', () => {
    const locked = new ApiError('Locked', 401, 'ACCOUNT_LOCKED', undefined, {
      lockedUntil: '2026-11-05T09:45:00+07:00',
    });

    expect(getLockedUntil(locked)).toBe(Date.parse('2026-11-05T02:45:00Z'));
    expect(getLoginNotice(locked).description).toContain('05/11/2026');
    expect(formatLockedUntil(Date.parse('2026-11-05T02:45:00Z'))).toContain('09:45');
  });

  it('rejects absent or malformed lock timestamps', () => {
    expect(parseLockedUntil(undefined)).toBeUndefined();
    expect(parseLockedUntil('not-a-date')).toBeUndefined();
    expect(parseLockedUntil('2026-11-05T09:45:00')).toBeUndefined();
    expect(parseLockedUntil('2026-02-30T09:45:00+07:00')).toBeUndefined();
  });

  it('uses existing API error categories for connection, timeout and server failures', () => {
    expect(getLoginNotice(new TypeError('offline')).title).toBe('Không thể kết nối');
    expect(getLoginNotice(new ApiError('Gateway timeout', 504)).title).toBe('Hết thời gian chờ');
    expect(getLoginNotice(new ApiError('Service unavailable', 503, undefined, 'trace-1'))).toEqual({
      title: 'Lỗi hệ thống',
      description: 'Service unavailable Mã tham chiếu: trace-1.',
    });
  });

  it('maps only username and password fields from contract validation details', () => {
    const error = new ApiError('Invalid request', 400, 'VALIDATION_ERROR', undefined, [
      { field: 'username', message: 'Tên đăng nhập không hợp lệ.' },
      { field: 'accessToken', message: 'Ignore unrelated field.' },
    ]);

    expect(getLoginFieldErrors(error)).toEqual({ username: 'Tên đăng nhập không hợp lệ.' });
  });
});
