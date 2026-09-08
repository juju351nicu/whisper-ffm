package jp.clip.whisper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.stream.IntStream;

import jp.clip.whisper.ffm.gen.whisper_context_params;
import jp.clip.whisper.ffm.gen.whisper_full_params;
import jp.clip.whisper.ffm.gen.whisper_grammar_element;
import jp.clip.whisper.ffm.gen.whisper_h;
import jp.clip.whisper.ffm.gen.whisper_vad_params;

/**
 * 読み込んだモデル 1 つ分のネイティブ状態（{@code whisper_context *}）を持ち、文字起こしを実行します。
 *
 * <p>
 * whisper.cpp の関数は Java 22 の FFM（Foreign Function &amp; Memory API）で直接呼びます。関数と
 * 構造体の定義は jextract の生成物 {@code jp.clip.whisper.ffm.gen} です
 * （{@code scripts/jextract-whisper.ps1} で作り直せます）。
 * </p>
 *
 * <p>
 * プロセス全体で 1 度だけ行う初期化（ライブラリの読み込み、ログの接続）は {@link NativeRuntime} が
 * 受け持ちます。こちらはモデル 1 つ分の寿命だけを見ます。
 * </p>
 *
 * <p>
 * <b>ライブラリの読み込みについて。</b>生成物の {@code whisper_h} はシンボルを
 * {@code SymbolLookup.loaderLookup()} で探します。これは <b>{@code whisper_h} と同じクラスローダー
 * から {@link System#load(String)} された</b>ライブラリしか見つけません。{@link NativeLibraryLoader}
 * が同じクラスローダーで読み込むので条件を満たします。
 * </p>
 */
final class NativeSession implements AutoCloseable
{
	/** whisper.cpp のタイムスタンプはセンチ秒単位なのでミリ秒へ変換する係数。 */
	private static final long CENTISECONDS_TO_MILLIS = 10L;

	private final WhisperConfig config;

	/** 解析済みの GBNF 文法。文法が設定されていなければ null。 */
	private final GbnfGrammar grammar;

	/** VAD モデルの絶対パス。VAD が無効なら null。 */
	private final String vadModelPath;

	/** {@code whisper_context *}。{@code whisper_free} で解放するまで有効。 */
	private final MemorySegment context;

	private boolean closed = false;

	private NativeSession(WhisperConfig config, MemorySegment context, GbnfGrammar grammar, String vadModelPath)
	{
		this.config = config;
		this.context = context;
		this.grammar = grammar;
		this.vadModelPath = vadModelPath;
	}

	/**
	 * モデルを読み込みます。ネイティブライブラリは読み込み済みであること。
	 *
	 * @param config 設定
	 * @return 使用可能なセッション
	 * @throws WhisperException モデルの読み込み、または文法の解析に失敗した場合
	 */
	static NativeSession open(WhisperConfig config)
	{
		// 文法の解析は純 Java なので、モデルを読む前に済ませて早く失敗させる
		GbnfGrammar grammar = config.hasGrammar() ? GbnfGrammar.parse(config.grammar()) : null;
		String vadModelPath = config.vadEnabled() ? NativeRuntime.resolveVadModelPath(config) : null;

		MemorySegment context = createContext(config);
		return new NativeSession(config, context, grammar, vadModelPath);
	}

	/**
	 * {@code whisper_init_from_file_with_params} を呼びます。
	 *
	 * <p>
	 * {@code whisper_context_params} は<b>値渡し</b>なので、ポインタを返す {@code _by_ref} 版ではなく
	 * {@link Arena} に確保する値返し版を使います。この Arena は初期化が終われば閉じてよく、
	 * 返ってきたコンテキストは whisper.cpp 側が確保したメモリを指しています。
	 * </p>
	 */
	private static MemorySegment createContext(WhisperConfig config)
	{
		try(Arena arena = Arena.ofConfined())
		{
			MemorySegment contextParams = whisper_h.whisper_context_default_params(arena);
			whisper_context_params.use_gpu(contextParams, config.useGpu());

			String modelPath = config.model().toAbsolutePath().toString();
			MemorySegment context = whisper_h.whisper_init_from_file_with_params(arena.allocateFrom(modelPath), contextParams);
			if(isNull(context))
			{
				throw new WhisperException("モデルの読み込みに失敗しました: " + config.model().toAbsolutePath());
			}
			return context;
		}
	}

	// ------------------------------------------------------------------------
	// 文字起こし
	// ------------------------------------------------------------------------

