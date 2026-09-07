package jp.clip.whisper;

/**
 * 文字起こし処理で発生した異常を表します。
 *
 * <p>
 * モデルの読み込み失敗、ネイティブライブラリのロード失敗、音声フォーマットの非対応、
 * whisper.cpp 側のエラーなどをまとめてこの例外で通知します。原因となった例外は
 * {@link #getCause()} から取得できます。
 * </p>
 */
public class WhisperException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	/**
	 * メッセージのみを指定して生成します。
	 *
	 * @param message エラーメッセージ
	 */
	public WhisperException(String message)
	{
		super(message);
	}

	/**
	 * メッセージと原因を指定して生成します。
	 *
	 * @param message エラーメッセージ
	 * @param cause   原因となった例外
	 */
	public WhisperException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
