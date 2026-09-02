package dev.brgr.outspoke.inference

/**
 * Słownik nazw ASZ — korekta po transkrypcji.
 *
 * Model ONNX nie przyjmuje podpowiedzi, więc jedyną gwarantowaną drogą jest przebieg
 * korekty na gotowym tekście: całe słowa, bez rozróżniania wielkości liter, najdłuższe
 * dopasowanie pierwsze. Czysty Kotlin, bez Androida — testowalny na JVM.
 *
 * Format reguł (jedna na linię, `#` zaczyna komentarz):
 *
 *   Claude Code                       fraza kanoniczna — łapie „claude code", „ClaudeCode",
 *                                     „claude-code" i zapisuje dokładnie tak, jak w regule
 *   klot kot | clot code => Claude Code   para korekty — każde źródło (po `|`) zamienia na cel
 *
 * Granica słowa to dowolny znak, który nie jest literą ani cyfrą (działa z polskimi znakami),
 * więc reguła „Claude" nie ruszy „Claude'a" ani „Anthropicem" nie zamieni na pół.
 */
class UserDictionary private constructor(
    private val rules: List<Rule>,
    val errors: List<String>,
) {
    private class Rule(val source: String, val regex: Regex, val target: String)

    /** Liczba aktywnych reguł (jedna para „a | b => c" liczy się jako dwie). */
    val size: Int get() = rules.size

    /** Zwraca tekst po korekcie. Gdy nic nie pasuje, zwraca ten sam obiekt. */
    fun apply(text: String): String {
        if (rules.isEmpty() || text.isEmpty()) return text
        var out = text
        for (rule in rules) {
            if (!rule.regex.containsMatchIn(out)) continue
            out = rule.regex.replace(out) { m -> if (m.value == rule.target) m.value else rule.target }
        }
        return out
    }

    companion object {
        val EMPTY = UserDictionary(emptyList(), emptyList())

        private val SEPARATOR = Regex("\\s*(=>|->|=)\\s*")
        private val PARTS = Regex("[\\s\\-]+")

        fun parse(text: String): UserDictionary {
            val rules = mutableListOf<Rule>()
            val errors = mutableListOf<String>()
            // Notatka z vaulta zaczyna się od frontmatteru YAML między liniami „---" — pomijamy go.
            val lines = text.lines()
            val body = if (lines.firstOrNull()?.trim() == "---") {
                val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
                if (end >= 0) lines.drop(end + 2) else lines
            } else lines
            body.forEachIndexed { index, rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val sep = SEPARATOR.find(line)
                if (sep == null) {
                    // Fraza kanoniczna: źródło i cel to to samo.
                    rules += buildRule(line, line) ?: run { errors += "linia ${index + 1}: pusta"; return@forEachIndexed }
                } else {
                    val sources = line.substring(0, sep.range.first).split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    val target = line.substring(sep.range.last + 1).trim()
                    if (sources.isEmpty() || target.isEmpty()) {
                        errors += "linia ${index + 1}: brak źródła albo celu"
                        return@forEachIndexed
                    }
                    for (s in sources) buildRule(s, target)?.let { rules += it }
                }
            }
            // Najdłuższe źródło pierwsze, żeby „Claude Code" wygrało z „Claude".
            rules.sortByDescending { it.source.replace(PARTS, "").length }
            return UserDictionary(rules, errors)
        }

        private fun buildRule(source: String, target: String): Rule? {
            val parts = source.split(PARTS).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val body = parts.joinToString("[\\s\\-]*") { Regex.escape(it) }
            val pattern = "(?<![\\p{L}\\p{N}])$body(?![\\p{L}\\p{N}])"
            return Rule(source, Regex(pattern, RegexOption.IGNORE_CASE), target)
        }

        /** Reguły startowe: słownik z serwera transkrypcji ASZ plus przekręcenia z testów 02.09.2026. */
        const val DEFAULT_RULES: String = """# Słownik nazw ASZ — jedna reguła na linię
# Fraza sama w sobie = pisownia kanoniczna (łapie wielkość liter, sklejenia, myślniki)
# „źródło | źródło => cel" = para korekty

# --- przekręcenia, które model robi po polsku
klot | clot | klod | klaud | claud | clode | klode | kloud => Claude
klot kot | clot code | klod kod | klaud kod | claud code | cloud code | clode code | klode kod | kod klot | clotkoad | clot koad | klotkoad | clotcode | clot kod => Claude Code
klot kowork | clot cowork | klod kowork | claude kowork => Claude Cowork
kowork | kołork | co work => Cowork
antropic | entropic | antropik | anthropik | antropiq => Anthropic
asz strategy | as strategy | asz strategi | aż strategy | a s z strategy | aszstrategy | asz-strategy => ASZSTRATEGY
cuk ubezpieczenia | cók ubezpieczenia | cuk ubezpieczenie => CUK Ubezpieczenia
pko, bp | pko bp | pekao bp | pe ka o be pe => PKO BP
eurostar | euro stars | eurostarz => Eurostars
horizon europe | horyzont europa => Horizon Europe
obsydian | obsidien | obsydien => Obsidian
sinkting | synk sing | syncing => Syncthing
łispr flow | wisper flow | whisper flow => Wispr Flow
parakit | parakiet => Parakeet
autspołk | outspok => Outspoke
bitvarden | bit warden | bitłarden | bitworden => Bitwarden
voltvarden | volt warden | wolt warden => Vaultwarden
google kalendar | gugle kalendarz | gugiel kalendarz => Google Kalendarz
google task | google taski | gugle taski => Google Tasks
ćwiga => śmiga
terminus | terminius => Termius
claude cote | claud code | klaud kod => Claude Code
claud => Claude
gramatli | gramarli | gramadly | gramerly | gramatly | gramaly => Grammarly
langłicz tul | language tool | lengłidż tul => LanguageTool

# --- firma i ludzie
ASZSTRATEGY
CUK Ubezpieczenia
CUK
ASZ Digital
ASZ Partners
ASZ Digital Health
ASZ CAK
PKO BP
Artur Szuba
Szuba
Krzyś
Bolek
Maksiu
Mariusz
Małgosia
Ania

# --- narzędzia
Claude
Claude Code
Claude Cowork
Cowork
Anthropic
Opus
Sonnet
Haiku
OpenAI
ChatGPT
Gemini
Codex
Perplexity
DeepMind
NotebookLM
Obsidian
Obsidian Sync
Syncthing
Granola
Asana
Gmail
Google
Microsoft
GitHub
YouTube
Hetzner
NetBird
Termius
Termux
tmux
Whisper
Wispr Flow
Voicenotes
GenSpark
Android
Gboard
OnePlus
Omarchy
Outspoke
Parakeet
LLM
API
RAG
MCP
Snapdragon
Grammarly
LanguageTool
Bitwarden
Vaultwarden
Google Tasks
Google Kalendarz
Termius
NetBird

# --- granty i regulacje
HORIZON
SENTINEL
EMERGING
Eurostars
Eurogranty
FENG
FEM
PARP
NCBR
MEWA
KKS
DORA
NIS2
AI Act
RODO
GPAI
"""
    }
}
