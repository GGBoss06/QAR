$ErrorActionPreference = "Stop"

$projectDirectory = Join-Path $PSScriptRoot "securitysystem\securitysystem"
if (-not (Test-Path -LiteralPath (Join-Path $projectDirectory "mvnw.cmd"))) {
    throw "未找到 Maven Wrapper，请确认脚本位于 QAR 项目根目录。"
}

if ([string]::IsNullOrWhiteSpace($env:APP_DB_USERNAME)) {
    $env:APP_DB_USERNAME = "root"
}

$passwordWasPrompted = [string]::IsNullOrWhiteSpace($env:APP_DB_PASSWORD)
if ($passwordWasPrompted) {
    $securePassword = Read-Host "请输入 MySQL 用户 $($env:APP_DB_USERNAME) 的密码" -AsSecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try {
        $env:APP_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
}

$adminPasswordWasPrompted = [string]::IsNullOrWhiteSpace($env:APP_ADMIN_PASSWORD)
if ($adminPasswordWasPrompted) {
    $secureAdminPassword = Read-Host "请输入初始管理员密码（数据库中已有管理员时可直接回车）" -AsSecureString
    $adminPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureAdminPassword)
    try {
        $candidateAdminPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($adminPasswordPointer)
        if (-not [string]::IsNullOrWhiteSpace($candidateAdminPassword)) {
            $env:APP_ADMIN_PASSWORD = $candidateAdminPassword
        }
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($adminPasswordPointer)
    }
}

Push-Location $projectDirectory
try {
    & .\mvnw.cmd spring-boot:run
    exit $LASTEXITCODE
}
finally {
    Pop-Location
    if ($passwordWasPrompted) {
        Remove-Item Env:APP_DB_PASSWORD -ErrorAction SilentlyContinue
    }
    if ($adminPasswordWasPrompted) {
        Remove-Item Env:APP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    }
}
