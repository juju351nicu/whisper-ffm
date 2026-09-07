<#
.SYNOPSIS
	whisper.h から FFM（Panama）用のバインディングを生成する。

.DESCRIPTION
	jextract で src/main/native/whisper/include/whisper.h を読み、
	jp.clip.whisper.ffm.gen パッケージのソースを src/main/java 配下へ出力する。

	生成物はコミット対象。利用側の PC に jextract を要求しないため、
	whisper.cpp の submodule を更新したときだけこのスクリプトを実行し直す。

	ggml の関数は数百あるので、--include-* で必要なシンボルだけに絞っている。
	候補を洗い出したいときは -DumpIncludes を付けて実行する
	（build/jextract-includes.txt に --include-* の一覧が出る）。

.PARAMETER JextractHome
	jextract の展開先。既定は環境変数 JEXTRACT_HOME、無ければ C:\tools\jextract。

.PARAMETER DumpIncludes
	バインディングを生成せず、指定可能な --include-* の一覧だけを出力する。

.EXAMPLE
	.\scripts\jextract-whisper.ps1
	.\scripts\jextract-whisper.ps1 -DumpIncludes
#>
[CmdletBinding()]
param(
	[string] $JextractHome = $(if($env:JEXTRACT_HOME) { $env:JEXTRACT_HOME } else { "C:\tools\jextract" }),
	[switch] $DumpIncludes
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot "..")

$jextract = Join-Path $JextractHome "bin\jextract.bat"
if(-not (Test-Path $jextract))
{
	Write-Error "jextract が見つかりません: $jextract`nhttps://jdk.java.net/jextract/ から取得して $JextractHome へ展開するか、-JextractHome で場所を指定してください。"
	exit 1
}

$header       = "src\main\native\whisper\include\whisper.h"
$whisperInclude = "src\main\native\whisper\include"
$ggmlInclude    = "src\main\native\whisper\ggml\include"
$outputRoot   = "src\main\java"
$targetPackage = "jp.clip.whisper.ffm.gen"
$generatedDir = "src\main\java\jp\clip\whisper\ffm\gen"

if(-not (Test-Path $header))
{
	Write-Error "whisper.h が見つかりません: $header`ngit submodule update --init --recursive を実行してください。"
	exit 1
}

Write-Host "jextract : $jextract"
& $jextract --version
if($LASTEXITCODE -ne 0) { Write-Error "jextract --version が失敗しました (exit $LASTEXITCODE)"; exit $LASTEXITCODE }

if($DumpIncludes)
{
	$dumpFile = "build\jextract-includes.txt"
	New-Item -ItemType Directory -Force -Path "build" | Out-Null
	& $jextract --dump-includes $dumpFile -I $whisperInclude -I $ggmlInclude $header
	if($LASTEXITCODE -ne 0) { Write-Error "jextract --dump-includes が失敗しました (exit $LASTEXITCODE)"; exit $LASTEXITCODE }
	Write-Host "候補を出力しました: $dumpFile"
	exit 0
}

# --- 生成対象のシンボル ---------------------------------------------------
# whisper.h のうち jp.clip.whisper が使うものだけ。ggml からは ggml_log_callback と
# ggml_abort_callback（whisper_full_params のフィールド型）のみ。

$functions = @(
	# 初期化と解放
	"whisper_init_from_file_with_params",
	"whisper_init_from_file_with_params_no_state",
	"whisper_init_state",
	"whisper_free",
	"whisper_free_state",
	"whisper_free_params",
	"whisper_free_context_params",
	# パラメータの既定値。whisper_full / whisper_init_* は構造体を「値渡し」で受けるので、
	# ポインタを返す _by_ref 版ではなく値返し版（Java 側は SegmentAllocator 引数になる）を使う
	"whisper_context_default_params",
	"whisper_context_default_params_by_ref",
	"whisper_full_default_params",
	"whisper_full_default_params_by_ref",
	# 推論
	"whisper_full",
	"whisper_full_with_state",
	# セグメント
	"whisper_full_n_segments",
	"whisper_full_n_segments_from_state",
	"whisper_full_get_segment_t0",
	"whisper_full_get_segment_t1",
	"whisper_full_get_segment_text",
	"whisper_full_get_segment_t0_from_state",
	"whisper_full_get_segment_t1_from_state",
	"whisper_full_get_segment_text_from_state",
	# トークン（WhisperToken 相当）
	"whisper_full_n_tokens",
	"whisper_full_n_tokens_from_state",
	"whisper_full_get_token_data",
	"whisper_full_get_token_data_from_state",
	"whisper_full_get_token_text",
	"whisper_full_get_token_text_from_state",
	# 言語
	"whisper_full_lang_id",
	"whisper_full_lang_id_from_state",
	"whisper_lang_id",
	"whisper_lang_str",
	"whisper_lang_max_id",
	"whisper_is_multilingual",
	# その他
	"whisper_print_system_info",
	"whisper_ctx_init_openvino_encoder",
	"whisper_log_set"
)

$structs = @(
	"whisper_context_params",
	"whisper_full_params",
	"whisper_vad_params",
	"whisper_token_data",
	"whisper_grammar_element",
	"whisper_ahead",
	"whisper_aheads"
)

$typedefs = @(
	"whisper_token",
	"whisper_new_segment_callback",
	"whisper_progress_callback",
	"whisper_encoder_begin_callback",
	"whisper_logits_filter_callback",
	"ggml_abort_callback",
	"ggml_log_callback"
)

$constants = @(
	"WHISPER_SAMPLE_RATE",
	"WHISPER_SAMPLING_GREEDY",
	"WHISPER_SAMPLING_BEAM_SEARCH",
	"WHISPER_AHEADS_NONE",
	"WHISPER_AHEADS_CUSTOM",
	"WHISPER_GRETYPE_END",
	"WHISPER_GRETYPE_ALT",
	"WHISPER_GRETYPE_RULE_REF",
	"WHISPER_GRETYPE_CHAR",
	"WHISPER_GRETYPE_CHAR_NOT",
	"WHISPER_GRETYPE_CHAR_RNG_UPPER",
	"WHISPER_GRETYPE_CHAR_ALT"
)

$arguments = @("--target-package", $targetPackage, "--output", $outputRoot, "-I", $whisperInclude, "-I", $ggmlInclude)
foreach($name in $functions) { $arguments += @("--include-function", $name) }
foreach($name in $structs)   { $arguments += @("--include-struct",   $name) }
foreach($name in $typedefs)  { $arguments += @("--include-typedef",  $name) }
foreach($name in $constants) { $arguments += @("--include-constant", $name) }
$arguments += $header

# 消えたシンボルの生成物が残らないよう、毎回作り直す
if(Test-Path $generatedDir)
{
	Write-Host "既存の生成物を削除します: $generatedDir"
	Remove-Item -Recurse -Force $generatedDir
}

Write-Host "生成中: $generatedDir"
& $jextract @arguments
if($LASTEXITCODE -ne 0) { Write-Error "jextract が失敗しました (exit $LASTEXITCODE)"; exit $LASTEXITCODE }

$generated = Get-ChildItem -Path $generatedDir -Filter "*.java" -File
Write-Host ""
Write-Host "生成しました: $($generated.Count) ファイル"
$generated | ForEach-Object { Write-Host ("  {0}" -f $_.Name) }
