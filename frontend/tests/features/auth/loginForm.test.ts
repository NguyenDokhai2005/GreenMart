import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../src/api/client';
import {
  clearLegacyLoginLockDeadline,
  formatLockedUntil,
  getAccountLockout,
  getLoginControlState,
  getLoginFieldErrors,
  getLoginNotice,
  getVisibleLoginNotice,
  getLockedUntil,
  getRemainingAttempts,
  isLoginLockedForUsername,
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
    const lockedUntil = Date.now() + 60_000;
    const locked = new ApiError('Locked', 401, 'ACCOUNT_LOCKED', undefined, {
      lockedUntil: new Date(lockedUntil).toISOString(),
    });

    expect(getLockedUntil(locked)).toBe(lockedUntil);
    expect(getLoginNotice(locked).description).toContain(formatLockedUntil(lockedUntil));
  });

  it('blocks only the username associated with a future server lockout', () => {
    const now = Date.now();
    const lockedUntil = now + 60_000;
    const error = new ApiError('Locked', 401, 'ACCOUNT_LOCKED', undefined, {
      lockedUntil: new Date(lockedUntil).toISOString(),
    });
    const lockout = getAccountLockout(error, 'cashier.a', now);
    const lockouts = lockout ? [lockout] : [];

    expect(lockout).toEqual({ username: 'cashier.a', lockedUntil });
    expect(isLoginLockedForUsername('cashier.a', lockouts, now)).toBe(true);
    expect(getLoginControlState('cashier.a', false, lockouts, now)).toEqual({
      isLocked: true,
      usernameDisabled: false,
      passwordDisabled: true,
      submitDisabled: true,
    });
    expect(getLoginControlState('cashier.a', true, lockouts, now)).toEqual({
      isLocked: true,
      usernameDisabled: true,
      passwordDisabled: true,
      submitDisabled: true,
    });
    expect(getLoginControlState('cashier.b', false, lockouts, now)).toEqual({
      isLocked: false,
      usernameDisabled: false,
      passwordDisabled: false,
      submitDisabled: false,
    });
    const accountANotice = {
      username: 'cashier.a',
      value: { title: 'Tài khoản tạm khóa', description: 'A bị khóa.' },
    };
    expect(getVisibleLoginNotice('cashier.a', accountANotice, lockouts, now)).toEqual(
      accountANotice.value,
    );
    expect(getVisibleLoginNotice('cashier.b', accountANotice, lockouts, now)).toBeNull();
  });

  it('releases the frontend lock at the backend deadline', () => {
    const lockout = { username: 'cashier.a', lockedUntil: 2_000 };
    const notice = {
      username: 'cashier.a',
      value: {
        title: 'Tài khoản tạm khóa',
        description: 'Đã khóa.',
        lockedUntil: lockout.lockedUntil,
      },
    };

    expect(getLoginControlState('cashier.a', false, [lockout], 1_999).submitDisabled).toBe(true);
    expect(isLoginLockedForUsername('cashier.a', [lockout], 2_000)).toBe(false);
    expect(getLoginControlState('cashier.a', false, [lockout], 2_000)).toEqual({
      isLocked: false,
      usernameDisabled: false,
      passwordDisabled: false,
      submitDisabled: false,
    });
    expect(getVisibleLoginNotice('cashier.a', notice, [lockout], 2_000)).toBeNull();
  });

  it('shows a fallback for an invalid deadline without blocking any username', () => {
    const malformed = new ApiError('Locked', 401, 'ACCOUNT_LOCKED', undefined, {
      lockedUntil: 'not-a-date',
    });
    const expired = new ApiError('Locked', 401, 'ACCOUNT_LOCKED', undefined, {
      lockedUntil: new Date(Date.now() - 1_000).toISOString(),
    });

    expect(getAccountLockout(malformed, 'cashier.a', Date.now())).toBeNull();
    expect(getAccountLockout(expired, 'cashier.a', Date.now())).toBeNull();
    expect(getLoginNotice(malformed).description).toBe(
      'Tài khoản đang tạm khóa. Vui lòng thử lại sau.',
    );
    expect(getLoginNotice(expired).description).toBe(
      'Tài khoản đang tạm khóa. Vui lòng thử lại sau.',
    );
    expect(
      getVisibleLoginNotice(
        'cashier.a',
        { username: 'cashier.a', value: getLoginNotice(expired) },
        [],
        Date.now(),
      ),
    ).not.toBeNull();
    expect(
      getAccountLockout(new ApiError('Locked', 401, 'ACCOUNT_LOCKED'), 'cashier.a'),
    ).toBeNull();
    expect(getLoginControlState('cashier.a', false, [], Date.now()).submitDisabled).toBe(false);
    expect(getLoginControlState('cashier.b', false, [], Date.now()).submitDisabled).toBe(false);
  });

  it('removes the legacy tab-wide deadline without reading it as a lockout', () => {
    const legacyKey = 'greenmart.auth.locked-until';
    const storedValues = new Map([[legacyKey, new Date(Date.now() + 60_000).toISOString()]]);
    const removeItem = vi.fn((key: string) => storedValues.delete(key));
    const storage = { removeItem };

    clearLegacyLoginLockDeadline(storage);

    expect(removeItem).toHaveBeenCalledWith(legacyKey);
    expect(storedValues.has(legacyKey)).toBe(false);
    expect(isLoginLockedForUsername('cashier.b', [], Date.now())).toBe(false);
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
