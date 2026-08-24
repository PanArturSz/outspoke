package dev.brgr.outspoke.inference

import dev.brgr.outspoke.audio.AudioChunk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Integration tests for the acoustic word-alternative capture (word-correction overhaul):
 * top-K token swaps + bounded local beam at decode time, written to the repository's
 * [AcousticCandidateCache].
 *
 * Drives the **real** Parakeet ONNX engine and WAV fixtures on the JVM, exactly like
 * [RealAudioPipelineTest]. Skipped when the model directory is unavailable.
 */
class AcousticCaptureIntegrationTest {

    companion object {
        private val engine: SpeechEngine = ParakeetEngine()
        private var modelAvailable = false

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
        }

        @AfterClass
        @JvmStatic
        fun closeModel() {
            if (modelAvailable) engine.close()
        }
    }

    private fun wavSamples(name: String): ShortArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("audio/$name")
            ?: throw IllegalStateException("WAV fixture '$name' not found on classpath")
        val pcm = WavReader().readPcm16(ByteArrayInputStream(stream.readBytes()))
        val resampled = WavReader().resampleLinear(pcm.samples, pcm.sampleRate, 16_000)
        return if (pcm.channels > 1) {
            ShortArray(resampled.size / 2) { i -> ((resampled[2 * i] + resampled[2 * i + 1]) / 2).toShort() }
        } else resampled
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

    /** Runs the full streaming pipeline on [repo] and returns the final text. */
    private fun transcribe(repo: InferenceRepository, samples: ShortArray): String {
        val results = runBlocking {
            repo.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                .toList()
        }
        return results.filterIsInstance<TranscriptResult.Final>()
            .lastOrNull()?.text
            ?: throw AssertionError("Pipeline did not emit a Final result")
    }

    private fun wordsOf(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    private fun assertValidAlternatives(word: String, alternatives: List<WordAlternative>) {
        assertThat(alternatives)
            .describedAs("alternatives for \"$word\"")
            .isNotEmpty
            .hasSizeLessThanOrEqualTo(5)
        for (alt in alternatives) {
            assertThat(alt.word)
                .describedAs("alternative '$alt.word' for \"$word\"")
                .isNotBlank
                .doesNotContain(" ")
                .hasSizeGreaterThanOrEqualTo(2)
            assertThat(alt.word.any { it.isLetter() })
                .describedAs("alternative '$alt.word' must contain a letter")
                .isTrue
            assertThat(alt.word.lowercase(Locale.ROOT))
                .describedAs("the emitted word itself must not be suggested")
                .isNotEqualTo(word)
            assertThat(alt.acousticLogProb)
                .describedAs("acoustic log-prob for '$alt.word'")
                .isFinite
                .isLessThanOrEqualTo(0f)
        }
    }

    @Test
    fun `streaming decode populates the acoustic cache`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")
        val repo = InferenceRepository(engine)
        val finalText = transcribe(repo, wavSamples("long-weather.wav"))
        val words = wordsOf(finalText)
        assertThat(words).isNotEmpty

        val withAlternatives = words.filter { repo.getAcousticAlternatives(it).isNotEmpty() }
        // At least one word of the sentence must have carried runner-up evidence.
        assertThat(withAlternatives)
            .describedAs(
                "words with cached alternatives (finalText='$finalText'); " +
                    withAlternatives.joinToString { w ->
                        "$w→${repo.getAcousticAlternatives(w).map { it.word }}"
                    }
            )
            .isNotEmpty

        for (word in words) {
            val alts = repo.getAcousticAlternatives(word)
            if (alts.isNotEmpty()) assertValidAlternatives(word, alts)
        }
    }

    @Test
    fun `cache is cleared at the start of a new session`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")
        // One long-lived repository across both sessions (the production shape): the
        // cache is process-local to the repository and cleared at each session start.
        val repo = InferenceRepository(engine)
        val textA = transcribe(repo, wavSamples("long-weather.wav"))
        val wordsA = wordsOf(textA)
        val distinctiveA = wordsA.filter { it.length >= 5 }   // "weather", "forecast", "rain"…
        assertThat(distinctiveA).isNotEmpty

        // A second session on the same repository must start with an empty cache.
        val textB = transcribe(repo, wavSamples("long-package.wav"))

        // None of session A's distinctive words may survive into session B.
        for (word in distinctiveA) {
            assertThat(repo.getAcousticAlternatives(word))
                .describedAs("'$word' from session A must be cleared (textB='$textB')")
                .isNullOrEmpty()
        }
    }

    @Test
    fun `local word beam returns hypotheses including the greedy word`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")
        val parakeet = engine as ParakeetEngine
        val samples = wavSamples("long-weather.wav")

        val (encoderOut, encLen) = parakeet.encodeBuffer(
            FloatArray(samples.size) { samples[it] / 32768f }
        )
        try {
            val decoded = parakeet.decodeChunk(
                encoderOut, encLen, 0, encLen, parakeet.initialTdtState()
            )
            assertThat(decoded.emissions).isNotEmpty

            // Segment the emissions into words (▁ starts a new word) and beam the first
            // word that is at least 2 tokens long (a single-token word has no
            // token-sequence alternatives for the beam to find).
            val segments = ArrayList<List<TokenEmission>>()
            val current = ArrayList<TokenEmission>()
            for (em in decoded.emissions) {
                if (current.isNotEmpty() && parakeet.tokenStartsWord(em.token)) {
                    segments.add(current)
                    current.clear()
                }
                current.add(em)
            }
            if (current.isNotEmpty()) segments.add(current)

            val target = segments.firstOrNull { it.size >= 2 }
                ?: segments.first()
            val greedyWord = parakeet.detokenizeTokens(target.map { it.token })
            val firstFrame = target.first().frame
            val lastFrame = target.last().frame
            val snapshot = decoded.stateSnapshots.firstOrNull { it.frame == firstFrame }
            assertThat(snapshot)
                .describedAs("state snapshot at the word's first frame")
                .isNotNull

            val beamEnd = (lastFrame + 2).coerceAtMost(encLen - 1)
            val alternatives = parakeet.localWordBeam(encoderOut, encLen, firstFrame, beamEnd, snapshot!!)

            assertThat(alternatives)
                .describedAs("beam hypotheses for greedy word '$greedyWord'")
                .isNotEmpty
                .hasSizeLessThanOrEqualTo(ParakeetEngine.MAX_ALTERNATIVES)
            for (alt in alternatives) {
                assertThat(alt.word).isNotBlank
                assertThat(alt.acousticLogProb).isFinite.isLessThanOrEqualTo(0f)
            }
            // The greedy path is one of the beam's paths (argmax is always in top-K), so
            // the greedy word itself must be among the hypotheses.
            assertThat(alternatives.map { it.word.lowercase(Locale.ROOT) })
                .describedAs("beam must include the greedy word '$greedyWord'")
                .contains(greedyWord.lowercase(Locale.ROOT))
        } finally {
            encoderOut.close()
        }
    }
}
