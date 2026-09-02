package dev.brgr.outspoke.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDictionaryTest {

    private fun dict(rules: String) = UserDictionary.parse(rules)

    @Test
    fun `fraza kanoniczna poprawia wielkość liter i sklejenia`() {
        val d = dict("Claude Code")
        assertEquals("Claude Code", d.apply("claude code"))
        assertEquals("Claude Code", d.apply("ClaudeCode"))
        assertEquals("Claude Code", d.apply("claude-code"))
        assertEquals("Użyj Claude Code teraz", d.apply("użyj claude   code teraz"))
    }

    @Test
    fun `para korekty zamienia każde źródło`() {
        val d = dict("klot kot | clot code => Claude Code")
        assertEquals("Claude Code działa", d.apply("Klot kot działa"))
        assertEquals("Claude Code działa", d.apply("clotcode działa"))
    }

    @Test
    fun `nie rusza środka słowa ani odmiany`() {
        val d = dict("Claude\nAnthropic")
        assertEquals("Anthropicem", d.apply("Anthropicem"))
        assertEquals("Claude'a", d.apply("claude'a"))
        assertEquals("Cloudflare", d.apply("Cloudflare"))
    }

    @Test
    fun `najdłuższa reguła wygrywa`() {
        val d = dict("klot => Claude\nklot kot => Claude Code")
        assertEquals("Claude Code i Claude", d.apply("klot kot i klot"))
    }

    @Test
    fun `komentarze i puste linie są pomijane, błędy raportowane`() {
        val d = dict("# komentarz\n\nPKO BP # kanon\n=> bez źródła")
        assertEquals(1, d.size)
        assertEquals(1, d.errors.size)
        assertEquals("PKO BP", d.apply("pkobp"))
    }

    @Test
    fun `polskie znaki są literami na granicy`() {
        val d = dict("Krzyś")
        assertEquals("Krzyś", d.apply("krzyś"))
        assertEquals("Krzysia", d.apply("Krzysia"))
    }

    @Test
    fun `domyślne reguły parsują się bez błędów i naprawiają test z 02_09`() {
        val d = dict(UserDictionary.DEFAULT_RULES)
        assertTrue(d.errors.isEmpty())
        assertEquals("Claude, Cowork, Anthropic", d.apply("Clot, Cowork, Antropic"))
        assertEquals("Spotkanie z PKO BP, ASZSTRATEGY", d.apply("Spotkanie z PKOBP, ASZ Strategy"))
    }
}
