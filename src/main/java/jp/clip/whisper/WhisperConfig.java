package jp.clip.whisper;

import java.nio.file.Path;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * {@link WhisperEngine} の設定。{@link #builder()} から組み立てます。
 *
 * <p>
 * モデル以外はすべて既定値を持ちます。日本語を扱う場合は {@code language("ja")} を
 * 明示してください（既定は whisper.cpp と同じ {@code "en"} です）。
 * </p>
 *
 * <pre>
 * WhisperConfig config = WhisperConfig.builder()
 * 		.model(Path.of("ggml-large-v3-turbo-q5_0.bin"))
 * 		.language("ja")
 * 		.threads(Runtime.getRuntime().availableProcessors())
 * 		.vadEnabled(true)
 * 		.build();
 *
 * // 一部だけ変えた設定を派生させる
 * WhisperConfig beam = config.toBuilder().samplingStrategy(SamplingStrategy.BEAM_SEARCH).build();
 * </pre>
 *
 * <p>
 * 各フィールドに対して、同名のビルダーメソッド（設定）とアクセサ（取得）が Lombok により
 * 生成されます。フィールドを 1 つ足せば両方が揃うので、設定項目を増やすときはフィールドと
 * その Javadoc だけを追加してください。
 * </p>
 */
@Value
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Accessors(fluent = true)
public class WhisperConfig
{
	// ------------------------------------------------------------------------
	// モデルと言語
	// ------------------------------------------------------------------------

	/** ggml モデルファイルのパス。<b>必須。</b>未設定で {@code build()} すると {@link NullPointerException}。 */
	@NonNull
	Path model;

	/** 言語コード（例 {@code "ja"}、{@code "en"}）。既定は {@code "en"}。 */
	@Builder.Default
	String language = "en";

	/** true なら言語を自動判定する。既定は false。 */
	@Builder.Default
	boolean detectLanguage = false;

	/** true なら英語に翻訳して出力する。既定は false。 */
	@Builder.Default
	boolean translateToEnglish = false;

	/** 初期プロンプト。固有名詞の認識精度を上げるのに使える。未設定なら null。 */
	String initialPrompt;

	// ------------------------------------------------------------------------
	// 実行方法
	// ------------------------------------------------------------------------

	/** 使用スレッド数。0 なら whisper.cpp の既定に任せる。既定は 0。 */
	@Builder.Default
	int threads = 0;

	/** サンプリング戦略。既定は {@link SamplingStrategy#GREEDY}。 */
	@Builder.Default
	SamplingStrategy samplingStrategy = SamplingStrategy.GREEDY;

	/** true なら GPU バックエンドを使う（ビルドが対応している場合）。既定は true。 */
	@Builder.Default
	boolean useGpu = true;

	/**
	 * ネイティブライブラリを読み込むディレクトリ。Vulkan / CUDA 版を使いたい場合に指定する。
	 * null なら jar 同梱の CPU 版を使う。
	 */
	Path nativeLibraryDirectory;

	/** true なら whisper.cpp の進捗ログを出力する。既定は false。 */
	@Builder.Default
	boolean printNativeProgress = false;

	// ------------------------------------------------------------------------
	// デコーダの調整 — 既定値はすべて whisper.cpp と同じ
	// ------------------------------------------------------------------------

	/**
	 * {@link SamplingStrategy#GREEDY} のとき、温度フォールバック時に走らせる候補デコーダの数。
	 * {@code whisper_full_params.greedy.best_of}。既定は 5（whisper.cpp と同じ）。
	 *
	 * <p>
	 * 認識が破綻して（{@link #entropyThreshold} などを割って）やり直すときだけ効きます。
	 * 温度 0.0 では 1 本しか走らないので、通常時の速度には影響しません。
	 * 1 以下にすると候補が 1 本に丸められ、同じ行が繰り返されるループから抜けにくくなります。
	 * </p>
	 */
	@Builder.Default
	int bestOf = 5;

	/**
	 * {@link SamplingStrategy#BEAM_SEARCH} のときのビーム幅。
	 * {@code whisper_full_params.beam_search.beam_size}。既定は 5（whisper.cpp と同じ）。
	 *
	 * <p>
	 * 大きいほど精度が上がる代わりに比例して遅くなります。
	 * </p>
	 */
	@Builder.Default
	int beamSize = 5;

	/**
	 * 認識が破綻したときに温度を上げ直す刻み幅。{@code whisper_full_params.temperature_inc}。
	 * 既定は 0.2（whisper.cpp と同じ）。
	 *
	 * <p>
	 * 温度 0.0 から 1.0 まで、この刻みでやり直します。0 にするとフォールバックしません。
	 * </p>
	 */
	@Builder.Default
	float temperatureIncrement = 0.2f;

	/**
	 * 非発話トークン（{@code [音楽]} {@code (笑)} {@code ♪} のような注記）を抑制するかどうか。
	 * {@code whisper_full_params.suppress_nst}。既定は false（whisper.cpp と同じ）。
	 *
	 * <p>
	 * 議事録のように注記が邪魔になる用途では true にしてください。
	 * </p>
	 */
	@Builder.Default
	boolean suppressNonSpeechTokens = false;

	/**
	 * この値を下回る確率のセグメントは信頼できないとみなしてやり直す。
	 * {@code whisper_full_params.entropy_thold}。既定は 2.4（whisper.cpp と同じ）。
	 */
	@Builder.Default
	float entropyThreshold = 2.4f;

	// ------------------------------------------------------------------------
	// VAD（無音区間の除去）— 長い録音では処理時間が大きく減る
	// ------------------------------------------------------------------------

	/** true なら VAD で無音区間を除去する。既定は false。 */
	@Builder.Default
	boolean vadEnabled = false;

	/** VAD モデル（silero）のパス。null なら jar 同梱のモデルを使う。 */
	Path vadModel;

	/** VAD の音声判定しきい値（0.0〜1.0）。上げると無音と判定されやすくなる。既定は 0.5。 */
	@Builder.Default
	float vadThreshold = 0.5f;

	/** VAD が音声区間とみなす最小長（ミリ秒）。既定は 250。 */
	@Builder.Default
	int vadMinSpeechDurationMs = 250;

	/** VAD が区間を分割する最小無音長（ミリ秒）。既定は 100。 */
	@Builder.Default
	int vadMinSilenceDurationMs = 100;

	/** VAD の 1 区間あたり最大長（秒）。既定は上限なし。 */
	@Builder.Default
	float vadMaxSpeechDurationSeconds = Float.MAX_VALUE;

	/** VAD 区間の前後に付けるパディング（ミリ秒）。語頭・語尾の切れを防ぐ。既定は 30。 */
	@Builder.Default
	int vadSpeechPadMs = 30;

	/** VAD 区間同士の重なり（秒）。既定は 0.1。 */
	@Builder.Default
	float vadSamplesOverlap = 0.1f;

	// ------------------------------------------------------------------------
	// 文法
	// ------------------------------------------------------------------------

	/** GBNF 文法のテキスト。出力を文法に沿った形に制約できる。未設定なら null。 */
	String grammar;

	/** 文法から外れたトークンへのペナルティ。既定は 100。 */
	@Builder.Default
	float grammarPenalty = 100.0f;

	/**
	 * 文法が設定されているかどうかを返します。
	 *
	 * @return {@link #grammar()} が非 null かつ空白以外を含むなら true
	 */
	public boolean hasGrammar()
	{
		return this.grammar != null && !this.grammar.isBlank();
	}
}
