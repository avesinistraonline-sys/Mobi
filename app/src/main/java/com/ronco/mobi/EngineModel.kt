package com.ronco.mobi

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Velocidade (GPS) -> marcha, rpm do SOM, acelerador virtual, turbo/supercharger e estouros.
 *
 * Regra do rpm do som: em cada marcha o som chega a 6000 rpm na velocidade em que voce
 * configurou a troca. Na ultima marcha o som vai de 4000 rpm (velocidade da ultima troca)
 * ate 6000 rpm na velocidade maxima configurada.
 */
class EngineModel {
    private val S = SoundState

    private class Ev(val t: Long, val type: Float, val amp: Float)

    var gear = 1; private set
    private var v = 0f
    private var gpsMs = 0f
    private var gAcc = 0f
    private var thr = 0f
    private var lastTick = 0L
    private var lastShift = 0L
    private var shiftUntil = 0L
    private var blipUntil = 0L
    private var wob = 0f
    private var turboP = 0f
    private var scP = 0f
    private var heat = 0f
    private var prevWant = 0f
    private var wasBlip = false
    private val sched = ArrayList<Ev>()

    fun onSpeed(ms: Float) { gpsMs = ms }

    private fun ev(type: Int, amp: Float, t: Long) { sched.add(Ev(t, type.toFloat(), amp.coerceIn(0f, 1f))) }

    private fun liftPops(now: Long, heatNow: Float) {
        val inten = max(0.8f, heatNow)
        var t = now + 50
        ev(1, 0.95f * inten, t)
        for (i in 0 until 4 + Random.nextInt(6)) {
            t += 45L + Random.nextInt(130)
            val type = if (Random.nextFloat() < 0.35f) 1 else 0
            ev(type, max(0.3f, 0.75f - 0.06f * i) * (0.7f + 0.3f * Random.nextFloat()) * inten, t)
        }
        for (i in 0 until 4 + Random.nextInt(6)) {
            t += 25L + Random.nextInt(70)
            ev(2, 0.3f + 0.25f * Random.nextFloat(), t)
        }
    }

    private fun shiftPops(now: Long, upshift: Boolean) {
        if (upshift) {
            ev(1, 0.8f, now + 50)
            ev(0, 0.6f, now + 110 + Random.nextInt(60))
            if (Random.nextFloat() < 0.5f) ev(0, 0.5f, now + 190 + Random.nextInt(80))
        } else {
            var t = now + 90
            for (i in 0 until 3 + Random.nextInt(3)) {
                ev(if (Random.nextFloat() < 0.4f) 1 else 0, 0.85f - 0.1f * i, t)
                t += 50L + Random.nextInt(90)
            }
        }
    }

