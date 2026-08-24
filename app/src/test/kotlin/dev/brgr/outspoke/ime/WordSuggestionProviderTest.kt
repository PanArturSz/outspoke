package dev.brgr.outspoke.ime

import android.content.ContextWrapper
import dev.brgr.outspoke.inference.WordAlternative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [WordSuggestionProvider] — the IME-side façade that looks up the ASR
 * model's acoustic alternatives, rescores them per active language, and delivers the
 * merged top-5 on the main thread.
 *
 * Runs on the JVM: a [ContextWrapper] fake supplies `filesDir` (the only Context use),
 * `Dispatchers.setMain` supplies the main dispatcher, and the synthetic dict/LM files
 * under `suggestion_files/en/` pin the expected ranking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WordSuggestionProviderTest {

    private val LN10 = 2.302585093f

    // LM: "is" favours weather; "whether" favours whether (mirrors WordCorrectorTest).
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

    private val DICT_FILE = """
        weather	-1.0
        whether	-1.5
        is	-0.5
        rain	-1.2
        shine	-1.8
        fine	-1.4
    """.trimIndent()

    private lateinit var filesDir: File
    private lateinit var provider: WordSuggestionProvider

    /** Minimal Context fake: the provider only reads [getFilesDir]. */
    private class FakeContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        filesDir = File.createTempFile("provider-test", "").apply {
            delete()
            mkdirs()
        }
        val enDir = File(filesDir, "suggestion_files/en").apply { mkdirs() }
        File(enDir, "dict_en.txt").writeText(DICT_FILE)
        File(enDir, "lm_en.arpa").writeText(LM_FILE)
        provider = WordSuggestionProvider(FakeContext(filesDir))
    }

    @After
    fun tearDown() {
        provider.close()
        Dispatchers.resetMain()
    }

    /** One-shot delivery holder: [await] blocks up to [timeoutMs] for a delivery. */
    private class Delivery {
        @Volatile
        private var value: List<String>? = null

        fun deliver(v: List<String>) {
            value = v
        }

        /** Returns the delivered value, or `null` if none within [timeoutMs] (10 ms poll). */
        fun await(timeoutMs: Long): List<String>? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                value?.let { return it }
                Thread.sleep(10)
            }
            return value
        }
    }

    /**
     * Requests suggestions and retries (real time, up to [timeoutMs]) until a non-empty
     * delivery arrives — the corrector loads on a real background thread, so an early
     * call may race it and deliver an empty list.
     */
    private fun awaitSuggestions(word: String, context: String, timeoutMs: Long = 15_000): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val delivery = Delivery()
            provider.onSuggestions = { delivery.deliver(it) }
            provider.getSuggestions(word, context)
            val delivered = delivery.await(1_000)
            if (delivered != null && delivered.isNotEmpty()) return delivered
            check(System.currentTimeMillis() < deadline) { "no non-empty delivery within ${timeoutMs}ms" }
            Thread.sleep(50)
        }
    }

    @Test
    fun `no active languages means no delivery`() {
        val delivery = Delivery()
        provider.onSuggestions = { delivery.deliver(it) }
        provider.getSuggestions("wheather", "the is wheather")
        // No active languages → no-op; nothing is ever delivered.
        assertThat(delivery.await(500)).isNull()
    }

    @Test
    fun `acoustic alternatives are rescored in context and delivered`() {
        provider.setActiveLanguages(setOf("en"))
        provider.open()
        // Acoustic: weather -1.0, whether -1.05. Context "is" favours weather.
        provider.acousticLookup = { word ->
            if (word == "wheather") listOf(
                WordAlternative("weather", -1.0f),
                WordAlternative("whether", -1.05f),
            ) else emptyList()
        }
        val suggestions = awaitSuggestions("wheather", "the is wheather")
        assertThat(suggestions).containsExactly("weather", "whether")
    }

    @Test
    fun `lookup is invoked with the cursor word`() {
        provider.setActiveLanguages(setOf("en"))
        provider.open()
        val lookedUp = mutableListOf<String>()
        provider.acousticLookup = { word ->
            lookedUp += word
            emptyList()
        }
        awaitSuggestions("wheather", "the is wheather")
        assertThat(lookedUp).contains("wheather")
    }

    @Test
    fun `no acoustic evidence falls back to dictionary candidates`() {
        provider.setActiveLanguages(setOf("en"))
        provider.open()
        provider.acousticLookup = { emptyList() }
        // "wheather" → dictionary: weather (edit distance 1), LM context "is" ranks it first.
        val suggestions = awaitSuggestions("wheather", "the is wheather")
        assertThat(suggestions).isNotEmpty
        assertThat(suggestions.first()).isEqualTo("weather")
    }

    @Test
    fun `no lookup wired means dictionary fallback`() {
        provider.setActiveLanguages(setOf("en"))
        provider.open()
        // acousticLookup left null.
        val suggestions = awaitSuggestions("wheather", "the is wheather")
        assertThat(suggestions.first()).isEqualTo("weather")
    }

    @Test
    fun `left context is extracted from the sentence`() {
        provider.setActiveLanguages(setOf("en"))
        provider.open()
        provider.acousticLookup = { word ->
            if (word == "wheather") listOf(
                WordAlternative("weather", -1.0f),
                WordAlternative("whether", -1.05f),
            ) else emptyList()
        }
        // Context "whether" (not "is") flips the ranking: whether first.
        val suggestions = awaitSuggestions("wheather", "asked whether wheather")
        assertThat(suggestions.first()).isEqualTo("whether")
    }

    @Test
    fun `unknown language tags are filtered out`() {
        provider.setActiveLanguages(setOf("xx"))   // not in SuggestionLanguage.TAG_SET
        provider.open()
        provider.acousticLookup = { emptyList() }
        val delivery = Delivery()
        provider.onSuggestions = { delivery.deliver(it) }
        provider.getSuggestions("wheather", "the is wheather")
        assertThat(delivery.await(500)).isNull()
    }
}
