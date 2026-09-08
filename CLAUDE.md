# CLAUDE.md — whisper-ffm 作業ルール

このリポジトリで作業するときの規約です。Claude Code / Cowork は自動で読み込みます。
人間が読む前提でも書いています。

---

## コーディングルール

### Java

**1. `var`（ローカル変数の型推論）を使わない。必ず明示的な型を書く。**
人間が読んだときに型がすぐ分からないのを避けるためです。

```java
// NG
var config = WhisperConfig.builder().model(path).build();
var arena = Arena.ofConfined();

// OK
WhisperConfig config = WhisperConfig.builder().model(path).build();
Arena arena = Arena.ofConfined();
```

**2. ファイル操作は `java.nio.file`（`Path` / `Files`）を使う。`java.io.File` は使わない。**
`toFile()` で `File` に落とすのも避ける。`File` しか受け付けない API（例 `AudioSystem`）には
`Files.newInputStream` で開いたストリームを渡す。

```java
// NG
File model = path.toFile();
if(!model.exists() || !model.isFile()) { ... }
AudioSystem.getAudioInputStream(path.toFile());

// OK
if(!Files.isRegularFile(path)) { ... }
AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(path)));
```

**3. 読みづらい `for` / `while` は Stream API に置き換える。**
「配列やリストを走査して、変換・絞り込み・集約する」ループは Stream の方が意図が読み取れる。
逆に、添字を複雑に操作する状態機械（`GbnfGrammar` の字句解析など）や
早期 `return` / `break` が要るループは、無理に Stream にしない。

```java
// NG
StringBuilder builder = new StringBuilder();
for(Segment segment : segments) { builder.append(segment.text()); }
return builder.toString().strip();

// OK
return segments.stream().map(Segment::text).collect(Collectors.joining()).strip();
```

**4. ライブラリの方針**

| ライブラリ | 扱い |
|---|---|
| Lombok | **使う**（`compileOnly`、利用側に影響しない）。ボイラープレートが明確に減る場所で。現在は `WhisperConfig`（`@Value` `@Builder`）と `NativeRuntime`（`@Slf4j`） |
| Apache Commons 等の実行時依存 | **原則入れない**。この jar を使うアプリ（transcribe-shell など）の依存関係を増やすため。Java 標準（`String.isBlank` `strip` `Files` `Stream`）で足りることがほとんど |
| SLF4J | 唯一の実行時依存（`api`）。ロガー実装は利用側が選ぶ |

Lombok を使う際の注意:
- `@Builder` のクラスでは必須項目に `@NonNull` を付ける（未設定なら `build()` で `NullPointerException`）。
- Javadoc はフィールドに書く。Lombok が生成するアクセサ / ビルダーメソッドへ複製される。

**5. 命名**

- 略語や whisper.cpp の C 名をそのまま Java の識別子にしない。読めば意味が分かる名前を付け、
  対応する C 名は Javadoc に併記する（例: `n_threads` → `threads`、`entropy_thold` → `entropyThreshold`、
  `full_n_segments` → `segmentCount`）。
- 例外は **jextract の生成物**（`jp.clip.whisper.ffm.gen`）。C のシンボル名そのままで、手で編集しない。
- 単位はメソッド名・フィールド名に含める（`startMs`、`vadMinSpeechDurationMs`）。

**6. その他**

- 公開 API のバイトコードターゲットは **Java 22**（`build.gradle` の `options.release = 22`）。
  FFM（`java.lang.foreign`）が正式版になったのが Java 22 なので、ここが下限。
  ビルド自体は Java 25 で行うが、利用側に 22 より新しい JDK を要求しない。
- **既定値は whisper.cpp の `whisper_full_default_params` と一致させる。**
  このライブラリを挟んだせいで認識結果が変わってはいけない。`WhisperEngineTest` の
  `defaultsMatchWhisperCpp` がこれを検証している。設定項目を足すときも同じ方針で。
- **既存クラスを record に変換しない。** フィールドアクセス `x.field` がアクセサ `x.field()` に
  変わるため、公開 API の破壊的変更になる。新規の値クラスは record でよい（`Segment` など）。
