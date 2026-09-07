package jp.clip.whisper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;

/**
 * ディレクトリに置かれたネイティブライブラリ群を、依存関係の順に {@link System#load(String)} します。
 *
 * <p>
 * whisper.cpp の配布物は複数の共有ライブラリに分かれており（ggml-base → ggml-cpu → ggml → whisper）、
 * 依存される側を先に読み込む必要があります。順序は whisper.cpp 側の依存関係そのままで、
 * {@link #LOAD_ORDER} に書いてあります。
 * </p>
 *
 * <p>
 * Vulkan 版のネイティブを使う場合は、先に {@link #loadVulkanRuntimeIfPresent()} で Vulkan
 * ランタイムを読み込んでください。
 * </p>
 *
 * <pre>
 * Path natives = Path.of("natives");
 * if(NativeLibraryLoader.loadVulkanRuntimeIfPresent())
 * {
 * 	logger.info("Vulkan ランタイムを読み込みました");
 * }
 * NativeLibraryLoader.load(logger, natives);
 * </pre>
 */
final class NativeLibraryLoader
{
	/**
	 * 読み込み順。依存される側が先。ここに無い名前のライブラリは最後に読み込まれます。
	 *
	 * <p>
	 * ファイル名との照合は「{@code <名前>.}」を含むかどうかで行います（{@code ggml} と
	 * {@code ggml-base} を区別するため）。
	 * </p>
	 */
	private static final List<String> LOAD_ORDER = List.of(
			// CUDA / BLAS のランタイム（ggml-cuda・ggml-blas が依存する）
			"cudart64_12", "cublasLt64_12", "cublas64_12",
			"openblas",
			// ggml 本体とバックエンド。ggml-base が最初、ggml（登録役）が最後
			"ggml-base", "ggml-cpu", "ggml-cuda", "ggml-blas", "ggml-metal", "ggml-vulkan", "ggml",
			// whisper.cpp 本体。ggml をすべて読み込んだ後
			"parakeet",
			"whisper");

	private NativeLibraryLoader()
	{
		// ユーティリティクラスなのでインスタンス化しない
	}

	/**
	 * ディレクトリ内のネイティブライブラリを依存関係の順にすべて読み込みます。
	 *
	 * <p>
	 * 実行中の OS / アーキテクチャに合ったネイティブが置かれていることは呼び出し側の責任です
	 * （{@link Platform} を使って判定できます）。
	 * </p>
	 *
	 * @param logger 読み込みの経過を記録する SLF4J {@link Logger}
	 * @param directory ネイティブライブラリが置かれたディレクトリ
	 * @throws IOException ディレクトリではない、ライブラリが 1 つも無い、または読み込みに失敗した場合
	 */
	public static void load(Logger logger, Path directory) throws IOException
	{
		logger.info("Loading natives from {}", directory);

		if(!Files.isDirectory(directory))
		{
			throw new IOException("Provided path does not denote a directory: " + directory);
		}

		List<Path> libraries = listNativeLibraries(directory);
		if(libraries.isEmpty())
		{
			throw new IOException("Failed to find any natives in " + directory
					+ ". If you're running in an IDE, build the natives for your platform first (see scripts/).");
		}

		for(Path library : libraries)
		{
			loadOne(logger, library);
		}
		logger.info("Done loading natives");
	}

	/**
	 * Vulkan ランタイムライブラリを OS ごとの既知の場所から探します。
	 *
	 * @return 見つかったランタイムのパス。無ければ空
	 */
	public static Optional<Path> findVulkanRuntime()
	{
		return vulkanRuntimeCandidates().stream()
				.filter(Files::isRegularFile)
				.findFirst();
	}

	/**
	 * Vulkan ランタイムライブラリが見つかれば読み込みます。Vulkan 版ネイティブを読み込む前に呼んでください。
	 *
	 * @return 読み込めたら true。見つからなければ false
	 */
	public static boolean loadVulkanRuntimeIfPresent()
	{
		Optional<Path> runtime = findVulkanRuntime();
		runtime.ifPresent(path -> System.load(path.toAbsolutePath().toString()));
		return runtime.isPresent();
	}

	// ------------------------------------------------------------------------
	// 内部
	// ------------------------------------------------------------------------

	private static List<Path> listNativeLibraries(Path directory) throws IOException
	{
		try(Stream<Path> files = Files.list(directory))
		{
			return files
					.filter(Files::isRegularFile)
					.filter(path -> Platform.isNativeLibrary(fileName(path)))
					.sorted(Comparator.comparingInt(NativeLibraryLoader::loadPriority))
					.toList();
		}
	}

	/**
	 * {@link #LOAD_ORDER} 上の位置を返します。該当が無ければ最後に回します。
	 */
	private static int loadPriority(Path library)
	{
		String name = fileName(library);
		return IntStream.range(0, LOAD_ORDER.size())
				.filter(index -> name.contains(LOAD_ORDER.get(index) + "."))
				.findFirst()
				.orElse(Integer.MAX_VALUE);
	}

	private static void loadOne(Logger logger, Path library) throws IOException
	{
		String absolutePath = library.toAbsolutePath().toString();
		logger.info("Loading {}", absolutePath);
		try
		{
			System.load(absolutePath);
		}
		catch(UnsatisfiedLinkError e)
		{
			logger.error("Failed to load {}. Is a dependency missing or the load order wrong?", absolutePath, e);
			throw new IOException(e);
		}
	}

	private static List<Path> vulkanRuntimeCandidates()
	{
		return switch(Platform.current())
		{
			case WINDOWS ->
			{
				String systemRoot = System.getenv("SystemRoot");
				yield List.of(
						Path.of(systemRoot, "System32", "vulkan-1.dll"),
						Path.of(systemRoot, "SysWOW64", "vulkan-1.dll"));
			}
			case LINUX -> List.of(
					Path.of("/usr/lib/libvulkan.so.1"),
					Path.of("/usr/lib/x86_64-linux-gnu/libvulkan.so.1"),
					Path.of("/usr/local/lib/libvulkan.so.1"));
			case MAC ->
			{
				// Vulkan SDK（CI/CD が使う）は環境変数で場所が変わる
				String sdkRoot = System.getenv("VULKAN_SDK");
				Stream<Path> fixed = Stream.of(
						Path.of("/usr/local/lib/libvulkan.1.dylib"),
						Path.of("/opt/homebrew/lib/libvulkan.1.dylib"),
						Path.of("/usr/lib/libvulkan.1.dylib"));
				Stream<Path> sdk = sdkRoot == null ? Stream.empty() : Stream.of(Path.of(sdkRoot, "lib", "libvulkan.1.dylib"));
				yield Stream.concat(fixed, sdk).toList();
			}
		};
	}

	private static String fileName(Path path)
	{
		return path.getFileName().toString();
	}
}
