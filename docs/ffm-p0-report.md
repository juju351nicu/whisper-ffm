# P0 レポート — jextract の導入と FFM バインディング生成

`docs/plan-ffm-v2.md` §5 P0 の実施記録。P1（`FfmBackend` の実装）を書くときに必要な、
生成 API の実際の形をここに残す。

実施日: 2026-09-07 / 環境: Windows 11、Amazon Corretto 25.0.2、Gradle 9.7.1

---

## 1. jextract のバージョン

```
> C:\tools\jextract\bin\jextract.bat --version
jextract 22
JDK version 22+35-2369
LibClang version clang version 13.0.0
```

**JDK 25 向けではなく JDK 22 向けを選んだ。** 理由は次のとおり。

`https://jdk.java.net/jextract/` が現在配っているのは jextract 25
（`openjdk-25-jextract+2-4`）で、これを使うと生成コードが `SymbolLookup.findOrThrow` を呼ぶ。
このメソッドは **JDK 23 で追加**されたため、`options.release = 22` ではコンパイルが通らない
（`シンボルを見つけられません: メソッド findOrThrow(String)` が 35 件）。

jextract 22 の生成コードは同じ処理を `SYMBOL_LOOKUP.find(symbol).orElseThrow(...)` で書くので、
Java 22 の API だけで済む。計画どおり利用側の下限を Java 22 に保てるのはこちら。

取得元（`jdk.java.net` のページからはリンクが消えているが、ファイルは残っている）:

```
https://download.java.net/java/early_access/jextract/22/6/openjdk-22-jextract+6-47_windows-x64_bin.tar.gz
```

展開先は `C:\tools\jextract`。バンドルされた `runtime/` で動くので、別途 JDK 22 は要らない。

> 将来 v2 の下限を Java 23+ に上げるなら jextract 25 に戻してよい。
> その場合 `build.gradle` の `options.release` も合わせること。

---

## 2. 生成されたファイル

`scripts\jextract-whisper.ps1` を実行して `src\main\java\jp\clip\whisper\ffm\gen\` に 14 ファイル。

| ファイル | 行数 |
|---|---:|
| `whisper_full_params.java` | 3009 |
| `whisper_h.java` | 2234 |
| `whisper_token_data.java` | 542 |
| `whisper_context_params.java` | 452 |
| `whisper_vad_params.java` | 357 |
| `whisper_ahead.java` | 173 |
| `whisper_aheads.java` | 173 |
| `whisper_grammar_element.java` | 173 |
| `ggml_log_callback.java` | 76 |
| `whisper_logits_filter_callback.java` | 72 |
| `whisper_encoder_begin_callback.java` | 70 |
| `whisper_new_segment_callback.java` | 70 |
| `whisper_progress_callback.java` | 70 |
| `ggml_abort_callback.java` | 68 |
| **合計** | **7539** |

生成対象のシンボルは `scripts\jextract-whisper.ps1` の `$functions` / `$structs` / `$typedefs` /
`$constants` に列挙してある。候補の一覧が要るときは `-DumpIncludes` を付けて実行する。

---

## 3. `whisper_h` の関数シグネチャ

**すべて `MemorySegment` でポインタを受け渡す。** `whisper_context *` `whisper_state *` は
不透明型なので Java 側の型は付かない（`MemorySegment` のまま）。

```java
// 初期化と解放
public static MemorySegment whisper_init_from_file_with_params(MemorySegment path_model, MemorySegment params)
public static MemorySegment whisper_init_from_file_with_params_no_state(MemorySegment path_model, MemorySegment params)
public static MemorySegment whisper_init_state(MemorySegment ctx)
public static void          whisper_free(MemorySegment ctx)
public static void          whisper_free_state(MemorySegment state)
public static void          whisper_free_params(MemorySegment params)
public static void          whisper_free_context_params(MemorySegment params)

// パラメータの既定値
public static MemorySegment whisper_context_default_params_by_ref()
public static MemorySegment whisper_context_default_params(SegmentAllocator allocator)   // 値返しなので allocator が要る
public static MemorySegment whisper_full_default_params_by_ref(int strategy)

// 推論
public static int whisper_full(MemorySegment ctx, MemorySegment params, MemorySegment samples, int n_samples)
public static int whisper_full_with_state(MemorySegment ctx, MemorySegment state, MemorySegment params, MemorySegment samples, int n_samples)

