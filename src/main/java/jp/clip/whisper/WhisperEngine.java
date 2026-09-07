package jp.clip.whisper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * whisper.cpp による文字起こしの入口となるクラス。
 *
 * <p>
 * ネイティブライブラリのロード、コンテキストの生成・破棄、パラメータの組み立てを
 * すべてこのクラスが隠します。利用側が触るのはこのクラスと {@link WhisperConfig} だけです。
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
 * try(WhisperEngine engine = WhisperEngine.open(config))
 * {
 * 	TranscriptionResult result = engine.transcribe(Path.of("input.wav"));
 * 	System.out.println(result.text());
 * }
 * </pre>
 *
 * <p>
 * <b>スレッド安全ではありません。</b>1 つのインスタンスに対して複数スレッドから同時に
 * {@code transcribe} を呼ばないでください。並列に処理したい場合はスレッドごとに
 * インスタンスを生成してください（モデルの分だけメモリを消費します）。
 * </p>
 *
 * <p>
 * whisper.cpp 自身のログは {@code "whisper.cpp"} という名前の SLF4J ロガーへ流れます。
 * 詳細は {@link NativeRuntime#NATIVE_LOGGER_NAME} を参照してください。
 * </p>
 */
public final class WhisperEngine implements AutoCloseable
{
	/** whisper.cpp が要求するサンプリングレート（Hz）。 */
	public static final int SAMPLE_RATE = 16000;

	private final WhisperConfig config;
	private final NativeSession session;

	private boolean closed = false;

	private WhisperEngine(WhisperConfig config, NativeSession session)
	{
		this.config = config;
		this.session = session;
	}

	// ------------------------------------------------------------------------
	// 生成
	// ------------------------------------------------------------------------

	/**
	 * エンジンを生成します。モデルの読み込みが完了した状態で返ります。
	 *
	 * <p>
	 * ネイティブライブラリは JVM 内で 1 度だけロードされます（2 回目以降の呼び出しでは
	 * 再ロードしません）。
	 * </p>
	 *
	 * @param config 設定
	 * @return 使用可能な {@link WhisperEngine}
	 * @throws WhisperException ネイティブのロード、モデルの読み込み、文法の解析に失敗した場合
	 */
	public static WhisperEngine open(WhisperConfig config)
	{
		if(config == null)
		{
			throw new WhisperException("config が null です。");
		}
		if(!Files.isRegularFile(config.model()))
		{
			throw new WhisperException("モデルファイルが見つかりません: " + config.model().toAbsolutePath());
		}

		NativeRuntime.ensureNativesLoaded(config);
		return new WhisperEngine(config, NativeSession.open(config));
	}

	// ------------------------------------------------------------------------
	// 文字起こし
	// ------------------------------------------------------------------------

	/**
	 * 音声ファイルを文字起こしします。
	 *
	 * <p>
	 * 16kHz モノラル 16bit PCM 以外のファイルも、Java の標準変換で対応できる範囲であれば
	 * 自動的に変換します（{@link AudioFileReader} 参照）。
	 * </p>
	 *
	 * @param audioFile 音声ファイル（WAV など Java が読める形式）
	 * @return 文字起こし結果
	 * @throws WhisperException 読み込み・変換・文字起こしに失敗した場合
	 */
	public TranscriptionResult transcribe(Path audioFile)
	{
		return this.transcribe(AudioFileReader.readSamples(audioFile));
	}

	/**
	 * 16kHz モノラルの float サンプル列を文字起こしします。
	 *
	 * <p>
	 * 各サンプルは -1.0f〜1.0f に正規化された値である必要があります。音声のデコードを
	 * 自前で行っている場合はこちらを使ってください。
	 * </p>
	 *
	 * @param samples 16kHz モノラルの正規化済みサンプル列
	 * @return 文字起こし結果。サンプルが空なら空の結果
	 * @throws WhisperException whisper.cpp が失敗した場合
	 */
	public TranscriptionResult transcribe(float[] samples)
	{
		this.assertOpen();
		if(samples == null || samples.length == 0)
		{
			return new TranscriptionResult(List.of(), 0L, 0L);
		}

		long startedAt = System.nanoTime();
		List<Segment> segments = this.session.transcribe(samples);
		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

		return new TranscriptionResult(segments, elapsedMs, audioMillis(samples.length));
	}

	/** サンプル数を音声長（ミリ秒）に換算します。16kHz なので 16 サンプル = 1 ミリ秒。 */
	private static long audioMillis(int sampleCount)
	{
		return (long) sampleCount * 1000L / SAMPLE_RATE;
	}

	// ------------------------------------------------------------------------
	// 照会と後始末
	// ------------------------------------------------------------------------

	/**
	 * 読み込んだモデルが多言語対応かどうかを返します。
	 *
	 * @return 多言語モデルなら true
	 */
	public boolean isMultilingual()
	{
		this.assertOpen();
		return this.session.isMultilingual();
	}

	/**
	 * whisper.cpp のシステム情報（有効な CPU 命令セットやバックエンド）を返します。
	 *
	 * @return システム情報の文字列
	 */
	public String systemInfo()
	{
		this.assertOpen();
		return NativeSession.systemInfo();
	}

	/**
	 * このエンジンの設定を返します。
	 *
	 * @return 設定
	 */
	public WhisperConfig config()
	{
		return this.config;
	}

	/**
	 * ネイティブメモリを解放します。以降このインスタンスは使用できません。多重呼び出しは安全です。
	 */
	@Override
	public void close()
	{
		if(this.closed)
		{
			return;
		}
		this.closed = true;
		this.session.close();
	}

	private void assertOpen()
	{
		if(this.closed)
		{
			throw new WhisperException("この WhisperEngine は既に close されています。");
		}
	}
}
