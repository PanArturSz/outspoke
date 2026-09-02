package dev.brgr.outspoke.inference

/**
 * Liczebniki po polsku → cyfry. Czysty Kotlin, testowalny na JVM.
 *
 * Zasady:
 *  - ciąg liczebników głównych („pięćset dziewięćdziesiąt trzy") → „593"; „tysiąc" wchodzi w liczbę
 *    („pięć tysięcy" → „5000"), „milion"/„miliard" zostają słowem („250 milionów");
 *  - pojedyncze słowo o wartości 0–9 zostaje słowem („dwa dni"), od 10 w górę idzie w cyfry;
 *  - data: liczebnik porządkowy w dopełniaczu + miesiąc („dziewiątego września") → „9 września";
 *  - godzina: po „o/do/od/po/przed/około/koło" forma „czternastej [trzydzieści]" → „14:30";
 *    bez minut tylko od dziesiątej w górę, żeby „po pierwszej rozmowie" zostało w spokoju.
 * Interpunkcja doklejona do słów jest zachowana.
 */
internal object PolishNumbers {

    private val UNITS = mapOf(
        "zero" to 0,
        "jeden" to 1, "jedna" to 1, "jedno" to 1, "jednego" to 1, "jednej" to 1,
        "dwa" to 2, "dwie" to 2, "dwaj" to 2, "dwóch" to 2, "dwu" to 2,
        "trzy" to 3, "trzech" to 3, "trzej" to 3,
        "cztery" to 4, "czterech" to 4, "czterej" to 4,
        "pięć" to 5, "pięciu" to 5,
        "sześć" to 6, "sześciu" to 6,
        "siedem" to 7, "siedmiu" to 7,
        "osiem" to 8, "ośmiu" to 8,
        "dziewięć" to 9, "dziewięciu" to 9,
        "dziesięć" to 10, "dziesięciu" to 10,
        "jedenaście" to 11, "jedenastu" to 11,
        "dwanaście" to 12, "dwunastu" to 12,
        "trzynaście" to 13, "trzynastu" to 13,
        "czternaście" to 14, "czternastu" to 14,
        "piętnaście" to 15, "piętnastu" to 15,
        "szesnaście" to 16, "szesnastu" to 16,
        "siedemnaście" to 17, "siedemnastu" to 17,
        "osiemnaście" to 18, "osiemnastu" to 18,
        "dziewiętnaście" to 19, "dziewiętnastu" to 19,
    )
    private val TENS = mapOf(
        "dwadzieścia" to 20, "dwudziestu" to 20,
        "trzydzieści" to 30, "trzydziestu" to 30,
        "czterdzieści" to 40, "czterdziestu" to 40,
        "pięćdziesiąt" to 50, "pięćdziesięciu" to 50,
        "sześćdziesiąt" to 60, "sześćdziesięciu" to 60,
        "siedemdziesiąt" to 70, "siedemdziesięciu" to 70,
        "osiemdziesiąt" to 80, "osiemdziesięciu" to 80,
        "dziewięćdziesiąt" to 90, "dziewięćdziesięciu" to 90,
    )
    private val HUNDREDS = mapOf(
        "sto" to 100, "stu" to 100,
        "dwieście" to 200, "dwustu" to 200,
        "trzysta" to 300, "trzystu" to 300,
        "czterysta" to 400, "czterystu" to 400,
        "pięćset" to 500, "pięciuset" to 500,
        "sześćset" to 600, "sześciuset" to 600,
        "siedemset" to 700, "siedmiuset" to 700,
        "osiemset" to 800, "ośmiuset" to 800,
        "dziewięćset" to 900, "dziewięciuset" to 900,
    )
    private val THOUSAND = setOf("tysiąc", "tysiące", "tysięcy", "tysiącu")
    private val BIG = setOf(
        "milion", "miliony", "milionów", "milionie",
        "miliard", "miliardy", "miliardów", "miliardzie",
    )