- 波括弧は次の行（Allman）。`if(` `for(` `catch(` は括弧の前にスペースを入れない。インデントはタブ。
- **Javadoc は日本語で書く。** whisper.cpp の関数名・構造体メンバー名を必ず併記する
  （whisper.cpp を更新するときの影響調査資料になる）。
- **公開するのは `jp.clip.whisper` の 7 型だけ**（`WhisperEngine` `WhisperConfig`
  `TranscriptionResult` `Segment` `SamplingStrategy` `WhisperException` `AudioFileReader`）。
  それ以外は package-private に保つ。増やすときは「利用側が本当に必要か」を先に確かめる。

### FFM（jextract 生成物）

whisper.cpp との結びつきは `whisper.h` 1 本です。**手書きの C++ はありません。**

- 生成物は `src/main/java/jp/clip/whisper/ffm/gen/`。**手で編集しない。**
  作り直すのは `scripts/jextract-whisper.ps1`。生成対象のシンボルはそのスクリプトの
  `$functions` / `$structs` / `$typedefs` / `$constants` に列挙してある。
- jextract は **JDK 22 向け**を `C:\tools\jextract` に置く。JDK 25 向けの生成コードは
  JDK 23 で追加された `SymbolLookup.findOrThrow` を呼ぶので `options.release = 22` で通らない
  （経緯は `docs/ffm-p0-report.md` §1）。
- **構造体を値渡しで受ける関数**（`whisper_full` `whisper_init_from_file_with_params`）には、
  ポインタを返す `_by_ref` 版ではなく `SegmentAllocator` を取る値返し版を渡す。
- **`char *` を返す関数の戻り値は長さ 0 の `MemorySegment`。**
  `pointer.reinterpret(Long.MAX_VALUE).getString(0)` で読む（`NativeSession.readString`）。
- **ネイティブへ渡すポインタの寿命に注意。** `language` / `initial_prompt` / `vad_model_path` /
  文法 / サンプル列は `whisper_full` が返るまで生きている必要がある。
  1 回の `transcribe` につき `Arena.ofConfined()` を 1 つ使って囲む。
- **アップコールスタブは `Arena.global()` で作る**（`NativeLogBridge`）。
  閉じた Arena のスタブをネイティブから呼ばれると JVM ごと落ちる。
- `whisper_h` の `SYMBOL_LOOKUP` は `SymbolLookup.loaderLookup()`。**`whisper_h` と同じ
  クラスローダーから `System.load` された**ライブラリしか見えない（`NativeLibraryLoader` が該当）。

whisper.cpp の submodule を更新したときの手順:

1. `git submodule update --remote src/main/native/whisper`（またはタグを指定して checkout）
2. `gradle.properties` の `whisperCppVersion` を更新
3. `.\scripts\jextract-whisper.ps1` で再生成
4. `.\scripts\download-natives.ps1` で新しいバイナリを取得
5. `.\gradlew.bat test`（構造体レイアウトやシグネチャが変わっていればここで落ちる）

### シェル / PowerShell スクリプト

- すべて `scripts/` に置く。ファイル名は kebab-case（`download-natives.ps1`）。
- **冒頭でプロジェクトルートへ `cd` する。** スクリプトはルートからの相対パスを前提にしている。
  - `.sh`: `cd "$(dirname "$0")/.." || exit 1`
  - `.ps1`: `Set-Location (Join-Path $PSScriptRoot "..")`
- 改行コードは `.gitattributes` で強制。`.sh` は LF、`.ps1` / `.cmd` / `.bat` は CRLF。
- **`.ps1` は UTF-8 BOM 付きで保存する。** PowerShell 5.1 で日本語が化けないため。
- スクリプトの失敗は必ず非ゼロ終了させる。`.ps1` では `$LASTEXITCODE` を明示的に確認する
  （`$ErrorActionPreference = 'Stop'` はネイティブコマンドの終了コードを見ない）。

---

## プロジェクト構成

