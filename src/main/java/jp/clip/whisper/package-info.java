/**
 * whisper.cpp による文字起こしの高水準 API。<b>利用側は原則このパッケージだけを使います。</b>
 *
 * <p>
 * 入口は {@link jp.clip.whisper.WhisperEngine} です。設定は {@link jp.clip.whisper.WhisperConfig} の
 * ビルダーで組み立て、結果は {@link jp.clip.whisper.TranscriptionResult}（{@link jp.clip.whisper.Segment} の列）
 * で受け取ります。時刻はすべて<b>ミリ秒</b>です。
 * </p>
 *
 * <pre>
 * WhisperConfig config = WhisperConfig.builder()
 * 		.model(Path.of("ggml-large-v3-turbo-q5_0.bin"))
 * 		.language("ja")
 * 		.vadEnabled(true)
 * 		.build();
 *
 * try(WhisperEngine engine = WhisperEngine.open(config))
 * {
 * 	TranscriptionResult result = engine.transcribe(Path.of("input.wav"));
 * 	System.out.println(result.text());
 * }
 * </pre>
 *
 * <p>
 * ネイティブライブラリの読み込み、ネイティブメモリの解放、パラメータの変換はこのパッケージが
 * 受け持ちます。whisper.cpp の関数は Java 22 の FFM（{@code java.lang.foreign}）で直接呼んでいて、
 * その定義は jextract が生成した {@link jp.clip.whisper.ffm.gen} にあります（手で編集しないこと）。
 * </p>
 *
 * <p>
 * <b>JVM の起動オプションに {@code --enable-native-access=ALL-UNNAMED} を付けてください。</b>
 * 無い場合、JDK 24 以降ではネイティブ呼び出しのたびに警告が出ます。
 * </p>
 */
package jp.clip.whisper;
