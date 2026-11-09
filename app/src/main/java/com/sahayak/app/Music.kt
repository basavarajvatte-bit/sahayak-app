package com.sahayak.app

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Khud ka banaya khushnuma background music: chords, bass, ukulele jaisi dhun aur halka dhol.
 * Har video ka title alag dhun deta hai. Kisi gaane ki copy nahi.
 */
object Music {
    private const val BPM = 120.0
    private val CHORDS = listOf(intArrayOf(0, 4, 7), intArrayOf(7, 11, 14), intArrayOf(9, 12, 16), intArrayOf(5, 9, 12))
    private val SCALE = intArrayOf(0, 2, 4, 7, 9, 12, 14, 16)
    private const val ROOT_HZ = 261.63
    private const val LEVEL = 6_500.0

    private fun hz(semi: Int) = ROOT_HZ * 2.0.pow(semi / 12.0)

    fun make(seconds: Double, rate: Int, seed: Int): ShortArray {
        val total = (seconds * rate).toInt()
        val out = DoubleArray(total)
        val beat = 60.0 / BPM
        val rnd = java.util.Random(seed.toLong())
        val tune = IntArray(16) { SCALE[rnd.nextInt(SCALE.size)] }
        val beats = (seconds / beat).toInt() + 1
        for (b in 0 until beats) {
            val start = (b * beat * rate).toInt()
            val chord = CHORDS[(b / 4) % CHORDS.size]
            // bass har beat par
            note(out, start, (beat * 0.9 * rate).toInt(), hz(chord[0] - 24), 0.55, 6.0, rate, 2)
            // halki chord har bar ki shuru mein
            if (b % 4 == 0) chord.forEach { note(out, start, (beat * 4 * rate).toInt(), hz(it - 12), 0.12, 0.8, rate, 1) }
            // dhun: aadhe beat par ukulele jaisi pluck
            for (half in 0..1) {
                if (rnd.nextInt(5) == 0) continue
                val step = tune[(b * 2 + half) % tune.size]
                val pitch = if (half == 0) chord[(b + half) % 3] + 12 else step + 12
                note(out, start + (half * beat / 2 * rate).toInt(), (beat * 0.6 * rate).toInt(), hz(pitch), 0.32, 9.0, rate, 3)
            }
            if (b % 2 == 0) kick(out, start, rate) else clap(out, start, rate, rnd)
        }
        val fade = (rate * 0.6).toInt()
        return ShortArray(total) { i ->
            val edge = minOf(1.0, i.toDouble() / fade, (total - i).toDouble() / fade)
            (out[i] * LEVEL * edge).coerceIn(-32_000.0, 32_000.0).toInt().toShort()
        }
    }

    private fun note(out: DoubleArray, start: Int, length: Int, freq: Double, volume: Double, decay: Double, rate: Int, harmonics: Int) {
        for (i in 0 until length) {
            val at = start + i
            if (at >= out.size) return
            val t = i.toDouble() / rate
            var wave = 0.0
            for (h in 1..harmonics) wave += sin(2 * PI * freq * h * t) / (h * h)
            out[at] += wave * volume * exp(-decay * t) * minOf(1.0, i / (rate * 0.005))
        }
    }

    private fun kick(out: DoubleArray, start: Int, rate: Int) {
        val length = (rate * 0.18).toInt()
        var phase = 0.0
        for (i in 0 until length) {
            val at = start + i
            if (at >= out.size) return
            val t = i.toDouble() / rate
            phase += 2 * PI * (50 + 90 * exp(-30 * t)) / rate
            out[at] += sin(phase) * 0.6 * exp(-14 * t)
        }
    }

    private fun clap(out: DoubleArray, start: Int, rate: Int, rnd: java.util.Random) {
        val length = (rate * 0.08).toInt()
        for (i in 0 until length) {
            val at = start + i
            if (at >= out.size) return
            out[at] += (rnd.nextDouble() * 2 - 1) * 0.18 * exp(-40.0 * i / rate)
        }
    }

    /** Awaaz chal rahi ho to music dheema, warna poora. */
    fun mix(voice: ShortArray, music: ShortArray?, rate: Int): ShortArray {
        if (music == null) return voice
        val window = rate / 10
        var loud = 0
        return ShortArray(voice.size) { i ->
            if (voice[i].toInt() != 0) loud = window else if (loud > 0) loud--
            val duck = if (loud > 0) 0.35 else 1.0
            (voice[i] + music.getOrElse(i) { 0 } * duck).toInt().coerceIn(-32_000, 32_000).toShort()
        }
    }
}
