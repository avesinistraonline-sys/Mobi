package com.ronco.mobi

import android.content.Context
import android.content.SharedPreferences

/**
 * Perfis e ajustes compartilhados entre a tela do celular (MainActivity) e as telas do Android Auto.
 * Tudo que antes morava dentro do MainActivity: carregar/salvar perfis, modos de conducao, rpm, cambio.
 */
object Profiles {
    private val S = SoundState
    private lateinit var app: Context
    private var loaded = false

    /** "b0..b2" = perfis base, "u:Nome" = perfil salvo por voce. */
    @Volatile var curId = "b0"

    /** Sobe a cada ajuste feito fora do celular (ex.: pelo carro), para a tela do celular se atualizar. */
    @Volatile var version = 0

    val profileNames = listOf("Perfil 1 – rpm máximo", "Perfil 2 – troca por rpm", "Perfil 3 – personalizado")

    /** Garante que o processo tem os ajustes salvos carregados (o carro pode abrir sem o app do celular). */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        app = ctx.applicationContext
        curId = prefs().getString("profile_id", "b0") ?: "b0"
        if (curId.startsWith("u:") && !savedNames().contains(curId.substring(2))) curId = "b0"
        loadProfile(curId)
        loaded = true
    }

    /** Usado pelo MainActivity: registra o contexto e recarrega o perfil atual. */
    fun attach(ctx: Context) {
        app = ctx.applicationContext
        if (!loaded) { ensureLoaded(ctx); return }
        curId = prefs().getString("profile_id", curId) ?: curId
        if (curId.startsWith("u:") && !savedNames().contains(curId.substring(2))) curId = "b0"
        loadProfile(curId)
    }

    fun prefs(): SharedPreferences = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun prefix(id: String) = if (id.startsWith("b")) "p${id.substring(1)}" else "u_${id.substring(2)}"
    fun savedNames(): List<String> =
        (prefs().getString("names", "") ?: "").split("\n").filter { it.isNotBlank() }
    fun setSavedNames(l: List<String>) { prefs().edit().putString("names", l.joinToString("\n")).apply() }
    fun modeOf(id: String) =
        if (id.startsWith("b")) id.substring(1).toIntOrNull() ?: 0 else prefs().getInt("${prefix(id)}_mode", 0)
    fun idLabel(id: String): String =
        if (id.startsWith("b")) profileNames[modeOf(id).coerceIn(0, 2)]
        else "★ ${id.substring(2)}  (${listOf("rpm máximo", "troca por rpm", "personalizado")[modeOf(id).coerceIn(0, 2)]})"

    /** Todos os perfis disponiveis (3 base + os seus). */
    fun allIds(): List<String> = listOf("b0", "b1", "b2") + savedNames().map { "u:$it" }

    // ---------- saida de audio (vale para o aparelho/carro, nao por perfil) ----------

    fun loadOutput() {
        S.monoOut = prefs().getBoolean("out_mono", false)
    }

    fun saveOutput() {
        prefs().edit().putBoolean("out_mono", S.monoOut).apply()
    }

    // ---------- perfis ----------

    fun defaults(slot: Int) {
        S.gears = 5
        applyDrive(0)
        S.peak.fill(6000f); S.idleRpm = 1000f; S.lastStart = 4000f; S.rpmCap = 6000f
        S.topSpeed = 152f; S.downRpm = 2500f
        S.mix.fill(1f); S.cylinders = 8; S.aspiration = 1; S.pops = true; S.engineOn = true; S.relief = 2; S.width = 1f
        S.power = 0.5f; S.volume = 0.7f
        if (slot == 1) {                       // perfil 2: trocas por rpm (4500 rpm)
            for (i in 0 until 7) { S.up[i] *= 0.75f; S.peak[i] = 4500f }
            S.lastStart = 3000f
            applyDownRpm(2500f)
        }
    }

    fun saveProfile(id: String) {
        val k = prefix(id)
        prefs().edit()
            .putInt("${k}_mode", S.profile)
            .putInt("${k}_gears", S.gears).putFloat("${k}_top", S.topSpeed)
            .putString("${k}_up", S.up.joinToString(",")).putString("${k}_down", S.down.joinToString(","))
            .putString("${k}_peak", S.peak.joinToString(",")).putString("${k}_mix", S.mix.joinToString(","))
            .putFloat("${k}_idle", S.idleRpm).putFloat("${k}_last", S.lastStart).putFloat("${k}_cap", S.rpmCap)
            .putFloat("${k}_drpm", S.downRpm).putInt("${k}_cyl", S.cylinders).putInt("${k}_asp", S.aspiration)
            .putInt("${k}_drv", S.drive).putBoolean("${k}_eng", S.engineOn).putInt("${k}_rel", S.relief).putFloat("${k}_wid", S.width)
            .putBoolean("${k}_pops", S.pops).putFloat("${k}_vol", S.volume).putFloat("${k}_pow", S.power)
            .apply()
    }

    fun save() = saveProfile(curId)

    /** Salva e avisa a tela do celular (se aberta) que algo mudou por fora. */
    fun saveFromCar() { save(); version++ }

    private fun loadList(src: String?, dst: FloatArray) {
        src?.split(",")?.forEachIndexed { i, s -> if (i < dst.size) s.toFloatOrNull()?.let { dst[i] = it } }
    }

    fun loadProfile(id: String) {
        val mode = modeOf(id).coerceIn(0, 2)
        S.profile = mode
        defaults(mode)
        val p = prefs()
        val k = prefix(id)
        if (p.contains("${k}_gears")) {
            S.gears = p.getInt("${k}_gears", 5).coerceIn(4, 8)
            S.topSpeed = p.getFloat("${k}_top", 152f)
            loadList(p.getString("${k}_up", null), S.up)
            loadList(p.getString("${k}_down", null), S.down)
            loadList(p.getString("${k}_peak", null), S.peak)
            loadList(p.getString("${k}_mix", null), S.mix)
            S.idleRpm = p.getFloat("${k}_idle", 1000f); S.lastStart = p.getFloat("${k}_last", 4000f)
            S.rpmCap = p.getFloat("${k}_cap", 6000f); S.downRpm = p.getFloat("${k}_drpm", 2500f)
            S.cylinders = p.getInt("${k}_cyl", 8); S.aspiration = p.getInt("${k}_asp", 1)
            S.drive = p.getInt("${k}_drv", 0); S.engineOn = p.getBoolean("${k}_eng", true); S.relief = p.getInt("${k}_rel", 2); S.width = p.getFloat("${k}_wid", 1f)
            S.pops = p.getBoolean("${k}_pops", true); S.volume = p.getFloat("${k}_vol", 0.7f)
            S.power = p.getFloat("${k}_pow", 0.5f)
        }
        S.turbo = S.aspiration == 1; S.sc = S.aspiration == 2
        loadOutput()
    }

    /** Troca de perfil (usado pelo carro): salva o atual, carrega o novo. */
    fun selectProfile(id: String) {
        if (id == curId) return
        saveProfile(curId)
        curId = id
        prefs().edit().putString("profile_id", id).apply()
        S.testKmh = -1f
        loadProfile(id)
        version++
    }

    fun setAspiration(a: Int) { S.aspiration = a; S.turbo = a == 1; S.sc = a == 2 }

    // ---------- cambio e rpm ----------

    /** km/h por 1000 rpm do som na marcha g (1..n), deduzido das velocidades e rpm configurados. */
    fun kOf(g: Int): Float {
        val n = S.gears
        return if (g < n) S.up[g - 1] * 1000f / S.peak[g - 1] else S.up[n - 2] * 1000f / S.lastStart
    }

    /** Modos de conducao: velocidades (km/h) em que cada marcha sobe. */
    fun applyDrive(d: Int) {
        S.drive = d
        val ups = when (d) {
            0 -> floatArrayOf(10f, 30f, 40f, 50f, 70f, 90f, 110f)       // economico
            1 -> floatArrayOf(15f, 30f, 60f, 80f, 100f, 120f, 140f)     // equilibrado
            2 -> floatArrayOf(15f, 40f, 70f, 90f, 110f, 130f, 150f)     // corrida
            else -> return
        }
        ups.copyInto(S.up)
        for (i in 0 until 7) S.down[i] = if (i == 0) 5f else (ups[i] * 0.7f).toInt().toFloat()
    }

    fun applyDownRpm(z: Float) {
        S.downRpm = z
        for (i in 0 until S.gears - 1) S.down[i] = kOf(i + 2) * z / 1000f
    }

    /** Perfil 1: todas as marchas sobem ate X rpm. */
    fun applyMaxRpm(x: Float) { S.peak.fill(x); S.rpmCap = x; S.lastStart = x * 2f / 3f }

    /** Perfil 2: troca de marcha em Y rpm (as relacoes de marcha ficam fixas; as velocidades de troca se movem). */
    fun applyShiftRpm(y: Float) {
        val n = S.gears
        val ks = FloatArray(n - 1) { S.up[it] * 1000f / S.peak[it] }
        val kn = S.up[n - 2] * 1000f / S.lastStart
        for (i in 0 until n - 1) { S.peak[i] = y; S.up[i] = ks[i] * y / 1000f }
        S.lastStart = S.up[n - 2] * 1000f / kn
        applyDownRpm(S.downRpm)
    }
}
