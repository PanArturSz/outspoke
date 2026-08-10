package dev.brgr.outspoke.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [AcousticCandidateCache] — the bounded, thread-safe per-word acoustic
 * alternative store written by the decode thread and read by the IME threads.
 */
class AcousticCandidateCacheTest {

    private val a1 = WordAlternative("weather", -0.5f)
    private val a2 = WordAlternative("whether", -1.2f)

    @Test
    fun `put then get returns the alternatives`() {
        val cache = AcousticCandidateCache()
        cache.put("weather", listOf(a1, a2))
        assertThat(cache.get("weather")).containsExactly(a1, a2)
    }

    @Test
    fun `get is case-insensitive while candidate casing is preserved`() {
        val cache = AcousticCandidateCache()
        cache.put("Weather", listOf(WordAlternative("Wether", -0.5f)))
        assertThat(cache.get("weather")).containsExactly(WordAlternative("Wether", -0.5f))
        assertThat(cache.get("WEATHER")).hasSize(1)
    }

    @Test
    fun `get returns empty list for unknown word`() {
        val cache = AcousticCandidateCache()
        assertThat(cache.get("absent")).isEmpty()
    }

    @Test
    fun `empty put is ignored`() {
        val cache = AcousticCandidateCache()
        cache.put("", listOf(a1))
        cache.put("word", emptyList())
        assertThat(cache.get("")).isEmpty()
        assertThat(cache.get("word")).isEmpty()
    }

    @Test
    fun `re-put replaces the alternatives for the same word`() {
        val cache = AcousticCandidateCache()
        cache.put("word", listOf(a1))
        cache.put("word", listOf(a2))
        assertThat(cache.get("word")).containsExactly(a2)
    }

    @Test
    fun `oldest entries are evicted beyond capacity`() {
        val cache = AcousticCandidateCache(capacity = 3)
        cache.put("w1", listOf(a1))
        cache.put("w2", listOf(a1))
        cache.put("w3", listOf(a1))
        cache.put("w4", listOf(a1))   // evicts w1
        assertThat(cache.get("w1")).isEmpty()
        assertThat(cache.get("w2")).isNotEmpty
        assertThat(cache.get("w3")).isNotEmpty
        assertThat(cache.get("w4")).isNotEmpty
    }

    @Test
    fun `re-put does not grow the map beyond capacity`() {
        val cache = AcousticCandidateCache(capacity = 2)
        cache.put("w1", listOf(a1))
        cache.put("w2", listOf(a1))
        cache.put("w1", listOf(a2))   // replace, not grow
        cache.put("w3", listOf(a1))   // evicts w2
        assertThat(cache.get("w1")).containsExactly(a2)
        assertThat(cache.get("w2")).isEmpty()
        assertThat(cache.get("w3")).isNotEmpty
    }

    @Test
    fun `non-positive capacity is clamped to one`() {
        val cache = AcousticCandidateCache(capacity = 0)
        cache.put("w1", listOf(a1))
        cache.put("w2", listOf(a1))
        assertThat(cache.get("w1")).isEmpty()
        assertThat(cache.get("w2")).isNotEmpty
    }

    @Test
    fun `clear drops all entries`() {
        val cache = AcousticCandidateCache()
        cache.put("w1", listOf(a1))
        cache.clear()
        assertThat(cache.get("w1")).isEmpty()
    }

    @Test
    fun `concurrent puts and gets do not corrupt the cache`() {
        val cache = AcousticCandidateCache(capacity = 50)
        val errors = AtomicReference<Throwable?>(null)
        val latches = CountDownLatch(4)
        val writer = Thread {
            try {
                for (i in 0 until 2_000) {
                    cache.put("word$i", listOf(WordAlternative("alt$i", -1f)))
                }
            } catch (t: Throwable) {
                errors.set(t)
            } finally {
                latches.countDown()
            }
        }
        val readers = (0..2).map { r ->
            Thread {
                try {
                    for (i in 0 until 2_000) {
                        cache.get("word${(i * 7 + r) % 2_000}")
                    }
                } catch (t: Throwable) {
                    errors.set(t)
                } finally {
                    latches.countDown()
                }
            }
        }
        writer.start()
        readers.forEach { it.start() }
        latches.await()
        writer.join()
        readers.forEach { it.join() }
        assertThat(errors.get()).isNull()
        // The most recent writer entries must be intact.
        assertThat(cache.get("word1999")).isNotEmpty
    }
}
