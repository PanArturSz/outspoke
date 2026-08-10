package dev.brgr.outspoke.ime.correction

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File

/**
 * Unit tests for [ArpaLanguageModel] — trigram scoring with standard ARPA backoff and
 * raw log10 output (no clamping). Uses small synthetic ARPA files written to a temp dir,
 * plus a production-format file (CRLF line endings, the literal `\data\nngram` header
 * quirk of the outspoke-data packs) to pin the parser against the real file format.
 */
class ArpaLanguageModelTest {

    // Synthetic trigram model with known values:
    //   unigrams: weather/whether/is/fine/rain = -1.00 (bow -0.30), <s> (bow -0.50), zzz = -2.00
    //   bigrams:  "<s> weather" -0.50, "weather is" -0.50 (bow -0.20), "is fine" -0.50,
    //             "is rain" -1.50, "is zzz" -1.00
    //   trigrams: "weather is fine" -0.30, "weather is rain" -0.80
    private val TRIGRAM_FILE = """
        \data
        ngram 1=9
        ngram 2=6
        ngram 3=2

        \1-grams:
        -1.00	<s>	-0.50
        -1.00	</s>	0.00
        -1.00	<unk>	0.00
        -1.00	weather	-0.30
        -1.00	whether	-0.30
        -1.00	is	-0.30
        -1.00	fine	-0.30
        -1.00	rain	-0.30
        -2.00	zzz	-0.30

        \2-grams:
        -0.50	<s> weather	-0.20
        -0.50	<s> whether	-0.20
        -0.50	weather is	-0.20
        -0.50	whether is	-0.20
        -0.50	is fine	-0.20
        -1.50	is rain	-0.20
        -1.00	is zzz	-0.10

        \3-grams:
        -0.30	weather is fine	0.00
        -0.80	weather is rain	0.00

        \end:
    """.trimIndent()

    // Same unigrams/bigrams, no trigram section (the production packs are bigram-only).
    private val BIGRAM_FILE = """
        \data
        ngram 1=9
        ngram 2=6

        \1-grams:
        -1.00	<s>	-0.50
        -1.00	</s>	0.00
        -1.00	<unk>	0.00
        -1.00	weather	-0.30
        -1.00	whether	-0.30
        -1.00	is	-0.30
        -1.00	fine	-0.30
        -1.00	rain	-0.30
        -2.00	zzz	-0.30

        \2-grams:
        -0.50	<s> weather	-0.20
        -0.50	<s> whether	-0.20
        -0.50	weather is	-0.20
        -0.50	whether is	-0.20
        -0.50	is fine	-0.20
        -1.50	is rain	-0.20
        -1.00	is zzz	-0.10

        \end:
    """.trimIndent()

    private fun loadModel(content: String): ArpaLanguageModel {
        val file = File.createTempFile("lm-test", ".arpa").apply { deleteOnExit() }
        file.writeText(content)
        return ArpaLanguageModel(file, "en").also { it.load() }
    }

    @Test
    fun `loads and reports ready`() {
        val lm = loadModel(TRIGRAM_FILE)
        assertThat(lm.isReady).isTrue
    }

    @Test
    fun `trigram hit returns the trigram log10 probability`() {
        val lm = loadModel(TRIGRAM_FILE)
        assertThat(lm.scoreInContext("fine", listOf("weather", "is"))).isCloseTo(-0.30f, org.assertj.core.data.Offset.offset(1e-4f))
        assertThat(lm.scoreInContext("rain", listOf("weather", "is"))).isCloseTo(-0.80f, org.assertj.core.data.Offset.offset(1e-4f))
    }

    @Test
    fun `missing trigram backs off to bigram with the bigram backoff weight`() {
        val lm = loadModel(TRIGRAM_FILE)
        // "weather is zzz": no trigram → bow("weather is") = -0.20 + P(zzz | is) = -1.00
        assertThat(lm.scoreInContext("zzz", listOf("weather", "is"))).isCloseTo(-1.20f, org.assertj.core.data.Offset.offset(1e-4f))
    }

    @Test
    fun `missing trigram and bigram backs off to unigram with accumulated weights`() {
        val lm = loadModel(TRIGRAM_FILE)
        // "weather is qqq": no trigram → -0.20; no "is qqq" → bow(is) = -0.30; no unigram → UNK -4
        assertThat(lm.scoreInContext("qqq", listOf("weather", "is"))).isCloseTo(-4.50f, org.assertj.core.data.Offset.offset(1e-4f))
    }