// セグメント
public static int  whisper_full_n_segments(MemorySegment ctx)
public static int  whisper_full_n_segments_from_state(MemorySegment state)
public static long whisper_full_get_segment_t0(MemorySegment ctx, int i_segment)
public static long whisper_full_get_segment_t1(MemorySegment ctx, int i_segment)
public static long whisper_full_get_segment_t0_from_state(MemorySegment state, int i_segment)
public static long whisper_full_get_segment_t1_from_state(MemorySegment state, int i_segment)
public static MemorySegment whisper_full_get_segment_text(MemorySegment ctx, int i_segment)
public static MemorySegment whisper_full_get_segment_text_from_state(MemorySegment state, int i_segment)

// トークン（WhisperToken 相当）
public static int whisper_full_n_tokens(MemorySegment ctx, int i_segment)
public static int whisper_full_n_tokens_from_state(MemorySegment state, int i_segment)
public static MemorySegment whisper_full_get_token_text(MemorySegment ctx, int i_segment, int i_token)
public static MemorySegment whisper_full_get_token_text_from_state(MemorySegment ctx, MemorySegment state, int i_segment, int i_token)
public static MemorySegment whisper_full_get_token_data(SegmentAllocator allocator, MemorySegment ctx, int i_segment, int i_token)
public static MemorySegment whisper_full_get_token_data_from_state(SegmentAllocator allocator, MemorySegment state, int i_segment, int i_token)

// 言語
public static int whisper_full_lang_id(MemorySegment ctx)
public static int whisper_full_lang_id_from_state(MemorySegment state)
public static int whisper_lang_id(MemorySegment lang)          // lang は const char *
public static MemorySegment whisper_lang_str(int id)
public static int whisper_lang_max_id()

// その他
public static int  whisper_is_multilingual(MemorySegment ctx)   // bool ではなく int で返る
public static MemorySegment whisper_print_system_info()
public static int  whisper_ctx_init_openvino_encoder(MemorySegment ctx, MemorySegment model_path, MemorySegment device, MemorySegment cache_dir)
public static void whisper_log_set(MemorySegment log_callback, MemorySegment user_data)
```

P1 で気をつける点:

- **`char *` を返す関数の戻り値は長さ 0 の `MemorySegment`**（`whisper_full_get_segment_text` など）。
  そのままでは `getString(0)` が `IndexOutOfBoundsException` になるので、
  `seg.reinterpret(Long.MAX_VALUE).getString(0)` で読む。UTF-8 は `getString` の既定。
- **`whisper_is_multilingual` は `int`**。`!= 0` で判定する。
- 値返しの `whisper_context_default_params` は `SegmentAllocator` が第 1 引数。
  `_by_ref` 版は malloc されたポインタを返すので、`whisper_free_context_params` での解放が要る。
- **`whisper_h` の `SYMBOL_LOOKUP` は `SymbolLookup.loaderLookup().or(defaultLookup())`。**
  つまり `whisper_h` と **同じクラスローダー**から `System.load(...)` していないとシンボルが見つからない。
  既存の `NativeRuntime` が DLL を展開して `System.load` しているので、その並びに乗せれば動く
  （jshell から試すとローダーが違って `UnsatisfiedLinkError` になる。P0 で踏んだ）。
  依存 DLL は `ggml-base` → `ggml-cpu` → `ggml` → `whisper` の順に読む。

---

## 4. `whisper_full_params`

### sizeof

```
whisper_full_params.sizeof()            = 304
whisper_full_params.vad_params$offset() = 280
```

### 生成される共通メソッド

```java
public static GroupLayout    layout()
public static long           sizeof()
public static MemorySegment  allocate(SegmentAllocator allocator)
public static MemorySegment  allocateArray(long elementCount, SegmentAllocator allocator)
public static MemorySegment  reinterpret(MemorySegment addr, Arena arena, Consumer<MemorySegment> cleanup)
public static long           <field>$offset()
public static <T>            <field>(MemorySegment struct)                 // getter
public static void           <field>(MemorySegment struct, <T> fieldValue) // setter
```

**`struct` を第 1 引数に取る static メソッド**で、getter / setter が同名オーバーロード。
インスタンスは作らない（`whisper_full_params` 型の変数は存在しない）。

### フィールドの読み書きの実例

```java
// int / float / boolean はそのままの型
int n = whisper_full_params.n_threads(params);
whisper_full_params.n_threads(params, 8);

