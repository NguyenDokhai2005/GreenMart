#!/usr/bin/env bash
# ==============================================================================
# seed.sh - Wrapper chạy bộ điều phối seed GreenMart cho Linux / macOS / Git Bash
# Issue #108
# ==============================================================================
set -euo pipefail

# Xác định thư mục gốc của repository (chạy an toàn từ mọi thư mục)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Tìm python khả dụng (ưu tiên python3, rồi đến python, bỏ qua alias không hợp lệ)
PYTHON_CMD=""
for cmd in python3 python py; do
    if command -v "${cmd}" >/dev/null 2>&1 && "${cmd}" -c "import sys" >/dev/null 2>&1; then
        PYTHON_CMD="${cmd}"
        break
    fi
done

if [ -z "${PYTHON_CMD}" ]; then
    echo "[LỖI] Không tìm thấy Python thực thi hợp lệ (python3 hoặc python) trên hệ thống." >&2
    echo "[GỢI Ý] Vui lòng cài đặt Python 3.11 trở lên và kiểm tra biến môi trường PATH." >&2
    exit 1
fi

# Chuyển tiếp toàn bộ tham số vào seed.py
exec "${PYTHON_CMD}" "${REPO_ROOT}/forecast-service/scripts/seed.py" "$@"
