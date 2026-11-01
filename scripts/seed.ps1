<#
.SYNOPSIS
    Wrapper chạy bộ điều phối seed GreenMart trên Windows PowerShell (Issue #108).
.DESCRIPTION
    Tìm kiếm Python và chuyển tiếp toàn bộ tham số vào forecast-service/scripts/seed.py.
    Trả về đúng mã thoát (exit code) của seed.py.
#>
$ErrorActionPreference = "Stop"

# Xác định thư mục gốc của repository
$RepoRoot = Split-Path -Parent $PSScriptRoot

# Tìm kiếm lệnh python khả dụng
$PythonCmd = $null
if (Get-Command python -ErrorAction SilentlyContinue) {
    $PythonCmd = "python"
} elseif (Get-Command py -ErrorAction SilentlyContinue) {
    $PythonCmd = "py -3"
} else {
    Write-Error "[LỖI] Không tìm thấy Python trên hệ thống. Vui lòng cài đặt Python 3.11+."
    exit 1
}

# Chuyển tiếp toàn bộ tham số và trả về mã thoát chính xác
if ($PythonCmd -eq "py -3") {
    & py -3 "$RepoRoot\forecast-service\scripts\seed.py" @args
} else {
    & python "$RepoRoot\forecast-service\scripts\seed.py" @args
}

exit $LASTEXITCODE
