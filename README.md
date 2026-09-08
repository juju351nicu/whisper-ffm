# whisper-ffm

[whisper.cpp](https://github.com/ggml-org/whisper.cpp) を Java から使うためのライブラリです。
Java 22 の **FFM（Foreign Function &amp; Memory API、`java.lang.foreign`）** で `whisper.dll` /
`libwhisper.so` / `libwhisper.dylib` を直接呼びます。**JNI の C++ コードを持ちません。**

| | |
|---|---|
| whisper.cpp | **v1.9.3**（`src/main/native/whisper` submodule に固定） |
| Maven 座標 | `jp.clip:whisper-ffm` |
| バージョン | `2.0.1` |
| ビルド JDK | Java 25 |
| Gradle | 9.7.1（wrapper 同梱） |
| 生成バイトコード | **Java 22**（FFM が正式版になったバージョン） |

## なぜ FFM なのか

whisper.cpp の関数は、jextract が `whisper.h` から生成したバインディング
（`jp.clip.whisper.ffm.gen`）を通して呼びます。この方式には次の利点があります。

- **自作の C++ が要らない。** JNI 版では `whisper.h` を呼ぶブリッジ（`.cpp`）を自分で書いて
  ビルドし、シンボル名・クラス名・フィールド名の 3 種類の文字列を Java 側と手で合わせていました。
  FFM ではそのブリッジが丸ごと不要になり、必要なのは whisper.cpp 側の共有ライブラリだけです。
  公式リリースに OS 用のバイナリが添付されていればそれをそのまま置けます。添付が無いタグ
  （v1.9.3 の Windows がこれ）では whisper.cpp 自体を CMake でビルドしますが、それは本家の
  ビルド手順そのままで、こちらで保守する C++ コードはありません
  （`scripts/build-whisper.ps1` と `.github/workflows/windows-natives.yml` が自動でやります）。
- **whisper.cpp の更新に追従しやすい。** submodule を進めて
  `scripts/jextract-whisper.ps1` を実行するだけで、シグネチャや構造体レイアウトの変更が
  コンパイルエラーとして出ます。
- **配布物が薄い。** 同梱するのは whisper.cpp 側の共有ライブラリだけです。

> **JVM の起動オプションに `--enable-native-access=ALL-UNNAMED` を付けてください。**
> JDK 24 以降、無いとネイティブ呼び出しのたびに警告が出ます。実行可能 jar なら
> `MANIFEST.MF` に `Enable-Native-Access: ALL-UNNAMED` でも代替できます。

## 対応プラットフォーム

コードは Windows x64 / macOS（x64・arm64）/ Linux（x64・arm64）に対応しています
（`Platform` が OS と CPU を判定し、`src/main/resources/<os>-<arch>/` から取り出します）。

ただし**CI で検証しているのは Windows x64 だけ**です。他のプラットフォームは、
そのマシンでネイティブを用意（公式バイナリの取得、または submodule のビルド）してから
`installNatives publishToMavenLocal` する必要があり、まだ自動検証していません。
公開している jar にも Windows 用のネイティブしか入っていません。

GPU 版（Vulkan / CUDA）を使いたい場合は、そのビルドのライブラリを置いたディレクトリを
`WhisperConfig.nativeLibraryDirectory(path)` で指定してください。

## 導入

Maven Central には公開していません。ローカルにインストールして使います。

```powershell
.\scripts\download-natives.ps1                      # 公式バイナリを natives\ へ
.\gradlew.bat installNatives publishToMavenLocal     # jar に同梱して ~/.m2 へ
```

`installNatives` は実行中の OS に合わせて `natives/` から `src/main/resources/<os>-<arch>/` へ
コピーします。jar には同梱ファイルの一覧 `natives.list` も入るので、Spring Boot の
実行可能 jar（jar の中の jar）からでもネイティブを取り出せます。

### Maven

```xml
<dependency>
    <groupId>jp.clip</groupId>
    <artifactId>whisper-ffm</artifactId>
    <version>2.0.1</version>
</dependency>
```

### Gradle

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'jp.clip:whisper-ffm:2.0.1'
}
```

## 使い方

```java
import java.nio.file.Path;
import jp.clip.whisper.SamplingStrategy;
import jp.clip.whisper.Segment;
import jp.clip.whisper.TranscriptionResult;
import jp.clip.whisper.WhisperConfig;
import jp.clip.whisper.WhisperEngine;

WhisperConfig config = WhisperConfig.builder()
        .model(Path.of("ggml-large-v3-turbo-q5_0.bin"))
        .language("ja")                                    // 既定は "en"
        .threads(Runtime.getRuntime().availableProcessors())
        .vadEnabled(true)                                  // 無音区間を除去して高速化
        .build();

