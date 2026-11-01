#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Bộ điều phối nạp dữ liệu mẫu một lệnh (One-command Seed Orchestrator) cho GreenMart (Issue #108).

Thực hiện quy trình:
1. Kiểm tra môi trường Docker Compose và dịch vụ cơ sở dữ liệu PostgreSQL.
2. Kiểm tra điều kiện tiên quyết Flyway migration (V1 - V4).
3. Sinh dữ liệu mẫu bằng generator (hoặc dùng dữ liệu sẵn có).
4. Tính, ghi nhận và so sánh SHA-256 (SHA256SUMS).
5. Cảnh báo an toàn nếu DB đã có đơn hàng (yêu cầu xác nhận y/N, bỏ qua với --yes).
6. Nạp SQL vào cơ sở dữ liệu với psql -v ON_ERROR_STOP=1.
7. Chạy kiểm đếm AC-01 tự khẳng định (verify_seed.sql).
8. In bảng mã vân tay (fingerprint) của từng bảng dữ liệu để đối chiếu tính tái lập.
"""

from __future__ import annotations

import argparse
import ast
import hashlib
from pathlib import Path
import subprocess
import sys
import time
from typing import Dict, List, Optional, Tuple

# Cấu hình UTF-8 cho stdout trên mọi nền tảng
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

# Định vị đường dẫn các thư mục và file trọng yếu
MODULE_DIR = Path(__file__).resolve().parent
SERVICE_DIR = MODULE_DIR.parent
REPO_ROOT = SERVICE_DIR.parent
DATA_DIR = SERVICE_DIR / "data"

SQL_FILE = DATA_DIR / "seed_data.sql"
JSON_FILE = DATA_DIR / "stockout_schedule.json"
SHA256SUMS_FILE = DATA_DIR / "SHA256SUMS"
VERIFY_SQL_FILE = MODULE_DIR / "verify_seed.sql"
GENERATOR_SCRIPT = MODULE_DIR / "generate_data.py"
TEST_GENERATOR_FILE = SERVICE_DIR / "tests" / "test_generator.py"

# Danh sách 22 bảng được seed dữ liệu cần tính mã vân tay (fingerprint)
SEEDED_TABLES: List[str] = [
    "branch",
    "category",
    "product",
    "app_user",
    "user_branch",
    "sales_channel",
    "channel_branch",
    "customer",
    "promotion",
    "promotion_branch",
    "promotion_channel",
    "promotion_product",
    "sales_order",
    "order_item",
    "payment",
    "return_document",
    "return_item",
    "inventory",
    "inventory_transaction",
    "stock_document",
    "stock_document_item",
    "audit_log",
]


class SeedError(Exception):
    """Ngoại lệ chung cho các lỗi trong quá trình điều phối seed."""

    def __init__(self, message: str, suggestion: Optional[str] = None):
        super().__init__(message)
        self.message = message
        self.suggestion = suggestion


def log_step(step_num: int, total_steps: int, title: str) -> None:
    """In tiêu đề bước thực hiện với định dạng rõ ràng."""
    print(f"\n[{step_num}/{total_steps}] === {title} ===")


def calculate_file_sha256(filepath: Path) -> str:
    """Tính toán mã băm SHA-256 của một file trên đĩa."""
    if not filepath.is_file():
        raise SeedError(
            f"Không tìm thấy file để tính SHA-256: {filepath.name}",
            f"Vui lòng đảm bảo file tồn tại tại đường dẫn: {filepath}",
        )
    hasher = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def extract_golden_hashes_from_test() -> Dict[str, str]:
    """Trích xuất động các hằng số golden SHA-256 từ test_generator.py bằng AST (không ghi cứng)."""
    if not TEST_GENERATOR_FILE.is_file():
        return {}
    try:
        content = TEST_GENERATOR_FILE.read_text(encoding="utf-8")
        tree = ast.parse(content)
        golden_hashes: Dict[str, str] = {}
        for node in ast.walk(tree):
            if isinstance(node, ast.Assign):
                for target in node.targets:
                    if isinstance(target, ast.Name) and target.id.startswith("GOLDEN_"):
                        if isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
                            golden_hashes[target.id] = node.value.value
        return golden_hashes
    except Exception as e:
        print(f"[CẢNH BÁO] Không thể đọc hằng số golden từ test_generator.py: {e}")
        return {}


def run_docker_compose_command(
    service_name: str,
    cmd_in_container: str,
    stdin_file: Optional[Path] = None,
    stdin_data: Optional[str] = None,
) -> Tuple[int, str, str]:
    """Thực thi câu lệnh trong container Postgres thông qua docker compose exec."""
    full_cmd = [
        "docker",
        "compose",
        "exec",
        "-T",
        service_name,
        "sh",
        "-c",
        cmd_in_container,
    ]

    try:
        if stdin_file is not None:
            with open(stdin_file, "rb") as f:
                res = subprocess.run(
                    full_cmd,
                    stdin=f,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    cwd=REPO_ROOT,
                )
        elif stdin_data is not None:
            res = subprocess.run(
                full_cmd,
                input=stdin_data.encode("utf-8"),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=REPO_ROOT,
            )
        else:
            res = subprocess.run(
                full_cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=REPO_ROOT,
            )

        stdout_str = res.stdout.decode("utf-8", errors="replace")
        stderr_str = res.stderr.decode("utf-8", errors="replace")
        return res.returncode, stdout_str, stderr_str

    except FileNotFoundError:
        raise SeedError(
            "Không tìm thấy công cụ lệnh 'docker' trên hệ thống.",
            "Vui lòng cài đặt Docker Desktop và đảm bảo lệnh 'docker' có trong biến môi trường PATH.",
        )
    except subprocess.SubprocessError as e:
        raise SeedError(
            f"Lỗi khi thực thi lệnh Docker: {e}",
            "Vui lòng kiểm tra Docker daemon đang chạy bình thường.",
        )


def check_docker_and_service(service_name: str) -> None:
    """Kiểm tra Docker Compose có khả dụng và service Postgres có đang chạy không."""
    try:
        res = subprocess.run(
            ["docker", "compose", "ps", "--services", "--filter", "status=running"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=REPO_ROOT,
        )
        if res.returncode != 0:
            stderr_msg = res.stderr.decode("utf-8", errors="replace").strip()
            raise SeedError(
                f"Docker Compose không phản hồi hoặc Docker daemon chưa khởi động.\nChi tiết: {stderr_msg}",
                "Vui lòng khởi động Docker Desktop và thử lại.",
            )

        running_services = res.stdout.decode("utf-8", errors="replace").splitlines()
        if service_name not in running_services:
            raise SeedError(
                f"Dịch vụ cơ sở dữ liệu '{service_name}' chưa chạy trong Docker Compose.",
                f"Vui lòng khởi động môi trường bằng lệnh: docker compose up -d {service_name}",
            )
    except FileNotFoundError:
        raise SeedError(
            "Không tìm thấy lệnh 'docker' trên hệ thống.",
            "Vui lòng cài đặt Docker Desktop và kiểm tra PATH.",
        )


def check_flyway_migration(service_name: str) -> None:
    """Kiểm tra điều kiện tiên quyết: bảng flyway_schema_history phải có đủ V1 - V4 thành công."""
    query = (
        "SELECT COUNT(*) FROM flyway_schema_history "
        "WHERE version IN ('1', '2', '3', '4') AND success = true;"
    )
    cmd = f'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -v ON_ERROR_STOP=1 -c "{query}"'
    code, out, err = run_docker_compose_command(service_name, cmd)

    if code != 0:
        raise SeedError(
            f"Không thể truy vấn bảng flyway_schema_history trên cơ sở dữ liệu.\nChi tiết: {err.strip()}",
            "Cơ sở dữ liệu có thể chưa được khởi tạo. Vui lòng chạy backend để Flyway tự động migrate: docker compose up -d backend",
        )

    count_str = out.strip()
    try:
        count_val = int(count_str)
    except ValueError:
        count_val = 0

    if count_val < 4:
        raise SeedError(
            f"Cơ sở dữ liệu chưa hoàn tất migration Flyway (chỉ có {count_val}/4 migration V1-V4 thành công).",
            "Vui lòng khởi động dịch vụ backend để Flyway hoàn tất các bước migration trước khi nạp dữ liệu: docker compose up -d backend",
        )

    print(f"  [OK] Đã hoàn tất đủ {count_val} migration Flyway (V1 - V4) hợp lệ.")


def execute_data_generator() -> None:
    """Kích hoạt script generate_data.py để sinh dữ liệu mẫu."""
    if not GENERATOR_SCRIPT.is_file():
        raise SeedError(
            f"Không tìm thấy script sinh dữ liệu: {GENERATOR_SCRIPT}",
            "Vui lòng kiểm tra lại cấu trúc thư mục repo.",
        )

    cmd = [sys.executable, str(GENERATOR_SCRIPT)]
    print(f"  Đang thực thi: {cmd[0]} {GENERATOR_SCRIPT.name} ...")
    res = subprocess.run(cmd, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    if res.returncode != 0:
        err_msg = res.stderr.decode("utf-8", errors="replace").strip()
        raise SeedError(
            f"Bộ sinh dữ liệu gặp lỗi khi thực thi (Mã thoát: {res.returncode}):\n{err_msg}",
            "Vui lòng kiểm tra lại lỗi logic trong generate_data.py hoặc cấu hình tham số trong config.py.",
        )

    out_msg = res.stdout.decode("utf-8", errors="replace").strip()
    # In tóm tắt kết quả
    lines = out_msg.splitlines()
    summary_lines = [l for l in lines if l.startswith("- ") or "Hoàn tất" in l]
    for sl in summary_lines:
        print(f"  {sl}")


def manage_sha256sums(check_golden: bool = False) -> None:
    """Tính mã băm SHA-256, so sánh với bản trước và ghi vào SHA256SUMS; kiểm tra golden nếu có yêu cầu."""
    if not SQL_FILE.is_file():
        raise SeedError(f"Không tìm thấy file SQL: {SQL_FILE.name}")
    if not JSON_FILE.is_file():
        raise SeedError(f"Không tìm thấy file JSON lịch stockout: {JSON_FILE.name}")

    sql_sha = calculate_file_sha256(SQL_FILE)
    json_sha = calculate_file_sha256(JSON_FILE)

    sql_size_mb = SQL_FILE.stat().st_size / (1024 * 1024)
    json_size_kb = JSON_FILE.stat().st_size / 1024

    print(f"  File SQL:  {SQL_FILE.name} ({sql_size_mb:.2f} MB)")
    print(f"    SHA-256: {sql_sha}")
    print(f"  File JSON: {JSON_FILE.name} ({json_size_kb:.2f} KB)")
    print(f"    SHA-256: {json_sha}")

    # Đọc bản cũ nếu có
    prev_sql_sha: Optional[str] = None
    prev_json_sha: Optional[str] = None
    if SHA256SUMS_FILE.is_file():
        for line in SHA256SUMS_FILE.read_text(encoding="utf-8").splitlines():
            parts = line.strip().split()
            if len(parts) >= 2:
                h, fn = parts[0], parts[1]
                if fn == SQL_FILE.name:
                    prev_sql_sha = h
                elif fn == JSON_FILE.name:
                    prev_json_sha = h

    if prev_sql_sha is not None:
        if prev_sql_sha == sql_sha:
            print("  -> So sánh với SHA256SUMS trước đó: KHỚP 100% (Tính xác định - Deterministic)")
        else:
            print("  -> So sánh với SHA256SUMS trước đó: KHÁC BIỆT")
            print(f"     Cũ: {prev_sql_sha}")
            print(f"     Mới: {sql_sha}")
    else:
        print("  -> Lần đầu tiên ghi nhận file SHA256SUMS.")

    # Ghi lại file SHA256SUMS mới
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    new_sums_content = f"{sql_sha}  {SQL_FILE.name}\n{json_sha}  {JSON_FILE.name}\n"
    SHA256SUMS_FILE.write_text(new_sums_content, encoding="utf-8")
    print(f"  [OK] Đã cập nhật: {SHA256SUMS_FILE.name}")

    # Kiểm tra với Golden Checksum nếu bật cờ --check-golden
    if check_golden:
        golden_hashes = extract_golden_hashes_from_test()
        golden_sql = golden_hashes.get("GOLDEN_SQL_SHA256")
        golden_json = golden_hashes.get("GOLDEN_JSON_SHA256")

        print("\n  --- ĐỐI CHIẾU VỚI MỐC VÀNG (GOLDEN SHA-256 TỪ test_generator.py) ---")
        if golden_sql:
            if golden_sql == sql_sha:
                print(f"  [GOLDEN MATCH] SQL SHA-256 trùng khớp mốc vàng: {golden_sql}")
            else:
                print(f"  [CẢNH BÁO] SQL SHA-256 KHÁC mốc vàng:\n    Hiện tại: {sql_sha}\n    Mốc vàng: {golden_sql}")
        if golden_json:
            if golden_json == json_sha:
                print(f"  [GOLDEN MATCH] JSON SHA-256 trùng khớp mốc vàng: {golden_json}")
            else:
                print(f"  [CẢNH BÁO] JSON SHA-256 KHÁC mốc vàng:\n    Hiện tại: {json_sha}\n    Mốc vàng: {golden_json}")


def prompt_confirm_if_orders_exist(service_name: str, auto_yes: bool) -> None:
    """Kiểm tra số dòng trong sales_order, cảnh báo và yêu cầu xác nhận xóa dữ liệu nếu có."""
    query = "SELECT COUNT(*) FROM sales_order;"
    cmd = f'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -v ON_ERROR_STOP=1 -c "{query}"'
    code, out, err = run_docker_compose_command(service_name, cmd)

    if code == 0:
        try:
            count = int(out.strip())
        except ValueError:
            count = 0

        if count > 0:
            print(f"\n  [CẢNH BÁO] Phát hiện {count:,} đơn hàng hiện có trong DB sắp bị xóa hoàn toàn (TRUNCATE).")
            if auto_yes:
                print("  -> Tự động xác nhận tiếp tục xóa do có cờ --yes.")
            else:
                try:
                    choice = input("  Bạn có chắc chắn muốn xóa và nạp lại dữ liệu? (y/N): ").strip().lower()
                except EOFError:
                    choice = "n"

                if choice != "y":
                    print("  Đã hủy tiến trình theo yêu cầu người dùng.")
                    sys.exit(0)


def load_sql_to_database(service_name: str) -> None:
    """Nạp file seed_data.sql vào Postgres thông qua stream nhị phân (open rb)."""
    if not SQL_FILE.is_file():
        raise SeedError(
            f"Không tìm thấy file SQL: {SQL_FILE}",
            "Vui lòng chạy bước sinh dữ liệu trước bằng cờ --generate-only.",
        )

    print(f"  Đang nạp file {SQL_FILE.name} vào database '{service_name}'...")
    print("  (Lệnh psql thực thi với -v ON_ERROR_STOP=1, quản lý giao dịch qua BEGIN/COMMIT của file)")

    start_time = time.perf_counter()
    # psql thực thi qua sh trong container, nhận input trực tiếp từ stdin nhị phân
    psql_cmd = 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'
    code, out, err = run_docker_compose_command(service_name, psql_cmd, stdin_file=SQL_FILE)
    elapsed = time.perf_counter() - start_time

    if code != 0:
        raise SeedError(
            f"Nạp dữ liệu SQL thất bại (Mã thoát: {code}).\nChi tiết lỗi từ psql:\n{err.strip()}",
            "Vui lòng kiểm tra lại cấu trúc bảng hoặc ràng buộc foreign key chưa khớp.",
        )

    print(f"  [OK] Nạp dữ liệu hoàn tất thành công trong {elapsed:.2f} giây.")


def run_verification_script(service_name: str) -> None:
    """Thực thi file verify_seed.sql, đọc cả stderr để hiển thị NOTICE và kiểm tra kết quả theo mã thoát."""
    if not VERIFY_SQL_FILE.is_file():
        raise SeedError(f"Không tìm thấy file kiểm đếm: {VERIFY_SQL_FILE.name}")

    print(f"  Đang chạy script kiểm đếm: {VERIFY_SQL_FILE.name} ...")
    psql_cmd = 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1'
    code, out, err = run_docker_compose_command(service_name, psql_cmd, stdin_file=VERIFY_SQL_FILE)

    # In thông tin stdout và stderr để hiển thị toàn bộ NOTICE thống kê soft
    full_output = (out + "\n" + err).strip()
    notice_lines = [l for l in full_output.splitlines() if "NOTICE:" in l or "ERROR:" in l or "KẾT LUẬN" in l or "PASS" in l]

    if notice_lines:
        for nl in notice_lines:
            # Loại bỏ tiền tố psql mặc định nếu có để dễ đọc
            clean_l = nl.replace("psql:<stdin>:1: ", "").replace("psql:<stdin>:2: ", "")
            print(f"  {clean_l}")
    else:
        print(f"  {full_output}")

    if code != 0:
        raise SeedError(
            f"Kiểm đếm AC-01 THẤT BẠI (Mã thoát: {code}).\nLỗi chi tiết từ DB:\n{err.strip()}",
            "Vui lòng rà soát lại các dòng dữ liệu không đạt tiêu chuẩn được nêu trong thông báo lỗi ở trên.",
        )

    print("\n  [PASS] Xác minh tự động: TẤT CẢ CÁC TIÊU CHÍ CỨNG AC-01 ĐẠT 100%!")


def print_database_fingerprint(service_name: str) -> None:
    """Tính toán và in bảng mã vân tay (fingerprint MD5) của từng bảng đã seed."""
    print("\n" + "=" * 78)
    print("BẢNG MÃ VÂN TAY DỮ LIỆU CƠ SỞ DỮ LIỆU (DATABASE FINGERPRINT)")
    print("=" * 78)
    print(f"{'Tên bảng (Table)':<26} | {'Số dòng (Rows)':<14} | {'Mã vân tay MD5 (Fingerprint)':<32}")
    print("-" * 26 + "-+-" + "-" * 14 + "-+-" + "-" * 32)

    for tbl in SEEDED_TABLES:
        # Cú pháp t::text trong PostgreSQL chuyển đổi toàn bộ hàng thành một chuỗi tuple đại diện
        query = (
            f"SELECT count(*)::text || '|' || "
            f"COALESCE(md5(string_agg(t::text, '|' ORDER BY t::text)), 'empty') "
            f"FROM {tbl} t;"
        )
        cmd = f'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -v ON_ERROR_STOP=1 -c "{query}"'
        code, out, err = run_docker_compose_command(service_name, cmd)

        if code == 0 and "|" in out:
            cnt_str, md5_str = out.strip().split("|", 1)
            try:
                cnt_int = int(cnt_str)
                cnt_formatted = f"{cnt_int:,}"
            except ValueError:
                cnt_formatted = cnt_str
            print(f"{tbl:<26} | {cnt_formatted:>14} | {md5_str:<32}")
        else:
            print(f"{tbl:<26} | {'LỖI':>14} | {err.strip()[:32]}")

    print("=" * 78)


def parse_arguments() -> argparse.Namespace:
    """Xây dựng và phân tích các tham số dòng lệnh CLI."""
    parser = argparse.ArgumentParser(
        description="Bộ điều phối nạp dữ liệu một lệnh (Seed Orchestrator) cho GreenMart DT03."
    )
    parser.add_argument(
        "--generate-only",
        action="store_true",
        help="Chỉ sinh dữ liệu mẫu ra file SQL + JSON và cập nhật SHA256SUMS, không nạp DB.",
    )
    parser.add_argument(
        "--load-only",
        action="store_true",
        help="Bỏ qua bước sinh dữ liệu, nạp ngay file SQL có sẵn vào DB và kiểm đếm.",
    )
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="Chỉ chạy script kiểm đếm AC-01 và in mã vân tay DB hiện tại, không sinh/nạp.",
    )
    parser.add_argument(
        "--check-golden",
        action="store_true",
        help="Đối chiếu mã SHA-256 sinh ra với mốc vàng (Golden Checksum) từ test_generator.py.",
    )
    parser.add_argument(
        "-y",
        "--yes",
        action="store_true",
        help="Tự động đồng ý xóa dữ liệu cũ mà không hỏi xác nhận tương tác.",
    )
    parser.add_argument(
        "--skip-verify",
        action="store_true",
        help="Bỏ qua bước kiểm đếm sau khi nạp dữ liệu SQL.",
    )
    parser.add_argument(
        "--db-service",
        default="postgres",
        help="Tên dịch vụ container Postgres trong Docker Compose (Mặc định: 'postgres').",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Hiển thị chi tiết dấu vết lỗi (stack trace) khi xảy ra sự cố ngoại lệ.",
    )
    return parser.parse_args()


def main() -> None:
    """Hàm điều phối trung tâm của toàn bộ tiến trình seed."""
    args = parse_arguments()

    print("=" * 70)
    print("BỘ ĐIỀU PHỐI SEED DỮ LIỆU GREENMART (Issue #108 - AC-01)")
    print("=" * 70)

    try:
        # 1. Chế độ CHỈ XÁC MINH (--verify-only)
        if args.verify_only:
            log_step(1, 3, "Kiểm tra kết nối Docker & dịch vụ PostgreSQL")
            check_docker_and_service(args.db_service)

            log_step(2, 3, "Thực thi kiểm đếm AC-01 (verify_seed.sql)")
            run_verification_script(args.db_service)

            log_step(3, 3, "Xuất mã vân tay cơ sở dữ liệu (Database Fingerprint)")
            print_database_fingerprint(args.db_service)
            print("\nHoàn tất kiểm đếm thành công!")
            return

        # 2. Chế độ CHỈ SINH DỮ LIỆU (--generate-only)
        if args.generate_only:
            log_step(1, 2, "Sinh dữ liệu mẫu (generate_data.py)")
            execute_data_generator()

            log_step(2, 2, "Cập nhật và đối chiếu SHA256SUMS")
            manage_sha256sums(check_golden=args.check_golden)

            print("\nHoàn tất sinh dữ liệu thành công! (Không nạp cơ sở dữ liệu theo cờ --generate-only)")
            return

        # 3. QUY TRÌNH NẠP ĐẦY ĐỦ (Hoặc --load-only)
        total_steps = 6 if not args.load_only else 5
        cur_step = 1

        # Bước 1: Kiểm tra Docker và Service
        log_step(cur_step, total_steps, "Kiểm tra kết nối Docker & dịch vụ PostgreSQL")
        cur_step += 1
        check_docker_and_service(args.db_service)

        # Bước 2: Kiểm tra Flyway migration
        log_step(cur_step, total_steps, "Kiểm tra điều kiện tiên quyết Flyway (V1 - V4)")
        cur_step += 1
        check_flyway_migration(args.db_service)

        # Bước 3: Sinh dữ liệu (nếu không có cờ --load-only)
        if not args.load_only:
            log_step(cur_step, total_steps, "Sinh dữ liệu mẫu (generate_data.py)")
            cur_step += 1
            execute_data_generator()

            log_step(cur_step, total_steps, "Cập nhật và đối chiếu SHA256SUMS")
            cur_step += 1
            manage_sha256sums(check_golden=args.check_golden)
        else:
            print("\n[BỎ QUA] Bỏ qua bước sinh dữ liệu theo cờ --load-only.")

        # Bước 4: Cảnh báo xác nhận xóa dữ liệu cũ
        prompt_confirm_if_orders_exist(args.db_service, args.yes)

        # Bước 5: Nạp SQL vào cơ sở dữ liệu
        log_step(cur_step, total_steps, "Nạp dữ liệu SQL vào PostgreSQL")
        cur_step += 1
        load_sql_to_database(args.db_service)

        # Bước 6: Kiểm đếm và in Fingerprint
        if not args.skip_verify:
            log_step(cur_step, total_steps, "Kiểm đếm AC-01 & xuất mã vân tay (Fingerprint)")
            run_verification_script(args.db_service)
            print_database_fingerprint(args.db_service)
        else:
            print("\n[BỎ QUA] Bỏ qua bước kiểm đếm theo cờ --skip-verify.")

        print("\n" + "=" * 70)
        print("HOÀN TẤT TIẾN TRÌNH SEED DỮ LIỆU GREENMART THÀNH CÔNG!")
        print("=" * 70)

    except SeedError as e:
        print(f"\n[LỖI] {e.message}")
        if e.suggestion:
            print(f"[GỢI Ý KHẮC PHỤC] {e.suggestion}")
        if args.debug:
            import traceback
            traceback.print_exc()
        sys.exit(1)
    except KeyboardInterrupt:
        print("\n\nTiến trình bị hủy bởi người dùng.")
        sys.exit(130)
    except Exception as e:
        print(f"\n[LỖI HỆ THỐNG] Đã xảy ra lỗi ngoài dự kiến: {e}")
        if args.debug:
            import traceback
            traceback.print_exc()
        else:
            print("[GỢI Ý] Sử dụng cờ --debug để xem chi tiết dấu vết lỗi.")
        sys.exit(1)


if __name__ == "__main__":
    main()