    /** Porządkowe w dopełniaczu (daty): „dziewiątego", „dwudziestego pierwszego". */
    private val ORD_GEN_UNITS = mapOf(
        "pierwszego" to 1, "drugiego" to 2, "trzeciego" to 3, "czwartego" to 4, "piątego" to 5,
        "szóstego" to 6, "siódmego" to 7, "ósmego" to 8, "dziewiątego" to 9, "dziesiątego" to 10,
        "jedenastego" to 11, "dwunastego" to 12, "trzynastego" to 13, "czternastego" to 14,
        "piętnastego" to 15, "szesnastego" to 16, "siedemnastego" to 17, "osiemnastego" to 18,
        "dziewiętnastego" to 19,
    )
    private val ORD_GEN_TENS = mapOf("dwudziestego" to 20, "trzydziestego" to 30)
    private val MONTHS_GEN = setOf(
        "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
        "lipca", "sierpnia", "września", "października", "listopada", "grudnia",
    )

    /** Godziny w miejscowniku: „o czternastej". */
    private val HOUR_UNITS = mapOf(
        "zerowej" to 0, "pierwszej" to 1, "drugiej" to 2, "trzeciej" to 3, "czwartej" to 4,
        "piątej" to 5, "szóstej" to 6, "siódmej" to 7, "ósmej" to 8, "dziewiątej" to 9,
        "dziesiątej" to 10, "jedenastej" to 11, "dwunastej" to 12, "trzynastej" to 13,
        "czternastej" to 14, "piętnastej" to 15, "szesnastej" to 16, "siedemnastej" to 17,
        "osiemnastej" to 18, "dziewiętnastej" to 19,
    )
    private const val HOUR_TWENTY = "dwudziestej"
    private val HOUR_PREPOSITIONS = setOf("o", "do", "od", "po", "przed", "około", "koło")

    private class Tok(val prefix: String, val core: String, val suffix: String) {
        val lower: String = core.lowercase()
        fun with(newCore: String) = prefix + newCore + suffix
        override fun toString() = prefix + core + suffix
    }

    private fun tokenize(text: String): List<Tok> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { w ->
        var start = 0
        var end = w.length
        while (start < end && !w[start].isLetterOrDigit()) start++
        while (end > start && !w[end - 1].isLetterOrDigit()) end--
        Tok(w.substring(0, start), w.substring(start, end), w.substring(end))
    }

    private fun isCardinal(t: Tok) =
        UNITS.containsKey(t.lower) || TENS.containsKey(t.lower) || HUNDREDS.containsKey(t.lower) ||
            THOUSAND.contains(t.lower) || BIG.contains(t.lower)

    /** Wartość ciągu liczebników głównych bez słów „milion"/„miliard"; null gdy ciąg nie jest sensowny. */
    private fun cardinalValue(run: List<Tok>): Long? {
        var total = 0L
        var current = 0L
        var lastUnitRank = Int.MAX_VALUE // 3 = setki, 2 = dziesiątki, 1 = jedności
        for (t in run) {
            val w = t.lower
            when {
                HUNDREDS.containsKey(w) -> { if (lastUnitRank <= 3) return null; current += HUNDREDS.getValue(w); lastUnitRank = 3 }
                TENS.containsKey(w) -> { if (lastUnitRank <= 2) return null; current += TENS.getValue(w); lastUnitRank = 2 }
                UNITS.containsKey(w) -> {
                    val v = UNITS.getValue(w)
                    if (lastUnitRank <= 1) return null
                    if (v >= 10 && lastUnitRank <= 2) return null
                    current += v; lastUnitRank = 1
                }
                THOUSAND.contains(w) -> {
                    if (current == 0L) current = 1
                    total += current * 1000; current = 0; lastUnitRank = Int.MAX_VALUE
                }
                else -> return null
            }
        }
        return total + current
    }

    // ── „10" zamiast „ten" ────────────────────────────────────────────────────
    // Parakeet słyszy polskie „ten" jak angielskie „ten" i od razu pisze cyfrę. Odwracamy to,
    // gdy „10" stoi tam, gdzie po polsku może stać tylko zaimek: bez liczby ani przyimka
    // przed sobą i z rzeczownikiem/przymiotnikiem w mianowniku po sobie.

