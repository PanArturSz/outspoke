package dev.brgr.outspoke.ime.correction

import dev.brgr.outspoke.audio.AudioChunk
import dev.brgr.outspoke.inference.InferenceRepository
import dev.brgr.outspoke.inference.ParakeetEngine
import dev.brgr.outspoke.inference.SpeechEngine
import dev.brgr.outspoke.inference.TranscriptResult
import dev.brgr.outspoke.inference.WavReader
import dev.brgr.outspoke.inference.resolveModelDir
import dev.brgr.outspoke.inference.resolveSuggestionDir
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

/**
 * Phase-0 recall harness for the word-correction overhaul (acoustic n-best + LM rescoring).
 *
 * **Metric:** for every *substitution* error the ASR model makes on a known utterance
 * (emitted word X where the reference word is Y), the harness looks up the model's own
 * acoustic alternatives for X (captured at decode time in the repository's acoustic
 * cache), rescores them with the ARPA language model and the surrounding context, and
 * records whether the true word Y is among the top 5 — **recall@5**.
 *
 * **Error generation:** the fixtures are clean studio TTS, which Parakeet transcribes
 * near-perfectly — there would be no errors to measure. The harness therefore adds
 * seeded white noise at 4 dB SNR (deterministic: same seed → same audio → same errors),
 * which produces genuine mis-hearings while keeping the transcripts mostly intelligible.
 *
 * **Scope:** only multi-character error words are scored. Single-character emissions
 * (digits for "six"/"three", "a" for "the") are out of scope for the correction feature
 * (the corrector rejects words shorter than 2 characters) and are reported but not
 * counted.
 *
 * Skipped when the model directory or the English suggestion data (dict + ARPA LM) are
 * unavailable.
 */
class CorrectionRecallTest {

    companion object {
        private val engine: SpeechEngine = ParakeetEngine()
        private var modelAvailable = false
        private var corrector: WordCorrector? = null

        @BeforeClass
        @JvmStatic
        fun loadModel() {
            val modelDir = try {
                resolveModelDir()
            } catch (e: IllegalStateException) {
                Assume.assumeFalse("Parakeet model dir not available", true)
                return
            }
            engine.load(modelDir)
            modelAvailable = true
            try {
                val dir = resolveSuggestionDir("en")
                corrector = WordCorrector(
                    File(dir, "dict_en.txt"),
                    File(dir, "lm_en.arpa"),
                    "en",
                ).also { it.load() }
            } catch (e: IllegalStateException) {
                println("[RECALL] English suggestion data unavailable — harness will skip: ${e.message?.lineSequence()?.first()}")
            }
        }

        @AfterClass
        @JvmStatic
        fun closeModel() {
            if (modelAvailable) engine.close()
        }
    }

    private data class Fixture(val name: String, val reference: String)

    private val fixtures = listOf(
        Fixture("long-weather", "The weather forecast predicts rain tomorrow so we should stay indoors."),
        Fixture("long-package", "The package arrived yesterday but the contents were damaged during shipping."),
        Fixture("long-plants", "Please remember to water the plants while I am away on my business trip."),
        Fixture("long-pizza", "The restaurant on the corner serves the best pizza in the whole city."),
        Fixture("long-reservation", "I am writing to confirm that our reservation for two people has been received."),
        Fixture("long-slides", "We need to finish the presentation slides before the client arrives."),
        Fixture("long-train", "Can you tell me what time the train leaves from the central station?"),
        Fixture("long-wallet", "I forgot my wallet at home so I cannot pay for the taxi now."),
        Fixture("long-appointment", "I would like to schedule an appointment with the doctor for next week if possible."),
        Fixture("long-approach", "I think we should reconsider our approach to the project before we continue."),
        Fixture("long-callback", "She said that she would call us back as soon as she got home."),
        Fixture("long-furniture", "He promised to help us move the furniture on Saturday morning."),
        Fixture("med-groceries", "I need to order some groceries before the store closes at six."),
        Fixture("med-report", "Could you please send me the report by end of day tomorrow?"),
        Fixture("med-meeting", "The meeting was moved to Thursday afternoon at three o'clock."),
    )

