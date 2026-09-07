package jp.clip.whisper;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import org.slf4j.Logger;

import jp.clip.whisper.ffm.gen.ggml_log_callback;
import jp.clip.whisper.ffm.gen.whisper_h;

/**
 * whisper.cpp / ggml のログを {@code whisper_log_set} で受け取り、SLF4J へ流します。
 *
 * <p>
 * whisper.cpp は既定で自分の標準エラーへ出力します。アプリのログ設定から制御できるように、
 * {@link NativeRuntime#NATIVE_LOGGER_NAME} のロガーへ差し替えます。
 * </p>
 *
 * <p>
 * <b>アップコールスタブの寿命に注意。</b>whisper.cpp はプロセスが終わるまでこのコールバックを呼ぶ
 * 可能性があるので、スタブは {@link Arena#global()} で確保します。{@code Arena.ofConfined()} で
 * 作って閉じると、死んだスタブをネイティブから呼ばれて JVM ごと落ちます。
 * </p>
 *
 * <p>
 * whisper.cpp の作業スレッドから呼ばれることもありますが、FFM はアップコール時にスレッドを
 * 自動でアタッチするので、呼び出し側で用意することはありません。
 * </p>
 */
final class NativeLogBridge
{
	// ggml_log_level。whisper.cpp の enum と一致させること
	private static final int LEVEL_NONE = 0;
	private static final int LEVEL_DEBUG = 1;
	private static final int LEVEL_INFO = 2;
	private static final int LEVEL_WARN = 3;
	private static final int LEVEL_ERROR = 4;
	private static final int LEVEL_CONT = 5;

	/** 一度作ったら JVM が終わるまで生かすアップコールスタブ。 */
	private static MemorySegment callbackStub = null;

	private NativeLogBridge()
	{
		// static メンバーのみ
	}

	/**
	 * ログの転送先を設定します。すでに設定されていれば転送先だけ差し替えます。
	 *
	 * @param logger 転送先の SLF4J {@link Logger}
	 */
	static synchronized void install(Logger logger)
	{
		if(callbackStub == null)
		{
			callbackStub = ggml_log_callback.allocate(
					(int level, MemorySegment text, MemorySegment userData) -> forward(logger, level, text),
					Arena.global());
		}
		whisper_h.whisper_log_set(callbackStub, MemorySegment.NULL);
	}

	/**
	 * ネイティブから呼ばれる本体。<b>ここから例外を投げてはいけません</b>（アップコールの外へ
	 * 例外が漏れると JVM が落ちます）。
	 */
	private static void forward(Logger logger, int level, MemorySegment text)
	{
		try
		{
			if(text == null || text.address() == 0L)
			{
				return;
			}
			// whisper.cpp のログは末尾に改行が付いている。SLF4J 側が行を管理するので落とす
			String message = text.reinterpret(Long.MAX_VALUE).getString(0).stripTrailing();
			if(message.isEmpty())
			{
				return;
			}
			switch(level)
			{
				case LEVEL_ERROR -> logger.error(message);
				case LEVEL_WARN -> logger.warn(message);
				case LEVEL_DEBUG -> logger.debug(message);
				// CONT は前行の続き、NONE はレベル指定なし。どちらも行単位で来るので INFO 扱い
				case LEVEL_INFO, LEVEL_CONT, LEVEL_NONE -> logger.info(message);
				default -> logger.info(message);
			}
		}
		catch(RuntimeException e)
		{
			// ログの転送で落ちるくらいなら黙って捨てる
		}
	}
}
