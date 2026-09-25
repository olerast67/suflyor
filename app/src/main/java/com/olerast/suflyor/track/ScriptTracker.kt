package com.olerast.suflyor.track

import com.olerast.suflyor.script.SpeechLang
import com.olerast.suflyor.script.Token
import kotlin.math.abs

/**
 * Follows the reader through a known script using the tail of the speech recognizer's output.
 *
 * Every update aligns the last few recognized words against the whole script (local alignment over words with
 * fuzzy similarity) and keeps only alignments that end on the last or second-to-last word heard (the latter with a
 * penalty). Each script word carries a weight: long words that occur once in the script prove the position,
 * function words and words repeated all over the script prove little. Then:
 * - following (0–3 informative words ahead) needs one decent match;
 * - a nearby line (within [NEAR_LINES] lines), read from its first word, needs two strong words, or one plus the
 *   start of the next;
 * - any other move — a retake further back, a skipped block, starting elsewhere — needs three strong words, enough
 *   evidence and a clear lead over every other place in the script, and the same verdict on the next update unless
 *   the evidence reaches [JUMP_SURE_EVIDENCE].
 * Silence or off-script talk leaves the position where it is.
 *
 * [lang] must be the language the tokens were built for (ScriptModel.lang) and the hypothesis normalized with
 * (SpeechLang.words): it decides fillers, word similarity and forms, and how recognized words are prepared.
 */
class ScriptTracker(tokens: List<Token>, private val lang: SpeechLang = SpeechLang.RU) {
    /** Indices (in the full token list) of words expected to be spoken. */
    private val spokenIdx: IntArray = tokens.indices.filter { tokens[it].spoken }.toIntArray()
    private val words: Array<String> = Array(spokenIdx.size) { tokens[spokenIdx[it]].norm }
    private val weights: FloatArray
    private val tokenCount = tokens.size

    /** Word starts a displayed line / sentence: readers restart and jump to such places. */
    private val anchors = BooleanArray(spokenIdx.size) { tokens[spokenIdx[it]].anchor }

    /** Number of line starts up to and including each word: distance in lines between two positions. */
    private val lineIndex = IntArray(spokenIdx.size).also { li ->
        var n = 0
        for (j in li.indices) {
            if (anchors[j]) n++
            li[j] = n
        }
    }

    /** Number of informative (non-zero weight) words before each position: numbers don't count as distance. */
    private val weightedBefore: IntArray

    /** Distinct script words: similarity is computed once per (heard word, distinct script word). */
    private val vocabId: IntArray
    private val vocab: Array<String>
    private val vocabSet: Set<String>
    private val simCache = HashMap<String, FloatArray>()

    /** The script writes numbers in digits, so numbers said as words are not script words (SpeechLang.prepareHypothesis). */
    private val scriptHasDigits = words.any { it.isNotEmpty() && it[0].isDigit() }
    private val strictGaps = lang.strictGapRuns

    // DP rows, allocated once: only rows i-1 and i are live, plus a copy of row m-1 for candidate extraction.
    private val rowA = Row(words.size + 1)
    private val rowB = Row(words.size + 1)
    private val rowPrevLast = Row(words.size + 1)

    init {
        val stems = words.map { lang.stem(it) }
        val df = stems.groupingBy { it }.eachCount()
        weights = FloatArray(words.size) { j ->
            val base = tokens[spokenIdx[j]].weight
            if (base == 0f) 0f else base * (0.35f + 0.65f / (df[stems[j]] ?: 1))
        }
        weightedBefore = IntArray(words.size + 1).also { wb ->
            for (j in words.indices) wb[j + 1] = wb[j] + if (weights[j] > 0f) 1 else 0
        }
        val ids = HashMap<String, Int>()
        vocabId = IntArray(words.size) { j -> ids.getOrPut(words[j]) { ids.size } }
        vocab = Array(ids.size) { "" }.also { v -> ids.forEach { (word, id) -> v[id] = word } }
        vocabSet = ids.keys
    }

    val size: Int get() = words.size

    /** Next expected word, in spoken-word space (0..size). */
    var position: Int = 0
        private set

    private var pendingTarget = -1
    private var lastHyp: List<String> = emptyList()

    data class Update(
        val moved: Boolean,
        val position: Int,
        /** Index in the full token list of the next word to read (tokens.size at the end). */
        val nextToken: Int,
        val matches: Int,
        val score: Float,
        val reason: String,
    )

    fun nextTokenIndex(): Int = tokenIndexAt(position)

