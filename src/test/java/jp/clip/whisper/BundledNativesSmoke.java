package jp.clip.whisper;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jar に同梱したネイティブだけで文字起こしができることを確かめるスモークテスト。
 *
 * <p>
 * <b>なぜ JUnit ではなく main メソッドなのか。</b> このライブラリを使うアプリ（transcribe-shell など）は
 * {@code natives/} ディレクトリを持たず、jar の中の {@code <os>-<arch>/} から DLL を取り出して読み込みます。
 * ところが {@link WhisperEngineTest} は {@code natives/} があればそちらを使うため、CI では
 * <b>その実使用経路を一度も通らない</b>という穴がありました。穴を埋めるには「main のクラスディレクトリを
 * クラスパスから外し、jar だけを見せる」という特殊なクラスパスが必要で、これは通常の {@code test} タスクでは
 * 作れません。そのため独立した JavaExec タスク（{@code gradlew smokeTestBundledNatives}）から呼ぶ
 * main クラスにしています。クラスパスの組み立ては {@code build.gradle} を参照してください。
 * </p>
 *
 * <p>
 * 確認することは 2 つです。
 * </p>
 *
 * <ol>
 * <li>ネイティブの取得元が本当に jar であること（{@code natives.list} の URL が {@code jar:} で始まる）。
 * これを見ないと、うっかりクラスパスに {@code build/resources/main} が混ざったまま「成功」してしまいます。</li>
 * <li>{@link WhisperConfig#nativeLibraryDirectory()} を指定せずに文字起こしが通ること。</li>
 * </ol>
 *
 * <p>
 * 必要なもの: {@code ggml-tiny.bin}（{@code scripts/download-test-model}）と
 * {@code src/main/native/whisper/samples/jfk.wav}（submodule）。どちらもプロジェクトルートからの相対パスです。
 * </p>
 */
public final class BundledNativesSmoke
{
	private static final Logger LOG = LoggerFactory.getLogger(BundledNativesSmoke.class);

	private static final Path MODEL_PATH = Path.of("ggml-tiny.bin");
	private static final Path SAMPLE_PATH = Path.of("src/main/native/whisper/samples/jfk.wav");

	/** 期待する認識結果。jfk.wav の冒頭。句読点の揺れに強い部分だけを見ます。 */
	private static final String EXPECTED_PHRASE = "fellow Americans";

	private BundledNativesSmoke()
	{
	}

	/**
	 * スモークテストを実行します。失敗したら例外を投げ、Gradle のタスクを失敗させます。
	 *
	 * @param args 使いません
	 */
	public static void main(String[] args)
	{
		requireFile(MODEL_PATH, "scripts/download-test-model を先に実行してください。");
		requireFile(SAMPLE_PATH, "git submodule update --init --recursive を先に実行してください。");

		verifyNativesComeFromJar();

		// nativeLibraryDirectory を指定しない。これが利用側アプリと同じ経路
		WhisperConfig config = WhisperConfig.builder()
				.model(MODEL_PATH)
				.language("en")
				.build();

		try(WhisperEngine engine = WhisperEngine.open(config))
		{
			TranscriptionResult result = engine.transcribe(SAMPLE_PATH);
			String text = result.text();
			LOG.info("結果: {}", text);
			if(!text.contains(EXPECTED_PHRASE))
			{
				throw new IllegalStateException("jar 同梱のネイティブで文字起こしはできましたが、結果が想定と違います。"
						+ " 期待: \"" + EXPECTED_PHRASE + "\" を含む / 実際: \"" + text + "\"");
			}
			LOG.info("jar 同梱のネイティブで文字起こしできました（RTF {}）", String.format("%.2f", result.realTimeFactor()));
		}
	}

	/**
	 * ネイティブの索引ファイルが jar の中から見えていることを確かめます。
	 *
	 * <p>
	 * クラスパスに {@code build/resources/main} が残っていると URL は {@code file:} になります。その場合、
	 * このテストは「jar 同梱の経路」を検証していないので、成功させずに失敗させます。
	 * </p>
	 */
	private static void verifyNativesComeFromJar()
	{
		String resource = Platform.current().nativeLibraryDirectoryName() + "/" + BundledResources.INDEX_FILE;
		URL url = BundledNativesSmoke.class.getClassLoader().getResource(resource);
		if(url == null)
		{
			throw new IllegalStateException("jar に同梱したネイティブの索引が見つかりません: " + resource
					+ " / gradlew installNatives jar を先に実行してください。");
		}
		if(!"jar".equals(url.getProtocol()))
		{
			throw new IllegalStateException("索引 " + resource + " が jar の外から見えています（" + url + "）。"
					+ " クラスパスに build/resources/main が混ざっていると jar 同梱の経路を検証できません。"
					+ " build.gradle の smokeTestBundledNatives のクラスパス構成を確認してください。");
		}
		LOG.info("ネイティブの取得元: {}", url);
	}

	private static void requireFile(Path path, String hint)
	{
		if(!Files.isRegularFile(path))
		{
			throw new IllegalStateException("ファイルがありません: " + path.toAbsolutePath() + " / " + hint);
		}
	}
}
