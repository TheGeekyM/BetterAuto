package com.simoneluconi.betterauto.voice.wake

import kotlin.math.max
import kotlin.math.min

/**
 * Decides whether a recognizer's text is the wake phrase "hi batata".
 *
 * "batata" is not in the Vosk small English lexicon (a grammar word outside it is dropped), so the recognizer runs
 * with [GRAMMAR]: in-lexicon words that sound alike ("bata", "ta", "tata", "but a ta"). Whatever comes out is
 * folded to a rough phonetic key (vowels merged, p/b and d/t merged) and compared with "batata"; unconstrained
 * text scores the same way ("hi potato", "high but a ta"). The choices below were measured on a laptop evaluation:
 * synthetic takes of the phrase, other speech (English and Arabic) and noise streamed through Vosk as the app does.
 *
 * Pure Kotlin, no Android: unit-tested.
 */
object WakeMatcher {
    /**
     * Vosk grammar: look-alikes of the phrase, and [unk] for everything else. "potato" is left out (no take of the
     * phrase came out as it, ordinary talk did), and so are filler words (they took a sixth of the phrases away for
     * a third of the false wakes).
     */
    const val GRAMMAR = """["hi bata ta", "hey bata ta", "high bata ta", "hi but a ta", "hi tata", "bata", "tata", "ta", "[unk]"]"""

    private val Greetings = setOf("hi", "hey", "high", "hay", "hai", "hii")
    private val Target = key("batata")

    /**
     * 0..1: how much of [text] looks like "hi batata": the best window of up to 3 words right after a greeting,
     * against "batata". Without the greeting it is 0: on the evaluation that cut false wakes 3 to 6 times
     * (Arabic speech is full of "bata ta") and cost almost no detections.
     */
    fun score(text: String): Float {
        val words = text.lowercase().split(Regex("[^a-z\\[\\]]+")).filter { it.isNotEmpty() && it != "[unk]" }
        var best = 0f
        for (i in 1 until words.size) {
            if (words[i - 1] !in Greetings) continue
            for (n in 1..min(3, words.size - i)) {
                val tail = words.subList(i, i + n)
                if (tail.last() in Greetings) break
                best = max(best, similarity(key(tail.joinToString("")), Target))
            }
        }
        return best
    }

    fun matches(text: String, threshold: Float): Boolean = score(text) >= threshold

    /**
     * The sensitivity (0 = strict, 1 = eager) as a score threshold. The default 0.5 gives 0.70: the whole phrase
     * ("hi bata ta", "hi but a ta"). From about 0.6 up (0.66 and below) a lost syllable ("hi tata", "hey bata")
     * wakes it too: on the evaluation that caught 97% of the phrases instead of 67%, but woke 7 times as often on
     * other speech.
     */
    fun threshold(sensitivity: Float): Float = 0.9f - sensitivity.coerceIn(0f, 1f) * 0.4f

    /** A rough phonetic key: letters only, a/e/o/u merged, p→b, d→t, v→b, doubled letters once. */
    internal fun key(s: String): String {
        val out = StringBuilder()
        for (c in s.lowercase()) {
            val k = when (c) {
                'a', 'e', 'o', 'u' -> 'a'
                'y' -> 'i'
                'p', 'v' -> 'b'
                'd' -> 't'
                in 'a'..'z' -> c
                else -> continue
            }
            if (out.isEmpty() || out.last() != k) out.append(k)
        }
        return out.toString()
    }

    /** 1 − edit distance / longer length. */
    internal fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return 1f - prev[b.length].toFloat() / max(a.length, b.length)
    }
}

/**
 * Recognizer results over time → wake or not. A final result counts at once; a partial has to hold over
 * [stable] partials in a row (100 ms each), since partials flicker through look-alikes on ordinary speech.
 */
class WakeDetector(private val stable: Int = 2) {
    private var run = 0

    fun feed(text: String, final: Boolean, threshold: Float): Boolean {
        val hit = text.isNotEmpty() && WakeMatcher.score(text) >= threshold
        if (final) {
            run = 0
            return hit
        }
        run = if (hit) run + 1 else 0
        return run >= stable
    }

    fun reset() {
        run = 0
    }
}
