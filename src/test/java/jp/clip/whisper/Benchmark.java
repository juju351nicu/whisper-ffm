package jp.clip.whisper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 高速化（Step 5）のための計測ツール。モデル × スレッド数 × VAD の組み合わせごとに RTF を測ります。
 *
 * <p>
 * JUnit テストではなく {@code main} を持つ通常のクラスです。Gradle の {@code benchmark} タスクから
 * 起動します（{@code build.gradle} 参照）。
 * </p>
 *
 * <pre>
 * .\gradlew.bat benchmark "-Pbench.audio=C:\audio\meeting.wav" "-Pbench.models=models\ggml-small.bin;models\ggml-large-v3-turbo-q5_0.bin"
 * .\gradlew.bat benchmark "-Pbench.audio=..." "-Pbench.models=..." "-Pbench.threads=4,8,12" "-Pbench.vad=both" "-Pbench.repeat=3"
 * </pre>
 *
 * <p>
 * 結果は標準出力の表と、{@code build/benchmark/<日時>.csv} の両方に出ます。CSV は作業記録に貼るためのものです。
 * </p>
 *
 * <p>
 * <b>RTF（Real Time Factor）= 処理時間 ÷ 音声長</b>。1.0 未満なら実時間より速い。
 * 同じ条件を {@code repeat} 回走らせ、最小値（ウォームアップ後の実力）と中央値（実運用の目安）を記録します。
 * </p>
 */
public final class Benchmark
{
	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private static boolean systemInfoPrinted = false;

	/** 1 条件の計測結果。 */
	private record Result(String model, int threads, boolean vad, SamplingStrategy strategy, long loadMs, double audioSeconds,
			long minMs, long medianMs, double minRtf, double medianRtf, String textPreview)
	{
		String toTableRow()
		{
			return String.format(Locale.ROOT, "%-34s %7d %5s %-11s %8d %9.1f %10d %10d %7.3f %7.3f  %s",
					this.model, this.threads, this.vad ? "on" : "off", this.strategy, this.loadMs, this.audioSeconds,
					this.minMs, this.medianMs, this.minRtf, this.medianRtf, this.textPreview);
		}

		String toCsvRow()
		{
			return String.join(",", this.model, Integer.toString(this.threads), Boolean.toString(this.vad),
					this.strategy.name(), Long.toString(this.loadMs), String.format(Locale.ROOT, "%.1f", this.audioSeconds),
					Long.toString(this.minMs), Long.toString(this.medianMs), String.format(Locale.ROOT, "%.3f", this.minRtf),
					String.format(Locale.ROOT, "%.3f", this.medianRtf), "\"" + this.textPreview.replace("\"", "\"\"") + "\"");
		}

		static String tableHeader()
		{
			return String.format(Locale.ROOT, "%-34s %7s %5s %-11s %8s %9s %10s %10s %7s %7s  %s",
					"model", "threads", "vad", "strategy", "load ms", "audio s", "min ms", "median ms", "minRTF", "medRTF", "text");
		}

		static String csvHeader()
		{
			return "model,threads,vad,strategy,load_ms,audio_s,min_ms,median_ms,min_rtf,median_rtf,text";
		}
	}

	private Benchmark()
	{
		// main のみ
	}

