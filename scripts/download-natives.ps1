<#
.SYNOPSIS
	whisper.cpp の公式 Windows バイナリ (x64) を natives\ へ取得する。

.DESCRIPTION
	GitHub Releases の whisper-bin-x64.zip から whisper.dll と ggml*.dll を取り出して
	natives\ に置く。ここに置いたものを gradlew installNatives が jar へ同梱する。

	このライブラリはネイティブを自前でビルドしない。公式配布物をそのまま使うため、
	C++ のツールチェーンは要らない。

	注意: リリースによっては Windows バイナリが添付されていないことがある
	（v1.9.3 がそうだった）。その場合はメッセージの指示に従って
	scripts\build-whisper.ps1 で whisper.cpp 本家の CMake からビルドすること。

	失敗の伝え方: 呼び出し側が「取得できなければビルドに切り替える」と分岐できるよう、
	終了エラーを投げずに exit 1 で失敗を伝える。$ErrorActionPreference = 'Stop' の下で
	Write-Error を使うと終了エラーになり、呼び出し側のスクリプトごとその場で止まってしまう
	（CI がこれで落ちていた）。メッセージは $host.UI.WriteErrorLine で stderr へ書く。

.PARAMETER Tag
	whisper.cpp のリリースタグ。既定は gradle.properties の whisperCppVersion に v を付けたもの。

.PARAMETER Variant
	取得する配布物。cpu（既定）/ blas / cublas-12。

.EXAMPLE
	.\scripts\download-natives.ps1
	.\scripts\download-natives.ps1 -Tag v1.9.2
#>
[CmdletBinding()]
param(
	[string] $Tag,
	[ValidateSet("cpu", "blas", "cublas-12")]
	[string] $Variant = "cpu"
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot "..")

if(-not $Tag)
{
	$line = Select-String -Path "gradle.properties" -Pattern '^whisperCppVersion=(.+)$'
	if(-not $line)
	{
		$host.UI.WriteErrorLine("gradle.properties に whisperCppVersion がありません。-Tag で明示してください。")
		exit 1
	}
	$Tag = "v" + $line.Matches[0].Groups[1].Value.Trim()
}

$assetName = switch($Variant)
{
	"cpu"       { "whisper-bin-x64.zip" }
	"blas"      { "whisper-blas-bin-x64.zip" }
	"cublas-12" { "whisper-cublas-12.4.0-bin-x64.zip" }
}

$url = "https://github.com/ggml-org/whisper.cpp/releases/download/$Tag/$assetName"
$outputDirectory = "natives"
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("whisper-natives-" + [System.Guid]::NewGuid().ToString("N"))

Write-Host "取得元: $url"

New-Item -ItemType Directory -Force -Path $temporary | Out-Null
$archive = Join-Path $temporary $assetName
try
{
	Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing
}
catch
{
	Remove-Item -Recurse -Force $temporary -ErrorAction SilentlyContinue
	$host.UI.WriteErrorLine(@"
$assetName を $Tag から取得できませんでした。

リリースに Windows バイナリが添付されていない可能性があります
（https://github.com/ggml-org/whisper.cpp/releases で確認してください）。

対処:
  1. バイナリが付いている別のタグを使う     .\scripts\download-natives.ps1 -Tag v1.9.2
  2. whisper.cpp 本家の CMake からビルドする .\scripts\build-whisper.ps1

なお 2 を選ぶ場合、submodule (src/main/native/whisper) が指すバージョンと
jextract の生成物が一致するので、そちらの方が安全です。
"@)
	exit 1
}

Expand-Archive -Path $archive -DestinationPath $temporary -Force

$libraries = Get-ChildItem -Path $temporary -Recurse -File -Include "whisper.dll", "ggml*.dll"
if($libraries.Count -eq 0)
{
	Remove-Item -Recurse -Force $temporary -ErrorAction SilentlyContinue
	$host.UI.WriteErrorLine("$assetName に whisper.dll / ggml*.dll が入っていません。")
	exit 1
}

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$libraries | ForEach-Object { Copy-Item $_.FullName -Destination $outputDirectory -Force }
Remove-Item -Recurse -Force $temporary -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "$outputDirectory に配置しました:"
Get-ChildItem -Path $outputDirectory -File | ForEach-Object { Write-Host ("  {0} ({1:N0} bytes)" -f $_.Name, $_.Length) }
