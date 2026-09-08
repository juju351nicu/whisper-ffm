# whisper-jni-custom v2 計画 — JNI を FFM（Panama）に置き換え、由来コードを完全に排除する

作成: 2026-09-07。対象: whisper.cpp v1.9.3 / ggml 0.20.2 のまま。
前段の作業記録は `WINDOWS-BUILD-1.9.3.md`、高速化の引継ぎは `handover-step5-speedup.md`。

---

## 1. 到達点

1. **JNI の C++（`src/main/native/jni/`）と自前 DLL `whisper-jni.dll` を無くす。** Java 22 以降の FFM
   （`java.lang.foreign`）で whisper.cpp の C API（`whisper.h`）を直接呼ぶ。ネイティブは whisper.cpp
   **公式リリースの DLL / .so だけ**になり、CPU 版・Vulkan 版・CUDA 版の切り替えが「DLL の置き換え」だけで済む。
   CMake・MSVC・GitHub Actions のネイティブビルドは不要になる。
2. **GiviMAD / Jaffe2718 由来のコードを 0 にする。** 残っているのはテストの構成・`GbnfGrammarValidator`・
   `WhisperTranscriptionParams` のフィールド選択・ビルドスクリプト・CI・README の断片（§6 の一覧）。
   FFM 化でそのほとんどが「不要になる」か「`whisper.h` だけを見て書き直す」対象になる。
   完了時に NOTICE の 1 節（Apache-2.0 帰属）を外し、履歴をリセットして **v2.0.0** とする。
3. **利用側（transcribe-shell）の import は変えない。** `jp.clip.whisper`（`WhisperEngine` / `WhisperConfig` /
   `TranscriptionResult` / `Segment`）は公開 API として維持する。変わるのは中身だけ。

やらないこと: 速度改善（FFM と JNI の呼び出しコストは同等で、処理時間は whisper.cpp 側が支配的）、
whisper.cpp のバージョンアップ（別作業）、文法（grammar）機能の維持（§4-6）。

---

## 2. 前提と制約

| 項目 | 決定 | 理由 |
|---|---|---|
| Java の最低バージョン | **22**（`options.release = 22`） | FFM が正式版になったのが 22。transcribe-shell は 25 なので問題なし。17 対応は諦める |
| ビルド JDK | 25（Corretto、今と同じ） | |
| バインディング生成 | **jextract**（JDK 22 向けビルド）で `whisper.h` から生成し、**生成物をコミットする** | 構造体 `whisper_full_params`（60 個超のフィールド、入れ子構造体、関数ポインタ）のレイアウトを手で書くと壊れやすい。コミットしておけば jextract が無いマシンでもビルドできる |
| 生成物の置き場所 | `src/main/java/jp/clip/whisper/ffm/gen/`（パッケージ `jp.clip.whisper.ffm.gen`） | 手書きコードと混ぜない。再生成で丸ごと上書きする |
| ネイティブ配布 | 今と同じ `src/main/resources/<os>-<arch>/` + `natives.list`。中身は whisper.cpp 公式リリース（`whisper-bin-x64.zip` 等）の DLL のみ | `BundledResources` はそのまま使える |
| whisper.cpp の取得 | submodule は **残す**（`whisper.h` を jextract に渡すため、サンプル音声・テスト用に使うため）。ビルドはしない | |
| 文法（GBNF） | **v2 では落とす** | `grammar-parser.cpp` は examples 側の C++ で C API に無い。必要になったら Java で GBNF パーサーを書き `whisper_grammar_element[]` を渡す（C API にある） |

### 作業環境の制約（重要）

Claude 側のクラウド環境は **JDK 21** で、jextract も JDK 25 もネットワーク制限で取得できない。
したがって **FFM コードのコンパイルとテストは姓Cさんの PC で行う**（`gradlew test`）。Claude はコードを書き、
デバイス側 VM の javac 25 で構文チェックまでは行う。往復を減らすため、1 回の受け渡しをフェーズ単位（§5）にまとめる。
jextract の実行も PC 側で 1 回だけ行う（スクリプト化する。§5 P0）。

---

