<#
.SYNOPSIS
	submodule の whisper.cpp を本家の CMake でビルドし、natives\ へ置く。

.DESCRIPTION
	公式リリースに Windows バイナリが添付されていないバージョンを使うときの手段。
	whisper.cpp 本家のビルド手順（BUILD_SHARED_LIBS=ON）をそのまま呼ぶだけで、
	このリポジトリ独自のビルド定義は持たない。

	必要なもの: CMake 3.21 以上と MSVC（Visual Studio の C++ ワークロード）。

	通常は scripts\download-natives.ps1 で公式バイナリを取る方が速い。

.PARAMETER Configuration
	CMake のビルド構成。既定は Release。

.EXAMPLE
	.\scripts\build-whisper.ps1
#>
[CmdletBinding()]
param(
	[string] $Configuration = "Release"
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot "..")

$source = "src\main\native\whisper"
$buildDirectory = "build\whisper-cpp"
$outputDirectory = "natives"

if(-not (Test-Path (Join-Path $source "CMakeLists.txt")))
{
	Write-Error "whisper.cpp の submodule がありません: $source`ngit submodule update --init --recursive を実行してください。"
	exit 1
}

cmake -B $buildDirectory -S $source -DBUILD_SHARED_LIBS=ON -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=OFF
if($LASTEXITCODE -ne 0) { Write-Error "cmake の構成に失敗しました (exit $LASTEXITCODE)"; exit $LASTEXITCODE }

cmake --build $buildDirectory --config $Configuration
if($LASTEXITCODE -ne 0) { Write-Error "cmake のビルドに失敗しました (exit $LASTEXITCODE)"; exit $LASTEXITCODE }

$libraries = Get-ChildItem -Path $buildDirectory -Recurse -File -Include "whisper.dll", "ggml*.dll"
if($libraries.Count -eq 0)
{
	Write-Error "ビルド結果に whisper.dll / ggml*.dll がありません: $buildDirectory"
	exit 1
}

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$libraries | ForEach-Object { Copy-Item $_.FullName -Destination $outputDirectory -Force }

Write-Host ""
Write-Host "$outputDirectory に配置しました:"
Get-ChildItem -Path $outputDirectory -File | ForEach-Object { Write-Host ("  {0} ({1:N0} bytes)" -f $_.Name, $_.Length) }