	/**
	 * 計測を実行します。
	 *
	 * @param args {@code key=value} 形式。{@code audio}（必須）、{@code models}（必須、{@code ;} 区切り）、
	 *             {@code threads}（既定 {@code 0}、{@code ,} 区切り）、{@code vad}（{@code on} / {@code off} / {@code both}、既定 off）、
	 *             {@code strategy}（{@code GREEDY} / {@code BEAM_SEARCH} / {@code both}、既定 GREEDY）、
	 *             {@code repeat}（既定 2）、{@code language}（既定 ja）、{@code natives}（ネイティブのディレクトリ、既定 natives）、
	 *             {@code out}（CSV の出力ディレクトリ、既定 build/benchmark）
	 * @throws IOException CSV の書き出しに失敗した場合
	 */
	public static void main(String[] args) throws IOException
	{
		Map<String, String> options = parseOptions(args);

		Path audio = Path.of(require(options, "audio"));
		List<Path> models = Stream.of(require(options, "models").split(";")).map(String::strip).filter(s -> !s.isEmpty()).map(Path::of).toList();
		List<Integer> threadCounts = Stream.of(options.getOrDefault("threads", "0").split(",")).map(String::strip).map(Integer::parseInt).toList();
		List<Boolean> vadModes = switch(options.getOrDefault("vad", "off"))
		{
			case "on" -> List.of(Boolean.TRUE);
			case "both" -> List.of(Boolean.FALSE, Boolean.TRUE);
			default -> List.of(Boolean.FALSE);
		};
		List<SamplingStrategy> strategies = switch(options.getOrDefault("strategy", "GREEDY"))
		{
			case "BEAM_SEARCH" -> List.of(SamplingStrategy.BEAM_SEARCH);
			case "both" -> List.of(SamplingStrategy.GREEDY, SamplingStrategy.BEAM_SEARCH);
			default -> List.of(SamplingStrategy.GREEDY);
		};
		int repeat = Integer.parseInt(options.getOrDefault("repeat", "2"));
		String language = options.getOrDefault("language", "ja");
		Path natives = Path.of(options.getOrDefault("natives", "natives"));
		Path outDirectory = Path.of(options.getOrDefault("out", "build/benchmark"));

		float[] samples = AudioFileReader.readSamples(audio);
		double audioSeconds = (double) samples.length / WhisperEngine.SAMPLE_RATE;

		System.out.println("=== whisper-jni benchmark ===");
		System.out.printf(Locale.ROOT, "audio    : %s (%.1f s)%n", audio, audioSeconds);
		System.out.printf(Locale.ROOT, "cpu      : %d logical processors, os=%s %s%n", Runtime.getRuntime().availableProcessors(),
				System.getProperty("os.name"), System.getProperty("os.arch"));
		System.out.printf(Locale.ROOT, "java     : %s%n", System.getProperty("java.version"));
		System.out.printf(Locale.ROOT, "repeat   : %d (min = 実力値, median = 実運用の目安)%n%n", repeat);

		List<Result> results = new ArrayList<>();
		System.out.println(Result.tableHeader());
		for(Path model : models)
		{
			for(int threads : threadCounts)
			{
				for(boolean vad : vadModes)
				{
					for(SamplingStrategy strategy : strategies)
					{
						Result result = measure(model, samples, audioSeconds, threads, vad, strategy, repeat, language, natives);
						results.add(result);
						System.out.println(result.toTableRow());
					}
				}
			}
		}

		Path csv = writeCsv(outDirectory, results);
		System.out.printf(Locale.ROOT, "%nCSV: %s%n", csv.toAbsolutePath());
	}

	private static Result measure(Path model, float[] samples, double audioSeconds, int threads, boolean vad, SamplingStrategy strategy,
			int repeat, String language, Path natives)
	{
		WhisperConfig config = WhisperConfig.builder()
				.model(model)
				.language(language)
				.threads(threads)
				.vadEnabled(vad)
				.samplingStrategy(strategy)
				.nativeLibraryDirectory(Files.isDirectory(natives) ? natives : null)
				.build();

		long loadStarted = System.nanoTime();
		try(WhisperEngine engine = WhisperEngine.open(config))
		{
			long loadMs = (System.nanoTime() - loadStarted) / 1_000_000L;
			if(!systemInfoPrinted)
			{
				// どのバックエンド・命令セットが有効かの記録。最初の 1 回だけ
				System.out.println("system   : " + engine.systemInfo());
				systemInfoPrinted = true;
			}

			List<Long> elapsed = new ArrayList<>();
			String text = "";
			for(int i = 0; i < repeat; i++)
			{
				TranscriptionResult result = engine.transcribe(samples);
				elapsed.add(result.elapsedMs());
				text = result.text();
			}
			List<Long> sorted = elapsed.stream().sorted().toList();
			long min = sorted.get(0);
			long median = sorted.get(sorted.size() / 2);
			return new Result(model.getFileName().toString(), threads, vad, strategy, loadMs, audioSeconds, min, median,
					min / 1000.0 / audioSeconds, median / 1000.0 / audioSeconds, preview(text));
		}
	}

	private static String preview(String text)
	{
		String oneLine = text.replace('\n', ' ').replace('\r', ' ');
		return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
	}

	private static Path writeCsv(Path outDirectory, List<Result> results) throws IOException
	{
		Files.createDirectories(outDirectory);
		Path csv = outDirectory.resolve("benchmark-" + LocalDateTime.now().format(FILE_TIMESTAMP) + ".csv");
		List<String> lines = Stream.concat(Stream.of(Result.csvHeader()), results.stream().map(Result::toCsvRow)).toList();
		Files.write(csv, lines, StandardCharsets.UTF_8);
		return csv;
	}

	private static Map<String, String> parseOptions(String[] args)
	{
		return Arrays.stream(args)
				.filter(arg -> arg.contains("="))
				.collect(Collectors.toMap(arg -> arg.substring(0, arg.indexOf('=')), arg -> arg.substring(arg.indexOf('=') + 1), (a, b) -> b));
	}

	private static String require(Map<String, String> options, String key)
	{
		String value = options.get(key);
		if(value == null || value.isBlank())
		{
			throw new IllegalArgumentException(key + " は必須です。例: gradlew benchmark \"-Pbench." + key + "=...\"");
		}
		return value;
	}
}
