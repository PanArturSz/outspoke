package dev.brgr.outspoke.ime.correction

import dev.brgr.outspoke.inference.WordAlternative
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

/**
 * Unit tests for [WordCorrector] — the log-domain acoustic + LM rescoring with the
 * dictionary demoted to a low-prior fallback. Uses small synthetic dict/LM files so the
 * expected rankings are computed by hand.
 *
 * Scoring under test: `score = acousticLogProb + λ · ln(10) · lmLog10`, λ = 0.5.
 * Dictionary fallback uses a fixed acoustic prior of -2.0.
 *
 * `correct` returns words only, so every test pins the *ranking* (the observable
 * contract) rather than the raw scores: each hand-computed score pair below differs
 * enough in ordering that any clamping, wrong weight, or scale mix-up flips the result.
 */
class WordCorrectorTest {

    // LM: bigram model where context "is" favours weather and context "whether" favours whether.
    private val LM_FILE = """
        \data
        ngram 1=8
        ngram 2=8

        \1-grams:
        -1.00	<s>	-0.50
        -1.00	</s>	0.00
        -1.00	<unk>	0.00
        -1.00	weather	-0.30
        -1.00	whether	-0.30
        -1.00	is	-0.30
        -1.00	rain	-0.30
        -1.00	shine	-0.30

        \2-grams:
        -0.50	<s> weather	-0.20
        -0.50	<s> whether	-0.20
        -0.50	is weather	-0.20
        -2.00	is whether	-0.20
        -2.00	whether weather	-0.20
        -0.50	whether whether	-0.20
        -2.00	is rain	-0.20
        -0.50	is shine	-0.20

        \end:
    """.trimIndent()

    // Dictionary: word + log10 frequency.
    private val DICT_FILE = """
        weather	-1.0
        whether	-1.5
        is	-0.5
        rain	-1.2
        shine	-1.8
        fine	-1.4
    """.trimIndent()

    private fun newCorrector(): WordCorrector {
        val dir = File.createTempFile("corrector-test", "").apply {
            delete()
            mkdirs()
        }
        File(dir, "dict.txt").writeText(DICT_FILE)
        File(dir, "lm.arpa").writeText(LM_FILE)
        return WordCorrector(File(dir, "dict.txt"), File(dir, "lm.arpa"), "en").also { it.load() }
    }

    @Test
    fun `not ready returns empty`() {
        val corrector = WordCorrector(File("missing-dict"), File("missing-lm"), "en")
        corrector.load()
        assertThat(corrector.isReady).isFalse
        assertThat(corrector.correct("weather", listOf("is"))).isEmpty()
    }

    @Test
    fun `acoustic candidates are rescored and returned in order`() {
        val corrector = newCorrector()
        // Acoustic: weather -1.0, whether -1.05 (natural log).
        // Context "is": weather -1.0 + 0.5·ln10·(-0.50) = -1.575
        //               whether -1.05 + 0.5·ln10·(-2.00) = -3.353
        val acoustic = listOf(
            WordAlternative("weather", -1.0f),
            WordAlternative("whether", -1.05f),
        )
        assertThat(corrector.correct("wheather", listOf("is"), acoustic))
            .containsExactly("weather", "whether")
    }

    @Test
    fun `LM context flips the ranking of acoustically close candidates`() {
        val corrector = newCorrector()
        val acoustic = listOf(
            WordAlternative("weather", -1.0f),
            WordAlternative("whether", -1.05f),
        )
        // Context "is" favours weather (bigram -0.50 vs -2.00):
        //   weather -1.575 vs whether -3.353 → weather first.
        assertThat(corrector.correct("wheather", listOf("is"), acoustic).first()).isEqualTo("weather")
        // Context "whether" favours whether (bigram -0.50 vs -2.00):
        //   weather -1.0 + 0.5·ln10·(-2.00) = -3.303
        //   whether -1.05 + 0.5·ln10·(-0.50) = -1.625 → whether first.
        assertThat(corrector.correct("wheather", listOf("whether"), acoustic).first()).isEqualTo("whether")
    }

