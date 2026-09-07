package jp.clip.whisper;

/**
 * 文字起こし結果の 1 区間（セグメント）。
 *
 * <p>
 * 時刻は音声先頭からのミリ秒です。whisper.cpp は内部でセンチ秒（10 ミリ秒単位）を
 * 返しますが、この層でミリ秒に変換しています。
 * </p>
 *
 * @param startMs 開始時刻（ミリ秒）
 * @param endMs   終了時刻（ミリ秒）
 * @param text    このセグメントの文字列。whisper.cpp の仕様上、先頭に半角スペースが
 *                入ることがあります
 */
public record Segment(long startMs, long endMs, String text)
{
	/**
	 * このセグメントの長さをミリ秒で返します。
	 *
	 * @return 長さ（ミリ秒）
	 */
	public long durationMs()
	{
		return this.endMs - this.startMs;
	}
}
