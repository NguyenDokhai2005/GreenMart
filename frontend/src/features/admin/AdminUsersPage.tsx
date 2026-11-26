import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router';
import type { Role } from '../../api/client';
import { ApiError, listBranches } from '../../api/client';
import { AsyncBlock, Button, Dialog, Input, Skeleton, Toast } from '../../components/ui';
import { mapErrorToState } from '../../lib/errors';
import { useAuth } from '../auth/useAuth';
import {
  createUser,
  getUser,
  listUsers,
  resetUserPassword,
  unlockUser,
  updateUser,
  type User,
  type UserCreate,
  type UserPage,
  type UserQuery,
} from './usersApi';
import './adminUsers.css';

const ROLES: Role[] = ['ADMIN', 'OWNER', 'BRANCH_MANAGER', 'CASHIER', 'STOREKEEPER'];
const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Quản trị viên',
  OWNER: 'Chủ chuỗi',
  BRANCH_MANAGER: 'Quản lý chi nhánh',
  CASHIER: 'Thu ngân',
  STOREKEEPER: 'Thủ kho',
};
const BRANCH_ROLES: Role[] = ['BRANCH_MANAGER', 'CASHIER', 'STOREKEEPER'];
const PAGE_SIZE = 10;

type Branch = { id: number; code: string; name: string };
type Mode = 'staff' | 'locked';
type FormState = {
  username: string;
  fullName: string;
  role: Role;
  branchIds: number[];
  active: boolean;
  password: string;
};
const emptyForm = (): FormState => ({
  username: '',
  fullName: '',
  role: 'CASHIER',
  branchIds: [],
  active: true,
  password: '',
});

function readFilters(params: URLSearchParams) {
  const rawPage = Number(params.get('page'));
  const rawBranch = Number(params.get('branchId'));
  const role = params.get('role');
  const active = params.get('active');
  return {
    q: params.get('q')?.trim() ?? '',
    role: ROLES.find((item) => item === role),
    branchId: rawBranch > 0 && Number.isInteger(rawBranch) ? rawBranch : undefined,
    active: active === 'true' ? true : active === 'false' ? false : undefined,
    page: Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0,
  };
}

function formFromUser(user: User): FormState {
  return {
    username: user.username,
    fullName: user.fullName,
    role: user.role,
    branchIds: user.branches.map((branch) => branch.id),
    active: user.active,
    password: '',
  };
}

function fieldErrors(error: unknown): Record<string, string> {
  const mapped = mapErrorToState(error);
  if (mapped.kind === 'form') return mapped.fieldErrors;
  if (error instanceof ApiError && error.code === 'DUPLICATE_USERNAME') {
    return { username: error.message };
  }
  return {};
}

async function allLockedUsers(signal: AbortSignal): Promise<User[]> {
  const first = await listUsers({ page: 0, size: 100 }, signal);
  const users = [...first.content];
  for (let page = 1; page < first.totalPages; page += 1) {
    signal.throwIfAborted();
    const next = await listUsers({ page, size: 100 }, signal);
    users.push(...next.content);
  }
  return users.filter((user) => user.locked);
}