## 3. 全体構成（v2）

```
jp.clip.whisper                 公開 API（変更なし）
  WhisperEngine / WhisperConfig / TranscriptionResult / Segment / SamplingStrategy / WhisperException
  AudioFileReader / NativeRuntime（ロードと VAD モデル展開。中身を FFM 用に修正）
  WhisperBackend               ← 新設。エンジンがネイティブを呼ぶための内部インタフェース（package-private）
    JniBackend                 ← 移行期間だけ。jp.clip.whisperjni を包む
    FfmBackend                 ← 新設。jp.clip.whisper.ffm を使う

jp.clip.whisper.ffm             新設。FFM の手書き部分（薄い）
  NativeLibraries              System.load の順序と SymbolLookup.loaderLookup()
  WhisperContextHandle         whisper_context* を持つ AutoCloseable（Arena 管理）
  FullParams                   whisper_full_params の組み立て（生成コードの setter を呼ぶだけ）
  LogBridge                    whisper_log_set の upcall stub → SLF4J "whisper.cpp"
  gen/                         jextract 生成物（whisper_h.java, whisper_full_params.java, ...）

jp.clip.whisperjni              v2.0.0 で削除
src/main/native/jni             v2.0.0 で削除（CMakeLists.txt も）
```

移行期間は `WhisperConfig.backend`（enum `JNI` / `FFM`、既定 JNI）で切り替えられるようにし、
transcribe-shell からは `-Dwhisper.backend=ffm` で同じ音声を両方で流して突き合わせる。
パリティが取れたら JNI 側を削除して既定を FFM にし、`backend` 設定も消す。

---

## 4. FFM の設計メモ（`whisper.h` との対応）

1. **ロード順**: `ggml-base` → `ggml-cpu` → `ggml` → （`ggml-vulkan` があれば）→ `whisper`。
   `System.load` で順に読み込んだ後 `SymbolLookup.loaderLookup()` で解決する（`libraryLookup(Path)` だと
   Windows で依存 DLL の解決がカレントディレクトリに依存するため）。今の `NativeLibraryLoader` の順序をそのまま使う。
2. **コンテキスト**: `whisper_init_from_file_with_params(path, whisper_context_params)`。構造体は値渡しなので
   jextract 生成の `whisper_context_params.allocate(arena)` に `use_gpu` 等をセットして渡す。
   解放は `whisper_free`。ハンドル表は不要（`MemorySegment` をそのまま持つ）。
3. **パラメータ**: `whisper_full_default_params_by_ref(strategy)` で既定値入りの構造体ポインタを得て
   `whisper_full_params` のレイアウトで `reinterpret` し、必要なフィールドだけ生成 setter で上書きする。
   文字列（`language`, `initial_prompt`, `vad_model_path`）は `arena.allocateFrom(String)`（UTF-8）で確保し、
   **`whisper_full` が返るまで同じ Arena を生かす**。1 回の `transcribe` につき `Arena.ofConfined()` を 1 つ。
   `whisper_full` は構造体を値で受けるので、jextract 生成の `whisper_h.whisper_full(ctx, paramsSegment, samples, n)` を呼ぶ。
   `by_ref` で得たポインタは `whisper_free_params` で解放する。
4. **音声**: `arena.allocateFrom(ValueLayout.JAVA_FLOAT, float[])` でネイティブメモリへコピーする
   （ヒープ配列の `MemorySegment.ofArray` は downcall に渡せない）。
5. **結果**: `whisper_full_n_segments` / `whisper_full_get_segment_t0` / `_t1` / `_get_segment_text`。
   文字列は `segment.reinterpret(Long.MAX_VALUE).getString(0)`（UTF-8）。JNI で必要だった
   修正 UTF-8 の回避策は不要になる。トークン列（`whisper_full_get_token_data`）は v2 の公開 API に無いので実装しない。
6. **ログ**: `whisper_log_set(ggml_log_callback, user_data)`。`ggml_log_callback` の upcall stub を
   `Arena.global()` で 1 回だけ作り、SLF4J の `"whisper.cpp"` ロガーへ流す（レベル対応は今の JNI と同じ）。
   whisper.cpp の作業スレッドからの upcall は FFM が自動でアタッチするので、JNI のような手動アタッチは不要。
