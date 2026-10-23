package com.ronco.mobi

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Saida ESTEREO.
 * Motor em camadas (centro): graves, corpo, escape, admissao, mecanico, rasgo.
 * Aspiracao em estereo: duas turbinas (uma por canal, levemente desafinadas e com "lag" diferente),
 * sopro de ar com ruido independente em cada lado, "swell" de enchimento, wastegate,
 * alivio (BOV) e flutter com pequena defasagem entre os lados.
 * Estouros: vozes independentes, cada uma com posicao propria no estereo.
 * Com [SoundState.engineOn] desligado, so a aspiracao (e os estouros, se ligados) soa.
 */
class EngineSynth {
    private val S = SoundState
    private val sr = 44100
    private val ts = 4096
    private val tw = (2.0 * PI).toFloat()

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ render() }, "EngineSynth").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(800)
        thread = null
    }

    /**
     * Um ciclo completo do motor. Cada cilindro tem um pulso de escape (ataque rapido, queda exponencial,
     * leve oscilacao), com pequenas irregularidades de tempo e amplitude que variam por tipo de motor
     * (V8 "cross-plane" bem irregular, V12 muito liso, etc.).
     */
    private fun buildTable(cyl: Int): FloatArray {
        val t = FloatArray(ts + 2)
        val slot = ts.toFloat() / cyl
        val irr = when (cyl) { 4 -> .10f; 6 -> .06f; 8 -> .22f; 10 -> .12f; else -> .05f }
        val rr = Random(cyl * 31 + 5)
        val w = (slot * 2.2f).roundToInt()
        for (c in 0 until cyl) {
            val off = (rr.nextFloat() * 2f - 1f) * irr * slot
            val amp = 1f + (rr.nextFloat() * 2f - 1f) * (irr + .06f)
            val st = (c * slot + off).roundToInt()
            for (i in 0 until w) {
                val x = i / slot
                val env = (1f - exp(-x * 14f)) * exp(-x * 4.2f)
                t[((st + i) % ts + ts) % ts] += amp * env * (1f + .55f * cos(tw * 1.9f * x))
            }
        }
        var m = 0f
        for (i in 0 until ts) m += t[i]
        m /= ts
        var pk = 1e-4f
        for (i in 0 until ts) { t[i] -= m; pk = max(pk, abs(t[i])) }
        for (i in 0 until ts) t[i] /= pk
        t[ts] = t[0]; t[ts + 1] = t[1]
        return t
    }

    private fun sv(fc: Float) = 2f * sin(PI.toFloat() * min(fc, sr / 6f) / sr)

    /** Uma turbina: assobio (3 harmonicos + sopro), zumbido do rotor, ar passando e wastegate. */
    private fun turbine(n: Float, p: Float, q: Float, hn: Float, nlow: Float, air: Float, flut: Float,
                        wg: Float, mWh: Float, mRu: Float): Float {
        val dip = 1f - .6f * flut                      // o flutter "rouba" o assobio a cada pulso
        val w1 = sin(tw * p)
        val whine = (w1 * .26f + sin(tw * 2f * p) * .09f + sin(tw * 3f * p) * .04f + hn * w1 * .7f) * dip
        val whir = (sin(tw * q) + .5f * sin(tw * 2f * q) + .25f * sin(tw * 3f * q)) * .13f * dip
        return (whine * mWh * (.5f + .5f * n) + whir) * n +
            (air * .9f * mRu + nlow * .35f * mRu + hn * wg * .6f) * n * n
    }

    private fun render() {
        val frames = 512
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(max(minBuf, frames * 4 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        var tab = buildTable(S.cylinders)
        var tabCyl = S.cylinders
        val out16 = ShortArray(frames * 2)
        val rng = Random(7)

        val cgT = FloatArray(12) { 1f }
        val cgS = FloatArray(12) { 1f }

        val nV = 8
        val vA = FloatArray(nV); val vDec = FloatArray(nV); val vL = FloatArray(nV); val vB = FloatArray(nV)
        val vF = FloatArray(nV); val vN = FloatArray(nV); val vRg = FloatArray(nV); val vRp = FloatArray(nV)
        val vRf = FloatArray(nV); val vRd = FloatArray(nV); val vPan = FloatArray(nV); val vDel = IntArray(nV)
        var nextV = 0

        var rpm = S.rpm; var sthr = 0f; var phase = 0f
        var nl = 0f; var nlR = 0f; var hp = 0f; var hpR = 0f; var br = 0f
        var jit = 0f; var jt = 0f; var jit2 = 0f; var jt2 = 0f; var cn = 0
        var inL = 0f; var inB = 0f; var lpB = 0f
        // formantes FIXOS do escapamento e do bloco: nao acompanham o rpm, por isso o timbre soa "de verdade"
        val fxL = FloatArray(5); val fxB = FloatArray(5)
        val fxC = floatArrayOf(sv(95f), sv(170f), sv(310f), sv(560f), sv(1050f))
        val fxW = floatArrayOf(1f, .9f, .8f, .5f, .3f)
        var gph = 0f; var scw = 0f; var scl = 0f; var sa = 0f; var bs = 0f
        var lf1 = 0f; var lf2 = 0.3f; var lf3 = 0.7f
        // aspiracao em estereo
        var nL = 0f; var nR = 0f; var nPrev = 0f
        var pL = 0f; var pR = 0f; var qL = 0f; var qR = 0f
        var aLl = 0f; var aLb = 0f; var aRl = 0f; var aRb = 0f
        var wgp = 0f; var fa = 0f; var ffl = 0f; var be = 0f; var bo = 0f; var bDel = 0; var tk = 0f; var bph = 0f
        var tPhase = 0; var tCount = 0; var tPh = 0f          // teste de canais: 1 esquerdo, 2 direito, 3 ambos

        while (running) {
            if (S.cylinders != tabCyl) { tab = buildTable(S.cylinders); tabCyl = S.cylinders }
            val cy = tabCyl
            if (S.chanTest == 1) { S.chanTest = 0; tPhase = 1; tCount = 0; tPh = 0f }
            val mono = S.monoOut
            val goal = S.rpm; val thr = S.thr; val vol = S.volume; val real = S.real
            val boost = if (S.turbo) S.boost else 0f
            val sc = S.scAmp; val eng = S.engineOn; val wd = S.width
            val m0 = S.mix[0]; val m1 = S.mix[1]; val m2 = S.mix[2]; val m3 = S.mix[3]
            val m4 = S.mix[4]; val m5 = S.mix[5]; val m6 = S.mix[6]
            val tm = S.mix[7]; val mWh = S.mix[8]; val mRu = S.mix[9]; val mRe = S.mix[10]
            val tMul = if (S.turbo) tm * 2.2f else tm
            val rMul = if (S.turbo) tm * 1.6f else tm

            val b = S.bov
            if (b > 0f) {                                     // alivio: BOV, flutter ou misto
                S.bov = 0f
                when (S.relief) {
                    0 -> { be = b; bDel = 0 }
                    1 -> { fa = b; bDel = 0 }
                    else -> { fa = b; be = b; bDel = (.22f * sr).toInt() }
                }
                tk = b
            }
            while (true) {                                    // novos estouros
                val e = S.popEvents.poll() ?: break
                val i = nextV; nextV = (nextV + 1) % nV
                val r = rng.nextFloat(); val type = e[0].toInt(); val amp = e[1]
                val fc: Float; val dec: Float; val ng: Float; val rf: Float; val rd: Float; val rg: Float
                when (type) {
                    1 -> { fc = 180f + 240f * r; dec = .9994f; ng = 2.2f; rf = 90f + 80f * r; rd = .9997f; rg = 1.0f }
                    0 -> { fc = 700f + 800f * r; dec = .9985f; ng = 1.8f; rf = 200f + 180f * r; rd = .9992f; rg = .5f }
                    else -> { fc = 120f + 140f * r; dec = .9991f; ng = 1.2f; rf = 60f + 50f * r; rd = .9996f; rg = .6f }
                }
                vF[i] = sv(fc); vA[i] = amp; vDec[i] = dec; vN[i] = ng; vRg[i] = rg * amp
                vRf[i] = rf; vRd[i] = rd; vRp[i] = 0f; vL[i] = 0f; vB[i] = 0f
                vPan[i] = (rng.nextFloat() * 2f - 1f) * .8f
                vDel[i] = rng.nextInt(frames)
            }

            lf1 += frames * .37f / sr; if (lf1 >= 1f) lf1 -= 1f
            lf2 += frames * 1.13f / sr; if (lf2 >= 1f) lf2 -= 1f
            lf3 += frames * 2.7f / sr; if (lf3 >= 1f) lf3 -= 1f
            val a1 = sin(tw * lf1); val a2 = sin(tw * lf2); val a3 = sin(tw * lf3)
            val am2 = 1f + .10f * a1 + .07f * a2 + .04f * a3
            val rpmMod = 1f + .004f * a1 + .003f * a2 + .002f * a3
            for (c in 0 until cy) {
                cgT[c] += (rng.nextFloat() * 2f - 1f) * .02f + (1f - cgT[c]) * .01f
                cgT[c] = cgT[c].coerceIn(.8f, 1.15f)
                cgS[c] += (cgT[c] - cgS[c]) * .2f
            }

            val cont = ((real - 1200f) / 800f).coerceIn(0f, 1f)
            val idleW = ((S.idleRpm + 350f - real) / 350f).coerceIn(0f, 1f)
            val toneG = sqrt(8f / cy)
            val fi = sv(330f + min(goal, 7000f) * .055f)
            val rfL = sv(900f + nL * 3200f); val rfR = sv(900f + nR * 3200f)
            val nMean = (nL + nR) * .5f
            val swell = (max(0f, nMean - nPrev) * (sr.toFloat() / frames) * .35f).coerceIn(0f, 1f)   // "whoosh" de enchimento
            nPrev = nMean

            for (i in 0 until frames) {
                rpm += (goal - rpm) * (if (goal > rpm) .0006f else .00025f)
                sthr += (thr - sthr) * .0005f
                val wn = rng.nextFloat() * 2f - 1f
                val wnR = rng.nextFloat() * 2f - 1f
                if ((cn++ and 255) == 0) jt = rng.nextFloat() * 2f - 1f
                if ((cn and 4095) == 0) jt2 = rng.nextFloat() * 2f - 1f
                jit += (jt - jit) * .002f; jit2 += (jt2 - jit2) * .0004f
                phase += (rpm * rpmMod + jit * 10f * (1f + 4f * idleW) + jit2 * 60f * idleW) / 120f / sr
                if (phase >= 1f) phase -= 1f
                val sV = (sthr + .04f * jit + .03f * a2).coerceIn(0f, 1.2f)

                val pf = (phase * cy) % 1f
                val ci = min((phase * cy).toInt(), cy - 1)
                val pos = phase * ts
                val i0 = pos.toInt()
                val fr = pos - i0
                val sp = (tab[i0] + (tab[i0 + 1] - tab[i0]) * fr) * cgS[ci]

                nl += (wn - nl) * .08f; hp += (wn - hp) * .3f; val hn = wn - hp
                nlR += (wnR - nlR) * .08f; hpR += (wnR - hpR) * .3f; val hnR = wnR - hpR
                br = (br + .02f * wn) * .998f

                // ---------- MOTOR (centro) ----------
                var oC = 0f
                if (eng) {
                    val bass = sin(tw * pf) + .55f * sin(tw * 2f * pf) + .25f * sin(tw * 3f * pf)
                    val tone = sin(tw * pf) + .62f * sin(tw * 2f * pf) + .4f * sin(tw * 3f * pf) +
                        .25f * sin(tw * 4f * pf) + .14f * sin(tw * 5f * pf) +
                        .35f * sin(tw * phase) + .18f * sin(tw * 2f * phase)
                    var body = sp * (1f - cont * .5f) + tone * .5f * toneG * (.25f + cont * .75f)
                    body *= 1f + .3f * idleW * jit2
                    body += hn * abs(sp) * .25f * (.4f + .6f * sV)         // chiado da combustao, sincronizado com os pulsos
                    lpB += (body - lpB) * .28f
                    body = body * .35f + lpB * .65f                         // tira o "zumbido" agudo
                    val exIn = body + nl * .3f
                    var exo = 0f
                    for (k in 0 until 5) {
                        fxL[k] += fxC[k] * fxB[k]
                        val h = exIn - fxL[k] - .3f * fxB[k]
                        fxB[k] += fxC[k] * h
                        exo += fxB[k] * fxW[k]
                    }
                    inL += fi * inB; val inH = body * .5f + nl * .9f + hn * .15f * sV - inL - .45f * inB; inB += fi * inH
                    gph += (350f + min(rpm, 7000f) * .30f) / sr; if (gph >= 1f) gph -= 1f
                    val mech = sin(tw * 7f * pf) * .06f + sin(tw * gph) * .025f + br * .9f + hn * .03f
                    val rasp = hn * sV * sV * (.5f + .5f * sin(tw * pf)) * .45f
                    var m = m0 * .55f * bass * (1f + .8f * idleW + .3f * sV) + m1 * .9f * body +
                        m2 * 1.0f * exo * .55f * am2 * (.5f + .7f * sV) +
                        m3 * .7f * inB * (.3f + .7f * sV) * (1f + .2f * jit2) + m4 * .4f * mech + m5 * .5f * rasp
                    m = tanh((1.1f + 2.6f * sV + 1.8f * (bs + sa)) * m * .9f)
                    val rpmN = ((rpm - S.idleRpm) / 5000f).coerceIn(0f, 1f)
                    oC = m * (.70f + .22f * sqrt(rpmN)) * (1f + .35f * sV) * (1f + .25f * (bs + sa))
                }

                // ---------- ASPIRACAO ----------
                nL += (boost - nL) * (if (boost > nL) .00035f else .00004f)     // turbina esquerda
                nR += (boost - nR) * (if (boost > nR) .00026f else .00004f)     // direita: enche um pouco depois
                bs = (nL + nR) * .5f
                if (bDel > 0) bDel--
                ffl += (6f + 12f * fa) / sr; if (ffl >= 1f) ffl -= 1f          // flutter: mais rapido com pressao, desacelera ao esvaziar
                fa *= .99996f
                val fgL = .5f + .5f * sin(tw * ffl); val fgR = .5f + .5f * sin(tw * (ffl + .13f))
                val flL = fgL * fgL * fgL * fa; val flR = fgR * fgR * fgR * fa
                bo += ((if (bDel <= 0) be else 0f) - bo) * .02f
                if (bDel <= 0) be *= .99994f
                tk *= .9993f
                bph += (55f + 30f * tk) / sr; if (bph >= 1f) bph -= 1f

                aLl += rfL * aLb; val aLh = wn - aLl - .6f * aLb; aLb += rfL * aLh      // sopro de ar (passa-faixa por lado)
                aRl += rfR * aRb; val aRh = wnR - aRl - .6f * aRb; aRb += rfR * aRh
                pL += (1300f + 5200f * nL) * (1f - .05f * flL) * (1f + .004f * jit) / sr; if (pL >= 1f) pL -= 1f
                pR += (1300f + 5200f * nR) * 1.017f * (1f - .05f * flR) * (1f + .004f * jit2) / sr; if (pR >= 1f) pR -= 1f
                qL += (240f + 1100f * nL) / sr; if (qL >= 1f) qL -= 1f
                qR += (255f + 1100f * nR) / sr; if (qR >= 1f) qR -= 1f

                var tL = 0f; var tR = 0f
                if (nL > .002f || nR > .002f || fa > .001f || bo > .001f || tk > .001f) {
                    wgp += (9f + 5f * jit) / sr; if (wgp >= 1f) wgp -= 1f
                    val g0 = .5f + .5f * sin(tw * wgp); val g1 = .5f + .5f * sin(tw * (wgp + .3f))
                    val wgL = if (nL > .7f) g0 * g0 * g0 * g0 * (nL - .7f) * 3.3f else 0f
                    val wgR = if (nR > .7f) g1 * g1 * g1 * g1 * (nR - .7f) * 3.3f else 0f
                    val airL = aLb * (1f + 1.5f * swell) + swell * hn * .4f
                    val airR = aRb * (1f + 1.5f * swell) + swell * hnR * .4f
                    tL = turbine(nL, pL, qL, hn, nl, airL, flL, wgL, mWh, mRu) * tMul
                    tR = turbine(nR, pR, qR, hnR, nlR, airR, flR, wgR, mWh, mRu) * tMul
                    val th = sin(tw * bph) * tk * 1.2f
                    tL += ((hn * .9f + aLb * .8f) * flL * 1.3f + (hn * 2.2f + nl * 2.2f + aLb * 1.5f) * bo + th) * rMul * mRe
                    tR += ((hnR * .9f + aRb * .8f) * flR * 1.3f + (hnR * 2.2f + nlR * 2.2f + aRb * 1.5f) * bo + th) * rMul * mRe
                }
                if (sc > .01f || sa > .001f) {                                   // supercharger (estilo Hellcat), centro
                    sa += (sc - sa) * .0005f
                    scw += (650f + min(rpm, 6500f) * .42f) / sr; if (scw >= 1f) scw -= 1f
                    scl += rpm / 18f / sr; if (scl >= 1f) scl -= 1f
                    val lobe = .65f + .35f * sin(tw * scl)
                    val wh = sin(tw * scw) * .22f + sin(tw * 2f * scw) * .15f +
                        sin(tw * 3f * scw) * .1f + sin(tw * 4f * scw) * .06f
                    oC += (wh * lobe + hn * sin(tw * scw) * .3f + hn * .08f + sin(tw * scl) * .15f) * sa * tm
                }

                // ---------- ESTOUROS (cada voz com a sua posicao) ----------
                var ppL = 0f; var ppR = 0f
                for (v in 0 until nV) {
                    if (vDel[v] > 0) { vDel[v]--; continue }
                    if (vA[v] > .003f || vRg[v] > .003f) {
                        val nn = wn * vA[v] * vN[v]
                        vL[v] += vF[v] * vB[v]
                        val h = nn - vL[v] - .5f * vB[v]
                        vB[v] += vF[v] * h
                        vRp[v] += vRf[v] / sr; if (vRp[v] >= 1f) vRp[v] -= 1f
                        val y = (vB[v] + sin(tw * vRp[v]) * vRg[v]) * m6
                        val pan = vPan[v]
                        ppL += y * (if (pan > 0f) 1f - pan else 1f)
                        ppR += y * (if (pan < 0f) 1f + pan else 1f)
                        vA[v] *= vDec[v]; vRg[v] *= vRd[v]
                    }
                }

                // ---------- MISTURA ESTEREO ----------
                val mid = (tL + tR) * .5f; val side = (tL - tR) * .5f
                var oL = oC + mid + wd * side + ppL
                var oR = oC + mid - wd * side + ppR
                if (mono) { val mm = (oL + oR) * .5f; oL = mm; oR = mm }       // mesmo sinal nos dois canais

                if (tPhase > 0) {                                                // teste: bipe 440 Hz esquerdo -> direito -> ambos
                    val tone = sin(tw * tPh) * .5f
                    tPh += 440f / sr; if (tPh >= 1f) tPh -= 1f
                    oL = if (tPhase != 2) tone else 0f
                    oR = if (tPhase != 1) tone else 0f
                    if (++tCount >= sr) { tCount = 0; tPhase = if (tPhase >= 3) 0 else tPhase + 1 }
                }
                out16[2 * i] = (tanh(oL * vol) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                out16[2 * i + 1] = (tanh(oR * vol) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            track.write(out16, 0, frames * 2)
        }
        track.stop()
        track.release()
    }
}
