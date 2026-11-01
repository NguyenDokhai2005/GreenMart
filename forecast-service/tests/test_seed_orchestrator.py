"""Unit tests cho bộ điều phối seed.py (Issue #108).

Các bài kiểm tra chạy 100% in-memory hoặc trên thư mục tạm thời,
hoàn toàn KHÔNG phụ thuộc vào Docker daemon và KHÔNG ghi file vào repository.
"""

from __future__ import annotations

import io
from pathlib import Path
import sys
import tempfile
from unittest import TestCase, mock

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))
SCRIPTS_DIR = PROJECT_ROOT / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from scripts import seed


class TestSeedCLIArguments(TestCase):
    """Kiểm tra việc phân tích cú pháp tham số dòng lệnh CLI."""

    def test_default_arguments(self):
        """Kiểm tra các giá trị mặc định của tham số dòng lệnh."""
        args = seed.parse_arguments([])
        self.assertFalse(args.generate_only)
        self.assertFalse(args.load_only)
        self.assertFalse(args.verify_only)
        self.assertFalse(args.check_golden)
        self.assertFalse(args.yes)
        self.assertFalse(args.skip_verify)
        self.assertEqual(args.db_service, "postgres")
        self.assertFalse(args.debug)

    def test_individual_flags(self):
        """Kiểm tra việc bật từng cờ tham số riêng biệt."""
        args_gen = seed.parse_arguments(["--generate-only", "--check-golden"])
        self.assertTrue(args_gen.generate_only)
        self.assertTrue(args_gen.check_golden)

        args_load = seed.parse_arguments(["--load-only", "-y", "--db-service", "custom_db"])
        self.assertTrue(args_load.load_only)
        self.assertTrue(args_load.yes)
        self.assertEqual(args_load.db_service, "custom_db")

        args_verify = seed.parse_arguments(["--verify-only", "--debug"])
        self.assertTrue(args_verify.verify_only)
        self.assertTrue(args_verify.debug)

    def test_conflicting_mode_flags(self):
        """Kiểm tra phát hiện và báo lỗi khi các cờ chế độ thực thi bị xung đột."""
        conflicting_cases = [
            ["--generate-only", "--load-only"],
            ["--generate-only", "--verify-only"],
            ["--load-only", "--verify-only"],
            ["--generate-only", "--load-only", "--verify-only"],
        ]
        for flags in conflicting_cases:
            with self.subTest(flags=flags):
                with mock.patch("sys.stderr", new_callable=io.StringIO):
                    with self.assertRaises(SystemExit) as cm:
                        seed.parse_arguments(flags)
                    self.assertNotEqual(cm.exception.code, 0)


class TestGoldenHashExtraction(TestCase):
    """Kiểm tra việc trích xuất hằng số mốc vàng (Golden Checksum) bằng AST."""

    def test_extract_golden_hashes_from_test_file(self):
        """Hàm extract_golden_hashes_from_test phải đọc được 2 hằng số SHA-256 từ test_generator.py."""
        hashes = seed.extract_golden_hashes_from_test()
        self.assertIn("GOLDEN_SQL_SHA256", hashes)
        self.assertIn("GOLDEN_JSON_SHA256", hashes)

        sql_sha = hashes["GOLDEN_SQL_SHA256"]
        json_sha = hashes["GOLDEN_JSON_SHA256"]

        self.assertEqual(len(sql_sha), 64, "Mã SHA-256 phải có đúng 64 ký tự hex")
        self.assertEqual(len(json_sha), 64, "Mã SHA-256 phải có đúng 64 ký tự hex")
        self.assertTrue(all(c in "0123456789abcdef" for c in sql_sha))
        self.assertTrue(all(c in "0123456789abcdef" for c in json_sha))


class TestManageSha256Sums(TestCase):
    """Kiểm tra việc tính toán, ghi nhận và đối chiếu SHA256SUMS trong thư mục tạm."""

    def test_manage_sha256sums_lifecycle(self):
        """Kiểm tra toàn bộ chu trình: lần đầu ghi nhận -> lần 2 khớp -> lần 3 phát hiện khác."""
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_path = Path(tmp_dir)
            fake_sql = tmp_path / "seed_data.sql"
            fake_json = tmp_path / "stockout_schedule.json"
            fake_sums = tmp_path / "SHA256SUMS"

            fake_sql.write_text("SELECT 1;\n", encoding="utf-8")
            fake_json.write_text("[]\n", encoding="utf-8")

            with mock.patch.object(seed, "SQL_FILE", fake_sql), \
                 mock.patch.object(seed, "JSON_FILE", fake_json), \
                 mock.patch.object(seed, "SHA256SUMS_FILE", fake_sums), \
                 mock.patch.object(seed, "DATA_DIR", tmp_path):

                # 1. Lần đầu ghi nhận
                out_buf = io.StringIO()
                with mock.patch("sys.stdout", out_buf):
                    seed.manage_sha256sums(check_golden=False)

                out1 = out_buf.getvalue()
                self.assertIn("Lần đầu tiên ghi nhận", out1)
                self.assertTrue(fake_sums.is_file())

                sums_content_1 = fake_sums.read_text(encoding="utf-8")
                self.assertIn("seed_data.sql", sums_content_1)
                self.assertIn("stockout_schedule.json", sums_content_1)

                # 2. Lần thứ hai không đổi nội dung -> KHỚP 100%
                out_buf2 = io.StringIO()
                with mock.patch("sys.stdout", out_buf2):
                    seed.manage_sha256sums(check_golden=False)

                out2 = out_buf2.getvalue()
                self.assertIn("KHỚP 100%", out2)

                # 3. Lần thứ ba thay đổi nội dung file -> KHÁC BIỆT
                fake_sql.write_text("SELECT 2;\n", encoding="utf-8")
                out_buf3 = io.StringIO()
                with mock.patch("sys.stdout", out_buf3):
                    seed.manage_sha256sums(check_golden=False)

                out3 = out_buf3.getvalue()
                self.assertIn("KHÁC BIỆT", out3)