	/**
	 * 16kHz モノラルの正規化済みサンプル列を文字起こしします。
	 *
	 * @param samples 16kHz モノラルの正規化済みサンプル列（空でないこと）
	 * @return 得られたセグメント。時刻はミリ秒
	 * @throws WhisperException whisper.cpp が失敗した場合
	 */
	List<Segment> transcribe(float[] samples)
	{
		this.assertOpen();

		// language / initialPrompt / vadModelPath / 文法 / samples が指す領域は whisper_full が
		// 終わるまで生きている必要があるので、呼び出し全体を 1 つの Arena で囲む
		int code;
		try(Arena arena = Arena.ofConfined())
		{
			MemorySegment params = this.buildParams(arena);
			MemorySegment nativeSamples = arena.allocateFrom(ValueLayout.JAVA_FLOAT, samples);
			code = whisper_h.whisper_full(this.context, params, nativeSamples, samples.length);
		}

		if(code != 0)
		{
			throw new WhisperException("whisper.cpp の文字起こしが失敗しました。戻り値=" + code);
		}
		return this.collectSegments();
	}

	/**
	 * 設定を {@code whisper_full_params} へ写します。
	 *
	 * <p>
	 * {@code whisper_full_default_params} で既定値を入れてから、{@link WhisperConfig} が持つ項目だけを
	 * 上書きします。設定に無い項目は whisper.cpp の既定値のままです。
	 * </p>
	 *
	 * @param arena 文字列や構造体を確保する Arena。{@code whisper_full} が終わるまで閉じないこと
	 */
	private MemorySegment buildParams(Arena arena)
	{
		SamplingStrategy strategy = this.config.samplingStrategy();
		MemorySegment params = whisper_h.whisper_full_default_params(arena, strategy.nativeValue());

		if(this.config.threads() > 0)
		{
			whisper_full_params.n_threads(params, this.config.threads()); // 0 以下なら whisper.cpp の既定値のまま
		}
		whisper_full_params.language(params, allocateString(arena, this.config.language()));
		whisper_full_params.detect_language(params, this.config.detectLanguage());
		whisper_full_params.translate(params, this.config.translateToEnglish());
		whisper_full_params.initial_prompt(params, allocateString(arena, this.config.initialPrompt()));
		// 0 にすると初期プロンプトごと無効になる点は WhisperConfig#maxTextContext の Javadoc 参照
		whisper_full_params.n_max_text_ctx(params, this.config.maxTextContext());
		whisper_full_params.carry_initial_prompt(params, this.config.carryInitialPrompt());

		// 結果は Java 側で受け取るので、whisper.cpp 側の標準出力は進捗以外すべて止める
		whisper_full_params.print_progress(params, this.config.printNativeProgress());
		whisper_full_params.print_realtime(params, false);
		whisper_full_params.print_timestamps(params, false);
		whisper_full_params.print_special(params, false);

		whisper_full_params.suppress_nst(params, this.config.suppressNonSpeechTokens());
		whisper_full_params.temperature_inc(params, this.config.temperatureIncrement());
		whisper_full_params.entropy_thold(params, this.config.entropyThreshold());

		switch(strategy)
		{
			case GREEDY -> whisper_full_params.greedy.best_of(whisper_full_params.greedy(params), this.config.bestOf());
			case BEAM_SEARCH -> whisper_full_params.beam_search.beam_size(
					whisper_full_params.beam_search(params), this.config.beamSize());
		}

		if(this.config.vadEnabled())
		{
			whisper_full_params.vad(params, true);
			whisper_full_params.vad_model_path(params, allocateString(arena, this.vadModelPath));
			this.applyVadParams(whisper_full_params.vad_params(params));
		}

		if(this.grammar != null)
		{
			this.applyGrammar(arena, params);
		}
		return params;
	}

	private void applyVadParams(MemorySegment target)
	{
		whisper_vad_params.threshold(target, this.config.vadThreshold());
		whisper_vad_params.min_speech_duration_ms(target, this.config.vadMinSpeechDurationMs());
		whisper_vad_params.min_silence_duration_ms(target, this.config.vadMinSilenceDurationMs());
		whisper_vad_params.max_speech_duration_s(target, this.config.vadMaxSpeechDurationSeconds());
		whisper_vad_params.speech_pad_ms(target, this.config.vadSpeechPadMs());
		whisper_vad_params.samples_overlap(target, this.config.vadSamplesOverlap());
	}

