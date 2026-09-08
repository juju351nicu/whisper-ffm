package jp.clip.whisper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WhisperEngine} の結合テスト。実際にモデルを読み込んで whisper.cpp を動かします。
 *
 * <p>
 * 検証するのは <b>API 層の振る舞い</b>（構造・時刻の単位・設定の反映・例外・後始末）です。
 * 認識結果の文字列は原則「一部が含まれること」で確認します。whisper.cpp を更新したときに
 * 句読点の揺れで落ちないようにするためです。
 * </p>
 *
 * <p>
 * 必要なもの: {@code ggml-tiny.bin}（{@code scripts/download-test-model}）と、
 * ネイティブライブラリ（{@code natives/} があればそこから、無ければ jar 同梱のもの）。
 * </p>
 */
public class WhisperEngineTest
{
	private static final Logger LOG = LoggerFactory.getLogger(WhisperEngineTest.class);

	private static final Path MODEL_PATH = Path.of("ggml-tiny.bin");
	private static final Path SAMPLE_PATH = Path.of("src/main/native/whisper/samples/jfk.wav");

	/** ネイティブの置き場所。取得スクリプトの出力があればそこから読む。 */
	private static Path nativeDirectory = null;

	@BeforeAll
	public static void beforeAll()
	{
		if(!Files.isRegularFile(MODEL_PATH))
		{
			throw new IllegalStateException("モデルがありません: " + MODEL_PATH.toAbsolutePath()
					+ " / scripts/download-test-model を先に実行してください。");
		}
		if(!Files.isRegularFile(SAMPLE_PATH))
		{
			throw new IllegalStateException("サンプル音声がありません: " + SAMPLE_PATH.toAbsolutePath());
		}

		Path natives = Path.of("natives");
		if(Files.isDirectory(natives))
		{
			LOG.info("ネイティブを {} から読み込みます", natives);
			nativeDirectory = natives;
		}
		else
		{
			LOG.info("jar 同梱のネイティブを読み込みます");
		}
	}

	private static WhisperConfig.WhisperConfigBuilder baseConfig()
	{
		WhisperConfig.WhisperConfigBuilder builder = WhisperConfig.builder()
				.model(MODEL_PATH)
				.language("en");
		if(nativeDirectory != null)
		{
			builder.nativeLibraryDirectory(nativeDirectory);
		}
		return builder;
	}

	// ------------------------------------------------------------------------
	// 設定（ネイティブ不要）
	// ------------------------------------------------------------------------

	@Test
	public void builderRequiresModel()
	{
		// model は @NonNull。Lombok が生成する null チェックは NullPointerException を投げる
		NullPointerException exception = assertThrows(NullPointerException.class, () -> WhisperConfig.builder().build());
		assertTrue(exception.getMessage().contains("model"), exception.getMessage());
	}

	@Test
	public void toBuilderCopiesEverySetting()
	{
		WhisperConfig original = WhisperConfig.builder().model(MODEL_PATH).language("ja").threads(3).vadEnabled(true).build();
		WhisperConfig derived = original.toBuilder().samplingStrategy(SamplingStrategy.BEAM_SEARCH).build();
		assertEquals("ja", derived.language());
		assertEquals(3, derived.threads());
		assertTrue(derived.vadEnabled());
		assertEquals(SamplingStrategy.BEAM_SEARCH, derived.samplingStrategy());
		assertEquals(SamplingStrategy.GREEDY, original.samplingStrategy(), "元の設定は変わらない");
	}

	/**
	 * 既定値は whisper.cpp の {@code whisper_full_default_params} と同じであること。
	 *
	 * <p>
	 * ここがずれると、このライブラリを通したときだけ認識結果が変わることになります。
	 * </p>
	 */
	@Test
	public void defaultsMatchWhisperCpp()
	{
		WhisperConfig config = WhisperConfig.builder().model(MODEL_PATH).build();
		assertEquals("en", config.language());
		assertEquals(SamplingStrategy.GREEDY, config.samplingStrategy());
		assertEquals(0, config.threads(), "0 は whisper.cpp の既定に任せるという意味");
		assertTrue(config.useGpu());
		assertFalse(config.translateToEnglish());
		assertFalse(config.vadEnabled());

		assertEquals(5, config.bestOf(), "greedy.best_of");
		assertEquals(5, config.beamSize(), "beam_search.beam_size");
		assertEquals(0.2f, config.temperatureIncrement(), "temperature_inc");
		assertEquals(2.4f, config.entropyThreshold(), "entropy_thold");
		assertFalse(config.suppressNonSpeechTokens(), "suppress_nst");
		assertEquals(16384, config.maxTextContext(), "n_max_text_ctx");
		assertFalse(config.carryInitialPrompt(), "carry_initial_prompt");
		assertEquals(100.0f, config.grammarPenalty(), "grammar_penalty");

		assertEquals(0.5f, config.vadThreshold());
		assertEquals(250, config.vadMinSpeechDurationMs());
		assertEquals(100, config.vadMinSilenceDurationMs());
		assertEquals(30, config.vadSpeechPadMs());
		assertEquals(0.1f, config.vadSamplesOverlap());
	}