```
whisper-ffm/
├── .github/workflows/            CI。windows-natives.yml（ネイティブ取得 + テスト + jar 同梱経路のスモーク。Windows x64 のみ）
├── docs/                         plan-ffm-v2.md（v2 の計画と実施記録）、ffm-p0-report.md（生成 API の資料）
├── scripts/                      ネイティブ取得・バインディング生成・モデル取得
├── models/                       計測用モデルの置き場所（ggml-*.bin は gitignore）
├── natives/                      取得したネイティブの置き場所（gitignore）
├── lombok.config                 Lombok 設定
├── src/main/java/jp/clip/whisper/
│   ├── （公開 API）              WhisperEngine / WhisperConfig / TranscriptionResult / Segment / …
│   ├── （内部）                  NativeSession / NativeRuntime / NativeLogBridge / NativeLibraryLoader / GbnfGrammar / …
│   └── ffm/gen/                  jextract の生成物。手で編集しない
├── src/main/native/whisper/      whisper.cpp submodule（v1.9.3 固定。ヘッダとサンプル音声に使う）
├── src/main/resources/<os>-<arch>/   同梱ネイティブの配置先（gitignore）
├── build.gradle / settings.gradle / gradle.properties
└── LICENSE  NOTICE               Apache-2.0（同梱ネイティブと VAD モデルは MIT）
```

- 公開パッケージは `jp.clip.whisper` だけ。利用側が触る型は 6 つ（README のクラス構成表）。
- Maven 座標: `jp.clip:whisper-ffm:<version>`
- **`gradle.properties` の version を上げたら `v<バージョン>` のタグを打って push する**（例 `v2.0.2`）。
  利用側の transcribe-shell の CI が `actions/checkout` の `ref:` でこのタグを固定して参照しているため、
  タグが無いとそちらの CI が落ちる。
- **公開リポジトリなので、README や `docs/` に実名・実録音のファイル名・文字起こしの実内容を書かない。**
  実測値は録音名を `sample-a` などの符号に、姓を「姓A」「姓B」…に匿名化して載せる（数値は実測のまま）。
- `settings.gradle` で `rootProject.name` を固定している。**消さないこと。**
  無いとフォルダ名が artifactId になり、座標がクローン場所に依存する。
- `.gitmodules` の submodule パスは変更しない。submodule は
  **ヘッダ（jextract の入力）とテスト用サンプル音声のために残している**。ビルドには使わない。

---

## ビルドとテスト

前提: **JDK 25**。Gradle はラッパー（9.7.1）が提供する。C++ のツールチェーンは不要
（公式バイナリが無いバージョンを使うときだけ CMake と MSVC が要る）。

```powershell
git submodule update --init --recursive
.\scripts\download-natives.ps1
.\scripts\download-test-model.ps1
.\scripts\download-vad-model.ps1
Move-Item .\ggml-silero-v6.2.0.bin .\src\main\resources\ -Force
.\gradlew.bat test
```

- jar に同梱して配布するときは `gradlew installNatives publishToMavenLocal`
  （`natives\` → `src/main/resources/<os>-<arch>/`、`natives.list` を自動生成）
- **jar 同梱のネイティブを使う経路は `gradlew installNatives jar smokeTestBundledNatives` で確かめる。**
  `test` は `natives\` があればそこから読むので、利用側アプリ（transcribe-shell 等）と同じ
  「jar の中から取り出す」経路を通らない。ネイティブの同梱・取り出し・`natives.list` を触ったら必ず実行する
  （`BundledNativesSmoke.java` の Javadoc に理由とクラスパスの都合を書いてある）
- 高速化の計測は `gradlew benchmark "-Pbench.audio=..." "-Pbench.models=..."`（`Benchmark.java` の Javadoc 参照）。
  モデルの取得は `scripts\download-model.ps1 <モデル名>` → `models\` に置かれる
- Gradle が `Could not initialize native services` で落ちる場合は
  `GRADLE_USER_HOME` を `C:\pr-work\.gradle-home` に向ける

---

## 作業の進め方

- **1 項目 1 コミット。** 各コミットの完了条件は `gradlew test` が全件パスすること。
- 構造変更（ファイル移動・リネーム）は `git mv` を使う。履歴を追えるようにするため。
- v1（JNI 版）の履歴は `v1-jni` ブランチとタグ `1.9.3-2`、および
  `../whisper-jni-custom-v1.bundle` に残してある。v2 の `main` は履歴を作り直したもの。
- 計画と実施記録は `docs/plan-ffm-v2.md` を参照。
