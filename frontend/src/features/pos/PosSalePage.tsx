import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import {
  lookupProduct,
  quoteOrder,
  searchProducts,
  type Product,
  type Quote,
} from '../../api/client';
import { AsyncBlock, Button, EmptyState, Input, Skeleton, Toast } from '../../components/ui';
import { useActiveBranch } from '../auth/useActiveBranch';
import { mapErrorToState } from '../../lib/errors';
import type { PosPageProps } from '../../layouts/pos/PosPlaceholderPage';
import type { Customer } from '../catalog/catalogApi';
import { cartKey, cartReducer, stockForLine } from './posCart';
import PosCustomerPanel from './PosCustomerPanel';
import './posSale.css';

type SearchState =
  | { status: 'loading'; key: string }
  | { status: 'ready'; key: string; products: Product[] }
  | { status: 'error'; key: string; error: unknown };
type QuoteState =
  | { status: 'idle'; key: string }
  | { status: 'loading'; key: string }
  | { status: 'ready'; key: string; quote: Quote }
  | { status: 'error'; key: string; error: unknown };

const money = (amount: number) => `${new Intl.NumberFormat('vi-VN').format(amount)} đ`;

function isEditable(target: EventTarget | null) {
  return (
    target instanceof HTMLElement &&
    Boolean(target.closest('input, textarea, select, [contenteditable="true"], [role="textbox"]'))
  );
}