	/**
	 * {@code n_max_text_ctx} と {@code carry_initial_prompt} が構造体の正しい位置に書けていることを確かめます。
	 *
	 * <p>
	 * どちらも既定から外れた値を渡しても文字起こしが壊れないことだけを見ます（jfk.wav は 30 秒に収まる
	 * 1 ウィンドウなので、引き継ぎの有無そのものは結果に現れません）。オフセットを間違えると別の
	 * フィールドを壊してクラッシュするか結果が崩れるため、この程度でも十分な番人になります。
	 * </p>
	 */
	@Test
	public void contextSettingsAreAcceptedByNativeLayer()
	{
		WhisperConfig noCarryOver = baseConfig()
				.maxTextContext(0)
				.initialPrompt("This is a speech by a president.")
				.build();
		try(WhisperEngine engine = WhisperEngine.open(noCarryOver))
		{
			assertTrue(engine.transcribe(SAMPLE_PATH).text().contains("fellow Americans"));
		}

		WhisperConfig carryOver = baseConfig()
				.carryInitialPrompt(true)
				.initialPrompt("This is a speech by a president.")
				.build();
		try(WhisperEngine engine = WhisperEngine.open(carryOver))
		{
			assertTrue(engine.transcribe(SAMPLE_PATH).text().contains("fellow Americans"));
		}
	}

	@Test
	public void openWithMissingModelFails()
	{
		WhisperConfig config = WhisperConfig.builder()
				.model(Path.of("does-not-exist-model.bin"))
				.build();
		assertThrows(WhisperException.class, () -> WhisperEngine.open(config));
	}

	@Test
	public void readAudioSamplesWithMissingFileFails()
	{
		assertThrows(WhisperException.class, () -> AudioFileReader.readSamples(Path.of("no-such.wav")));
	}

	@Test
	public void readAudioSamplesReturnsSixteenKilohertzMono()
	{
		float[] samples = AudioFileReader.readSamples(SAMPLE_PATH);
		assertTrue(samples.length > 0);
		// jfk.wav は約 11 秒。16kHz モノラルなら 16000 * 11 前後になる
		assertTrue(samples.length > WhisperEngine.SAMPLE_RATE * 5,
				"サンプル数が少なすぎます: " + samples.length);
		for(float sample : samples)
		{
			assertTrue(sample >= -1.0f && sample <= 1.0f, "正規化されていません: " + sample);
		}
	}

	// ------------------------------------------------------------------------
	// 文字起こし
	// ------------------------------------------------------------------------

