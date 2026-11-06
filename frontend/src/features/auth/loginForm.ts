import { ApiError } from '../../api/client';

export interface LoginFieldErrors {
  username?: string;
  password?: string;
}

export interface LoginNotice {
  title: string;
  description: string;
  lockedUntil?: number;
}

export interface UserLoginNotice {
  username: string;
  value: LoginNotice;
}

export interface LoginLockout {
  username: string;
  lockedUntil: number;
}

const LEGACY_LOCKED_UNTIL_STORAGE_KEY = 'greenmart.auth.locked-until';

const USERNAME_MAX_LENGTH = 60;
const PASSWORD_MAX_LENGTH = 100;

export function validateLoginRequest(username: string, password: string): LoginFieldErrors {
  const errors: LoginFieldErrors = {};

  if (username.length === 0) errors.username = 'Vui lòng nhập tên đăng nhập.';
  else if (username.length > USERNAME_MAX_LENGTH) {
    errors.username = `Tên đăng nhập không được vượt quá ${USERNAME_MAX_LENGTH} ký tự.`;
  }

  if (password.length === 0) errors.password = 'Vui lòng nhập mật khẩu.';
  else if (password.length > PASSWORD_MAX_LENGTH) {
    errors.password = `Mật khẩu không được vượt quá ${PASSWORD_MAX_LENGTH} ký tự.`;
  }

  return errors;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function getErrorDetails(error: unknown): Record<string, unknown> | undefined {
  if (!(error instanceof ApiError) || !isRecord(error.details)) return undefined;
  return error.details;
}

export function getRemainingAttempts(error: unknown): number | undefined {
  if (!(error instanceof ApiError) || error.code !== 'INVALID_CREDENTIALS') return undefined;
  const remainingAttempts = getErrorDetails(error)?.remainingAttempts;
  return typeof remainingAttempts === 'number' &&
    Number.isInteger(remainingAttempts) &&
    remainingAttempts >= 0
    ? remainingAttempts
    : undefined;
}

export function parseLockedUntil(value: unknown): number | undefined {
  if (typeof value !== 'string') return undefined;

  const match = value.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|([+-])(\d{2}):(\d{2}))$/i,
  );
  if (!match) return undefined;

  const [
    ,
    yearValue,
    monthValue,
    dayValue,
    hourValue,
    minuteValue,
    secondValue,
    ,
    ,
    offsetHour,
    offsetMinute,
  ] = match;
  const year = Number(yearValue);
  const month = Number(monthValue);
  const day = Number(dayValue);
  const hour = Number(hourValue);
  const minute = Number(minuteValue);
  const second = Number(secondValue);
  const offsetHours = Number(offsetHour ?? 0);
  const offsetMinutes = Number(offsetMinute ?? 0);
  const daysPerMonth = [31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > daysPerMonth[month - 1]! ||
    hour > 23 ||
    minute > 59 ||
    second > 59 ||
    offsetHours > 23 ||
    offsetMinutes > 59
  ) {
    return undefined;
  }

  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? timestamp : undefined;
}

