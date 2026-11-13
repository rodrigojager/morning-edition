package dobra.collect.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeywordMatcherTest {
    @Test
    fun ignoresCaseAndAccentsBothWays() {
        assertTrue(KeywordMatcher.matches("Segurança", "Guia de seguranca", ""))
        assertTrue(KeywordMatcher.matches("seguranca", "SEGURANÇA em primeiro lugar", ""))
        assertTrue(KeywordMatcher.matches("ÁGUA", "", "a agua do rio"))
        // Text in decomposed form (letter + combining accent) matches too.
        assertTrue(KeywordMatcher.matches("ação", "uma ação rápida", ""))
    }

    @Test
    fun matchesWordForWordOnly() {
        assertTrue(KeywordMatcher.matches("IA", "A IA na escola", ""))
        assertFalse(KeywordMatcher.matches("IA", "Mídia e democracia", ""), "not inside another word")
        assertFalse(KeywordMatcher.matches("test", "testes automatizados", ""))
        assertTrue(KeywordMatcher.matches("Kotlin", "", "Novidades do Kotlin, hoje."))
    }

    @Test
    fun aKeywordOfSeveralWordsMatchesThemInARow() {
        assertTrue(KeywordMatcher.matches("código aberto", "Projetos de Código-Aberto", ""))
        assertFalse(KeywordMatcher.matches("código aberto", "Código fechado, portão aberto", ""))
    }

    @Test
    fun looksInTheTitleOrTheText() {
        assertTrue(KeywordMatcher.matches("chuva", "Previsão", "chuva forte"))
        assertTrue(KeywordMatcher.matches("chuva", "Chuva forte", "previsão"))
        assertFalse(KeywordMatcher.matches("chuva", "Sol", "calor"))
    }

    @Test
    fun reportsWhichKeywordsMatchedInTheirOrder() {
        assertEquals(
            listOf("Kotlin", "segurança"),
            KeywordMatcher.matching(listOf("Kotlin", "segurança", "pão"), "Segurança em Kotlin", ""),
        )
        assertEquals(emptyList(), KeywordMatcher.matching(listOf("", "  "), "qualquer coisa", ""))
    }
}