    /** Index in the full token list of the spoken word number [pos] (tokens.size past the end). */
    fun tokenIndexAt(pos: Int): Int = if (pos >= spokenIdx.size) tokenCount else spokenIdx[pos.coerceAtLeast(0)]

    fun reset(toPosition: Int = 0) {
        position = toPosition.coerceIn(0, size)
        pendingTarget = -1
        lastHyp = emptyList()
    }

    /** Manual jump (tap on a word): next expected word becomes the first spoken word at or after [tokenIndex]. */
    fun jumpToToken(tokenIndex: Int) {
        var p = spokenIdx.binarySearch(tokenIndex)
        if (p < 0) p = -p - 1
        reset(p)
    }

    /** @param hypothesis normalized recognized words, oldest first (finalized history + current partial). */
    fun onHypothesis(hypothesis: List<String>): Update {
        val heard = hypothesis.filter { it.isNotEmpty() && it !in lang.fillers }
        val hyp = lang.prepareHypothesis(heard, vocabSet, scriptHasDigits).takeLast(TAIL)
        if (hyp.isEmpty() || hyp == lastHyp || size == 0) return noMove("no new words")
        lastHyp = hyp

        val candidates = align(hyp)
        if (candidates.isEmpty()) return noMove("no match")
        val best = candidates.maxBy { net(it) }
        val target = best.end + 1
        val delta = wordDelta(target)

        if (delta in 0..FOLLOW_MAX) {
            if (best.score < FOLLOW_MIN_SCORE) return noMove("weak follow")
            pendingTarget = -1
            return move(target, best, "follow")
        }

        val rival = candidates.filter { abs(it.end - best.end) > 3 }.maxOfOrNull { net(it) } ?: 0f
        val lead = net(best) - rival

        // A neighbouring line (up or down), started from its first word: two words — or one informative word and the
        // start of the next — are enough. This is the common case of re-reading a line or skipping to the next one.
        if (isNear(best)) {
            val anchored = startsAtAnchor(best)
            val quick = anchored && best.evidence >= NEAR_MIN_EVIDENCE && lead >= NEAR_MARGIN &&
                (best.strong >= 2 || (best.strong >= 1 && best.endsWithPrefix))
            val solid = best.strong >= 3 && best.evidence >= NEAR_MIN_EVIDENCE && lead >= NEAR_MARGIN
            if (quick || solid) {
                pendingTarget = -1
                return move(target, best, if (delta < 0) "back (retake)" else "jump ahead")
            }
        }

        // A far jump: back (retake) or ahead (skipped text). Demand strong, unambiguous, repeated evidence.
        val strong = best.strong >= 3 && best.evidence >= JUMP_MIN_EVIDENCE && lead >= JUMP_MARGIN
        if (!strong) {
            return noMove("weak jump to $target (%d words, evidence %.1f, lead %.1f)".format(best.strong, best.evidence, net(best) - rival))
        }
        val confirmed = pendingTarget >= 0 && target - pendingTarget in 0..2
        pendingTarget = target
        if (!confirmed && best.evidence < JUMP_SURE_EVIDENCE) return noMove("jump to $target pending confirmation")
        pendingTarget = -1
        return move(target, best, if (delta < 0) "back (retake)" else "jump ahead")
    }

    private fun move(target: Int, c: Candidate, reason: String): Update {
        val moved = target != position
        position = target.coerceIn(0, size)
        return Update(moved, position, nextTokenIndex(), c.strong, c.evidence, reason)
    }

    private fun noMove(reason: String) = Update(false, position, nextTokenIndex(), 0, 0f, reason)

    /** Alignment score minus the cost of moving that far from the current position. */
    private fun net(c: Candidate): Float {
        val delta = wordDelta(c.end + 1)
        val cost = when {
            delta in 0..FOLLOW_MAX -> 0f
            isNear(c) && startsAtAnchor(c) -> 0.3f
            delta in -3..-1 -> 0.9f
            delta < 0 -> 1.0f
            delta <= 12 -> 0.6f
            else -> 1.2f
        }
        return c.score - cost
    }

    /** Within a couple of displayed lines of the current position. */
    private fun isNear(c: Candidate): Boolean {
        if (size == 0) return false
        val here = lineIndex[position.coerceIn(0, size - 1)]
        return abs(lineIndex[c.end] - here) <= NEAR_LINES && abs(wordDelta(c.end + 1)) <= NEAR_WORDS
    }