try (WhisperEngine engine = WhisperEngine.open(config))
{
    TranscriptionResult result = engine.transcribe(Path.of("input.wav"));

    System.out.println(result.text());
    System.out.printf("%d ms / RTF %.2f%n", result.elapsedMs(), result.realTimeFactor());

    for (Segment segment : result.segments())
    {
        System.out.printf("[%d-%d] %s%n", segment.startMs(), segment.endMs(), segment.text());
    }
}
```

### クラス構成

利用側が触るのは次の型だけです。

| クラス | 役割 |
|---|---|
| `WhisperEngine` | 入口。`AutoCloseable`。`open` / `transcribe` / `close` |
| `WhisperConfig` | 設定。`WhisperConfig.builder()` から組み立てる |
| `TranscriptionResult` | 結果全体。`text()` `segments()` `elapsedMs()` `realTimeFactor()` |
| `Segment` | 1 区間。`startMs()` `endMs()` `text()` `durationMs()` |
| `SamplingStrategy` | `GREEDY` / `BEAM_SEARCH` |
| `WhisperException` | 失敗時の非チェック例外 |
| `AudioFileReader` | 音声ファイル → 16kHz モノラルの `float[]`。自分でサンプルを扱うとき用 |

### 補足

- 時刻は**ミリ秒**です（whisper.cpp のセンチ秒からこの層で変換しています）。
- `transcribe(Path)` は 16kHz モノラル 16bit PCM 以外も、Java の標準変換で対応できる
  範囲であれば自動変換します。自前でデコードしている場合は `transcribe(float[])` を
  使ってください（16kHz モノラル、-1.0f〜1.0f 正規化）。
- `vadEnabled(true)` を指定すると、VAD モデルは jar 同梱のものが一時ファイルへ自動展開されます。
  自分で用意したモデルを使う場合は `vadModel(path)` を指定してください。
- **`WhisperEngine` はスレッド安全ではありません。** 並列処理したい場合はスレッドごとに
  インスタンスを生成してください（モデルの分だけメモリを消費します）。
- whisper.cpp 自身のログは `whisper.cpp` という名前の SLF4J ロガーへ流れます。
  モデル読み込み時の INFO を抑えたい場合はこの名前のレベルを WARN にしてください
  （Spring Boot なら `logging.level.whisper.cpp=WARN`）。

## デコーダの調整

`WhisperConfig` の既定値は **whisper.cpp の `whisper_full_default_params` と同じ**です。
このライブラリを挟んだせいで結果が変わることはありません。

| 設定 | 既定 | 対応する `whisper_full_params` |
|---|---|---|
| `bestOf` | 5 | `greedy.best_of` |
| `beamSize` | 5 | `beam_search.beam_size` |
| `temperatureIncrement` | 0.2 | `temperature_inc` |
| `entropyThreshold` | 2.4 | `entropy_thold` |
| `suppressNonSpeechTokens` | false | `suppress_nst` |
| `maxTextContext` | 16384 | `n_max_text_ctx` |
| `carryInitialPrompt` | false | `carry_initial_prompt` |

`bestOf` と `temperatureIncrement` は、認識が破綻したとき（`entropyThreshold` を割ったとき）に
温度を上げてやり直す動作を決めます。温度 0.0 では候補は 1 本しか走らないので、通常時の速度には
影響しません。`bestOf` を 1 以下にすると、whisper.cpp 内部で `n_decoders = max(1, best_of)` と
丸められ、やり直しが 1 候補になります。

> **実測での注意**: 「候補を増やせば繰り返しループから抜けやすい」というのは理屈であって、
> 手元の日本語会議録音では確認できていません。同じ音声で `best_of` を -1 と 5 で振っても
> 処理時間は同じ、文字数はむしろ 5 の方が 2.6% 少ないという結果でした（`docs/plan-ffm-v2.md`
> の「(a) の実測」）。**用途ごとに実測して決めてください。** 既定値を whisper.cpp に合わせているのは
> 「上流と同じ結果になること」を優先しているためで、日本語会議録音に最適だからではありません。

### 繰り返しループと初期プロンプト（`maxTextContext` / `carryInitialPrompt`）

whisper.cpp は音声を 30 秒のウィンドウに切って順に処理し、**前のウィンドウの出力を次のウィンドウの
プロンプトに引き継ぎます**。これが「同じ行が何十回も続く」繰り返しループの伝播経路です
（1 度出た行が次のウィンドウのプロンプトになり、さらに同じ行を誘発する）。faster-whisper には
`compression_ratio_threshold` による繰り返し検出がありますが、whisper.cpp には相当機能がありません。

- `maxTextContext`（`-mc` 相当）を小さくすると引き継ぎが減ります。**ただし 0 にすると
  `initialPrompt` も効かなくなります。** whisper.cpp のプロンプト構築が `if (n_max_text_ctx > 0)` の
  中にあるため、0 では初期プロンプトを含めて一切プロンプトを渡しません。
- `carryInitialPrompt` を true にすると、引き継ぎ用のバッファが**今回のウィンドウの出力だけ**になり、
  初期プロンプトは静的な別枠として**毎ウィンドウに前置**されます。固有名詞のヒントを音声全体に
  効かせたまま、繰り返しの伝播を短く抑えられます。

> **実測（2026-09-08、日本語の会議録音 12 分 38 秒 / small / VAD off / 参加者名のプロンプトあり）**:
> `carryInitialPrompt(true)` は**固有名詞には効くが繰り返しを増やしました**。ある姓が 2 回 → 7 回
> 正しく出るようになった一方で、**初期プロンプト自身が出力に漏れ、同じ 1 行が 42 行連続**しました
> （文字数 3,542 → 4,713 に増えたのはこの水増し）。毎ウィンドウ前置すると語彙への影響が強まる代わりに、
> モデルがプロンプトを復唱し始めます。**この条件では採用できません。**
>
> 未検証の中間案として、プロンプトを「会議の文」ではなく名前の羅列だけにする、あるいは
> 極端に短くすると復唱が減る可能性があります。試す場合は必ず同一音声で A/B を取ってください。

プロンプトを使わないなら `maxTextContext(0)` が最も単純な対策になります。
プロンプトを使う場合は、`maxTextContext` も `carryInitialPrompt` も既定のままにするのが
現時点で確認できている最良の組み合わせです。

`suppressNonSpeechTokens` を true にすると非発話トークンを抑制します。対象は whisper.cpp が
持つ**固定の記号リスト**（``" # ( ) * + / : ; < = > @ [ \ ] ^ _ ` { | } ~ 「 」 『 』 ♪ ♫ ♬`` など）で、
`【` `】` は含まれません。したがって「【アイテム】」のような字幕由来のハルシネーションは
この設定では止まりません。議事録用途では記号の注記が邪魔なので true を指定してください。