    @Test
    fun `bigram hit with one context word`() {
        val lm = loadModel(TRIGRAM_FILE)
        assertThat(lm.scoreInContext("fine", listOf("is"))).isCloseTo(-0.50f, org.assertj.core.data.Offset.offset(1e-4f))
        assertThat(lm.scoreInContext("rain", listOf("is"))).isCloseTo(-1.50f, org.assertj.core.data.Offset.offset(1e-4f))
    }

    @Test
    fun `bigram miss backs off to unigram`() {
        val lm = loadModel(TRIGRAM_FILE)
        // bow(is) = -0.30 + UNK = -4
        assertThat(lm.scoreInContext("qqq", listOf("is"))).isCloseTo(-4.30f, org.assertj.core.data.Offset.offset(1e-4f))
    }

    @Test
    fun `no context scores the unigram`() {
        val lm = loadModel(TRIGRAM_FILE)
        assertThat(lm.scoreInContext("rain", emptyList())).isCloseTo(-1.00f, org.assertj.core.data.Offset.offset(1e-4f))
        assertThat(lm.scoreInContext("qqq", emptyList())).isCloseTo(-4.00f, org.assertj.core.data.Offset.offset(1e-4f))
    }

    @Test
    fun `two-word context on a bigram-only model uses backoff to the bigram`() {
        // The production packs have no \3-grams: section; the trigram path must degrade
        // to bow2 + bigram for every two-word context.
        val lm = loadModel(BIGRAM_FILE)
        // bow("weather is") = -0.20 + P(fine | is) = -0.50
        assertThat(lm.scoreInContext("fine", listOf("weather", "is"))).isCloseTo(-0.70f, org.assertj.core.data.Offset.offset(1e-4f))
    }

    @Test
    fun `context longer than two words uses only the last two`() {
        val lm = loadModel(TRIGRAM_FILE)
        // The trigram only sees (is, fine) context regardless of earlier words.
        assertThat(lm.scoreInContext("fine", listOf("a", "b", "is")))
            .isCloseTo(lm.scoreInContext("fine", listOf("is")), org.assertj.core.data.Offset.offset(1e-4f))
    }

    @Test
    fun `scoreSequence uses the full n-gram context per position`() {
        val lm = loadModel(TRIGRAM_FILE)
        // P(weather|<s>) -0.50, P(is|<s> weather) -0.70 (backoff), P(fine|weather is) -0.30,
        // P(</s>|weather is) -1.50 (double backoff) → sum -3.00 / 3
        assertThat(lm.scoreSequence(listOf("weather", "is", "fine"))).isCloseTo(-1.00f, org.assertj.core.data.Offset.offset(1e-4f))
    }
    @Test
    fun `capitalised context misses the bigram and backs off to the unigram`() {
        // ARPA keys are lowercase; a capitalised context word is a miss (callers lowercase).
        val lm = loadModel(TRIGRAM_FILE)
        assertThat(lm.scoreInContext("rain", listOf("is"))).isCloseTo(-1.50f, org.assertj.core.data.Offset.offset(1e-4f))
        // "Is rain" misses → bow("Is") absent → 0 + unigram(rain) = -1.00
        assertThat(lm.scoreInContext("rain", listOf("Is"))).isCloseTo(-1.00f, org.assertj.core.data.Offset.offset(1e-4f))
    }

    @Test
    fun `production file format with CRLF and literal backslash-n header parses`() {
        // The outspoke-data packs use CRLF line endings and a first line containing a
        // literal "\n" (backslash + n) between \data and ngram — the parser must handle
        // both (trim() strips \r; the \data\ prefix match skips the mangled header).
        val lines = listOf(
            "\\data\\nngram 1=2",
            "ngram 2=1",
            "",
            "\\1-grams:",
            "-1.00\t<unk>\t0.00",
            "-1.00\thello\t-0.30",
            "",
            "\\2-grams:",
            "-0.50\t<s> hello\t0.00",
            "",
            "\\end:",
        )
        val file = File.createTempFile("lm-prod", ".arpa").apply { deleteOnExit() }
        file.writeText(lines.joinToString("\r\n") + "\r\n")
        val lm = ArpaLanguageModel(file, "en")
        lm.load()
        assertThat(lm.isReady).isTrue
        assertThat(lm.scoreInContext("hello", emptyList())).isCloseTo(-1.00f, org.assertj.core.data.Offset.offset(1e-4f))
        assertThat(lm.scoreInContext("hello", listOf("<s>"))).isCloseTo(-0.50f, org.assertj.core.data.Offset.offset(1e-4f))
    }

    @Test
    fun `missing file leaves the model not ready and scoring neutral`() {
        val lm = ArpaLanguageModel(File("does-not-exist.arpa"), "en")
        lm.load()
        assertThat(lm.isReady).isFalse
        assertThat(lm.scoreInContext("anything", listOf("x"))).isZero()
    }
}
