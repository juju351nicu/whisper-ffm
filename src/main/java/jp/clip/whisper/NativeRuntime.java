package jp.clip.whisper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.extern.slf4j.Slf4j;

/**
 * JVM 内で 1 度だけ行えばよい初期化（ネイティブライブラリの読み込み、同梱 VAD モデルの取り出し）を
 * 受け持ちます。{@link WhisperEngine} からのみ使います。
 *
 * <p>
 * 共有ライブラリは同じ JVM に 2 回読み込めないため、{@link #ensureNativesLoaded(WhisperConfig)} は
 * 最初の 1 回だけ実際に読み込み、以降は何もしません。したがって
 * {@link WhisperConfig#nativeLibraryDirectory()} は最初に生成したエンジンのものが JVM 全体で使われます。
 * </p>
 */
@Slf4j
final class NativeRuntime
{
	/**
	 * whisper.cpp / ggml 自身が出すログの出力先。
	 *
	 * <p>
	 * 独立したロガー名 {@value #NATIVE_LOGGER_NAME} にしているので、モデル読み込み時の大量の INFO を
	 * 抑えたい場合はこの名前のレベルを WARN にしてください（例: Spring Boot なら
	 * {@code logging.level.whisper.cpp=WARN}）。
	 * </p>
	 */
	static final String NATIVE_LOGGER_NAME = "whisper.cpp";

	private static final Logger NATIVE_LOG = LoggerFactory.getLogger(NATIVE_LOGGER_NAME);

	private static boolean nativesLoaded = false;
	private static Path extractedVadModel = null;

	private NativeRuntime()
	{
		// static メンバーのみ
	}

	/**
	 * ネイティブライブラリを（まだなら）読み込み、whisper.cpp のログを SLF4J へ接続します。
	 *
	 * @param config ネイティブの置き場所を含む設定
	 * @throws WhisperException 読み込みに失敗した場合
	 */
	static synchronized void ensureNativesLoaded(WhisperConfig config)
	{
		if(nativesLoaded)
		{
			return;
		}
		try
		{
			if(config.nativeLibraryDirectory() != null)
			{
				// Vulkan 版ネイティブの場合は先に Vulkan ランタイムが要る。無ければ何もしない（CPU 版なら不要）
				if(NativeLibraryLoader.loadVulkanRuntimeIfPresent())
				{
					log.info("Vulkan ランタイムを読み込みました");
				}
				log.info("ネイティブライブラリを {} から読み込みます", config.nativeLibraryDirectory());
				NativeLibraryLoader.load(log, config.nativeLibraryDirectory());
			}
			else
			{
				loadBundledLibraries();
			}
			// ライブラリを読み込んだ後でないと whisper_log_set のシンボルが引けない
			NativeLogBridge.install(NATIVE_LOG);
			nativesLoaded = true;
		}
		// LinkageError も捕まえる。System.load は UnsatisfiedLinkError（Error であって Exception ではない）を
		// 投げるので、これを漏らすと WhisperEngine#open の「WhisperException を投げる」という契約が破れる。
		// 利用側は catch(Exception) で拾えず、バッチ処理などが突き抜けて止まってしまう。
		catch(IOException | RuntimeException | LinkageError e)
		{
			throw new WhisperException("ネイティブライブラリの読み込みに失敗しました。"
					+ " この OS / アーキテクチャ用のライブラリが jar に同梱されていないか、依存 DLL が足りない可能性があります。", e);
		}
	}

	/**
	 * jar に同梱したネイティブを一時ディレクトリへ取り出して読み込みます。
	 *
	 * <p>
	 * {@link System#load(String)} は実ファイルしか受け付けないので、jar の中身をいったん展開します。
	 * </p>
	 */
	private static void loadBundledLibraries() throws IOException
	{
		Path extracted = BundledResources.extractDirectory(log, Platform.current().nativeLibraryDirectoryName());
		NativeLibraryLoader.load(log, extracted);
	}

	/**
	 * 設定に従って VAD モデルファイルのパスを決めます。
	 *
	 * <p>
	 * 明示指定があればそのファイルを、無ければ jar 同梱の silero モデルを一時ファイルへ取り出して
	 * 使います。取り出しは JVM 内で 1 度だけ行い、JVM 終了時に削除します。
	 * </p>
	 *
	 * @param config 設定
	 * @return VAD モデルの絶対パス（文字列）
	 * @throws WhisperException 明示指定されたモデルが無い、または同梱モデルを取り出せない場合
	 */
	static synchronized String resolveVadModelPath(WhisperConfig config)
	{
		if(config.vadModel() != null)
		{
			if(!Files.isRegularFile(config.vadModel()))
			{
				throw new WhisperException("VAD モデルが見つかりません: " + config.vadModel().toAbsolutePath());
			}
			return config.vadModel().toAbsolutePath().toString();
		}

		if(extractedVadModel == null || !Files.isRegularFile(extractedVadModel))
		{
			extractedVadModel = extractBundledVadModel();
		}
		return extractedVadModel.toAbsolutePath().toString();
	}

	private static Path extractBundledVadModel()
	{
		try
		{
			Path temporary = Files.createTempFile("whisper-vad-", ".bin");
			deleteOnExit(temporary);
			BundledResources.exportVadModel(log, temporary);
			return temporary;
		}
		catch(IOException e)
		{
			throw new WhisperException("jar 同梱の VAD モデルを展開できませんでした。"
					+ "WhisperConfig.vadModel(path) で明示的に指定してください。", e);
		}
	}

	/**
	 * {@code File#deleteOnExit} の NIO 版。JVM 終了時にファイルを消します（消せなくても無視）。
	 */
	private static void deleteOnExit(Path file)
	{
		Runtime.getRuntime().addShutdownHook(new Thread(() ->
		{
			try
			{
				Files.deleteIfExists(file);
			}
			catch(IOException ignored)
			{
				// 一時ファイルなので削除失敗は無視する
			}
		}, "whisper-vad-cleanup"));
	}
}