export default function PosSalePage({ item }: PosPageProps) {
  const branch = useActiveBranch();
  const branchId = branch.state.status === 'active' ? branch.state.branch.id : 0;
  const [lines, dispatch] = useReducer(cartReducer, []);
  const [quantityDrafts, setQuantityDrafts] = useState<Record<number, string>>({});
  const invalidQuantity = Object.values(quantityDrafts).some(
    (value) => !/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 9999,
  );
  const [customer, setCustomer] = useState<Customer | null>(null);
  const customerId = customer?.id;
  const [query, setQuery] = useState('');
  const normalizedQuery = query.trim();
  const [searchRetry, setSearchRetry] = useState(0);
  const [quoteRetry, setQuoteRetry] = useState(0);
  const [searchState, setSearchState] = useState<SearchState>({ status: 'loading', key: '' });
  const [quoteState, setQuoteState] = useState<QuoteState>({ status: 'idle', key: '' });
  const [lookupBusy, setLookupBusy] = useState(false);
  const [lookupError, setLookupError] = useState<string | undefined>();
  const [notice, setNotice] = useState<string | undefined>();
  const scanner = useRef({ value: '', at: 0 });
  const currentCartKey = useMemo(
    () => `${cartKey(branchId, lines)}:${customerId ?? 'walk-in'}`,
    [branchId, lines, customerId],
  );

  useEffect(() => {
    const controller = new AbortController();
    const timer = window.setTimeout(
      () => {
        setSearchState({ status: 'loading', key: normalizedQuery });
        void searchProducts(normalizedQuery, controller.signal).then(
          (page) => {
            if (!controller.signal.aborted)
              setSearchState({ status: 'ready', key: normalizedQuery, products: page.content });
          },
          (error: unknown) => {
            if (!controller.signal.aborted)
              setSearchState({ status: 'error', key: normalizedQuery, error });
          },
        );
      },
      normalizedQuery ? 200 : 0,
    );
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [normalizedQuery, searchRetry]);

  useEffect(() => {
    if (lines.length === 0 || invalidQuantity) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setQuoteState({ status: 'loading', key: currentCartKey });
      void quoteOrder(
        {
          branchId,
          ...(customerId === undefined ? {} : { customerId }),
          items: lines.map(({ product, quantity }) => ({ productId: product.id, quantity })),
        },
        controller.signal,
      ).then(
        (quote) => {
          if (!controller.signal.aborted)
            setQuoteState({ status: 'ready', key: currentCartKey, quote });
        },
        (error: unknown) => {
          if (!controller.signal.aborted)
            setQuoteState({ status: 'error', key: currentCartKey, error });
        },
      );
    }, 120);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [branchId, customerId, currentCartKey, lines, quoteRetry, invalidQuantity]);

  const addByCode = useCallback(
    async (code: string) => {
      const trimmed = code.trim();
      if (!trimmed || !branchId) return;
      setLookupBusy(true);
      setLookupError(undefined);
      try {
        const result = await lookupProduct(trimmed, branchId);
        if (!result.product.active) {
          setLookupError('Sản phẩm đã ngừng bán, không thể thêm vào giỏ.');
          return;
        }
        if (lines.length >= 100 && !lines.some((line) => line.product.id === result.product.id)) {
          setLookupError('Giỏ hàng chỉ nhận tối đa 100 sản phẩm.');
          return;
        }
        if (lines.some((line) => line.product.id === result.product.id && line.quantity === 9999)) {
          setLookupError('Mỗi sản phẩm chỉ nhận tối đa 9.999 đơn vị.');
          return;
        }
        dispatch({ type: 'add', product: result.product, available: result.available });
        setQuery('');
        document.getElementById('pos-product-search')?.focus();
      } catch (error) {
        const state = mapErrorToState(error, 'block');
        if (state.kind === 'dialog') setNotice(state.message);
        else if (state.kind === 'block' || state.kind === 'form') setLookupError(state.message);
        else setNotice('Phiên làm việc cần được kiểm tra lại.');
      } finally {
        setLookupBusy(false);
      }
    },
    [branchId, lines],
  );

  useEffect(() => {
    function onKeyDown(event: globalThis.KeyboardEvent) {
      if (
        event.defaultPrevented ||
        event.isComposing ||
        event.ctrlKey ||
        event.altKey ||
        event.metaKey
      )
        return;
      if (document.querySelector('[role="dialog"][aria-modal="true"], dialog[open]')) return;
      if (event.key === 'F2') {
        event.preventDefault();
        document.getElementById('pos-product-search')?.focus();
        return;
      }
      if (event.key === 'F4') {
        event.preventDefault();
        document.getElementById('pos-checkout')?.focus();
        return;
      }
      if (event.key === 'Escape') {
        setQuery('');
        setLookupError(undefined);
        scanner.current.value = '';
        return;
      }
      if (isEditable(event.target)) return;
      if (event.key === 'Enter') {
        const code = scanner.current.value;
        scanner.current.value = '';
        if (code.length >= 2) {
          event.preventDefault();
          void addByCode(code);
        }
        return;
      }
      if (event.key.length !== 1) return;
      const now = performance.now();
      scanner.current.value =
        now - scanner.current.at <= 80 ? scanner.current.value + event.key : event.key;
      scanner.current.at = now;
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [addByCode]);

  const searchCurrent = searchState.key === normalizedQuery;
  const searchLoading = !searchCurrent || searchState.status === 'loading';
  const products = searchCurrent && searchState.status === 'ready' ? searchState.products : [];
  const searchError =
    searchCurrent && searchState.status === 'error' ? searchState.error : undefined;
  const quoteCurrent = quoteState.key === currentCartKey;
  const quoteLoading =
    !invalidQuantity && lines.length > 0 && (!quoteCurrent || quoteState.status === 'loading');
  const quote =
    !invalidQuantity && quoteCurrent && quoteState.status === 'ready' ? quoteState.quote : null;
  const quoteError = quoteCurrent && quoteState.status === 'error' ? quoteState.error : undefined;
  const mappedQuoteError =
    quoteError === undefined ? undefined : mapErrorToState(quoteError, 'block');
  const fieldErrors = mappedQuoteError?.kind === 'form' ? mappedQuoteError.fieldErrors : {};
  const shortage = lines.some((line) => !stockForLine(line, quote).sufficient);
  const canCheckout =
    !invalidQuantity && lines.length > 0 && !quoteLoading && !!quote?.canComplete && !shortage;
  const quotedMoney = (amount: number | undefined) =>
    quote ? money(amount ?? 0) : lines.length === 0 ? money(0) : '—';
  const quotedDiscount = (amount: number | undefined) =>
    quote ? (amount ? `−${money(amount)}` : money(0)) : lines.length === 0 ? money(0) : '—';

  function onSearchKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') {
      setQuery('');
      setLookupError(undefined);
    }
    if (event.key === 'Enter' && normalizedQuery) {
      event.preventDefault();
      const exact = products.find(
        (product) =>
          product.sku.toLowerCase() === normalizedQuery.toLowerCase() ||
          product.barcode === normalizedQuery,
      );
      const code =
        exact?.sku ??
        (products.length === 1 ? products[0].sku : undefined) ??
        (/^[A-Za-z0-9-]+$/.test(normalizedQuery) && /[0-9-]/.test(normalizedQuery)
          ? normalizedQuery
          : undefined);
      if (code) void addByCode(code);
    }
  }

  return (
    <section className="pos-sale" aria-label={item.title}>
      <div className="pos-sale__heading">
        <h1 data-pos-heading tabIndex={-1}>
          Bán hàng tại quầy
        </h1>
        <span>Chi nhánh {branch.state.status === 'active' ? branch.state.branch.code : ''}</span>
      </div>
      <div className="pos-sale__grid">
        <section className="pos-sale__panel" aria-labelledby="pos-sale-search-title">
          <h2 id="pos-sale-search-title" className="pos-sale__sr-only">
            Quét và tìm sản phẩm
          </h2>
          <Input
            id="pos-product-search"
            label="Quét mã vạch hoặc tìm theo tên / SKU"
            placeholder="Nhập hoặc quét mã sản phẩm…"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={onSearchKeyDown}
            error={lookupError}
            autoComplete="off"
            aria-keyshortcuts="F2"
          />
          <h3>Sản phẩm gợi ý</h3>
          <AsyncBlock
            key={`${normalizedQuery}:${searchRetry}`}
            isLoading={searchLoading}
            error={searchError}
            isEmpty={!searchLoading && products.length === 0}
            onRetry={() => setSearchRetry((value) => value + 1)}
            empty={{
              message: 'Chưa có sản phẩm để bán',
              filtered: !!normalizedQuery,
              onClearFilters: () => setQuery(''),
            }}
            skeleton={<Skeleton lines={3} height={40} />}
          >
            <ul className="pos-sale__products">
              {products.slice(0, 8).map((product) => (
                <li key={product.id}>
                  <div>
                    <strong>{product.name}</strong>
                    <small>
                      {product.sku} · {product.unit}
                    </small>
                  </div>
                  <span>{money(product.salePrice)}</span>
                  <Button
                    variant="secondary"
                    disabled={lookupBusy}
                    onClick={() => void addByCode(product.sku)}
                  >
                    Thêm
                  </Button>
                </li>
              ))}
            </ul>
          </AsyncBlock>
          <p className="pos-sale__hint">
            Quét barcode để thêm nhanh · Nhập tên rồi chọn Thêm · F2 tìm kiếm
          </p>
          <PosCustomerPanel customer={customer} onChange={setCustomer} />
        </section>

        <section className="pos-sale__panel pos-sale__cart" aria-labelledby="pos-sale-cart-title">
          <div className="pos-sale__cart-heading">
            <h2 id="pos-sale-cart-title">Giỏ hàng</h2>
            <span>{lines.length} loại hàng</span>
          </div>
          {lines.length === 0 ? (
            <EmptyState
              message="Giỏ hàng đang trống"
              description="Quét mã hoặc tìm sản phẩm để bắt đầu bán hàng."
            />
          ) : (
            <div className="pos-sale__cart-lines">
              <div className="pos-sale__cart-columns" aria-hidden="true">
                <span>Sản phẩm</span>
                <span>SL</span>
                <span>Đơn giá</span>
                <span>Giảm</span>
                <span>Thành tiền</span>
                <span />
              </div>
              {lines.map((line, index) => {
                const stock = stockForLine(line, quote);
                const quotedLine = quote?.lines.find((item) => item.productId === line.product.id);
                return (
                  <div
                    className={`pos-sale__cart-line${stock.sufficient ? '' : ' pos-sale__cart-line--short'}`}
                    key={line.product.id}
                  >
                    <div className="pos-sale__cart-product">
                      <strong>{line.product.name}</strong>
                      <small>
                        {line.product.sku} · Khả dụng {stock.available}
                      </small>
                      {quotedLine?.promotion && (
                        <small className="pos-sale__promotion">
                          {quotedLine.promotion.name || quotedLine.promotion.code}
                        </small>
                      )}
                      {!stock.sufficient && (
                        <span role="alert" className="pos-sale__shortage">
                          Thiếu hàng · chỉ còn {stock.available}
                        </span>
                      )}
                      {fieldErrors[`items[${index}].quantity`] && (
                        <span role="alert" className="pos-sale__shortage">
                          {fieldErrors[`items[${index}].quantity`]}
                        </span>
                      )}
                    </div>
                    <div className="pos-sale__quantity-field">
                      <div
                        className="pos-sale__quantity"
                        aria-label={`Số lượng ${line.product.name}`}
                      >
                        <button
                          type="button"
                          aria-label={`Giảm số lượng ${line.product.name}`}
                          disabled={
                            line.quantity <= 1 || quantityDrafts[line.product.id] !== undefined
                          }
                          onClick={() =>
                            dispatch({
                              type: 'quantity',
                              productId: line.product.id,
                              quantity: line.quantity - 1,
                            })
                          }
                        >
                          −
                        </button>
                        <input
                          type="number"
                          min="1"
                          max="9999"
                          aria-label={`Số lượng ${line.product.name}`}
                          value={quantityDrafts[line.product.id] ?? line.quantity}
                          aria-invalid={quantityDrafts[line.product.id] !== undefined || undefined}
                          aria-describedby={
                            quantityDrafts[line.product.id] !== undefined
                              ? `quantity-error-${line.product.id}`
                              : undefined
                          }
                          onChange={(event) => {
                            const value = event.target.value;
                            const valid =
                              /^\d+$/.test(value) && Number(value) >= 1 && Number(value) <= 9999;
                            setQuantityDrafts((old) => {
                              const next = { ...old };
                              if (valid) delete next[line.product.id];
                              else next[line.product.id] = value;
                              return next;
                            });
                            if (valid)
                              dispatch({
                                type: 'quantity',
                                productId: line.product.id,
                                quantity: Number(value),
                              });
                          }}
                        />
                        <button
                          type="button"
                          aria-label={`Tăng số lượng ${line.product.name}`}
                          disabled={
                            line.quantity >= 9999 || quantityDrafts[line.product.id] !== undefined
                          }
                          onClick={() =>
                            dispatch({
                              type: 'quantity',
                              productId: line.product.id,
                              quantity: line.quantity + 1,
                            })
                          }
                        >
                          +
                        </button>
                      </div>
                      {quantityDrafts[line.product.id] !== undefined && (
                        <span
                          className="pos-sale__form-error"
                          id={`quantity-error-${line.product.id}`}
                          role="alert"
                        >
                          Số lượng phải là số nguyên từ 1 đến 9.999.
                        </span>
                      )}
                    </div>
                    <span className="pos-sale__amount" data-label="Đơn giá">
                      {money(quotedLine?.unitPrice ?? line.product.salePrice)}
                    </span>
                    <span className="pos-sale__amount pos-sale__discount" data-label="Giảm">
                      {quotedLine?.discount ? `−${money(quotedLine.discount)}` : '—'}
                    </span>
                    <strong className="pos-sale__amount" data-label="Thành tiền">
                      {money(quotedLine?.lineTotal ?? line.product.salePrice * line.quantity)}
                    </strong>
                    <button
                      className="pos-sale__remove"
                      type="button"
                      aria-label={`Xóa ${line.product.name}`}
                      onClick={() => {
                        setQuantityDrafts((old) => {
                          const next = { ...old };
                          delete next[line.product.id];
                          return next;
                        });
                        dispatch({ type: 'remove', productId: line.product.id });
                      }}
                    >
                      ×
                    </button>
                  </div>
                );
              })}
            </div>
          )}
          <div className="pos-sale__summary">
            <AsyncBlock
              key={`${currentCartKey}:${quoteRetry}`}
              isLoading={quoteLoading}
              error={mappedQuoteError?.kind === 'block' ? quoteError : undefined}
              onRetry={() => setQuoteRetry((value) => value + 1)}
              skeleton={<Skeleton lines={3} />}
            >
              {quoteLoading ? (
                <p className="pos-sale__quote-pending" role="status">
                  Đang tính báo giá…
                </p>
              ) : (
                <div className="pos-sale__totals">
                  <div>
                    <span>Tạm tính</span>
                    <span>{quotedMoney(quote?.subtotal)}</span>
                  </div>
                  <div>
                    <span>Giảm giá theo dòng</span>
                    <span>{quotedDiscount(quote?.itemDiscount)}</span>
                  </div>
                  <div>
                    <span>Giảm giá đơn hàng</span>
                    <span>{quotedDiscount(quote?.orderDiscount)}</span>
                  </div>
                  <div>
                    <span>Tổng giảm giá</span>
                    <span>{quotedDiscount(quote?.discount)}</span>
                  </div>
                  <div className="pos-sale__total">
                    <strong>Tổng thanh toán</strong>
                    <strong>{quotedMoney(quote?.total)}</strong>
                  </div>
                </div>
              )}
            </AsyncBlock>
            {mappedQuoteError?.kind === 'form' && (
              <p role="alert" className="pos-sale__form-error">
                {mappedQuoteError.message}
              </p>
            )}
            {mappedQuoteError?.kind === 'dialog' && (
              <Toast variant="warning" title={mappedQuoteError.message} />
            )}
            {shortage && (
              <p role="alert" className="pos-sale__form-error">
                Giỏ hàng có sản phẩm thiếu tồn. Hãy sửa số lượng trước khi thanh toán.
              </p>
            )}
            <Button
              id="pos-checkout"
              className="pos-sale__checkout"
              disabled={!canCheckout}
              aria-keyshortcuts="F4"
              onClick={() =>
                setNotice('Thanh toán chưa khả dụng. Giỏ hàng của bạn vẫn được giữ nguyên.')
              }
            >
              Thanh toán · F4
            </Button>
            <small>Quét mã để tiếp tục thêm hàng · Esc để xóa tìm kiếm</small>
          </div>
        </section>
      </div>
      {notice && (
        <Toast
          className="pos-sale__notice"
          variant="warning"
          title={notice}
          onClose={() => setNotice(undefined)}
        />
      )}
    </section>
  );
}
