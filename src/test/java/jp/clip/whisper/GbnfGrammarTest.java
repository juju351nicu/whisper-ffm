package jp.clip.whisper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link GbnfGrammar}（whisper.cpp の {@code grammar_parser} の Java 移植）の単体テスト。
 *
 * <p>
 * ネイティブライブラリを使わない純 Java のテストです。期待値は移植元
 * {@code examples/grammar-parser.cpp} の書き換え規則から導いています。
 * </p>
 */
public class GbnfGrammarTest
{
	/** 規則を「type:value」の並びに直して比較しやすくします。 */
	private static List<String> flatten(List<GbnfGrammar.Element> rule)
	{
		return rule.stream().map(element -> element.type() + ":" + element.value()).toList();
	}

	@Test
	public void literalBecomesCharElements()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("root ::= \"ab\"");

		assertEquals(0, grammar.rootRuleId());
		assertEquals(1, grammar.rules().size());
		assertEquals(List.of(
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'a',
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'b',
				GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(0)));
	}

	@Test
	public void characterClassBecomesRangeElements()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("root ::= [a-z0]");

		assertEquals(List.of(
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'a',
				GbnfGrammar.TYPE_CHAR_RNG_UPPER + ":" + (int) 'z',
				GbnfGrammar.TYPE_CHAR_ALT + ":" + (int) '0',
				GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(0)));
	}

	@Test
	public void negatedCharacterClassUsesCharNot()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("root ::= [^ab]");

		assertEquals(List.of(
				GbnfGrammar.TYPE_CHAR_NOT + ":" + (int) 'a',
				GbnfGrammar.TYPE_CHAR_ALT + ":" + (int) 'b',
				GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(0)));
	}

	@Test
	public void alternatesAreSeparatedByAltElement()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("root ::= \"a\" | \"b\"");

		assertEquals(List.of(
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'a',
				GbnfGrammar.TYPE_ALT + ":0",
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'b',
				GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(0)));
	}

	@Test
	public void ruleReferenceGetsItsOwnId()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("""
				root ::= word
				word ::= "hi"
				""");

		assertEquals(2, grammar.rules().size());
		assertEquals(0, grammar.rootRuleId());
		assertEquals(List.of(GbnfGrammar.TYPE_RULE_REF + ":1", GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(0)));
	}

	/**
	 * {@code S* → S' ::= S S' |}（空を許す再帰）。
	 */
	@Test
	public void starRewritesToRecursiveRuleWithEmptyAlternate()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("root ::= \"a\"*");

		assertEquals(2, grammar.rules().size());
		assertEquals(List.of(GbnfGrammar.TYPE_RULE_REF + ":1", GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(0)));
		assertEquals(List.of(
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'a',
				GbnfGrammar.TYPE_RULE_REF + ":1",
				GbnfGrammar.TYPE_ALT + ":0",
				GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(1)));
	}

	/**
	 * {@code S+ → S' ::= S S' | S}（1 回以上）。
	 */
	@Test
	public void plusRewritesToRecursiveRuleWithSelfAlternate()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("root ::= \"a\"+");

		assertEquals(List.of(
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'a',
				GbnfGrammar.TYPE_RULE_REF + ":1",
				GbnfGrammar.TYPE_ALT + ":0",
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'a',
				GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(1)));
	}

	/**
	 * {@code S? → S' ::= S |}（再帰しない）。
	 */
	@Test
	public void questionMarkRewritesWithoutRecursion()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("root ::= \"a\"?");

		assertEquals(List.of(
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'a',
				GbnfGrammar.TYPE_ALT + ":0",
				GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(1)));
	}

	@Test
	public void groupBecomesSynthesizedRule()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("root ::= (\"a\" | \"b\") \"c\"");

		assertEquals(2, grammar.rules().size());
		assertEquals(List.of(
				GbnfGrammar.TYPE_RULE_REF + ":1",
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'c',
				GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(0)));
		assertEquals(List.of(
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'a',
				GbnfGrammar.TYPE_ALT + ":0",
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'b',
				GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(1)));
	}

	@Test
	public void escapesAreDecoded()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("root ::= \"\\n\\t\\x41\"");

		assertEquals(List.of(
				GbnfGrammar.TYPE_CHAR + ":" + (int) '\n',
				GbnfGrammar.TYPE_CHAR + ":" + (int) '\t',
				GbnfGrammar.TYPE_CHAR + ":" + (int) 'A',
				GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(0)));
	}

	@Test
	public void commentsAndBlankLinesAreIgnored()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("""
				# 先頭のコメント
				root ::= "a"   # 行末のコメント

				""");

		assertEquals(1, grammar.rules().size());
		assertEquals(List.of(GbnfGrammar.TYPE_CHAR + ":" + (int) 'a', GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(0)));
	}

	@Test
	public void nonAsciiLiteralKeepsCodePoint()
	{
		GbnfGrammar grammar = GbnfGrammar.parse("root ::= \"あ\"");

		assertEquals(List.of(GbnfGrammar.TYPE_CHAR + ":" + (int) 'あ', GbnfGrammar.TYPE_END + ":0"),
				flatten(grammar.rules().get(0)));
	}

	@Test
	public void blankGrammarIsRejected()
	{
		assertThrows(WhisperException.class, () -> GbnfGrammar.parse("   "));
	}

	@Test
	public void grammarWithoutRootIsRejected()
	{
		WhisperException exception = assertThrows(WhisperException.class, () -> GbnfGrammar.parse("other ::= \"a\""));
		assertTrue(exception.getMessage().contains("root"), exception.getMessage());
	}

	@Test
	public void missingAssignmentIsRejected()
	{
		WhisperException exception = assertThrows(WhisperException.class, () -> GbnfGrammar.parse("root \"a\""));
		assertTrue(exception.getMessage().contains("::="), exception.getMessage());
	}

	@Test
	public void unclosedGroupIsRejected()
	{
		assertThrows(WhisperException.class, () -> GbnfGrammar.parse("root ::= (\"a\""));
	}

	@Test
	public void undefinedRuleReferenceIsRejected()
	{
		WhisperException exception = assertThrows(WhisperException.class,
				() -> GbnfGrammar.parse("root ::= missing"));
		assertTrue(exception.getMessage().contains("missing"), exception.getMessage());
	}

	@Test
	public void repetitionWithoutPrecedingItemIsRejected()
	{
		assertThrows(WhisperException.class, () -> GbnfGrammar.parse("root ::= *"));
	}
}
