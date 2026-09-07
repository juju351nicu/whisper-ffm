package jp.clip.whisper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文字起こしの結果全体。
 *
 * <p>
 * 使用例:
 * </p>
 *
 * <pre>
 * TranscriptionResult result = engine.transcribe(wav);
 * System.out.println(result.text());
 * for(Segment segment : result.segments())
 * {
 * 	System.out.printf("[%d-%d] %s%n", segment.startMs(), segment.endMs(), segment.text());
 * }
 * </pre>
 *
 * @param segments  検出されたセグメントの一覧。空になることもあります（無音など）
 * @param elapsedMs 文字起こしにかかった時間（ミリ秒）。チューニングの目安に使えます
 * @param audioMs   入力音声の長さ（ミリ秒）。サンプル数から求めた値で、無音区間も含みます
 */
public record TranscriptionResult(List<Segment> segments, long elapsedMs, long audioMs)
{
	/**
	 * 正規化コンストラクタ。セグメント一覧を変更不可なコピーとして保持します。
	 */
	public TranscriptionResult
	{
		segments = List.copyOf(segments);
	}

	/**
	 * 全セグメントを連結した文字列を返します。前後の空白は除去します。
	 *
	 * @return 文字起こし結果の全文。セグメントが無い場合は空文字列
	 */
	public String text()
	{
		return this.segments.stream()
				.map(Segment::text)
				.collect(Collectors.joining())
				.strip();
	}

	/**
	 * セグメントが 1 つも無いかどうかを返します。
	 *
	 * @return セグメントが空なら true
	 */
	public boolean isEmpty()
	{
		return this.segments.isEmpty();
	}

	/**
	 * 音声長に対する処理時間の比率（Real Time Factor）を返します。
	 *
	 * <p>
	 * 1.0 を下回っていれば実時間より速く処理できています。分母は {@link #audioMs()}（入力サンプル数から
	 * 求めた音声長）です。最後のセグメントの終了時刻ではありません。VAD で無音を飛ばした場合や末尾が無音の場合、
	 * セグメントの終了時刻は音声長より短くなり、RTF を悪く見せてしまうためです。
	 * 音声長が 0 の場合は計算できないため {@link Double#NaN} を返します。
	 * </p>
	 *
	 * @return RTF（処理時間 ÷ 音声長）
	 */
	public double realTimeFactor()
	{
		if(this.audioMs <= 0L)
		{
			return Double.NaN;
		}
		return (double) this.elapsedMs / (double) this.audioMs;
	}
}
