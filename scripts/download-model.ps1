param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Name
)

# scripts\ 配下のスクリプトはプロジェクトルートからの相対パスを前提とするため、
# どこから呼ばれてもルートへ移動する。
Set-Location (Join-Path $PSScriptRoot "..")

# 使い方: .\scripts\download-model.ps1 <モデル名>
#   例: .\scripts\download-model.ps1 large-v3-turbo-q5_0
#       .\scripts\download-model.ps1 small-q5_1
# モデル名の一覧は src\main\native\whisper\models\download-ggml-model.cmd を参照。
# 取得したモデルは .\models\ggml-<モデル名>.bin に置く（ggml-*.bin は .gitignore 済み）。

$modelsDir = Join-Path (Get-Location) "models"
$target = Join-Path $modelsDir "ggml-$Name.bin"
if (Test-Path $target) {
    Write-Host "[OK] $target は既にあります"
    exit 0
}
New-Item -ItemType Directory -Force $modelsDir | Out-Null

# whisper.cpp のスクリプトは第 2 引数で保存先ディレクトリを指定できる
.\src\main\native\whisper\models\download-ggml-model.cmd $Name $modelsDir
if ($LASTEXITCODE -ne 0) {
    Write-Error "ダウンロードに失敗しました（終了コード $LASTEXITCODE）"
    exit 1
}
if (-not (Test-Path $target)) {
    Write-Error "$target が見つかりません。ダウンロードに失敗している可能性があります。"
    exit 1
}
$mb = [math]::Round((Get-Item $target).Length / 1MB)
Write-Host "[OK] $target ($mb MB)"