whisper_full_params.translate(params, false);          // bool は boolean
whisper_full_params.no_context(params, true);
whisper_full_params.print_progress(params, false);
whisper_full_params.temperature(params, 0.0f);         // float は f 付き
whisper_full_params.vad(params, true);
```

### ポインタフィールド（`const char *`）の setter は `MemorySegment`

`language` / `initial_prompt` / `vad_model_path` はいずれも次の形。**Java の String は受け取らない。**

```java
public static MemorySegment language(MemorySegment struct)
public static void          language(MemorySegment struct, MemorySegment fieldValue)
public static void          initial_prompt(MemorySegment struct, MemorySegment fieldValue)
public static void          vad_model_path(MemorySegment struct, MemorySegment fieldValue)
```

書くときは `Arena` で C 文字列を確保して渡す。**この Arena は `whisper_full` の呼び出しが
終わるまで生かしておくこと**（閉じるとポインタが外れる）。

```java
try(Arena arena = Arena.ofConfined())
{
	whisper_full_params.language(params, arena.allocateFrom("ja"));
	whisper_full_params.initial_prompt(params, arena.allocateFrom(prompt));
	whisper_h.whisper_full(ctx, params, samples, sampleCount);
}
```

読むときは §3 と同じく `reinterpret` が要る。

```java
String lang = whisper_full_params.language(params).reinterpret(Long.MAX_VALUE).getString(0);
```

### 入れ子の構造体（`greedy` / `beam_search` / `vad_params`）

**入れ子は「親の中のスライス」を返す getter**。返ってきた `MemorySegment` を、その入れ子の型の
static メソッドに渡す。コピーではなく親を指しているので、書き込むとそのまま親に反映される。

```java
// whisper_full_params.greedy / beam_search は内部クラスとして生成される
int bestOf = whisper_full_params.greedy.best_of(whisper_full_params.greedy(params));
whisper_full_params.beam_search.beam_size(whisper_full_params.beam_search(params), 2);

// vad_params は独立クラス whisper_vad_params
MemorySegment vadParams = whisper_full_params.vad_params(params);
whisper_vad_params.threshold(vadParams, 0.5f);
whisper_vad_params.min_speech_duration_ms(vadParams, 250);
whisper_vad_params.max_speech_duration_s(vadParams, Float.MAX_VALUE);
whisper_vad_params.min_silence_duration_ms(vadParams, 100);
whisper_vad_params.speech_pad_ms(vadParams, 30);
whisper_vad_params.samples_overlap(vadParams, 0.1f);
```

`whisper_vad_params.sizeof() = 24`。フィールドは上の 6 個で全部。

### コールバック / grammar のフィールド

いずれも setter は `MemorySegment`（アップコールスタブか、ポインタ配列）。

```java
public static void new_segment_callback(MemorySegment struct, MemorySegment fieldValue)
public static void new_segment_callback_user_data(MemorySegment struct, MemorySegment fieldValue)
public static void abort_callback(MemorySegment struct, MemorySegment fieldValue)
```

---

## 5. `whisper_context_params`

```
whisper_context_params.sizeof() = 48
```

フィールドは 8 個。呼び方は `whisper_full_params` と同じ形。

```java
public static boolean use_gpu(MemorySegment struct)              / (…, boolean)
public static boolean flash_attn(MemorySegment struct)           / (…, boolean)
public static int     gpu_device(MemorySegment struct)           / (…, int)
public static boolean dtw_token_timestamps(MemorySegment struct) / (…, boolean)
public static int     dtw_aheads_preset(MemorySegment struct)    / (…, int)
public static int     dtw_n_top(MemorySegment struct)            / (…, int)
public static MemorySegment dtw_aheads(MemorySegment struct)     / (…, MemorySegment)  // 入れ子 whisper_aheads
public static long    dtw_mem_size(MemorySegment struct)         / (…, long)           // size_t は long
```

---

## 6. `ggml_log_callback`（ログ転送）

```java
public interface Function
{
	void apply(int level, MemorySegment text, MemorySegment user_data);
}

public static FunctionDescriptor descriptor()
public static MemorySegment allocate(ggml_log_callback.Function fi, Arena arena)
public static void invoke(MemorySegment funcPtr, int level, MemorySegment text, MemorySegment user_data)
```

`allocate` が返すアップコールスタブの寿命は渡した `Arena` が握る。
**ログは JVM が動いている間ずっと呼ばれるので、`Arena.global()` を渡す**（`ofConfined` を
閉じるとネイティブから死んだスタブを呼ばれてクラッシュする）。

```java
MemorySegment stub = ggml_log_callback.allocate(
	(int level, MemorySegment text, MemorySegment userData) ->
		log.info(text.reinterpret(Long.MAX_VALUE).getString(0).strip()),
	Arena.global());
