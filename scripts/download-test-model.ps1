# scripts\ 配下のスクリプトはプロジェクトルートからの相対パスを前提とするため、
# どこから呼ばれてもルートへ移動する。
Set-Location (Join-Path $PSScriptRoot "..")

$env:MODEL_NAME = 'tiny'
.\src\main\native\whisper\models\download-ggml-model.cmd $env:MODEL_NAME

# whisper.cpp の download-ggml-model.cmd はカレントディレクトリに保存するため、
# models\ 配下に出力された場合だけプロジェクトルートへ移動する。
$fromModels = ".\src\main\native\whisper\models\ggml-$($env:MODEL_NAME).bin"
if (Test-Path $fromModels) {
    Move-Item -Path $fromModels -Destination .\ -Force
}

if (-not (Test-Path ".\ggml-$($env:MODEL_NAME).bin")) {
    Write-Error "ggml-$($env:MODEL_NAME).bin が見つかりません。ダウンロードに失敗しています。"
    exit 1
}
Write-Host "[OK] .\ggml-$($env:MODEL_NAME).bin"