	/**
	 * 解析済みの文法をネイティブメモリへ写して {@code whisper_full_params} に結び付けます。
	 *
	 * <p>
	 * {@code grammar_rules} は「規則ごとの配列へのポインタ」の配列（{@code const
	 * whisper_grammar_element **}）です。規則の中身とポインタ配列の両方を、{@code whisper_full} が
	 * 終わるまで生かしておく必要があります。
	 * </p>
	 */
	private void applyGrammar(Arena arena, MemorySegment params)
	{
		List<List<GbnfGrammar.Element>> rules = this.grammar.rules();

		MemorySegment rulePointers = arena.allocate(ValueLayout.ADDRESS, rules.size());
		for(int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++)
		{
			List<GbnfGrammar.Element> rule = rules.get(ruleIndex);
			MemorySegment elements = whisper_grammar_element.allocateArray(rule.size(), arena);
			for(int elementIndex = 0; elementIndex < rule.size(); elementIndex++)
			{
				GbnfGrammar.Element element = rule.get(elementIndex);
				MemorySegment slot = whisper_grammar_element.asSlice(elements, elementIndex);
				whisper_grammar_element.type(slot, element.type());
				whisper_grammar_element.value(slot, element.value());
			}
			rulePointers.setAtIndex(ValueLayout.ADDRESS, ruleIndex, elements);
		}

		whisper_full_params.grammar_rules(params, rulePointers);
		whisper_full_params.n_grammar_rules(params, rules.size());
		whisper_full_params.i_start_rule(params, this.grammar.rootRuleId());
		whisper_full_params.grammar_penalty(params, this.config.grammarPenalty());
	}

	private List<Segment> collectSegments()
	{
		int segmentCount = whisper_h.whisper_full_n_segments(this.context);
		return IntStream.range(0, segmentCount)
				.mapToObj(this::segmentAt)
				.toList();
	}

	private Segment segmentAt(int index)
	{
		long startMs = whisper_h.whisper_full_get_segment_t0(this.context, index) * CENTISECONDS_TO_MILLIS;
		long endMs = whisper_h.whisper_full_get_segment_t1(this.context, index) * CENTISECONDS_TO_MILLIS;
		String text = readString(whisper_h.whisper_full_get_segment_text(this.context, index));
		return new Segment(startMs, endMs, text);
	}

	// ------------------------------------------------------------------------
	// 照会と後始末
	// ------------------------------------------------------------------------

	/**
	 * 読み込んだモデルが多言語対応かどうかを返します。{@code whisper_is_multilingual}。
	 *
	 * @return 多言語モデルなら true
	 */
	boolean isMultilingual()
	{
		this.assertOpen();
		return whisper_h.whisper_is_multilingual(this.context) != 0; // C の戻り値は bool ではなく int
	}

	/**
	 * whisper.cpp のシステム情報を返します。{@code whisper_print_system_info}。
	 *
	 * @return 有効な CPU 命令セットやバックエンドを示す文字列
	 */
	static String systemInfo()
	{
		return readString(whisper_h.whisper_print_system_info());
	}

	@Override
	public void close()
	{
		if(this.closed)
		{
			return;
		}
		this.closed = true;
		whisper_h.whisper_free(this.context);
	}

	/**
	 * 解放済みのコンテキストを whisper.cpp へ渡すと落ちるので、その手前で止めます。
	 *
	 * <p>
	 * 通常は {@link WhisperEngine} 側が先に弾くので、ここまで来るのは実装ミスのときだけです。
	 * </p>
	 */
	private void assertOpen()
	{
		if(this.closed)
		{
			throw new WhisperException("このセッションは既に close されています。");
		}
	}

	// ------------------------------------------------------------------------
	// FFM の小物
	// ------------------------------------------------------------------------

	/**
	 * Java の文字列を NUL 終端の UTF-8 として確保します。null なら {@code NULL} ポインタ。
	 */
	private static MemorySegment allocateString(Arena arena, String value)
	{
		return value == null ? MemorySegment.NULL : arena.allocateFrom(value);
	}

	/**
	 * C の {@code const char *} を Java の文字列にします。
	 *
	 * <p>
	 * ネイティブから返るポインタは長さ 0 の {@link MemorySegment} なので、そのままでは読めません。
	 * {@code reinterpret} で「長さは分からないが読んでよい」ことにしてから NUL 終端まで読みます。
	 * </p>
	 */
	private static String readString(MemorySegment pointer)
	{
		if(isNull(pointer))
		{
			return "";
		}
		return pointer.reinterpret(Long.MAX_VALUE).getString(0);
	}

	private static boolean isNull(MemorySegment pointer)
	{
		return pointer == null || pointer.address() == 0L;
	}
}