```java
WhisperConfig config = WhisperConfig.builder()
        .model(model)
        .language("ja")
        .suppressNonSpeechTokens(true)   // 注記を出さない
        .initialPrompt("姓Cさん、姓Dさんが参加する定例会議です。")
        .build();
```

## 文法（GBNF）

出力を [GBNF 文法](https://github.com/ggml-org/whisper.cpp/blob/master/grammars/)で制約できます。

```java
WhisperConfig config = WhisperConfig.builder()
        .model(model)
        .grammar(Files.readString(Path.of("my_grammar.gbnf")))
        .grammarPenalty(100.0f)
        .build();
```

whisper.cpp の GBNF 解析器は `examples/` にあり共有ライブラリにエクスポートされていないため、
`GbnfGrammar` として Java で実装しています。文法が壊れている場合は、モデルを読み込む前に
`WhisperException` になります。

## ビルドとテスト

必要なもの: **JDK 25**（`JAVA_HOME` がそれを指していること）。Gradle は wrapper が提供します。
C++ のツールチェーンは、公式バイナリを使う限り不要です。

```powershell
git submodule update --init --recursive

.\scripts\download-natives.ps1        # 公式バイナリを natives\ へ
                                      # そのタグに Windows バイナリが無ければ
                                      # .\scripts\build-whisper.ps1（CMake と MSVC が要る）

.\scripts\download-test-model.ps1     # ggml-tiny.bin
.\scripts\download-vad-model.ps1
Move-Item .\ggml-silero-v6.2.0.bin .\src\main\resources\ -Force

.\gradlew.bat test
```

処理速度の計測は次のとおりです（詳細は `src/test/java/jp/clip/whisper/Benchmark.java` の Javadoc）。

```powershell
.\scripts\download-model.ps1 small
.\gradlew.bat benchmark "-Pbench.audio=C:\audio\meeting.wav" "-Pbench.models=models\ggml-small.bin"
```

### バインディングの再生成

whisper.cpp の submodule を更新したら、FFM バインディングを作り直します。

```powershell
.\scripts\jextract-whisper.ps1
```

jextract は **JDK 22 向け**のビルドを `C:\tools\jextract` に置いてください。JDK 25 向けの
生成コードは JDK 23 で追加された API を使うため、`options.release = 22` ではコンパイルできません
（経緯は `docs/ffm-p0-report.md`）。

### Lombok

`WhisperConfig` は [Lombok](https://projectlombok.org/)（`@Value` / `@Builder`）でビルダーと
アクセサを生成しています。Lombok はコンパイル時のみの依存で、生成された jar を使う側には
一切影響しません（`compileOnly`）。Gradle ビルドは `io.freefair.lombok` プラグインが自動設定
しますが、**IDE でソースを開く場合は IDE 側の Lombok 対応が必要**です。

## ライセンス

Apache License, Version 2.0 — [LICENSE](LICENSE) を参照してください。

同梱するネイティブライブラリと VAD モデルは MIT ライセンスです。帰属表示は
[NOTICE](NOTICE) にあります。