export default function AdminUsersPage({
  mode,
  readOnly = false,
}: {
  mode: Mode;
  readOnly?: boolean;
}) {
  const [params, setParams] = useSearchParams();
  const paramKey = params.toString();
  const filters = useMemo(() => readFilters(new URLSearchParams(paramKey)), [paramKey]);
  const [result, setResult] = useState<{
    key: string;
    page?: UserPage;
    locked?: User[];
    error?: unknown;
  }>();
  const [revision, setRevision] = useState(0);
  const [branches, setBranches] = useState<Branch[]>([]);
  const [editor, setEditor] = useState<'create' | 'edit' | null>(null);
  const [selected, setSelected] = useState<User | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [formError, setFormError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<'unlock' | 'reset' | null>(null);
  const [newPassword, setNewPassword] = useState('');
  const [notice, setNotice] = useState('');
  const { state: auth } = useAuth();
  const key = `${mode}:${paramKey}:${revision}`;
  const loading = result?.key !== key;

  useEffect(() => {
    const controller = new AbortController();
    void listBranches(true, controller.signal).then(
      (items) => {
        if (!controller.signal.aborted) setBranches(items);
      },
      () => {
        /* The list error is reported by the field when a branch is required. */
      },
    );
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    const query: UserQuery = {
      q: filters.q || undefined,
      role: filters.role,
      branchId: filters.branchId,
      active: filters.active,
      page: filters.page,
      size: PAGE_SIZE,
    };
    const load =
      mode === 'staff'
        ? listUsers(query, controller.signal).then((page) => ({ page }))
        : allLockedUsers(controller.signal).then((locked) => ({ locked }));
    void load.then(
      (value) => {
        if (!controller.signal.aborted) setResult({ key, ...value });
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setResult({ key, error });
      },
    );
    return () => controller.abort();
  }, [key, mode, filters.q, filters.role, filters.branchId, filters.active, filters.page]);

  function changeFilter(name: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(name, value);
    else next.delete(name);
    next.delete('page');
    setParams(next);
  }

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    changeFilter('q', String(new FormData(event.currentTarget).get('q') ?? '').trim());
  }

  function changePage(page: number) {
    const next = new URLSearchParams(params);
    if (page > 0) next.set('page', String(page));
    else next.delete('page');
    setParams(next);
  }

  function openCreate() {
    setSelected(null);
    setForm(emptyForm());
    setFormError(undefined);
    setEditor('create');
  }

  async function openEdit(user: User) {
    setFormError(undefined);
    setBusy(true);
    try {
      const detail = await getUser(user.id);
      setSelected(detail);
      setForm(formFromUser(detail));
      setEditor('edit');
    } catch (error) {
      setNotice(mapErrorToState(error).message ?? 'Không tải được tài khoản.');
    } finally {
      setBusy(false);
    }
  }

  async function save() {
    const errors: Record<string, string> = {};
    if (!form.fullName.trim()) errors.fullName = 'Nhập họ tên.';
    if (BRANCH_ROLES.includes(form.role) && form.branchIds.length === 0) {
      errors.branchIds = 'Chọn ít nhất một chi nhánh.';
    }
    if (editor === 'create') {
      if (!/^[a-z0-9._]{3,60}$/.test(form.username))
        errors.username = 'Tên đăng nhập từ 3–60 ký tự thường, số, dấu chấm hoặc gạch dưới.';
      if (
        form.password.length < 8 ||
        !/[a-zA-Z]/.test(form.password) ||
        !/\d/.test(form.password)
      ) {
        errors.password = 'Mật khẩu từ 8 ký tự, có chữ và số.';
      }
    }
    if (Object.keys(errors).length) {
      setFormError({ local: errors });
      return;
    }
    setBusy(true);
    setFormError(undefined);
    try {
      const branchIds = BRANCH_ROLES.includes(form.role) ? form.branchIds : undefined;
      if (editor === 'create') {
        const body: UserCreate = {
          username: form.username,
          fullName: form.fullName.trim(),
          role: form.role,
          password: form.password,
          ...(branchIds ? { branchIds } : {}),
        };
        await createUser(body);
      } else if (selected) {
        await updateUser(selected.id, {
          fullName: form.fullName.trim(),
          role: form.role,
          active: form.active,
          ...(branchIds ? { branchIds } : {}),
        });
      }
      setEditor(null);
      setForm(emptyForm());
      setNotice('Đã lưu tài khoản.');
      setRevision((value) => value + 1);
    } catch (error) {
      setFormError(error);
    } finally {
      setBusy(false);
    }
  }

  async function runConfirmed() {
    if (!selected || !confirm) return;
    if (confirm === 'reset' && newPassword.length < 8) {
      setFormError({ local: { newPassword: 'Mật khẩu cần ít nhất 8 ký tự.' } });
      return;
    }
    setBusy(true);
    setFormError(undefined);
    try {
      if (confirm === 'unlock') await unlockUser(selected.id);
      else await resetUserPassword(selected.id, newPassword);
      setNotice(confirm === 'unlock' ? 'Đã mở khóa đăng nhập.' : 'Đã đặt lại mật khẩu.');
      setConfirm(null);
      setNewPassword('');
      setRevision((value) => value + 1);
    } catch (error) {
      setFormError(error);
    } finally {
      setBusy(false);
    }
  }

  const localErrors =
    formError && typeof formError === 'object' && 'local' in formError
      ? (formError.local as Record<string, string>)
      : {};
  const errors = { ...fieldErrors(formError), ...localErrors };
  const formMessage =
    formError && !Object.keys(errors).length ? mapErrorToState(formError).message : '';
  const staff = result?.key === key ? result.page : undefined;
  const locked = result?.key === key ? result.locked : undefined;
  const rows = mode === 'staff' ? (staff?.content ?? []) : (locked ?? []);
  const pageCount =
    mode === 'staff' ? (staff?.totalPages ?? 0) : Math.ceil(rows.length / PAGE_SIZE);
  const displayed =
    mode === 'locked' ? rows.slice(filters.page * PAGE_SIZE, (filters.page + 1) * PAGE_SIZE) : rows;

  return (
    <section className="admin-users">
      <header className="admin-users__heading">
        <div>
          <h1>{mode === 'staff' ? 'Nhân sự & phân quyền' : 'Tài khoản bị khóa'}</h1>
          <p>
            {mode === 'staff'
              ? readOnly
                ? 'Xem tài khoản, vai trò và chi nhánh của nhân viên.'
                : 'Quản lý tài khoản và quyền truy cập.'
              : 'Các tài khoản đang bị khóa đăng nhập tạm thời.'}
          </p>
        </div>
        {!readOnly && mode === 'staff' && <Button onClick={openCreate}>Tạo tài khoản</Button>}
      </header>
      {notice && <Toast variant="success" title={notice} />}
      {mode === 'staff' && (
        <form className="admin-users__filters" onSubmit={search}>
          <Input key={filters.q} name="q" label="Tìm theo tên" defaultValue={filters.q} />
          <Button type="submit">Tìm kiếm</Button>
          <label>
            Vai trò
            <select
              value={filters.role ?? ''}
              onChange={(event) => changeFilter('role', event.target.value)}
            >
              <option value="">Tất cả vai trò</option>
              {ROLES.map((role) => (
                <option value={role} key={role}>
                  {ROLE_LABELS[role]}
                </option>
              ))}
            </select>
          </label>
          <label>
            Chi nhánh
            <select
              value={filters.branchId ?? ''}
              onChange={(event) => changeFilter('branchId', event.target.value)}
            >
              <option value="">Tất cả chi nhánh</option>
              {branches.map((branch) => (
                <option value={branch.id} key={branch.id}>
                  {branch.code} {branch.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            Trạng thái
            <select
              value={filters.active === undefined ? '' : String(filters.active)}
              onChange={(event) => changeFilter('active', event.target.value)}
            >
              <option value="">Tất cả trạng thái</option>
              <option value="true">Đang hoạt động</option>
              <option value="false">Đã vô hiệu hóa</option>
            </select>
          </label>
        </form>
      )}
      <AsyncBlock
        isLoading={loading}
        error={result?.key === key ? result.error : undefined}
        onRetry={() => setRevision((value) => value + 1)}
        skeleton={
          <div aria-label="Đang tải danh sách tài khoản">
            <Skeleton lines={8} />
          </div>
        }
        isEmpty={!loading && rows.length === 0}
        empty={{
          message: 'Chưa có tài khoản',
          description: 'Thử tải lại danh sách.',
          filtered: mode === 'staff' && !!paramKey,
          onClearFilters: mode === 'staff' ? () => setParams(new URLSearchParams()) : undefined,
        }}
      >
        <div className="admin-users__table-wrap">
          <table>
            <thead>
              <tr>
                <th>Tên đăng nhập</th>
                <th>Họ tên</th>
                <th>Vai trò</th>
                <th>Chi nhánh</th>
                <th>Trạng thái</th>
                {!readOnly && <th>Thao tác</th>}
              </tr>
            </thead>
            <tbody>
              {displayed.map((user) => (
                <tr key={user.id}>
                  <td>{user.username}</td>
                  <td>{user.fullName}</td>
                  <td>{ROLE_LABELS[user.role]}</td>
                  <td>
                    {user.branches.map((branch) => branch.code).join(', ') || 'Toàn hệ thống'}
                  </td>
                  <td>
                    {user.active ? (user.locked ? 'Bị khóa tạm thời' : 'Hoạt động') : 'Vô hiệu hóa'}
                  </td>
                  {!readOnly && (
                    <td className="admin-users__actions">
                      {mode === 'staff' && (
                        <Button
                          variant="secondary"
                          disabled={busy}
                          onClick={() => void openEdit(user)}
                        >
                          Sửa
                        </Button>
                      )}
                      {mode === 'staff' && (
                        <Button
                          variant="secondary"
                          onClick={() => {
                            setSelected(user);
                            setNewPassword('');
                            setFormError(undefined);
                            setConfirm('reset');
                          }}
                        >
                          Đặt lại mật khẩu
                        </Button>
                      )}
                      {user.locked && (
                        <Button
                          variant="secondary"
                          onClick={() => {
                            setSelected(user);
                            setFormError(undefined);
                            setConfirm('unlock');
                          }}
                        >
                          Mở khóa
                        </Button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {pageCount > 1 && (
          <nav className="admin-users__pages" aria-label="Phân trang tài khoản">
            <Button
              variant="secondary"
              disabled={filters.page === 0}
              onClick={() => changePage(filters.page - 1)}
            >
              Trang trước
            </Button>
            <span>
              Trang {filters.page + 1}/{pageCount}
            </span>
            <Button
              variant="secondary"
              disabled={filters.page + 1 >= pageCount}
              onClick={() => changePage(filters.page + 1)}
            >
              Trang sau
            </Button>
          </nav>
        )}
      </AsyncBlock>
      <Dialog
        open={!!editor}
        title={editor === 'create' ? 'Tạo tài khoản' : 'Sửa tài khoản'}
        onClose={() => setEditor(null)}
        onConfirm={() => void save()}
        confirmText="Lưu"
        busy={busy}
      >
        <div className="admin-users__form">
          {editor === 'create' && (
            <Input
              label="Tên đăng nhập"
              value={form.username}
              error={errors.username}
              onChange={(event) => setForm({ ...form, username: event.target.value })}
            />
          )}
          <Input
            label="Họ tên"
            value={form.fullName}
            error={errors.fullName}
            onChange={(event) => setForm({ ...form, fullName: event.target.value })}
          />
          <label>
            Vai trò
            <select
              value={form.role}
              onChange={(event) =>
                setForm({ ...form, role: event.target.value as Role, branchIds: [] })
              }
            >
              {ROLES.map((role) => (
                <option key={role} value={role}>
                  {ROLE_LABELS[role]}
                </option>
              ))}
            </select>
          </label>
          {BRANCH_ROLES.includes(form.role) && (
            <fieldset>
              <legend>Chi nhánh</legend>
              {branches.map((branch) => (
                <label key={branch.id}>
                  <input
                    type="checkbox"
                    checked={form.branchIds.includes(branch.id)}
                    onChange={(event) =>
                      setForm({
                        ...form,
                        branchIds: event.target.checked
                          ? [...form.branchIds, branch.id]
                          : form.branchIds.filter((id) => id !== branch.id),
                      })
                    }
                  />
                  {branch.code} {branch.name}
                </label>
              ))}
              {!branches.length && <p>Chưa tải được chi nhánh. Hãy thử mở lại trang.</p>}
              {errors.branchIds && <p role="alert">{errors.branchIds}</p>}
            </fieldset>
          )}
          {editor === 'create' && (
            <Input
              label="Mật khẩu ban đầu"
              type="password"
              value={form.password}
              error={errors.password}
              onChange={(event) => setForm({ ...form, password: event.target.value })}
            />
          )}
          {editor === 'edit' && (
            <label>
              <input
                type="checkbox"
                checked={form.active}
                onChange={(event) => setForm({ ...form, active: event.target.checked })}
              />
              Đang hoạt động
            </label>
          )}
          {formMessage && <p role="alert">{formMessage}</p>}
          {auth.status === 'authenticated' &&
            selected?.id === auth.user.id &&
            editor === 'edit' && (
              <p>Bạn không thể tự đổi vai trò hoặc vô hiệu hóa tài khoản của mình.</p>
            )}
        </div>
      </Dialog>
      <Dialog
        open={!!confirm}
        title={confirm === 'unlock' ? 'Mở khóa đăng nhập' : 'Đặt lại mật khẩu'}
        onClose={() => {
          setConfirm(null);
          setNewPassword('');
        }}
        onConfirm={() => void runConfirmed()}
        confirmText="Xác nhận"
        busy={busy}
      >
        {confirm === 'unlock' ? (
          <p>
            Mở khóa đăng nhập cho {selected?.username}? Tài khoản bị vô hiệu hóa vẫn không thể đăng
            nhập.
          </p>
        ) : (
          <Input
            label="Mật khẩu mới"
            type="password"
            autoComplete="new-password"
            value={newPassword}
            error={errors.newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
          />
        )}
        {formMessage && <p role="alert">{formMessage}</p>}
      </Dialog>
    </section>
  );
}