    /** Słowa, które po liczbie 10 są normalne („10 minut") — wtedy cyfra zostaje. */
    private val COUNTED = setOf(
        "minut", "godzin", "sekund", "dni", "tygodni", "miesięcy", "lat", "osób", "ludzi", "sztuk",
        "razy", "procent", "złotych", "zł", "euro", "dolarów", "tysięcy", "milionów", "miliardów",
        "km", "kilometrów", "metrów", "centymetrów", "milimetrów", "gram", "gramów", "kilogramów", "kilo",
        "ton", "litrów", "mil", "stopni", "stron", "punktów", "firm", "spółek", "spraw", "zmian",
        "tabel", "kolumn", "linii", "wersji", "kroków", "notatek", "kobiet", "mężczyzn", "dzieci",
        "rzeczy", "umów", "decyzji", "wiadomości", "maili", "plików", "zadań", "projektów", "spotkań",
        "rozmów", "pytań", "zdań", "słów", "znaków", "cyfr", "liczb", "urządzeń", "aplikacji",
        "modeli", "agentów", "sesji", "promptów", "tokenów", "sekcji", "akapitów", "slajdów", "pozycji",
        "elementów", "kategorii", "tematów", "zespołów", "pracowników", "klientów", "uczestników",
        "głosów", "miejsc", "egzemplarzy", "mln", "mld", "tys", "proc", "min", "sek", "godz", "szt",
    )
    /** Po tych słowach „10" jest liczbą („o 10", „ponad 10"). */
    private val BEFORE_NUMBER = setOf(
        "o", "do", "od", "po", "przed", "około", "koło", "na", "za", "przez", "ponad", "prawie",
        "jakieś", "z", "ze", "niż", "nad", "pod", "między", "plus", "minus", "razy", "i", "oraz",
    )
    /** Przymiotniki i zaimki, które w mianowniku kończą się na -y/-i i stoją po „ten". */
    private val AFTER_TEN_ADJ = setOf(
        "sam", "nowy", "stary", "pierwszy", "drugi", "trzeci", "ostatni", "cały", "każdy", "jeden",
        "mały", "duży", "wielki", "główny", "konkretny", "dobry", "zły", "inny", "kolejny", "następny",
        "poprzedni", "ważny", "prosty", "trudny", "długi", "krótki", "szybki", "wolny", "gotowy",
        "pełny", "pusty", "własny", "obecny", "dzisiejszy", "wczorajszy", "jutrzejszy", "jakiś",
        "taki", "który", "mój", "twój", "nasz", "wasz", "jego", "jej", "ich", "cudzy", "dowolny",
        "polski", "angielski", "cyfrowy", "głosowy", "tekstowy", "nowszy", "starszy", "lepszy", "gorszy",
    )

    private fun looksLikeNumberWord(t: Tok): Boolean =
        t.core.any { it.isDigit() } || isCardinal(t)

    private fun tenToDemonstrative(toks: List<Tok>): List<Tok> {
        val out = toks.toMutableList()
        for (i in toks.indices) {
            val t = toks[i]
            if (t.core != "10" || t.prefix.isNotEmpty()) continue
            if (t.suffix.isNotEmpty() && t.suffix != ",") continue
            val prev = toks.getOrNull(i - 1)
            if (prev != null && (looksLikeNumberWord(prev) || prev.lower in BEFORE_NUMBER || prev.suffix.isEmpty().not() && prev.lower in BEFORE_NUMBER)) continue
            val next = toks.getOrNull(i + 1) ?: continue
            if (next.prefix.isNotEmpty() || looksLikeNumberWord(next)) continue
            val n = next.lower
            if (n.isEmpty() || !n.all { it.isLetter() }) continue
            val demonstrative = when {
                t.suffix == "," -> n.startsWith("kt")                    // „ten, który"
                n in COUNTED -> false
                n in AFTER_TEN_ADJ -> true
                n.endsWith("ów") || n.endsWith("ek") || n.endsWith("ań") || n.endsWith("eń") -> false
                n.endsWith("y") || n.endsWith("i") || n.endsWith("a") || n.endsWith("e") || n.endsWith("o") || n.endsWith("ę") || n.endsWith("ą") -> false
                else -> true                                              // mianownik męski: „ten przycisk"
            }
            if (demonstrative) {
                val word = if (i == 0 || (prev != null && prev.suffix.endsWith("."))) "Ten" else "ten"
                out[i] = Tok(t.prefix, word, t.suffix)
            }
        }
        return out
    }