whisper_h.whisper_log_set(stub, MemorySegment.NULL);
```

`level` は `GGML_LOG_LEVEL_NONE=0 / DEBUG=1 / INFO=2 / WARN=3 / ERROR=4 / CONT=5`。
既存の JNI 実装（`setLogger`）と同じ対応付けにすること。

なお `whisper_full_params` のコールバック型 `whisper_new_segment_callback` /
`whisper_progress_callback` / `whisper_encoder_begin_callback` / `whisper_logits_filter_callback` /
`ggml_abort_callback` も同じ形（`Function` インターフェース + `allocate(fi, arena)`）で生成されている。

---

## 7. 定数

`whisper_h` に static メソッドとして出る（フィールドではない）。

```java
whisper_h.WHISPER_SAMPLE_RATE()          // 16000
whisper_h.WHISPER_SAMPLING_GREEDY()      // 0
whisper_h.WHISPER_SAMPLING_BEAM_SEARCH() // 1
whisper_h.WHISPER_GRETYPE_END() ほか grammar 用 7 個
whisper_h.WHISPER_AHEADS_NONE() / WHISPER_AHEADS_CUSTOM()
```

---

## 8. レイアウトの実機検証

`plan-ffm-v2.md` §4「レイアウトの移植性」の懸念に対して、生成バインディングから
`whisperjni-build\whisper.dll` を実際に呼び、既定値が C 側と一致するか確かめた。

| 読んだ値 | 結果 | whisper.cpp の既定値 |
|---|---|---|
| `strategy` | 0 | `WHISPER_SAMPLING_GREEDY` |
| `n_threads` | 4 | `min(4, hardware_concurrency)` |
| `n_max_text_ctx` | 16384 | 16384 |
| `translate` / `no_context` | false / true | false / true |
| `language` | `"en"` | `"en"` |
| `temperature` | 0.0 | 0.0 |
| `entropy_thold` | 2.4 | 2.4 |
| `logprob_thold` | -1.0 | -1.0 |
| `greedy.best_of` | 5 | 5 |
| `beam_search.beam_size` | -1 | -1（greedy 時） |
| `vad_params.threshold` | 0.5 | 0.5 |
| `vad_params.min_speech_duration_ms` | 250 | 250 |
| `vad_params.samples_overlap` | 0.1 | 0.1 |
| `context_params.use_gpu` | true | true |
| `context_params.flash_attn` | true | true |
| `whisper_lang_id("ja")` | 7 | 7 |

`whisper_print_system_info()` も正しく読めた:
`WHISPER : COREML = 0 / OPENVINO = 0 / CPU : SSE3 = 1 / SSSE3 = 1 / AVX = 1 / AVX2 = 1 / F16C = 1 / FMA = 1 / BMI2 = 1 / OPENMP = 1 / REPACK = 1`
（実際の区切りは `|`）。

**全フィールドが期待値と一致。** MSVC の構造体レイアウトと jextract の推定は合っている。

---

## 9. `build.gradle` / `CLAUDE.md` の変更

- `sourceCompatibility` / `targetCompatibility` を `VERSION_17` → `VERSION_22`
- `options.release` を 17 → 22、Javadoc の `options.source` を `'17'` → `'22'`
- `javadoc` / `sourcesJar` から `jp/clip/whisper/ffm/gen/**` を除外
- `CLAUDE.md`「バイトコードターゲットは Java 17」を Java 22 に更新

`-Xlint` の追加抑制は不要だった（生成コードは警告ゼロでコンパイルされる）。

---

## 10. テスト結果

```
> .\gradlew.bat test
BUILD SUCCESSFUL
```

| テストクラス | 件数 | 失敗 |
|---|---:|---:|
| `jp.clip.whisper.WhisperEngineTest` | 18 | 0 |
| `jp.clip.whisperjni.WhisperJNITest` | 19 | 0 |
| `jp.clip.whisperjni.GbnfGrammarValidatorTest` | 13 | 0 |
| **合計** | **50** | **0** |

`javadoc` / `sourcesJar` も成功。sources jar に `ffm/gen` のエントリが 0 件、
javadoc に `jp/clip/whisper/ffm` が出ていないことを確認済み。

---

## 11. P0 でやらなかったこと

- **`scripts/download-natives.ps1`（公式 Windows バイナリの取得）は未着手。**
  手元の `whisperjni-build\` の DLL で P1 の動作確認は足りるので、公式バイナリへの
  切り替えは P3 でまとめて行う（先にやると手戻りになる）。
- `FfmBackend` などの実装コードは書いていない（P1）。
- `jp.clip.whisperjni` / `src/main/native` / `CMakeLists.txt` は無変更。
