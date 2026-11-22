const formatter = new Intl.NumberFormat('vi-VN');

export function money(amount: number): string {
  return `${formatter.format(amount)} ₫`;
}

export function discountMoney(amount: number): string {
  return amount > 0 ? `−${money(amount)}` : money(0);
}
