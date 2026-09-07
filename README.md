# whisper-ffm

[whisper.cpp](https://github.com/ggml-org/whisper.cpp) を Java から使うためのライブラリです。
Java 22 の **FFM（Foreign Function &amp; Memory API、`java.lang.foreign`）** で `whisper.dll` /
`libwhisper.so` / `libwhisper.dylib` を直接呼びます。**JNI の C++ コードを持ちません。**

| | |
|---|---|
| whisper.cpp | **v1.9.3**（`src/main/native/whisper` submodule に固定） |
| Maven 座標 | `jp.clip:whisper-ffm` |
| バージョン | `2.0.0` |
| ビルド JDK | Java 25 |
| Gradle | 9.7.1（wrapper 同梱） |
| 生成バイトコード | **Java 22**（FFM が正式版になったバージョン） |

## なぜ FFM なのか

whisper.cpp の関数は、jextract が `whisper.h` から生成したバインディング
（`jp.clip.whisper.ffm.gen`）を通して呼びます。この方式には次の利点があります。

- **C++ のビルドが要らない。** whisper.cpp の公式リリースバイナリをそのまま置けます。
  新しい OS やアーキテクチャに対応するのに、こちらでツールチェーンを用意する必要がありません。
- **whisper.cpp の更新に追従しやすい。** submodule を進めて
  `scripts/jextract-whisper.ps1` を実行するだけで、シグネチャや構造体レイアウトの変更が
  コンパイルエラーとして出ます。
- **配布物が薄い。** 同梱するのは whisper.cpp 側の共有ライブラリだけです。

> **JVM の起動オプションに `--enable-native-access=ALL-UNNAMED` を付けてください。**
> JDK 24 以降、無いとネイティブ呼び出しのたびに警告が出ます。実行可能 jar なら
> `MANIFEST.MF` に `Enable-Native-Access: ALL-UNNAMED` でも代替できます。

## 対応プラットフォーム

Windows x64 / macOS（x64・arm64）/ Linux（x64・arm64）。jar には、ビルド時に
`src/main/resources/<os>-<arch>/` へ置いたネイティブが同梱されます。

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
    <version>2.0.0</version>
</dependency>
```

### Gradle

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'jp.clip:whisper-ffm:2.0.0'
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

`bestOf` と `temperatureIncrement` は、認識が破綻したとき（`entropyThreshold` を割ったとき）に
温度を上げてやり直す動作を決めます。温度 0.0 では候補は 1 本しか走らないので、通常時の速度には
影響しません。**`bestOf` を 1 以下にするとやり直しが 1 候補に丸められ、同じ行が繰り返される
ループから抜けにくくなります。**

`suppressNonSpeechTokens` を true にすると `[音楽]` `(笑)` `♪` のような注記を抑制します。
議事録用途では true を指定してください。

```java
WhisperConfig config = WhisperConfig.builder()
        .model(model)
        .language("ja")
        .suppressNonSpeechTokens(true)   // 注記を出さない
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