    private fun loadSamples(name: String): ShortArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("audio/$name.wav")
            ?: throw IllegalStateException("WAV fixture '$name' not found on classpath")
        val pcm = WavReader().readPcm16(ByteArrayInputStream(stream.readBytes()))
        return WavReader().resampleLinear(pcm.samples, pcm.sampleRate, 16_000)
    }

    /** Adds seeded white noise at [snrDb] dB signal-to-noise ratio (deterministic per seed). */
    private fun addNoise(samples: ShortArray, snrDb: Double, seed: Long): ShortArray {
        val signalEnergy = samples.sumOf { (it.toDouble() * it) } / samples.size
        val noiseVariance = signalEnergy / Math.pow(10.0, snrDb / 10.0)
        val noiseStd = Math.sqrt(noiseVariance)
        val rnd = java.util.Random(seed)
        return ShortArray(samples.size) { i ->
            val n = rnd.nextGaussian() * noiseStd
            (samples[i].toDouble() + n).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    private fun chunkify(samples: ShortArray, chunkSize: Int = 640): List<AudioChunk> {
        val chunks = mutableListOf<AudioChunk>()
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + chunkSize, samples.size)
            chunks.add(AudioChunk(samples.copyOfRange(i, end)))
            i = end
        }
        return chunks
    }

    private fun normWords(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    /**
     * Word-level Levenshtein alignment of [emitted] against [reference]; returns the
     * substitution pairs (emitted → reference) where the words differ.
     */
    private fun substitutionPairs(emitted: List<String>, reference: List<String>): List<Pair<String, String>> {
        val n = emitted.size
        val m = reference.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in n downTo 1) {
            for (j in m downTo 1) {
                dp[i][j] = if (emitted[i - 1] == reference[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        val subs = mutableListOf<Pair<String, String>>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when {
                emitted[i - 1] == reference[j - 1] -> { i--; j-- }
                dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    if (emitted[i - 1] != reference[j - 1]) subs.add(0, emitted[i - 1] to reference[j - 1])
                    i--; j--
                }
                dp[i][j] == dp[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
        return subs
    }

    @Test
    fun `recall at 5 of the true word over ASR error words`() {
        Assume.assumeTrue(modelAvailable)
        val corrector = CorrectionRecallTest.corrector
        Assume.assumeTrue("English suggestion data (dict + LM) not available", corrector != null)
        engine.setLanguage("en")

        val SNR_DB = 4.0
        val seeds = listOf(42L, 43L, 44L)

        var scoreableErrors = 0
        var hits = 0
        var baselineHits = 0
        var acousticEvidenceErrors = 0

        for (seed in seeds) {
            for (fixture in fixtures) {
                val samples = addNoise(loadSamples(fixture.name), SNR_DB, seed)
                val repo = InferenceRepository(engine)
                val results = runBlocking {
                    repo.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                        .toList()
                }
                val finalText = results.filterIsInstance<TranscriptResult.Final>()
                    .lastOrNull()?.text
                    ?: throw AssertionError("Pipeline did not emit a Final for ${fixture.name} (seed=$seed)")
                val emitted = normWords(finalText)
                val reference = normWords(fixture.reference)
                val subs = substitutionPairs(emitted, reference)
                if (subs.isEmpty()) continue

                for ((wrong, right) in subs) {
                    println("[RECALL] seed=$seed ${fixture.name}: '$wrong' → ref '$right'")
                    if (wrong.length < 2) {
                        // Out of scope for the correction feature (single char / digit).
                        continue
                    }
                    scoreableErrors++
                    val acoustic = repo.getAcousticAlternatives(wrong)
                    if (acoustic.isNotEmpty()) acousticEvidenceErrors++
                    // Context: up to 2 emitted words before the error (what the user sees).
                    val idx = emitted.indexOf(wrong)
                    val context = if (idx >= 0) emitted.subList((idx - 2).coerceAtLeast(0), idx) else emptyList()
                    val candidates = corrector!!.correct(wrong, context, acoustic)
                    val hit = right in candidates
                    if (hit) hits++
                    // Dictionary-only baseline (the pre-overhaul pipeline: no acoustic
                    // evidence, phonetic + edit-distance candidates with the fixed prior).
                    val baseline = corrector.correct(wrong, context, emptyList())
                    if (right in baseline) baselineHits++
                    println(
                        "  acoustic=${acoustic.take(5).map { it.word }} rescored=$candidates hit=$hit baseline=$baseline"
                    )
                }
            }
        }

        val recall = if (scoreableErrors > 0) hits.toDouble() / scoreableErrors else 0.0
        val baselineRecall = if (scoreableErrors > 0) baselineHits.toDouble() / scoreableErrors else 0.0
        println(("[RECALL] SUMMARY snr=${SNR_DB}dB seeds=$seeds scoreableErrors=$scoreableErrors " +
            "withAcousticEvidence=$acousticEvidenceErrors hits=$hits recall@5=%.3f " +
            "baselineHits=$baselineHits baselineRecall@5=%.3f").format(recall, baselineRecall))

        // The harness must actually have found multi-character mis-hearings to measure —
        // with the fixed seeds this is deterministic for the current model.
        Assume.assumeTrue(
            "No multi-character ASR errors found (nothing to measure)",
            scoreableErrors > 0,
        )
        // Regression floor: the true word must be recovered for a solid share of the
        // error words it is scored on. (Baseline at first measurement: see SUMMARY line.)
        org.assertj.core.api.Assertions.assertThat(recall)
            .describedAs("recall@5 over $scoreableErrors multi-character error words")
            .isGreaterThanOrEqualTo(0.5)
        // The acoustic pipeline must not do worse than the dictionary-only baseline.
        org.assertj.core.api.Assertions.assertThat(hits)
            .describedAs("acoustic+LM hits vs dictionary-only baseline hits")
            .isGreaterThanOrEqualTo(baselineHits)
    }
}