class TestMockedExecutionFlow(TestCase):
    """Kiểm tra luồng xử lý và ngoại lệ thông qua mock subprocess."""

    def test_docker_not_running_raises_seed_error(self):
        """Khi Docker chưa bật, check_docker_and_service phải ném SeedError với thông báo tiếng Việt."""
        fake_res = mock.Mock()
        fake_res.returncode = 1
        fake_res.stderr = b"Cannot connect to the Docker daemon"

        with mock.patch("subprocess.run", return_value=fake_res):
            with self.assertRaises(seed.SeedError) as cm:
                seed.check_docker_and_service("postgres")
            self.assertIn("Docker Compose không phản hồi", cm.exception.message)
            self.assertIsNotNone(cm.exception.suggestion)

    def test_flyway_missing_migration_raises_seed_error(self):
        """Khi bảng flyway_schema_history chưa đủ 4 migration, check_flyway_migration phải ném SeedError."""
        # Mock run_docker_compose_command trả về count = 2
        with mock.patch.object(seed, "run_docker_compose_command", return_value=(0, "2\n", "")):
            with self.assertRaises(seed.SeedError) as cm:
                seed.check_flyway_migration("postgres")
            self.assertIn("chưa hoàn tất migration Flyway", cm.exception.message)
            self.assertIn("chỉ có 2/4", cm.exception.message)

    def test_confirm_orders_aborted_when_user_declines(self):
        """Khi có đơn hàng cũ và người dùng nhập 'n', tiến trình phải thoát 0 và dừng lại an toàn."""
        # Mock sales_order count = 500
        with mock.patch.object(seed, "run_docker_compose_command", return_value=(0, "500\n", "")):
            with mock.patch("builtins.input", return_value="n"):
                with mock.patch("sys.stdout", new_callable=io.StringIO) as out_mock:
                    with self.assertRaises(SystemExit) as cm:
                        seed.prompt_confirm_if_orders_exist("postgres", auto_yes=False)
                    self.assertEqual(cm.exception.code, 0)
                    self.assertIn("Đã hủy tiến trình", out_mock.getvalue())

    def test_confirm_orders_proceeds_with_yes_flag(self):
        """Khi có cờ --yes, tiến trình tự động xác nhận xóa mà không cần gọi input()."""
        with mock.patch.object(seed, "run_docker_compose_command", return_value=(0, "500\n", "")):
            with mock.patch("builtins.input") as input_mock:
                with mock.patch("sys.stdout", new_callable=io.StringIO) as out_mock:
                    # Không raise SystemExit
                    seed.prompt_confirm_if_orders_exist("postgres", auto_yes=True)
                    input_mock.assert_not_called()
                    self.assertIn("Tự động xác nhận tiếp tục xóa do có cờ --yes", out_mock.getvalue())

    def test_verify_script_failure_raises_seed_error(self):
        """Khi verify_seed.sql trả mã lỗi khác 0, run_verification_script phải ném SeedError."""
        with tempfile.NamedTemporaryFile(suffix=".sql") as tmp_sql:
            tmp_sql_path = Path(tmp_sql.name)
            with mock.patch.object(seed, "VERIFY_SQL_FILE", tmp_sql_path):
                err_output = "psql: ERROR: THẤT BẠI [TC-AC01-01]: Số chi nhánh hoạt động = 2, kỳ vọng = 3\n"
                with mock.patch.object(seed, "run_docker_compose_command", return_value=(1, "", err_output)):
                    with self.assertRaises(seed.SeedError) as cm:
                        seed.run_verification_script("postgres")
                    self.assertIn("Kiểm đếm AC-01 THẤT BẠI", cm.exception.message)


class TestErrorFormattingAndTraceback(TestCase):
    """Kiểm tra định dạng thông báo lỗi và cờ --debug."""

    def test_error_output_without_debug(self):
        """Khi không có --debug, thông báo lỗi phải in [LỖI] và [GỢI Ý KHẮC PHỤC], không có traceback."""
        with mock.patch("scripts.seed.parse_arguments") as mock_args:
            mock_args.return_value = mock.Mock(
                verify_only=False, generate_only=False, load_only=False,
                db_service="postgres", debug=False
            )
            with mock.patch.object(seed, "check_docker_and_service", side_effect=seed.SeedError("Lỗi kết nối", "Bật Docker")):
                out_buf = io.StringIO()
                with mock.patch("sys.stdout", out_buf):
                    with self.assertRaises(SystemExit) as cm:
                        seed.main()
                    self.assertEqual(cm.exception.code, 1)

                output = out_buf.getvalue()
                self.assertIn("[LỖI] Lỗi kết nối", output)
                self.assertIn("[GỢI Ý KHẮC PHỤC] Bật Docker", output)
                self.assertNotIn("Traceback (most recent call last):", output)


if __name__ == "__main__":
    import unittest
    unittest.main()
