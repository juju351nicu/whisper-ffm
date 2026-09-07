package jp.clip.whisper;

/**
 * デコード時のサンプリング戦略。whisper.cpp の {@code enum whisper_sampling_strategy} に対応します。
 *
 * <pre>
 * WhisperConfig config = WhisperConfig.builder()
 * 		.model(Path.of("ggml-tiny.bin"))
 * 		.samplingStrategy(SamplingStrategy.BEAM_SEARCH)
 * 		.build();
 * </pre>
 */
public enum SamplingStrategy
{
	/** 貪欲法。{@code WHISPER_SAMPLING_GREEDY}。高速で、多くの用途ではこちらで十分です。 */
	GREEDY(0),

	/** ビームサーチ。{@code WHISPER_SAMPLING_BEAM_SEARCH}。GREEDY より低速ですが精度が上がることがあります。 */
	BEAM_SEARCH(1);

	private final int nativeValue;

	SamplingStrategy(int nativeValue)
	{
		this.nativeValue = nativeValue;
	}

	/**
	 * whisper.cpp 側の列挙値を返します。{@code whisper_full_default_params} に渡す値です。
	 *
	 * @return {@code whisper_sampling_strategy} の値
	 */
	int nativeValue()
	{
		return this.nativeValue;
	}
}