7. **VAD**: `whisper_full_params.vad` / `vad_model_path` / 入れ子の `vad_params`（`threshold` 等）に値を入れる。
   VAD モデルの展開は今の `NativeRuntime.resolveVadModelPath` のまま。
8. **エラー**: `whisper_full` の戻り値 ≠ 0 と `whisper_init_*` の NULL を `WhisperException` にする。
   `whisper_full` 中の abort は使わない（`abort_callback` は NULL のまま）。
9. **GPU**: `use_gpu=true` のまま。CPU 版 DLL では無視される。Vulkan 版 DLL に差し替えれば有効。
10. **`--enable-native-access=ALL-UNNAMED`**: 利用側の起動オプションに必要（無いと警告）。
    transcribe-shell の `.bat` にはすでに付いている。jar の `MANIFEST.MF` に `Enable-Native-Access: ALL-UNNAMED`
    を入れるのは実行可能 jar でしか効かないので、README に明記する。

### レイアウトの移植性

`whisper_full_params` の要素は `bool` / `int` / `float` / `size_t` / ポインタ / 入れ子構造体 / 関数ポインタ / `enum` で、
x86-64（Windows・Linux）と arm64（macOS）で同じレイアウトになる（`enum` は 4 バイト、`bool` は 1 バイト、
ポインタと `size_t` は 8 バイト、アラインメント規則も同じ）。jextract は 1 プラットフォームで生成したものを共通利用し、
テストで `whisper_full_params.sizeof()` を各 OS の実測（`sizeof(struct whisper_full_params)` を C で出した値）と
突き合わせて検出する。whisper.cpp を更新したら再生成する（`scripts/jextract-whisper.ps1`）。

---

## 5. 工程

各フェーズの完了条件は `gradlew test` 全件パス。1 フェーズ 1〜数コミット。

### P0. 準備（姓Cさんの PC で 0.5 セッション）— **完了 2026-09-07**

結果は `docs/ffm-p0-report.md`。生成 API の実際のシグネチャと構造体レイアウトはそちらを参照。
jextract は JDK 25 向けではなく **JDK 22 向け**を使う（25 の生成コードは JDK 23 で追加された
`SymbolLookup.findOrThrow` を呼ぶため `options.release = 22` で通らない）。
`scripts/download-natives.ps1` だけ P3 へ回した。


- jextract（JDK 22 向けの最新ビルド、https://jdk.java.net/jextract/）を `C:\tools\jextract` に展開。
- `scripts/jextract-whisper.ps1` を追加: `whisper.h`（+ `ggml.h`）を入力に、`--target-package jp.clip.whisper.ffm.gen`
  `--output src/main/java` で生成。`--include-function` / `--include-struct` で必要なシンボルだけに絞る
  （全部出すと ggml の数百関数が入る）。
- `build.gradle`: `options.release = 22`、`sourcesJar` / `javadoc` から `gen/` を除外（生成物に Javadoc 規約は課さない）。
  `CLAUDE.md` の「Java 17」の記述を 22 に。
- whisper.cpp 公式 Windows バイナリを取得する `scripts/download-natives.ps1`（GitHub Releases の
  `whisper-bin-x64.zip` から `whisper.dll` `ggml*.dll` を `whisperjni-build/` へ）。既存の `installNatives` がそのまま同梱する。

### P1. FfmBackend 最小実装（1 セッション）— **完了 2026-09-07**

- `WhisperBackend`（package-private インタフェース）を切り出し、`WhisperConfig.backend`
  （enum `Backend`、既定 `JNI`）で切り替え可能にした。既存 50 テストは無変更で通る。
- `JniBackend`: `WhisperEngine` にあった JNI 経路をそのまま移しただけ（挙動不変）。
- `TranscriptionParams`: 設定 → `WhisperTranscriptionParams` の写しを両バックエンドで共有する。
  この型は whisper.cpp と違う既定値を 4 つ持つ（`suppressNonSpeechTokens` / `temperatureIncrement` /
  `greedyBestOf` / `beamSize`）ので、**唯一の出どころにしないと FFM と結果がずれる**。