function isLeapYear(year: number): boolean {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

export function getLockedUntil(error: unknown): number | undefined {
  if (!(error instanceof ApiError) || error.code !== 'ACCOUNT_LOCKED') return undefined;
  return parseLockedUntil(getErrorDetails(error)?.lockedUntil);
}

/** Returns undefined for other errors and null for a lock with no usable future deadline. */
export function getAccountLockout(
  error: unknown,
  username: string,
  now: number = Date.now(),
): LoginLockout | null | undefined {
  if (!(error instanceof ApiError) || error.code !== 'ACCOUNT_LOCKED') return undefined;

  const lockedUntil = getLockedUntil(error);
  if (lockedUntil === undefined || lockedUntil <= now) return null;
  return { username, lockedUntil };
}

export function isLoginLockedForUsername(
  username: string,
  lockouts: readonly LoginLockout[],
  now: number,
): boolean {
  return lockouts.some((lockout) => lockout.username === username && lockout.lockedUntil > now);
}

export interface LoginControlState {
  isLocked: boolean;
  usernameDisabled: boolean;
  passwordDisabled: boolean;
  submitDisabled: boolean;
}

export function getLoginControlState(
  username: string,
  isSubmitting: boolean,
  lockouts: readonly LoginLockout[],
  now: number,
): LoginControlState {
  const isLocked = isLoginLockedForUsername(username, lockouts, now);
  return {
    isLocked,
    usernameDisabled: isSubmitting,
    passwordDisabled: isSubmitting || isLocked,
    submitDisabled: isSubmitting || isLocked,
  };
}

export function getVisibleLoginNotice(
  username: string,
  notice: UserLoginNotice | null,
  lockouts: readonly LoginLockout[],
  now: number,
): LoginNotice | null {
  if (notice?.username === username) {
    if (
      notice.value.title === 'Tài khoản tạm khóa' &&
      notice.value.lockedUntil !== undefined &&
      notice.value.lockedUntil <= now
    ) {
      return null;
    }
    return notice.value;
  }
  if (!isLoginLockedForUsername(username, lockouts, now)) return null;
  return { title: 'Tài khoản tạm khóa', description: 'Tài khoản đang tạm khóa.' };
}

/** Clears the old tab-wide lock marker. New lockout state is held per page and username. */
export function clearLegacyLoginLockDeadline(storage?: Pick<Storage, 'removeItem'>): void {
  try {
    (storage ?? globalThis.sessionStorage)?.removeItem(LEGACY_LOCKED_UNTIL_STORAGE_KEY);
  } catch {
    // Storage can be unavailable in restricted browser contexts.
  }
}

export function formatLockedUntil(timestamp: number): string {
  return new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZone: 'Asia/Ho_Chi_Minh',
  }).format(timestamp);
}

export function getLoginFieldErrors(error: unknown): LoginFieldErrors {
  if (
    !(error instanceof ApiError) ||
    error.code !== 'VALIDATION_ERROR' ||
    !Array.isArray(error.details)
  ) {
    return {};
  }

  const fieldErrors: LoginFieldErrors = {};
  for (const detail of error.details) {
    if (
      !isRecord(detail) ||
      typeof detail.field !== 'string' ||
      typeof detail.message !== 'string'
    ) {
      continue;
    }
    if (detail.field === 'username' || detail.field === 'password') {
      fieldErrors[detail.field] = detail.message;
    }
  }
  return fieldErrors;
}

export function getLoginNotice(error: unknown): LoginNotice {
  if (error instanceof ApiError && error.code === 'INVALID_CREDENTIALS') {
    const remainingAttempts = getRemainingAttempts(error);
    return {
      title: 'Đăng nhập không thành công',
      description:
        remainingAttempts === undefined
          ? 'Sai tên đăng nhập hoặc mật khẩu.'
          : `Sai tên đăng nhập hoặc mật khẩu. Bạn còn ${remainingAttempts} lần thử trước khi bị khóa tạm.`,
    };
  }

  if (error instanceof ApiError && error.code === 'ACCOUNT_LOCKED') {
    const lockedUntil = getLockedUntil(error);
    return {
      title: 'Tài khoản tạm khóa',
      description:
        lockedUntil !== undefined && lockedUntil > Date.now()
          ? `Bạn có thể thử đăng nhập lại sau ${formatLockedUntil(lockedUntil)}.`
          : 'Tài khoản đang tạm khóa. Vui lòng thử lại sau.',
      lockedUntil: lockedUntil !== undefined && lockedUntil > Date.now() ? lockedUntil : undefined,
    };
  }

  if (error instanceof TypeError) {
    return {
      title: 'Không thể kết nối',
      description: 'Không thể kết nối đến máy chủ. Kiểm tra kết nối mạng rồi thử lại.',
    };
  }

  if (
    (error instanceof DOMException && error.name === 'TimeoutError') ||
    (error instanceof ApiError && (error.status === 408 || error.status === 504))
  ) {
    return {
      title: 'Hết thời gian chờ',
      description: 'Máy chủ chưa phản hồi kịp thời. Vui lòng thử lại.',
    };
  }

  if (error instanceof ApiError && error.status >= 500) {
    const trace = error.traceId ? ` Mã tham chiếu: ${error.traceId}.` : '';
    return {
      title: 'Lỗi hệ thống',
      description: `${error.message}${trace}`,
    };
  }

  if (error instanceof ApiError) {
    return { title: 'Đăng nhập không thành công', description: error.message };
  }

  return {
    title: 'Đăng nhập không thành công',
    description: 'Đã xảy ra lỗi. Vui lòng thử lại.',
  };
}
