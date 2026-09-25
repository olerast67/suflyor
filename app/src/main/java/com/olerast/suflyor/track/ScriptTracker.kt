package com.olerast.suflyor.track

import com.olerast.suflyor.script.TextNorm
import com.olerast.suflyor.script.Token
import kotlin.math.abs

/**
 * Follows the reader through a known script using the tail of the speech recognizer's output.
 *
 * Every update aligns the last few recognized words against the whole script (local alignment over words with
 * fuzzy similarity) and keeps only alignments that end on the word just spoken. Each script word carries a weight:
 * long words that occur once in the script prove the position, function words and words repeated all over the
 * script prove little. Then:
 * - following (0–3 words ahead) needs one decent match;
 * - any other move — a retake, a skipped block, starting elsewhere — needs three matched words, enough weight,
 *   a clear lead over every other place in the script, and the same verdict on two updates in a row.
 * Silence or off-script talk leaves the position where it is.
 */
class ScriptTracker(tokens: List<Token>) {
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

    init {
        val stems = words.map { stem(it) }
        val df = stems.groupingBy { it }.eachCount()
        weights = FloatArray(words.size) { j ->
            val base = tokens[spokenIdx[j]].weight
            if (base == 0f) 0f else base * (0.35f + 0.65f / (df[stems[j]] ?: 1))
        }
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
        val hyp = hypothesis.filter { it.isNotEmpty() && it !in TextNorm.FILLERS }.takeLast(TAIL)
        if (hyp.isEmpty() || hyp == lastHyp || size == 0) return noMove("no new words")
        lastHyp = hyp

        val candidates = align(hyp)
        if (candidates.isEmpty()) return noMove("no match")
        val best = candidates.maxBy { net(it) }
        val target = best.end + 1
        val delta = target - position

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
        val delta = c.end + 1 - position
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
        return abs(lineIndex[c.end] - here) <= NEAR_LINES && abs(c.end + 1 - position) <= NEAR_WORDS
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
        val h = Array(m + 1) { FloatArray(w + 1) }
        val strong = Array(m + 1) { IntArray(w + 1) }
        val ev = Array(m + 1) { FloatArray(w + 1) }
        val endsWithMatch = Array(m + 1) { BooleanArray(w + 1) }
        // Runs of skipped script words / extra recognized words on the path. Capping them keeps a path from
        // "leaking" old, correctly read words across a long gap onto a far-away match of a common word.
        val scriptGap = Array(m + 1) { IntArray(w + 1) }
        val hypGap = Array(m + 1) { IntArray(w + 1) }
        val start = Array(m + 1) { IntArray(w + 1) { -1 } }
        val prefixEnd = Array(m + 1) { BooleanArray(w + 1) }
        for (i in 1..m) {
            val word = hyp[i - 1]
            for (j in 1..w) {
                val sj = j - 1
                var weight = weights[sj]
                var sim = if (weight == 0f) 0f else TextNorm.similarity(word, words[sj])
                var isMatch = sim >= MATCH_SIM
                var isStrong = isMatch
                // The recognizer's last word is often still being spoken ("вз" of "взгляни"): a prefix counts, weakly.
                if (!isMatch && i == m && weight > 0f && isSpokenPrefix(word, words[sj])) {
                    isMatch = true
                    isStrong = false
                    sim = PREFIX_SIM
                    weight *= 0.5f
                }
                // A mismatch is a skipped script word and an extra heard word at once, so it extends both gap runs.
                val mismatchAllowed = scriptGap[i - 1][j - 1] < MAX_GAP && hypGap[i - 1][j - 1] < MAX_GAP
                val diag = when {
                    isMatch -> h[i - 1][j - 1] + weight * (1f + sim)
                    mismatchAllowed -> h[i - 1][j - 1] + MISMATCH
                    else -> 0f
                }
                val up = if (hypGap[i - 1][j] < MAX_GAP) h[i - 1][j] - GAP_HYP else 0f
                val left = if (scriptGap[i][j - 1] < MAX_GAP) h[i][j - 1] - GAP_SCRIPT * maxOf(weight, 0.1f) else 0f
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
                    s = strong[i - 1][j - 1] + if (isStrong) 1 else 0
                    e = ev[i - 1][j - 1] + if (isMatch) weight * sim else 0f
                    matched = isMatch
                    pre = isMatch && !isStrong
                    st = if (h[i - 1][j - 1] > 0f) start[i - 1][j - 1] else if (isMatch) sj else -1
                    if (!isMatch) {
                        sg = scriptGap[i - 1][j - 1] + 1
                        hg = hypGap[i - 1][j - 1] + 1
                    }
                }
                if (up > best) {
                    best = up
                    s = strong[i - 1][j]
                    e = ev[i - 1][j]
                    matched = false
                    pre = false
                    st = start[i - 1][j]
                    hg = hypGap[i - 1][j] + 1
                }
                if (left > best) {
                    best = left
                    s = strong[i][j - 1]
                    e = ev[i][j - 1]
                    matched = false
                    pre = false
                    st = start[i][j - 1]
                    hg = 0
                    sg = scriptGap[i][j - 1] + 1
                }
                h[i][j] = best
                strong[i][j] = s
                ev[i][j] = e
                endsWithMatch[i][j] = matched
                scriptGap[i][j] = sg
                hypGap[i][j] = hg
                start[i][j] = st
                prefixEnd[i][j] = pre
            }
        }
        val out = ArrayList<Candidate>()
        for (j in 1..w) {
            var bestScore = 0f
            var bestI = -1
            for (i in maxOf(1, m - 1)..m) {
                if (!endsWithMatch[i][j]) continue
                val s = h[i][j] - if (i == m) 0f else LAST_WORD_PENALTY
                if (s > bestScore) {
                    bestScore = s
                    bestI = i
                }
            }
            if (bestI > 0) {
                out += Candidate(j - 1, bestScore, strong[bestI][j], ev[bestI][j], start[bestI][j], prefixEnd[bestI][j])
            }
        }
        return out
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

        /** Word forms of one Russian word usually share the first five letters. */
        fun stem(w: String): String = if (w.length > 5) w.substring(0, 5) else w
    }
}