    fun tick(now: Long) {
        val dt = if (lastTick == 0L) 0.05f else ((now - lastTick) / 1000f).coerceIn(0.01f, 0.2f)
        lastTick = now
        val n = S.gears.coerceIn(4, 8)

        // velocidade suavizada e aceleracao estimada
        val v0 = v
        v += (gpsMs - v) * (1f - exp(-dt * 2.5f))
        gAcc += ((v - v0) / dt - gAcc) * min(1f, dt * 1.5f)
        val k2 = v * 3.6f

        // acelerador virtual
        wob += (Random.nextFloat() * 2f - 1f - wob) * min(1f, dt * 1.2f)
        val want = when {
            k2 < 1.5f -> 0f
            gAcc > 0.3f -> min(1f, 0.3f + gAcc / 2.5f)
            gAcc < -0.6f -> 0f
            else -> 0.25f
        }
        thr += (want - thr) * (1f - exp(-dt * 5f))
        val lift = prevWant > 0.4f && want < 0.15f
        prevWant = want
        val blip = now < blipUntil
        val thrEff = if (blip) max(thr, 0.7f) else thr

        // marchas (limites configuraveis)
        if (gear > n) gear = n
        if (k2 < 3f) {
            gear = 1
        } else if (now - lastShift > 500) {
            if (gear < n && thr >= 0.08f && k2 > S.up[gear - 1]) {
                gear++; lastShift = now; shiftUntil = now + 250
                turboP = if (thr > 0.3f) 1f else max(turboP, 0.7f)      // turbina cheia no momento da troca
                if (S.turbo) S.bov = turboP
                if (S.sc) S.bov = 0.6f
                if (S.pops && thr > 0.35f) shiftPops(now, true)
            } else if (gear > 1 && k2 < S.down[gear - 2]) {
                gear--; lastShift = now; blipUntil = now + 320
                if (S.pops) shiftPops(now, false)
            }
        }

        // rpm do som
        val idle = S.idleRpm + 15f * sin(now / 170f)
        val lim = max(S.rpmCap, S.peak.maxOrNull() ?: 6000f)
        var base = when {
            k2 < 1.5f -> idle
            gear < n -> max(k2 / S.up[gear - 1] * S.peak[gear - 1], if (gear == 1) S.idleRpm + 110f * k2 else S.idleRpm)
            else -> {
                val u = S.up[n - 2]
                S.lastStart + (k2 - u) * (S.rpmCap - S.lastStart) / max(S.topSpeed - u, 20f)
            }
        }
        base = base.coerceIn(idle, lim)
        val real = S.idleRpm + (base - S.idleRpm) * 0.3f
        var tgt = base * (1f + 0.06f * thr)
        if (k2 > 3f) tgt += sin(now / 950f) * 70f + sin(now / 410f + 2f) * 40f + wob * 110f
        if (blip) tgt += 900f
        if (tgt > lim) tgt = if (gear == n && k2 > S.topSpeed) (if ((now / 70) % 2L == 1L) lim else lim * 0.92f) else lim

        // turbo / supercharger (dependem do rpm, nao so do acelerador)
        val prox = if (gear < n && thr >= 0.08f) ((k2 / S.up[gear - 1] - 0.5f) * 2f).coerceIn(0f, 1f) else 0f   // enche na reta final antes da troca
        val tgtBoost = max(((real - 1000f) / 2400f).coerceIn(0f, 1f) * (0.3f + 0.7f * thrEff), prox * thrEff)
        turboP += (tgtBoost - turboP) * (1f - exp(-dt * (if (tgtBoost > turboP) (if (prox > 0.2f) 6f else 3.2f) else 3.5f)))
        scP = if (S.sc) max(((real - 900f) / 2800f).coerceIn(0f, 1f) * (0.35f + 0.65f * thrEff), prox * thrEff) else 0f

        if (S.turbo && lift && turboP > 0.4f) S.bov = turboP
        if (S.sc && lift && scP > 0.4f) S.bov = scP * 0.6f
        if (wasBlip && !blip) {
            if (S.turbo && turboP > 0.25f) S.bov = turboP * 0.8f
            else if (S.sc && scP > 0.25f) S.bov = scP * 0.5f
        }
        wasBlip = blip

        // estouros: ao tirar o pe, trocas, reducoes e "burble" na desaceleracao
        if (S.pops && lift && heat > 0.3f && real > 1500f) liftPops(now, heat)
        heat = max(heat * (1f - dt * 0.2f), thr)
        if (S.pops && k2 > 3f && thr < 0.1f && real > 1400f && heat > 0.1f && Random.nextFloat() < dt * 16f * heat)
            ev(if (Random.nextFloat() < 0.2f) 0 else 2, 0.3f + 0.3f * Random.nextFloat(), now)
        if (!S.pops) sched.clear()
        var i = 0
        while (i < sched.size) {
            val e = sched[i]
            if (e.t <= now) { S.popEvents.add(floatArrayOf(e.type, e.amp)); sched.removeAt(i) } else i++
        }

        // saidas
        S.rpm = max(idle, tgt)
        S.real = real
        S.thr = if (now < shiftUntil) 0f else min(1f, thrEff * (1f + 0.5f * S.power) + (if (k2 > 3f) 0.15f * S.power else 0f))
        S.boost = if (S.turbo) turboP else 0f
        S.scAmp = if (S.sc) 0.4f + 0.6f * scP else 0f
        S.kmh = k2
        S.gear = gear
    }
}