- `FfmBackend`: コンテキスト生成 → `whisper_full` → セグメント取得。パラメータの写しは
  `.cpp` の `readTranscriptionParams` と同じ項目・同じ順序で書いてある。
  VAD と初期プロンプトまで対応済み。**文法だけ未対応**（指定すると `WhisperException`）。
- `BackendParityTest`（9 件）: greedy / beam search / VAD / 初期プロンプトの 4 通りで
  JNI と FFM のテキストとタイムスタンプが完全一致することを確認。合計 59 テストが通る。

**構成の変更**: §3 で予定していた `jp.clip.whisper.ffm` の手書きクラス群（`NativeLibraries` /
`WhisperContextHandle` / `FullParams`）は作らず、`FfmBackend` 1 つにまとめた。ロードは既存の
`NativeRuntime` / `NativeLibraryLoader` がそのまま使えたため。`LogBridge` は P2 で作る。

**P2 へ持ち越した依存**: `Backend.FFM` でも whisper.cpp のログ転送は JNI の
`WhisperJNI.setLogger` を使っている。つまり FFM を選んでも `whisper-jni.dll` がまだ要る。
これを外すのが P2 の `whisper_log_set` 対応。

### P2. パリティ（1〜2 セッション）— **完了 2026-09-07**

- **文法**: whisper.cpp の解析器は `examples/grammar-parser.cpp` にあり DLL にエクスポート
  されていないため、`GbnfGrammar` として Java へ移植した（リテラル・文字クラス・否定・
  規則参照・グループ・`*` `+` `?` の書き換え・エスケープ・コメント）。
  移植元が解析エラーを stderr に出して空を返すのに対し、こちらは `WhisperException` を投げる。
  未定義の規則参照も弾く（移植元は素通しする）。単体テスト `GbnfGrammarTest` が 18 件。
- **ログ転送**: `FfmLogBridge` が `ggml_log_callback` のアップコールスタブを `Arena.global()` で
  作り、`whisper_log_set` に登録する。レベル対応は JNI 実装と同じ。
  `NativeRuntime` がバックエンドに応じて経路を選ぶ（`whisper_log_set` はプロセスに 1 つなので、
  1 JVM で両方使うと後から開いた方に切り替わる。出力は同じ）。
- `WhisperEngineTest` を `@ParameterizedTest` + `@EnumSource(Backend.class)` に変更し、
  ネイティブを触る 12 件を両バックエンドで実行（18 → 30 件）。
- `BackendParityTest` に文法を追加（10 件）。合計 **90 テスト**が通る。

**`whisper-jni.dll` なしで動くことを確認済み**: `ggml-base` / `ggml-cpu` / `ggml` / `whisper` の
4 つだけを置いたディレクトリを `nativeLibraryDirectory` に指定して `Backend.FFM` で実行し、
文字起こしと whisper.cpp のログ転送の両方が動くことを確かめた。

**未実施（別リポジトリ）**: transcribe-shell で実録音 1 本を両バックエンドで処理して
出力・時間を比較する件。こちらのリポジトリからは触っていない。

### P3. JNI 削除と由来排除（1 セッション）→ v2.0.0 — **完了 2026-09-07**

実施内容は下の一覧のとおり。計画から変えた点が 3 つある。

**(a) 既定値は whisper.cpp に合わせたうえで、設定項目として公開した。**
`WhisperTranscriptionParams` が持っていた由来の既定値 4 つを捨て、
`bestOf` / `beamSize` / `temperatureIncrement` / `suppressNonSpeechTokens` を
`WhisperConfig` の項目にした。既定値は whisper.cpp と同じ（5 / 5 / 0.2 / false）。