    /** Distance from the current position to [target] in informative words: "12 345 678" is not three words ahead. */
    private fun wordDelta(target: Int): Int {
        val d = weightedBefore[target.coerceIn(0, size)] - weightedBefore[position.coerceIn(0, size)]
        return if (target < position) minOf(d, -1) else d
    }

    private fun startsAtAnchor(c: Candidate): Boolean = c.start >= 0 && anchors[c.start]

    private class Candidate(
        val end: Int,
        val score: Float,
        val strong: Int,
        val evidence: Float,
        /** Script index of the first matched word of the path. */
        val start: Int,
        /** The last word was only partly heard (prefix match). */
        val endsWithPrefix: Boolean,
    )

    /**
     * Local alignment of [hyp] against the whole script. Returns, for every script word that the hypothesis' last
     * (or second to last) word matches, the best path ending there.
     */
    private fun align(hyp: List<String>): List<Candidate> {
        val m = hyp.size
        val w = size
        // Similarity rows per heard word, kept while the word stays in the tail.
        simCache.keys.retainAll(hyp.toSet())
        val sims = Array(m) { i -> simCache.getOrPut(hyp[i]) { FloatArray(vocab.size) { v -> lang.similarity(hyp[i], vocab[v]) } } }
        val lastMayBePrefix = lang.canBePrefix(hyp[m - 1])
        var prev = rowA
        var cur = rowB
        prev.clear()
        rowPrevLast.clear()
        for (i in 1..m) {
            val word = hyp[i - 1]
            val simRow = sims[i - 1]
            cur.clearCell(0)
            for (j in 1..w) {
                val sj = j - 1
                var weight = weights[sj]
                if (weight == 0f) {
                    // Numbers (weight 0) can't be matched; they are transparent, so "в 2025 году" still reads as a run.
                    cur.copyCell(j - 1, j)
                    cur.ends[j] = false
                    cur.prefix[j] = false
                    continue
                }
                var sim = simRow[vocabId[sj]]
                var isMatch = sim >= MATCH_SIM
                var isStrong = isMatch
                // The recognizer's last word is often still being spoken ("вз" of "взгляни"): a prefix counts, weakly.
                if (!isMatch && i == m && lastMayBePrefix && isSpokenPrefix(word, words[sj])) {
                    isMatch = true
                    isStrong = false
                    sim = PREFIX_SIM
                    weight *= 0.5f
                }
                // A mismatch is a skipped script word and an extra heard word at once, so it extends both gap runs.
                val mismatchAllowed = prev.scriptGap[j - 1] < MAX_GAP && prev.hypGap[j - 1] < MAX_GAP
                val diag = when {
                    isMatch -> prev.h[j - 1] + weight * (1f + sim)
                    mismatchAllowed -> prev.h[j - 1] + MISMATCH
                    else -> 0f
                }
                val up = if (prev.hypGap[j] < MAX_GAP) prev.h[j] - GAP_HYP else 0f
                val left = if (cur.scriptGap[j - 1] < MAX_GAP) cur.h[j - 1] - GAP_SCRIPT * maxOf(weight, 0.1f) else 0f
                var best = 0f
                var s = 0
                var e = 0f
                var matched = false
                var sg = 0
                var hg = 0
                var st = -1
                var pre = false
                if (diag > best) {
                    best = diag
                    s = prev.strong[j - 1] + if (isStrong) 1 else 0
                    e = prev.ev[j - 1] + if (isMatch) weight * sim else 0f
                    matched = isMatch
                    pre = isMatch && !isStrong
                    st = if (prev.h[j - 1] > 0f) prev.start[j - 1] else if (isMatch) sj else -1
                    if (!isMatch) {
                        sg = prev.scriptGap[j - 1] + 1
                        hg = prev.hypGap[j - 1] + 1
                    }
                }
                // An extra heard word ends a run of skipped script words and vice versa, unless runs are strict
                // (English): then both runs last until the next match, so a path can't cross "in twenty twenty
                // five" by alternating the two kinds of gap.
                if (up > best) {
                    best = up
                    s = prev.strong[j]
                    e = prev.ev[j]
                    matched = false
                    pre = false
                    st = prev.start[j]
                    sg = if (strictGaps) prev.scriptGap[j] else 0
                    hg = prev.hypGap[j] + 1
                }
                if (left > best) {
                    best = left
                    s = cur.strong[j - 1]
                    e = cur.ev[j - 1]
                    matched = false
                    pre = false
                    st = cur.start[j - 1]
                    hg = if (strictGaps) cur.hypGap[j - 1] else 0
                    sg = cur.scriptGap[j - 1] + 1
                }
                cur.h[j] = best
                cur.strong[j] = s
                cur.ev[j] = e
                cur.ends[j] = matched
                cur.scriptGap[j] = sg
                cur.hypGap[j] = hg
                cur.start[j] = st
                cur.prefix[j] = pre
            }
            if (i == m - 1) rowPrevLast.copyRow(cur)
            val t = prev
            prev = cur
            cur = t
        }
        val last = prev
        val out = ArrayList<Candidate>()
        for (j in 1..w) {
            var bestScore = 0f
            var bestRow: Row? = null
            if (m >= 2 && rowPrevLast.ends[j]) {
                val s = rowPrevLast.h[j] - LAST_WORD_PENALTY
                if (s > bestScore) {
                    bestScore = s
                    bestRow = rowPrevLast
                }
            }
            if (last.ends[j] && last.h[j] > bestScore) {
                bestScore = last.h[j]
                bestRow = last
            }
            if (bestRow != null) {
                out += Candidate(j - 1, bestScore, bestRow.strong[j], bestRow.ev[j], bestRow.start[j], bestRow.prefix[j])
            }
        }
        return out
    }

