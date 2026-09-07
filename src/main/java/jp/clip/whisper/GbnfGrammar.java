package jp.clip.whisper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GBNF 文法テキストを {@code whisper_grammar_element} の並びへ解析したもの。
 *
 * <p>
 * whisper.cpp の {@code examples/grammar-parser.cpp}（{@code grammar_parser::parse}）を Java へ
 * 移植したものです。あちらは <b>examples の一部で DLL にエクスポートされていない</b>ため、
 * FFM から呼ぶことができません。JNI バックエンドは自作 C++ ブリッジがそれをリンクして使っていますが、
 * FFM バックエンドではこのクラスが同じ仕事をします。
 * </p>
 *
 * <p>
 * 移植元と同じ規則を実装しています。
 * </p>
 * <ul>
 * <li>{@code name ::= alternates} を改行区切りで並べる。{@code #} から行末までコメント</li>
 * <li>{@code "リテラル"}、文字クラス {@code [a-z]} {@code [^abc]}、規則参照、{@code (…)} のグループ</li>
 * <li>繰り返し {@code *} {@code +} {@code ?} は補助規則へ書き換える（{@code S* → S' ::= S S' |}）</li>
 * <li>エスケープ {@code \n} {@code \r} {@code \t} {@code \\} {@code \"} {@code \[} {@code \]}
 * {@code \xHH} {@code &#92;uHHHH} {@code &#92;UHHHHHHHH}（Javadoc 上の &#92; はバックスラッシュ）</li>
 * </ul>
 *
 * <p>
 * 移植元は解析エラーを stderr に出して空の結果を返しますが、こちらは
 * {@link WhisperException} を投げます（JNI バックエンドが {@code IOException} 経由で
 * 同じことをしているのと揃えています）。
 * </p>
 *
 * <p>
 * <b>文字はコードポイント単位で扱います。</b>移植元が UTF-8 のバイト列を自前でデコードしているのに対し、
 * こちらは Java の文字列をコードポイントの配列にしてから読み進めます。結果は同じです。
 * </p>
 */
final class GbnfGrammar
{
	/** {@code whisper_gretype} の値。C の enum と一致させること。 */
	static final int TYPE_END = 0;
	static final int TYPE_ALT = 1;
	static final int TYPE_RULE_REF = 2;
	static final int TYPE_CHAR = 3;
	static final int TYPE_CHAR_NOT = 4;
	static final int TYPE_CHAR_RNG_UPPER = 5;
	static final int TYPE_CHAR_ALT = 6;

	/**
	 * 文法規則の 1 要素。{@code struct whisper_grammar_element} に対応します。
	 *
	 * @param type  {@code whisper_gretype} の値
	 * @param value コードポイント、または規則 ID
	 */
	record Element(int type, int value)
	{
	}

	/** 規則 ID 順に並んだ規則。各規則は {@link #TYPE_END} で終わります。 */
	private final List<List<Element>> rules;

	/** {@code root} 規則の ID。 */
	private final int rootRuleId;

	private GbnfGrammar(List<List<Element>> rules, int rootRuleId)
	{
		this.rules = rules;
		this.rootRuleId = rootRuleId;
	}

	/**
	 * GBNF テキストを解析します。
	 *
	 * @param grammarText GBNF 文法のテキスト
	 * @return 解析結果
	 * @throws WhisperException 文法が空、構文が不正、または {@code root} 規則が無い場合
	 */
	static GbnfGrammar parse(String grammarText)
	{
		if(grammarText == null || grammarText.isBlank())
		{
			throw new WhisperException("文法の解析に失敗しました。文法が空です。");
		}

		Parser parser = new Parser(grammarText);
		parser.parseAll();

		Integer rootRuleId = parser.symbolIds.get("root");
		if(rootRuleId == null || parser.rules.isEmpty())
		{
			throw new WhisperException("文法の解析に失敗しました。root 規則が定義されていません。");
		}
		for(int ruleId = 0; ruleId < parser.rules.size(); ruleId++)
		{
			if(parser.rules.get(ruleId) == null)
			{
				throw new WhisperException("文法の解析に失敗しました。参照されている規則 "
						+ parser.nameOf(ruleId) + " が定義されていません。");
			}
		}
		return new GbnfGrammar(List.copyOf(parser.rules), rootRuleId);
	}

	/**
	 * 規則 ID 順に並んだ規則を返します。
	 *
	 * @return 規則の一覧。各規則は {@link #TYPE_END} で終わる
	 */
	List<List<Element>> rules()
	{
		return this.rules;
	}

	/**
	 * {@code root} 規則の ID を返します（{@code whisper_full_params.i_start_rule}）。
	 *
	 * @return root 規則の ID
	 */
	int rootRuleId()
	{
		return this.rootRuleId;
	}

	// ------------------------------------------------------------------------
	// 解析本体
	// ------------------------------------------------------------------------

	/**
	 * 移植元の {@code grammar_parser} と同じ再帰下降パーサ。
	 *
	 * <p>
	 * {@code position} は {@link #codePoints} 上の添字で、移植元の {@code const char * pos} に相当します。
	 * 添字を細かく前後させる字句解析なので、Stream には置き換えていません。
	 * </p>
	 */
	private static final class Parser
	{
		private final int[] codePoints;
		private int position = 0;

		/** 規則名 → 規則 ID。挿入順を保つので {@link #nameOf(int)} で逆引きできる。 */
		private final Map<String, Integer> symbolIds = new LinkedHashMap<>();

		/** 規則 ID → 規則。まだ定義されていない ID には null が入る。 */
		private final List<List<Element>> rules = new ArrayList<>();

		private Parser(String grammarText)
		{
			this.codePoints = grammarText.codePoints().toArray();
		}

		private void parseAll()
		{
			this.skipSpace(true);
			while(!this.atEnd())
			{
				this.parseRule();
			}
		}

		// --- 文字の読み取り -------------------------------------------------

		private boolean atEnd()
		{
			return this.position >= this.codePoints.length;
		}

		/** 現在位置の文字。終端なら 0（移植元の NUL 終端に合わせている）。 */
		private int current()
		{
			return this.atEnd() ? 0 : this.codePoints[this.position];
		}

		private int at(int offset)
		{
			int index = this.position + offset;
			return index >= this.codePoints.length ? 0 : this.codePoints[index];
		}

		private static boolean isWordChar(int codePoint)
		{
			return ('a' <= codePoint && codePoint <= 'z')
					|| ('A' <= codePoint && codePoint <= 'Z')
					|| codePoint == '-'
					|| ('0' <= codePoint && codePoint <= '9');
		}

		/** 空白とコメントを読み飛ばします。{@code newlineOk} が false なら改行で止まります。 */
		private void skipSpace(boolean newlineOk)
		{
			while(!this.atEnd())
			{
				int codePoint = this.current();
				if(codePoint == '#')
				{
					while(!this.atEnd() && this.current() != '\r' && this.current() != '\n')
					{
						this.position++;
					}
				}
				else if(codePoint == ' ' || codePoint == '\t'
						|| (newlineOk && (codePoint == '\r' || codePoint == '\n')))
				{
					this.position++;
				}
				else
				{
					return;
				}
			}
		}

		private String parseName()
		{
			int start = this.position;
			while(isWordChar(this.current()))
			{
				this.position++;
			}
			if(this.position == start)
			{
				throw this.error("規則名が必要です");
			}
			return new String(this.codePoints, start, this.position - start);
		}

		/** 1 文字（エスケープを含む）を読んでコードポイントを返します。 */
		private int parseChar()
		{
			if(this.atEnd())
			{
				throw this.error("文字が必要ですが入力が終わりました");
			}
			if(this.current() != '\\')
			{
				int codePoint = this.current();
				this.position++;
				return codePoint;
			}

			int escaped = this.at(1);
			this.position += 2;
			return switch(escaped)
			{
				case 'x' -> this.parseHex(2);
				case 'u' -> this.parseHex(4);
				case 'U' -> this.parseHex(8);
				case 't' -> '\t';
				case 'r' -> '\r';
				case 'n' -> '\n';
				case '\\', '"', '[', ']' -> escaped;
				default -> throw this.error("未知のエスケープです");
			};
		}

		private int parseHex(int digits)
		{
			int value = 0;
			for(int index = 0; index < digits; index++)
			{
				int codePoint = this.current();
				int digit = Character.digit(codePoint, 16);
				if(digit < 0)
				{
					throw this.error("16 進数が " + digits + " 桁必要です");
				}
				value = (value << 4) + digit;
				this.position++;
			}
			return value;
		}

		// --- 規則 -----------------------------------------------------------

		private int symbolId(String name)
		{
			return this.symbolIds.computeIfAbsent(name, key -> this.reserveRuleId());
		}

		/** {@code (…)} や繰り返しのために合成する規則の ID を採番します。名前は衝突しないよう連番付き。 */
		private int generateSymbolId(String baseName)
		{
			int ruleId = this.reserveRuleId();
			this.symbolIds.put(baseName + "_" + ruleId, ruleId);
			return ruleId;
		}

		private int reserveRuleId()
		{
			int ruleId = this.symbolIds.size();
			while(this.rules.size() <= ruleId)
			{
				this.rules.add(null);
			}
			return ruleId;
		}

		private void addRule(int ruleId, List<Element> rule)
		{
			while(this.rules.size() <= ruleId)
			{
				this.rules.add(null);
			}
			this.rules.set(ruleId, List.copyOf(rule));
		}

		private String nameOf(int ruleId)
		{
			return this.symbolIds.entrySet().stream()
					.filter(entry -> entry.getValue() == ruleId)
					.map(Map.Entry::getKey)
					.findFirst()
					.orElse("#" + ruleId);
		}

		private void parseRule()
		{
			String name = this.parseName();
			this.skipSpace(false);
			int ruleId = this.symbolId(name);

			if(!(this.current() == ':' && this.at(1) == ':' && this.at(2) == '='))
			{
				throw this.error("::= が必要です");
			}
			this.position += 3;
			this.skipSpace(true);

			this.parseAlternates(name, ruleId, false);

			if(this.current() == '\r')
			{
				this.position += this.at(1) == '\n' ? 2 : 1;
			}
			else if(this.current() == '\n')
			{
				this.position++;
			}
			else if(!this.atEnd())
			{
				throw this.error("改行または入力の終わりが必要です");
			}
			this.skipSpace(true);
		}

		private void parseAlternates(String ruleName, int ruleId, boolean nested)
		{
			List<Element> rule = new ArrayList<>();
			this.parseSequence(ruleName, rule, nested);
			while(this.current() == '|')
			{
				rule.add(new Element(TYPE_ALT, 0));
				this.position++;
				this.skipSpace(true);
				this.parseSequence(ruleName, rule, nested);
			}
			rule.add(new Element(TYPE_END, 0));
			this.addRule(ruleId, rule);
		}

		private void parseSequence(String ruleName, List<Element> elements, boolean nested)
		{
			int lastSymbolStart = elements.size();
			while(!this.atEnd())
			{
				if(this.current() == '"')
				{
					this.position++;
					lastSymbolStart = elements.size();
					while(this.current() != '"')
					{
						if(this.atEnd())
						{
							throw this.error("文字列が閉じていません");
						}
						elements.add(new Element(TYPE_CHAR, this.parseChar()));
					}
					this.position++;
					this.skipSpace(nested);
				}
				else if(this.current() == '[')
				{
					this.position++;
					int startType = TYPE_CHAR;
					if(this.current() == '^')
					{
						this.position++;
						startType = TYPE_CHAR_NOT;
					}
					lastSymbolStart = elements.size();
					while(this.current() != ']')
					{
						if(this.atEnd())
						{
							throw this.error("文字クラスが閉じていません");
						}
						int codePoint = this.parseChar();
						int type = lastSymbolStart < elements.size() ? TYPE_CHAR_ALT : startType;
						elements.add(new Element(type, codePoint));
						if(this.current() == '-' && this.at(1) != ']')
						{
							this.position++;
							elements.add(new Element(TYPE_CHAR_RNG_UPPER, this.parseChar()));
						}
					}
					this.position++;
					this.skipSpace(nested);
				}
				else if(isWordChar(this.current()))
				{
					int referencedRuleId = this.symbolId(this.parseName());
					this.skipSpace(nested);
					lastSymbolStart = elements.size();
					elements.add(new Element(TYPE_RULE_REF, referencedRuleId));
				}
				else if(this.current() == '(')
				{
					this.position++;
					this.skipSpace(true);
					int subRuleId = this.generateSymbolId(ruleName);
					this.parseAlternates(ruleName, subRuleId, true);
					lastSymbolStart = elements.size();
					elements.add(new Element(TYPE_RULE_REF, subRuleId));
					if(this.current() != ')')
					{
						throw this.error("')' が必要です");
					}
					this.position++;
					this.skipSpace(nested);
				}
				else if(this.current() == '*' || this.current() == '+' || this.current() == '?')
				{
					this.applyRepetition(ruleName, elements, lastSymbolStart, nested);
					// 直前の記号は補助規則への参照 1 個に置き換わっている
					lastSymbolStart = elements.size() - 1;
				}
				else
				{
					return;
				}
			}
		}

		/**
		 * 繰り返し演算子を補助規則へ書き換えます。
		 *
		 * <pre>
		 * S* → S' ::= S S' |
		 * S+ → S' ::= S S' | S
		 * S? → S' ::= S |
		 * </pre>
		 */
		private void applyRepetition(String ruleName, List<Element> elements, int lastSymbolStart, boolean nested)
		{
			if(lastSymbolStart == elements.size())
			{
				throw this.error("*, +, ? の前に要素が必要です");
			}
			int operator = this.current();

			int subRuleId = this.generateSymbolId(ruleName);
			List<Element> precedingSymbol = List.copyOf(elements.subList(lastSymbolStart, elements.size()));

			List<Element> subRule = new ArrayList<>(precedingSymbol);
			if(operator == '*' || operator == '+')
			{
				subRule.add(new Element(TYPE_RULE_REF, subRuleId)); // 自分自身を参照して繰り返す
			}
			subRule.add(new Element(TYPE_ALT, 0));
			if(operator == '+')
			{
				subRule.addAll(precedingSymbol); // '+' は 1 回以上なので空を許さない
			}
			subRule.add(new Element(TYPE_END, 0));
			this.addRule(subRuleId, subRule);

			// 元の規則では直前の記号を補助規則への参照に置き換える
			elements.subList(lastSymbolStart, elements.size()).clear();
			elements.add(new Element(TYPE_RULE_REF, subRuleId));

			this.position++;
			this.skipSpace(nested);
		}

		private WhisperException error(String message)
		{
			int remaining = Math.min(20, this.codePoints.length - this.position);
			String near = remaining <= 0 ? "（入力の終わり）"
					: new String(this.codePoints, this.position, remaining);
			return new WhisperException("文法の解析に失敗しました。" + message + " 位置=" + this.position + " 付近=\"" + near + "\"");
		}
	}
}
