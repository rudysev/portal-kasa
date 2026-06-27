package com.portal.kasa.provider

import kotlin.math.roundToInt

/**
 * Pure spoken-name → plug resolution (Android-free, unit-tested) — the portal-assistant `AppMatch` pattern,
 * applied to Kasa aliases. Strips a leading article, then scores each alias by tiers (exact → prefix →
 * contains → token-overlap → shared-prefix), with a short-query guard and a confidence threshold so a weak
 * match returns **candidates** (speakable aliases) rather than a wrong pick.
 */
object PlugMatch {
    data class Candidate(
        val id: String,
        val alias: String,
    )

    data class Result(
        val pick: Candidate?,
        val candidates: List<String>,
    )

    private val FILLER = setOf("the", "a", "an", "my")
    private const val MIN_QUERY_LEN = 3
    private const val PICK_THRESHOLD = 60 // exact(100)/prefix(80)/contains(60) auto-pick; weaker → candidates
    private const val EXACT_SCORE = 100 // only an exact match resolves a tie; any weaker tie is ambiguous
    private const val MAX_CANDIDATES = 3

    fun best(
        query: String,
        plugs: List<Candidate>,
    ): Result {
        val tokens = tokenize(query).dropWhile { it in FILLER }
        val q = tokens.joinToString("")
        if (q.isEmpty() || plugs.isEmpty()) return Result(null, emptyList())
        val ranked =
            plugs
                .map { it to score(q, tokens, it.alias) }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<Candidate, Int>> { it.second }
                        .thenBy { compact(it.first.alias).length } // tighter (shorter) alias wins at equal score
                        .thenBy { it.first.alias },
                )
        val top = ranked.firstOrNull() ?: return Result(null, emptyList())
        // Auto-pick a confident match. A tie at the top means several plugs match equally well; only an exact
        // match resolves that (two distinct aliases can't both be exact), so any weaker tie — prefix or
        // contains, e.g. "piano" matching three piano lights, or "lamp" matching two lamps — is genuinely
        // ambiguous and offers candidates rather than guessing the shortest.
        val tiedAtTop = ranked.count { it.second == top.second } > 1
        val confident = top.second >= PICK_THRESHOLD && (top.second == EXACT_SCORE || !tiedAtTop)
        return if (confident) {
            Result(top.first, emptyList())
        } else {
            Result(null, ranked.take(MAX_CANDIDATES).map { it.first.alias })
        }
    }

    private fun score(
        q: String,
        qTokens: List<String>,
        alias: String,
    ): Int {
        val a = compact(alias)
        if (q == a) return EXACT_SCORE
        if (q.length < MIN_QUERY_LEN || a.length < MIN_QUERY_LEN) return 0
        if (a.startsWith(q) || q.startsWith(a)) return 80
        if (a.contains(q) || q.contains(a)) return 60
        val j = jaccard(qTokens.toSet(), tokenize(alias).toSet())
        val tokenScore = if (j > 0.0) (j * 40).roundToInt().coerceIn(1, 40) else 0
        val cp = commonPrefixLen(q, a)
        val fuzzy = if (cp >= MIN_QUERY_LEN) cp else 0
        return maxOf(tokenScore, fuzzy)
    }

    private fun tokenize(s: String): List<String> = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun compact(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun commonPrefixLen(
        a: String,
        b: String,
    ): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun jaccard(
        a: Set<String>,
        b: Set<String>,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        return inter / (a.size + b.size - inter)
    }
}