品質面でも有利だろうと考えたのが決め手（**この見立ては後日の実測で否定された。下の「(a) の実測」を参照**）。**旧既定の `greedyBestOf = -1` は
whisper.cpp 内部で `n_decoders = 1` に丸められ、温度フォールバックが実質無効だった。**
認識が破綻したとき（`entropy_thold` を割ったとき）に候補を 1 本しか引き直せないので、
同じ行が何度も続く繰り返しループから抜けられない。whisper.cpp 既定の
`best_of = 5` / `temperature_inc = 0.2` なら温度 0.0→1.0 を 6 段で 5 候補ずつ引き直す。
温度 0.0 では 1 本しか走らないので、**通常時の速度は変わらない**（デコーダ 5 本分の
メモリが増えるだけ）。

`suppressNonSpeechTokens` は既定 false になるので `[音楽]` `(笑)` `♪` のような注記が
混じりうる。**日本語の議事録では利用側（transcribe-shell）で明示的に true を指定する方針。**
ライブラリの既定は whisper.cpp に合わせ、用途ごとの判断は利用側が持つ。

#### (a) の実測（2026-09-07）— **`best_of = 5` の優位は確認できなかった**

上の「品質面でも有利」は仮説であり、実測では支持されなかった。同じ録音での比較は次のとおり。

**sample-b**（31 分、small、VAD off、初期プロンプトあり）:

| 条件 | suppress_nst / best_of / temp_inc | 文字数 | 処理時間 | 繰り返し |
|---|---|---|---|---|
| v1（JNI・旧既定） | true / -1 / 0.4 | 7,195 | 8 分 32 秒 | 「私は、私のビデオを紹介します。」× 8 |
| v2 既定（whisper.cpp と同じ） | false / 5 / 0.2 | 5,724 | 11 分 59 秒 | 「【アイテム】」× 57（冒頭から連続） |
| v2 + 旧既定相当 | true / -1 / 0.4 | 7,195 | 8 分 58 秒 | 「私は、私のビデオを紹介します。」× 8 |

3 行目は v1 の出力と **MD5 まで一致**（`79cc74f8d88de9066f10524f1a95fc4f`）。31 分の実録音で JNI と FFM が
バイト単位で同じ結果を出すことの確認になっている（`BackendParityTest` の jfk.wav より強い証拠）。

**sample-c**（12 分 38 秒、`suppress_nst = true` に固定し `best_of` / `temp_inc` だけを振った）:

| best_of / temp_inc | 文字数 | 処理時間 | 繰り返し |
|---|---|---|---|
| -1 / 0.4 | 3,542 | 245 秒 | 0 件 |
| 5 / 0.2 | 3,451 | 244 秒 | 0 件 |

処理時間は同じ、文字数はむしろ 2.6% 減。この音声では繰り返し自体が起きなかったため、
**ループ抑制の効果は未検証のまま**である（`best_of = 5` が有利という証拠も不利という証拠も得られていない）。

**結論**: ライブラリの既定は whisper.cpp のまま（由来排除の目的と「上流に合わせる」原則を優先）。
一方 **transcribe-shell は実測に基づき `best-of=-1` / `temperature-increment=0.4` / `beam-size=2` /
`suppress-non-speech-tokens=true` を明示指定する**。数値と理由は transcribe-shell の README
「デコーダの調整」の節にある。ライブラリと利用側で既定が違うのは意図的で、この実測が根拠。

**未解決**: 2 行目の「【アイテム】× 57」が 3 つの変更のどれで起きたかは切り分けていない。
`suppress_nst` が抑制するのは whisper.cpp の固定の記号リスト（`" # ( ) [ ] 「 」 『 』 ♪ ♫` 等）だけで
**`【` `】` は含まれない**ため、`suppress_nst` が直接の原因とは考えにくい。決着させるなら
sample-b に `suppress_nst=true` + `best_of=5` + `temp_inc=0.2` を 1 回流せばよい（約 9 分）。

**教訓**: 既定値を変えるときは、仮説ではなく**同一音声での A/B を先に取る**。今回は 3 つの値を
同時に変えたため、劣化の原因の切り分けに余計な往復が発生した。

#### 追記 2.0.1 — 繰り返しループの本命は `n_max_text_ctx` だった（2026-09-07）