    fun normalise(text: String): String {
        if (text.isBlank()) return text
        val toks = tenToDemonstrative(tokenize(text))
        val out = mutableListOf<String>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            val w = t.lower

            // ── godzina: „o czternastej trzydzieści" ─────────────────────────────
            val prev = if (i > 0) toks[i - 1].lower else ""
            if (prev in HOUR_PREPOSITIONS && t.prefix.isEmpty()) {
                var hour: Int? = HOUR_UNITS[w]
                var used = 1
                if (w == HOUR_TWENTY) {
                    hour = 20
                    val nxt = toks.getOrNull(i + 1)
                    if (nxt != null && nxt.prefix.isEmpty() && toks[i].suffix.isEmpty()) {
                        val u = HOUR_UNITS[nxt.lower]
                        if (u != null && u in 1..4) { hour = 20 + u; used = 2 }
                    }
                }
                if (hour != null) {
                    // minuty: ciąg liczebników głównych zaraz po godzinie, wartość 0..59
                    val hourTok = toks[i + used - 1]
                    var mins: Long? = null
                    var minsUsed = 0
                    if (hourTok.suffix.isEmpty()) {
                        var j = i + used
                        val run = mutableListOf<Tok>()
                        while (j < toks.size && isCardinal(toks[j]) && !BIG.contains(toks[j].lower) &&
                            !THOUSAND.contains(toks[j].lower) && toks[j].prefix.isEmpty() && run.size < 2
                        ) {
                            run += toks[j]
                            if (toks[j].suffix.isNotEmpty()) { j++; break }
                            j++
                        }
                        if (run.isNotEmpty()) {
                            val v = cardinalValue(run)
                            if (v != null && v in 0..59) { mins = v; minsUsed = run.size }
                        }
                    }
                    if (mins != null || hour >= 10) {
                        val last = toks[i + used + minsUsed - 1]
                        val hh = hour.toString()
                        val mm = (mins ?: 0).toString().padStart(2, '0')
                        out += t.prefix + "$hh:$mm" + last.suffix
                        i += used + minsUsed
                        continue
                    }
                }
            }

            // ── data: „dziewiątego września", „dwudziestego pierwszego maja" ─────
            if (t.prefix.isEmpty()) {
                var day: Int? = ORD_GEN_UNITS[w]
                var used = 1
                if (ORD_GEN_TENS.containsKey(w)) {
                    day = ORD_GEN_TENS.getValue(w)
                    val nxt = toks.getOrNull(i + 1)
                    if (t.suffix.isEmpty() && nxt != null && nxt.prefix.isEmpty()) {
                        val u = ORD_GEN_UNITS[nxt.lower]
                        if (u != null && u in 1..9) { day = day + u; used = 2 }
                    }
                }
                val month = toks.getOrNull(i + used)
                if (day != null && day in 1..31 && toks[i + used - 1].suffix.isEmpty() &&
                    month != null && month.prefix.isEmpty() && month.lower in MONTHS_GEN
                ) {
                    out += t.prefix + day.toString()
                    i += used
                    continue
                }
            }

            // ── liczebniki główne ────────────────────────────────────────────────
            if (isCardinal(t) && !BIG.contains(w)) {
                val run = mutableListOf<Tok>()
                var j = i
                while (j < toks.size && isCardinal(toks[j]) && !BIG.contains(toks[j].lower) && (j == i || toks[j].prefix.isEmpty())) {
                    run += toks[j]
                    j++
                    if (toks[j - 1].suffix.isNotEmpty()) break
                }
                val value = cardinalValue(run)
                val single = run.size == 1
                val onlyThousand = run.size == 1 && THOUSAND.contains(run[0].lower)
                if (value != null && !onlyThousand && !(single && value < 10)) {
                    out += run.first().prefix + value.toString() + run.last().suffix
                    i = j
                    continue
                }
            }

            out += t.toString()
            i++
        }
        return out.joinToString(" ")
    }
}