    @Test
    fun `acoustic score dominates a weaker LM preference`() {
        val corrector = newCorrector()
        // The LM favours "shine" (bigram "is shine" -0.50 vs "is rain" -2.00), but the
        // acoustic gap (2.2 natural log) dominates:
        //   rain  -0.3 + 0.5·ln10·(-2.00) = -2.603
        //   shine -2.5 + 0.5·ln10·(-0.50) = -3.075 → rain first.
        val acoustic = listOf(
            WordAlternative("rain", -0.3f),
            WordAlternative("shine", -2.5f),
        )
        assertThat(corrector.correct("reyn", listOf("is"), acoustic)).containsExactly("rain", "shine")
    }

    @Test
    fun `no acoustic evidence falls back to dictionary candidates`() {
        val corrector = newCorrector()
        // "wheather" is one edit away from both "weather" and "whether"; no acoustic
        // list → dictionary path with the fixed -2.0 prior, so the LM decides:
        //   weather -2.0 + 0.5·ln10·(-0.50) = -2.576
        //   whether -2.0 + 0.5·ln10·(-2.00) = -4.303 → weather first.
        val results = corrector.correct("wheather", listOf("is"), emptyList())
        assertThat(results).contains("weather")
        assertThat(results.first()).isEqualTo("weather")
    }

    @Test
    fun `query word is excluded from results`() {
        val corrector = newCorrector()
        val acoustic = listOf(
            WordAlternative("weather", -0.5f),
            WordAlternative("whether", -1.0f),
        )
        assertThat(corrector.correct("weather", listOf("is"), acoustic)).containsExactly("whether")
    }

    @Test
    fun `single-character words are rejected`() {
        val corrector = newCorrector()
        assertThat(corrector.correct("a", emptyList(), emptyList())).isEmpty()
    }

    @Test
    fun `non-dictionary acoustic candidates are filtered out`() {
        val corrector = newCorrector()
        // "wheathr" is the strongest acoustic alternative but not a dictionary word —
        // the lexicon-unconstrained beam can emit plausible token strings that are not
        // words. Only the genuine word survives the filter.
        val acoustic = listOf(
            WordAlternative("wheathr", -0.2f),
            WordAlternative("weather", -1.0f),
        )
        assertThat(corrector.correct("wheather", listOf("is"), acoustic))
            .containsExactly("weather")
    }

    @Test
    fun `acoustic candidates are deduplicated case-insensitively`() {
        val corrector = newCorrector()
        // Both "Rain" and "rain" are dictionary words (case-insensitive), but the LM is
        // case-sensitive (lowercase ARPA pack): "Rain" takes the UNK penalty. The
        // acoustically weaker "rain" would lose, but "Rain" is acoustically strong
        // enough to win the combined score — one entry, highest-scoring casing kept:
        //   Rain -1.5 + 0.5·ln10·(-0.30 + -4.00) = -6.451
        //   rain -5.0 + 0.5·ln10·(-2.00)         = -7.303 → "Rain" first (and only).
        val acoustic = listOf(
            WordAlternative("rain", -5.0f),
            WordAlternative("Rain", -1.5f),
        )
        assertThat(corrector.correct("reyn", listOf("is"), acoustic))
            .containsExactly("Rain")
    }

    @Test
    fun `all-non-dictionary acoustic falls back to dictionary candidates`() {
        val corrector = newCorrector()
        // Both acoustic alternatives are non-words (the query itself is excluded
        // anyway) → nothing survives the lexicon filter → the dictionary fallback
        // applies, exactly as with no acoustic evidence at all.
        val acoustic = listOf(
            WordAlternative("wheathr", -0.1f),
            WordAlternative("wheather", -0.05f),
        )
        val results = corrector.correct("wheather", listOf("is"), acoustic)
        assertThat(results).isNotEmpty
        assertThat(results.first()).isEqualTo("weather")
        assertThat(results).doesNotContain("wheathr")
    }
}