別リポジトリ（`juju351nicu/transcribe-shell` の `docs/ENGINE_BENCHMARK.md`）で、whisper-cli を
使った比較が既に行われていた。その結論は「cpp は `-mc 0`（`max-context=0`）が必須。素の既定のままでは
幻覚ループで品質が大きく崩れる」で、`-mc 0` を足すと**速くもなる**（15 分 02 秒 → 13 分 37 秒。
ループは無駄なトークン生成なので）と実測されている。faster-whisper には `compression_ratio_threshold`
による繰り返し検出があるが、whisper.cpp には相当機能が無く、**文脈の引き継ぎを切ることが実質的な対策**
という分析だった。`bestOf` をいじるより先に見るべき項目だった。

whisper.cpp のソースを読んで確認した機構は次のとおり。

- 30 秒ウィンドウごとに、前のウィンドウの出力が次のプロンプトに引き継がれる（7627〜7637 行）。
  1 度出た繰り返し行が次のプロンプトになり、さらに同じ行を誘発する。
- `n_max_text_ctx = 0` にするとプロンプト構築そのものが飛ぶ（7127 行の `if (n_max_text_ctx > 0)`）。
  **このため `initial_prompt` も無効になる。** 参加者名のヒントを使っている用途では使えない。
- `carry_initial_prompt = true`（既定 false）にすると、引き継ぎバッファは**今回のウィンドウの出力だけ**に
  なり、初期プロンプトは静的な別枠（`prompt_past0`）として毎ウィンドウ前置される（7128 行、6962 行）。
  固有名詞のヒントを全体に効かせたまま、伝播を短く抑えられる。

そこで **`maxTextContext` と `carryInitialPrompt` の両方を `WhisperConfig` に公開した**（2.0.1）。
既定は whisper.cpp と同じ 16384 / false。利用側で用途に応じて選ぶ。
未実測なので**アプリ側の既定も変えていない**（今回の教訓の適用）。A/B の結果が出たらここに追記する。

**(b) 文法を残した。** 計画では「grammar 関連の設定を削除」としていたが、P2 で
`GbnfGrammar`（whisper.cpp の解析器の Java 移植）を書いてパリティまで確認できたので、
`WhisperConfig.grammar` はそのまま使える。削除したのは由来のある `GbnfGrammarValidator` だけ。

**(c) `scripts/build-whisper.ps1` を追加した。** whisper.cpp の v1.9.3 リリースには
Windows バイナリが添付されていない（v1.9.2 以前にはある）。公式バイナリだけに頼ると
submodule が指すバージョンでテストできないので、**本家の CMake をそのまま呼ぶだけの**
スクリプトを用意した。このリポジトリ独自のビルド定義は持たない。


- `jp.clip.whisperjni` / `src/main/native/jni` / `CMakeLists.txt` / `scripts/build-*.{sh,ps1}` / `.github/workflows/main.yml` を削除。
  `windows-natives.yml` は「公式バイナリを取得して jar を組む」ワークフローに書き換える。
- `GbnfGrammarValidator` と grammar 関連の設定を削除。
- テストを `whisper.h` と公開 API だけを見て書き直す（期待文字列は再取得する）。
- README を書き直す。NOTICE から 1 節（Apache-2.0 帰属）を削除し、2 節（whisper.cpp MIT）と 3 節（silero-vad MIT）を残す。
- §6 の監査チェックリストを全部埋める。
- バージョン `2.0.0`。**履歴のリセット**: `git checkout --orphan v2` → 1 コミット → `main` を置き換え。
  旧履歴は `v1-jni` ブランチとタグ `1.9.3-2` として残す（消す判断は後でよい）。
  ※ 別 AI の提案どおり「新しいリポジトリ名にする」選択もある。名前を変えるなら P3 の前に決める。

### P4. その後（別計画）

- VAD を「区間の切り出し」だけに使い、区間ごとに `whisper_full` を呼ぶ方式（繋ぎ合わせない）。
  2026-09-06 の比較で whisper.cpp 内蔵 VAD が文を落とした問題への対策。
- 幻覚ループの抑制（同一行の連続を検出して `no_speech_thold` / `entropy_thold` を調整、または区間再処理）。
- 必要なら GBNF パーサーの Java 実装。

