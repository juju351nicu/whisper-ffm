package jp.clip.whisper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * 音声ファイルを whisper.cpp が受け付ける形式（16kHz モノラル、-1.0〜1.0 の float）に変換して読み込みます。
 *
 * <p>
 * Java 標準の {@link AudioSystem} が扱える形式（WAV など）に対応します。サンプリングレートや
 * チャネル数が違っても、標準の変換で対応できる範囲であれば自動的に変換します。
 * MP3 など標準で扱えない形式は、事前に WAV へ変換してから渡してください。
 * </p>
 *
 * <pre>
 * float[] samples = AudioFileReader.readSamples(Path.of("input.wav"));
 * TranscriptionResult result = engine.transcribe(samples);
 * </pre>
 */
public final class AudioFileReader
{
	/** whisper.cpp が要求する PCM 形式: 16kHz / 16bit / モノラル / リトルエンディアン。 */
	private static final AudioFormat TARGET_FORMAT = new AudioFormat(
			AudioFormat.Encoding.PCM_SIGNED,
			WhisperEngine.SAMPLE_RATE,
			16,
			1,
			2,
			WhisperEngine.SAMPLE_RATE,
			false);

	private AudioFileReader()
	{
		// ユーティリティクラスなのでインスタンス化しない
	}

	/**
	 * 音声ファイルを 16kHz モノラルの正規化済み float サンプル列として読み込みます。
	 *
	 * @param audioFile 音声ファイル
	 * @return 16kHz モノラルの正規化済みサンプル列
	 * @throws WhisperException ファイルが無い、形式に対応していない、または読み込みに失敗した場合
	 */
	public static float[] readSamples(Path audioFile)
	{
		if(audioFile == null || !Files.isRegularFile(audioFile))
		{
			throw new WhisperException("音声ファイルが見つかりません: " + audioFile);
		}

		// AudioSystem は mark/reset できるストリームを要求するので BufferedInputStream で包む
		try(InputStream file = new BufferedInputStream(Files.newInputStream(audioFile));
				AudioInputStream source = AudioSystem.getAudioInputStream(file);
				AudioInputStream pcm16k = convertToTargetFormat(source))
		{
			return toNormalizedFloats(pcm16k.readAllBytes());
		}
		catch(UnsupportedAudioFileException e)
		{
			throw new WhisperException("対応していない音声ファイル形式です: " + audioFile.toAbsolutePath(), e);
		}
		catch(IOException e)
		{
			throw new WhisperException("音声ファイルの読み込みに失敗しました: " + audioFile.toAbsolutePath(), e);
		}
	}

	private static AudioInputStream convertToTargetFormat(AudioInputStream source)
	{
		AudioFormat sourceFormat = source.getFormat();
		if(sourceFormat.matches(TARGET_FORMAT))
		{
			return source;
		}
		if(!AudioSystem.isConversionSupported(TARGET_FORMAT, sourceFormat))
		{
			throw new WhisperException("この音声フォーマットは変換できません: " + sourceFormat
					+ " / 16kHz モノラル 16bit PCM の WAV に変換してから渡してください。");
		}
		return AudioSystem.getAudioInputStream(TARGET_FORMAT, source);
	}

	/**
	 * 16bit リトルエンディアン PCM のバイト列を -1.0〜1.0 の float に変換します。
	 */
	private static float[] toNormalizedFloats(byte[] pcm16LittleEndian)
	{
		ShortBuffer shorts = ByteBuffer.wrap(pcm16LittleEndian).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
		float[] samples = new float[shorts.remaining()];
		IntStream.range(0, samples.length)
				.forEach(index -> samples[index] = normalize(shorts.get(index)));
		return samples;
	}

	private static float normalize(short sample)
	{
		float value = (float) sample / (float) Short.MAX_VALUE;
		return Math.max(-1.0f, Math.min(value, 1.0f));
	}
}