    /** One DP row: path score and what the best path ending in each cell has collected so far. */
    private class Row(n: Int) {
        val h = FloatArray(n)
        val strong = IntArray(n)
        val ev = FloatArray(n)
        val ends = BooleanArray(n)

        // Runs of skipped script words / extra recognized words on the path. Capping them keeps a path from
        // "leaking" old, correctly read words across a long gap onto a far-away match of a common word.
        val scriptGap = IntArray(n)
        val hypGap = IntArray(n)
        val start = IntArray(n) { -1 }
        val prefix = BooleanArray(n)

        fun clear() {
            h.fill(0f)
            strong.fill(0)
            ev.fill(0f)
            ends.fill(false)
            scriptGap.fill(0)
            hypGap.fill(0)
            start.fill(-1)
            prefix.fill(false)
        }

        /** Column 0 is "nothing matched yet"; every other cell is rewritten on each row. */
        fun clearCell(j: Int) {
            h[j] = 0f
            strong[j] = 0
            ev[j] = 0f
            ends[j] = false
            scriptGap[j] = 0
            hypGap[j] = 0
            start[j] = -1
            prefix[j] = false
        }

        /** Cell [to] takes over the path of cell [from] unchanged. */
        fun copyCell(from: Int, to: Int) {
            h[to] = h[from]
            strong[to] = strong[from]
            ev[to] = ev[from]
            ends[to] = ends[from]
            scriptGap[to] = scriptGap[from]
            hypGap[to] = hypGap[from]
            start[to] = start[from]
            prefix[to] = prefix[from]
        }

        fun copyRow(src: Row) {
            src.h.copyInto(h)
            src.strong.copyInto(strong)
            src.ev.copyInto(ev)
            src.ends.copyInto(ends)
            src.scriptGap.copyInto(scriptGap)
            src.hypGap.copyInto(hypGap)
            src.start.copyInto(start)
            src.prefix.copyInto(prefix)
        }
    }

    private fun isSpokenPrefix(partial: String, word: String): Boolean =
        partial.length >= 2 && word.length > partial.length && word.startsWith(partial)

    companion object {
        const val TAIL = 8
        const val FOLLOW_MAX = 3
        const val FOLLOW_MIN_SCORE = 0.6f
        const val JUMP_MIN_EVIDENCE = 1.4f
        const val JUMP_SURE_EVIDENCE = 3.2f
        const val JUMP_MARGIN = 0.6f

        /** "Near" = within this many displayed lines and words of the current position. */
        const val NEAR_LINES = 2
        const val NEAR_WORDS = 30
        const val NEAR_MIN_EVIDENCE = 0.9f
        const val NEAR_MARGIN = 0.4f
        const val PREFIX_SIM = 0.75f
        const val MATCH_SIM = 0.72f
        const val MISMATCH = -0.7f
        const val GAP_HYP = 0.6f
        const val GAP_SCRIPT = 0.35f
        const val LAST_WORD_PENALTY = 0.4f

        /** At most this many script words skipped (or extra words heard) in a row inside one path. */
        const val MAX_GAP = 2

        /** Word forms of one Russian word usually share the first five letters. Other languages: [SpeechLang.stem]. */
        fun stem(w: String): String = SpeechLang.RU.stem(w)
    }
}