	@Test
	public void transcribeFromSampleArray()
	{
		float[] samples = AudioFileReader.readSamples(SAMPLE_PATH);
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			TranscriptionResult result = engine.transcribe(samples);
			assertFalse(result.isEmpty(), "セグメントが空です");
			LOG.info("結果: {}", result.text());
			assertTrue(result.text().contains("fellow Americans"),
					"想定した語が含まれていません: " + result.text());
			assertTrue(result.elapsedMs() > 0L);
		}
	}

	@Test
	public void transcribeFromAudioFile()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			assertFalse(result.isEmpty());
			assertTrue(result.text().contains("fellow Americans"),
					"想定した語が含まれていません: " + result.text());
		}
	}

	@Test
	public void timestampsAreInMilliseconds()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			List<Segment> segments = result.segments();
			assertFalse(segments.isEmpty());

			Segment first = segments.get(0);
			Segment last = segments.get(segments.size() - 1);
			assertEquals(0L, first.startMs());
			// jfk.wav は約 10.5 秒 = 10500 ミリ秒。センチ秒のままなら 1050 になるので単位を検証できる
			assertTrue(last.endMs() > 8000L && last.endMs() < 13000L,
					"終了時刻がミリ秒になっていません: " + last.endMs());
			assertTrue(last.durationMs() > 0L);
		}
	}

	@Test
	public void realTimeFactorUsesAudioLengthNotLastSegment()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			float[] samples = AudioFileReader.readSamples(SAMPLE_PATH);
			TranscriptionResult result = engine.transcribe(samples);

			// 分母はサンプル数から求めた音声長。jfk.wav は約 11 秒
			long expectedAudioMs = (long) samples.length * 1000L / WhisperEngine.SAMPLE_RATE;
			assertEquals(expectedAudioMs, result.audioMs());
			assertTrue(result.audioMs() > 10000L && result.audioMs() < 12000L, "音声長がおかしい: " + result.audioMs());
			assertEquals((double) result.elapsedMs() / (double) result.audioMs(), result.realTimeFactor(), 1e-9);
			LOG.info("RTF = {} ({} ms / {} ms)", result.realTimeFactor(), result.elapsedMs(), result.audioMs());
		}
	}

	@Test
	public void emptyInputHasNaNRealTimeFactor()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			TranscriptionResult result = engine.transcribe(new float[0]);
			assertTrue(result.isEmpty());
			assertEquals(0L, result.audioMs());
			assertTrue(Double.isNaN(result.realTimeFactor()));
		}
	}

	@Test
	public void emptySamplesReturnEmptyResult()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			TranscriptionResult result = engine.transcribe(new float[0]);
			assertTrue(result.isEmpty());
			assertEquals("", result.text());
		}
	}

	@Test
	public void transcribeWithBeamSearch()
	{
		try(WhisperEngine engine = WhisperEngine
				.open(baseConfig().samplingStrategy(SamplingStrategy.BEAM_SEARCH).build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			assertTrue(result.text().contains("fellow Americans"), result.text());
		}
	}

	@Test
	public void transcribeWithVad()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().vadEnabled(true).vadThreshold(0.5f).build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			LOG.info("VAD 有効時のセグメント数: {}", result.segments().size());
			assertFalse(result.isEmpty());
			for(Segment segment : result.segments())
			{
				assertTrue(segment.endMs() >= segment.startMs());
			}
		}
	}

	/**
	 * 文法で出力を 1 つの文字列に固定できること。
	 *
	 * <p>
	 * 文法の解析は {@link GbnfGrammar}（純 Java）が行い、その結果を
	 * {@code whisper_full_params.grammar_rules} へ渡しています。
	 * </p>
	 */
	@Test
	public void transcribeWithGrammar()
	{
		String grammar = "root ::= \" And so, my fellow Americans, ask not what your country can do for you,"
				+ " ask what you can do for your country.\"";
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().grammar(grammar).build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			assertFalse(result.isEmpty());
			LOG.info("文法適用後: {}", result.text());
		}
	}

	@Test
	public void brokenGrammarFailsBeforeLoadingTheModel()
	{
		WhisperConfig config = baseConfig().grammar("root ::= undefined-rule").build();
		WhisperException exception = assertThrows(WhisperException.class, () -> WhisperEngine.open(config));
		assertTrue(exception.getMessage().contains("文法の解析に失敗"), exception.getMessage());
	}

	@Test
	public void initialPromptIsAccepted()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().initialPrompt("John F. Kennedy, inaugural address.").build()))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			assertFalse(result.isEmpty());
			LOG.info("初期プロンプトあり: {}", result.text());
		}
	}

	// ------------------------------------------------------------------------
	// 照会と後始末
	// ------------------------------------------------------------------------

	@Test
	public void systemInfoIsNotBlank()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			String info = engine.systemInfo();
			assertNotNull(info);
			assertFalse(info.isBlank());
			LOG.info("whisper.cpp: {}", info);
		}
	}

	@Test
	public void modelIsMultilingual()
	{
		try(WhisperEngine engine = WhisperEngine.open(baseConfig().build()))
		{
			assertTrue(engine.isMultilingual());
		}
	}

	@Test
	public void useAfterCloseFails()
	{
		WhisperEngine engine = WhisperEngine.open(baseConfig().build());
		engine.close();
		// close は何度呼んでも安全
		engine.close();
		assertThrows(WhisperException.class, () -> engine.transcribe(new float[] { 0.0f, 0.1f }));
	}

	@Test
	public void configIsReadableFromTheEngine()
	{
		WhisperConfig config = baseConfig().language("ja").threads(2).build();
		try(WhisperEngine engine = WhisperEngine.open(config))
		{
			assertEquals(config, engine.config());
			assertEquals("ja", engine.config().language());
			assertEquals(2, engine.config().threads());
		}
	}
}
