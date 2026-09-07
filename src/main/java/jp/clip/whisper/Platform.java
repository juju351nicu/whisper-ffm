package jp.clip.whisper;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * 実行中の OS と CPU アーキテクチャ。同梱ネイティブの置き場所（{@code <os>-<arch>}）を決めるのに使います。
 *
 * <pre>
 * Platform.current();                     // 例: WINDOWS
 * Platform.current().nativeLibraryDirectoryName(); // 例: "windows-x64"
 * </pre>
 */
enum Platform
{
	/** Windows。ネイティブは {@code .dll}。 */
	WINDOWS("windows", ".dll"),

	/** macOS。ネイティブは {@code .dylib}。 */
	MAC("mac", ".dylib"),

	/** Linux。ネイティブは {@code .so}。 */
	LINUX("linux", ".so");

	/** {@code os.name} を小文字化したもの。 */
	public static final String OS_NAME = System.getProperty("os.name").toLowerCase(Locale.ROOT);

	/** {@code os.arch} を小文字化したもの。 */
	public static final String OS_ARCH = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

	private final String directoryName;
	private final String libraryExtension;

	Platform(String directoryName, String libraryExtension)
	{
		this.directoryName = directoryName;
		this.libraryExtension = libraryExtension;
	}

	/**
	 * 実行中の OS を返します。
	 *
	 * @return 実行中の OS
	 * @throws UnsupportedOperationException Windows / macOS / Linux のいずれでもない場合
	 */
	public static Platform current()
	{
		if(OS_NAME.contains("win"))
		{
			return WINDOWS;
		}
		if(OS_NAME.contains("mac"))
		{
			return MAC;
		}
		if(OS_NAME.contains("nux"))
		{
			return LINUX;
		}
		throw new UnsupportedOperationException("Unknown operating system: " + OS_NAME);
	}

	/**
	 * CPU アーキテクチャを一般化した名前で返します。
	 *
	 * <p>
	 * {@code "x86"}（32bit）、{@code "x64"}（64bit）、{@code "arm64"}（64bit ARM）のいずれか。
	 * どれにも当たらない場合は {@code os.arch} の生の値を返します。
	 * </p>
	 *
	 * @return アーキテクチャ名
	 */
	public static String architecture()
	{
		return switch(OS_ARCH)
		{
			case "i386", "i486", "i586", "i686", "x86", "x86_32" -> "x86";
			case "amd64", "x86_64", "x86-64" -> "x64";
			case "aarch64" -> "arm64";
			default -> OS_ARCH;
		};
	}

	/**
	 * jar 内で同梱ネイティブを置くディレクトリ名（{@code <os>-<arch>}）を返します。
	 *
	 * @return 例 {@code "windows-x64"}
	 */
	public String nativeLibraryDirectoryName()
	{
		return this.directoryName + "-" + architecture();
	}

	/**
	 * この OS の共有ライブラリの拡張子を返します。
	 *
	 * @return {@code ".dll"} / {@code ".dylib"} / {@code ".so"}
	 */
	public String libraryExtension()
	{
		return this.libraryExtension;
	}

	/**
	 * ファイル名がいずれかの OS の共有ライブラリに見えるかどうかを返します。
	 *
	 * <p>
	 * Linux の {@code libfoo.so.1.2} のようにバージョン番号が後ろに付くものも許容します。
	 * </p>
	 *
	 * @param fileName ファイル名
	 * @return 共有ライブラリなら true
	 */
	public static boolean isNativeLibrary(String fileName)
	{
		return Stream.of(values())
				.anyMatch(platform -> fileName.matches(".*\\" + platform.libraryExtension + "(\\.\\d+)*$"));
	}
}