工数の目安: P0 0.5 + P1 1 + P2 1〜2 + P3 1 = **3.5〜4.5 セッション**（Fable の枠は実装フェーズで多く使う）。

---

## 6. 由来コードの監査チェックリスト（P3 で全部 ✅ にする）

| ファイル / 領域 | 現状 | v2 での扱い |
|---|---|---|
| `src/main/native/jni/*.cpp/.h` | 全面書き直し済み（独立） | ✅ 削除 |
| `jp.clip.whisperjni.*` | 構造・メソッドの並びに由来が残る | ✅ 削除。`BundledResources` / `Platform` / `NativeLibraryLoader` だけ `jp.clip.whisper` へ移動 |
| `WhisperTranscriptionParams` のフィールド選択・既定値 | 由来あり（`beamSize=2` 等） | ✅ 削除。`whisper_full_default_params` の既定値に置き換え、調整が要る 5 項目は `WhisperConfig` の設定として公開（上の (a)） |
| `GbnfGrammarValidator` + テスト | 由来あり（修正版） | ✅ 削除。文法は P2 で書いた `GbnfGrammar` が担う（上の (b)） |
| `WhisperJNITest` / `WhisperEngineTest` の構成・期待文字列 | 由来あり | ✅ `WhisperJNITest` は削除。`WhisperEngineTest` は公開 API だけを見て書き直し（21 件） |
| `NativeLibraryLoader` のロード順の考え方 | RNNoise4J 由来の発想 | ✅ コメントを書き直し、由来の記述を削除。`whisper-jni` をロード順から除去 |
| `CMakeLists.txt` / `scripts/build-*` | 由来あり | ✅ 削除。`download-natives.ps1`（公式バイナリ）と `build-whisper.ps1`（本家 CMake を呼ぶだけ）に置き換え |
| `.github/workflows/main.yml` | 由来あり | ✅ 削除。`windows-natives.yml` を書き換え |
| `README.md` | 断片に由来あり | ✅ 全文書き直し。`WINDOWS-BUILD-1.9.3.md` と `docs/handover-step5-speedup.md`（JNI 前提の作業記録）も削除 |
| `jp.clip.whisper.*`、`BundledResources`、`Platform`、Lombok 設定、Gradle の natives.list / benchmark | 独立 | ✅ 維持 |
| `NOTICE` 1 節 | Apache-2.0 帰属 | ✅ 削除。残りを 1〜3 節に振り直し、jextract 生成物の節を追加 |

注意: Claude はこれまでの作業で元コードを読んでいるため、厳密なクリーンルーム再実装ではない。
実務上は「`whisper.h` と自分の設計だけを見て書き直し、上の一覧で出自を確認する」で十分と考えるが、
法的な確証が必要なら最終的に専門家に確認する。whisper.cpp と silero-vad の MIT 表記は残す（義務）。

---

## 7. リスクと対処

| リスク | 対処 |
|---|---|
| 構造体レイアウトの不一致（クラッシュや無視されるパラメータ） | jextract 生成 + `sizeof` の突き合わせテスト。whisper.cpp 更新時は必ず再生成 |
| jextract のバージョンと JDK の FFM API の不一致 | JDK 22 向け jextract の出力は 22 以降で動く。生成物をコミットするので再現性あり |
| パラメータ文字列の寿命切れ（Arena を早く閉じる） | `transcribe` 1 回 = Arena 1 つ、`whisper_full` が返ってから閉じる。テストで長文プロンプトを渡す |
| Windows で依存 DLL の解決失敗 | `System.load` を依存順に呼んでから `loaderLookup()`。今の JNI と同じ手順 |
| FFM 化で速度が変わる不安 | P2 で同一音声の処理時間を比較して記録する（差は誤差の範囲のはず） |
| Claude 側で FFM コードをコンパイルできない | フェーズ単位でまとめて受け渡し、PC で `gradlew test`。エラーはログを貼ってもらい修正 |
| 履歴リセットの取り消し不能 | `v1-jni` ブランチとタグを残す。リセット前に `git bundle` でバックアップ |
