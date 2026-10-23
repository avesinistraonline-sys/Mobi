package com.ronco.mobi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val S = SoundState
    private lateinit var big: TextView
    private lateinit var detail: TextView
    private lateinit var toggle: Button
    private lateinit var testSwitch: Switch
    private lateinit var testSeek: SeekBar
    private lateinit var testLabel: TextView
    private lateinit var gearBox: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { updateUi(); handler.postDelayed(this, 200) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 15f; setPadding(0, dp(16), 0, dp(4)) }
    private fun title(t: String) = TextView(this).apply { text = t; textSize = 19f; setPadding(0, dp(28), 0, dp(4)) }
    private fun ord(i: Int) = "${i}ª"

    private fun seek(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = value.coerceIn(0, max)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { save() }
        })
    }

    /** Titulo com o valor atual + barra (de [min] a [max]), adicionados ao [parent]. */
    private fun row(parent: LinearLayout, text: (Int) -> String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit) {
        val tv = TextView(this).apply { this.text = text(value); textSize = 15f; setPadding(0, dp(12), 0, dp(2)) }
        parent.addView(tv)
        parent.addView(seek(max - min, value - min) { tv.text = text(it + min); onChange(it + min) })
    }

    private fun spinner(items: List<String>, sel: Int, onSel: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onSel(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    // ================= PERFIS (a logica mora em Profiles.kt, compartilhada com o Android Auto) =================

    private val profileNames get() = Profiles.profileNames
    private var curId: String
        get() = Profiles.curId
        set(v) { Profiles.curId = v }
    private var shownVersion = 0

    private fun defaults(slot: Int) = Profiles.defaults(slot)
    private fun prefs() = Profiles.prefs()
    private fun savedNames() = Profiles.savedNames()
    private fun setSavedNames(l: List<String>) = Profiles.setSavedNames(l)
    private fun prefix(id: String) = Profiles.prefix(id)
    private fun idLabel(id: String) = Profiles.idLabel(id)
    private fun saveProfile(id: String) = Profiles.saveProfile(id)
    private fun save() = Profiles.save()
    private fun loadProfile(id: String) = Profiles.loadProfile(id)
    private fun applyDrive(d: Int) = Profiles.applyDrive(d)
    private fun applyDownRpm(z: Float) = Profiles.applyDownRpm(z)
    private fun applyMaxRpm(x: Float) = Profiles.applyMaxRpm(x)
    private fun applyShiftRpm(y: Float) = Profiles.applyShiftRpm(y)

    private fun askSaveNew() {
        val et = EditText(this).apply { hint = "Nome do perfil"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Salvar o perfil atual como...").setView(et)
            .setPositiveButton("Salvar") { _, _ ->
                val name = et.text.toString().trim().replace("\n", " ").take(40)
                if (name.isNotEmpty()) {
                    val list = savedNames().toMutableList()
                    if (!list.contains(name)) list.add(name)
                    setSavedNames(list)
                    saveProfile(curId)
                    val id = "u:$name"
                    saveProfile(id)
                    curId = id
                    prefs().edit().putString("profile_id", id).apply()
                    recreate()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun confirmDelete() {
        val id = curId
        AlertDialog.Builder(this).setTitle("Excluir \"${id.substring(2)}\"?")
            .setPositiveButton("Excluir") { _, _ ->
                val e = prefs().edit()
                prefs().all.keys.filter { it.startsWith(prefix(id) + "_") }.forEach { e.remove(it) }
                e.apply()
                setSavedNames(savedNames().filter { it != id.substring(2) })
                curId = "b${S.profile}"
                prefs().edit().putString("profile_id", curId).apply()
                loadProfile(curId)
                recreate()
            }.setNegativeButton("Cancelar", null).show()
    }

    // ================= TELA =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Profiles.attach(this)
        shownVersion = Profiles.version

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        root.addView(TextView(this).apply { text = "Ronco Mobi"; textSize = 26f })

        big = TextView(this).apply { textSize = 44f; gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(4)) }
        detail = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        root.addView(big); root.addView(detail)

        toggle = Button(this).apply {
            setOnClickListener {
                if (S.running) stopService(Intent(this@MainActivity, EngineSoundService::class.java))
                else startEngine()
            }
        }
        root.addView(toggle)

        root.addView(title("Perfil sonoro"))
        val ids = listOf("b0", "b1", "b2") + savedNames().map { "u:$it" }
        root.addView(spinner(ids.map { idLabel(it) }, ids.indexOf(curId).coerceAtLeast(0)) { pos ->
            val id = ids[pos]
            if (id != curId) {
                saveProfile(curId)
                curId = id
                prefs().edit().putString("profile_id", id).apply()
                S.testKmh = -1f
                loadProfile(id)
                recreate()
            }
        })
        root.addView(Button(this).apply { text = "Salvar como novo perfil…"; setOnClickListener { askSaveNew() } })
        if (curId.startsWith("u:")) {
            root.addView(Button(this).apply { text = "Excluir este perfil"; setOnClickListener { confirmDelete() } })
        }
        root.addView(Button(this).apply {
            text = "Restaurar padrão deste perfil"
            setOnClickListener { defaults(S.profile); S.turbo = S.aspiration == 1; S.sc = S.aspiration == 2; Profiles.loadOutput(); save(); recreate() }
        })

        row(root, { "Volume geral: $it%" }, 0, 100, (S.volume * 100).toInt()) { S.volume = it / 100f }
        row(root, { "Agressividade do motor: $it%" }, 0, 100, (S.power * 100).toInt()) { S.power = it / 100f }

        root.addView(label("Tipo de motor"))
        val cyls = listOf(4, 6, 8, 10, 12)
        root.addView(spinner(listOf("4 cilindros", "V6", "V8", "V10", "V12"), cyls.indexOf(S.cylinders).coerceAtLeast(0)) {
            S.cylinders = cyls[it]; save()
        })

        root.addView(label("Aspiração"))
        root.addView(spinner(listOf("Aspirado", "Turbo (estilo GT-R)", "Supercharger (estilo Hellcat)"), S.aspiration) {
            S.aspiration = it; S.turbo = it == 1; S.sc = it == 2; save()
        })

        root.addView(Switch(this).apply {
            text = "Estouros no escape  "
            isChecked = S.pops
            setOnCheckedChangeListener { _, c -> S.pops = c; save() }
        })

        root.addView(title("Turbo e estéreo"))
        root.addView(Switch(this).apply {
            text = "Só o som da aspiração (sem motor artificial)  "
            isChecked = !S.engineOn
            setOnCheckedChangeListener { _, c -> S.engineOn = !c; save() }
        })
        root.addView(label("Tipo de alívio ao tirar o pé"))
        root.addView(spinner(listOf("BOV (chiado)", "Flutter (tu-tu-tu)", "Misto: flutter e depois chiado"), S.relief) {
            S.relief = it; save()
        })
        row(root, { "Largura estéreo: $it%" }, 0, 150, (S.width * 100).toInt()) { S.width = it / 100f }

        root.addView(title("Saída de áudio (carro / Android Auto)"))
        root.addView(note("Se no carro o som sair só de um alto-falante, use \"Mono duplicado\" e rode o teste de canais."))
        root.addView(spinner(listOf("Estéreo", "Mono duplicado (mesmo som nos dois lados)"), if (S.monoOut) 1 else 0) {
            S.monoOut = it == 1; Profiles.saveOutput()
        })
        root.addView(Button(this).apply {
            text = "Testar canais (esquerdo, direito, ambos)"
            setOnClickListener { S.chanTest = 1; if (!S.running) startEngine() }
        })

        root.addView(title("Camadas do som"))
        val names = listOf("Graves (subwoofer)", "Corpo do motor", "Escape", "Admissão", "Mecânico / vibração", "Rasgo (agudos)", "Estouros", "Turbo / supercharger (geral)", "Turbo: assobio", "Turbo: sopro de ar", "Turbo: alívio e flutter")
        for (i in names.indices) {
            row(root, { "${names[i]}: $it%" }, 0, 100, (S.mix[i] * 50f).toInt().coerceIn(0, 100)) { S.mix[i] = it / 50f }
        }

        root.addView(title("Câmbio e rpm  (${profileNames[S.profile]})"))
        root.addView(label("Modo de condução (velocidades de troca)"))
        root.addView(spinner(
            listOf("Econômico: 10 / 30 / 40 / 50 km/h", "Equilibrado: 15 / 30 / 60 / 80 km/h",
                "Corrida: 15 / 40 / 70 / 90 km/h", "Personalizado (ajustes manuais)"), S.drive
        ) {
            if (it != S.drive) { if (it < 3) { applyDrive(it); buildGearUi() } else S.drive = 3; save() }
        })
        root.addView(label("Número de marchas"))
        root.addView(spinner(listOf("4", "5", "6", "7", "8"), S.gears - 4) {
            if (it + 4 != S.gears) { S.gears = it + 4; buildGearUi(); save() }
        })
        gearBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(gearBox)
        buildGearUi()

        root.addView(title("Modo teste (sem sair de casa)"))
        testLabel = TextView(this).apply { text = "Velocidade simulada: 0 km/h" }
        testSwitch = Switch(this).apply {
            text = "Ignorar o GPS e usar a velocidade abaixo  "
            setOnCheckedChangeListener { _, _ -> applyTest() }
        }
        testSeek = seek(200, 0) { applyTest() }
        root.addView(testSwitch); root.addView(testLabel); root.addView(testSeek)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(6), 0, dp(2)) }

    private fun buildGearUi() {
        gearBox.removeAllViews()
        val n = S.gears
        when (S.profile) {
            0 -> {   // rpm maximo: todas as marchas sobem ate X
                gearBox.addView(note("Todas as marchas chegam ao rpm abaixo na velocidade em que trocam. Ajuste as velocidades de troca à vontade."))
                row(gearBox, { "Rpm máximo (pico de cada marcha): $it rpm" }, 3000, 10000, S.rpmCap.toInt()) { applyMaxRpm(it.toFloat()) }
                for (i in 0 until n - 1) {
                    row(gearBox, { "${ord(i + 1)} → ${ord(i + 2)}: sobe em $it km/h" }, 0, 200, S.up[i].toInt()) { S.up[i] = it.toFloat(); S.drive = 3 }
                    row(gearBox, { "${ord(i + 2)} → ${ord(i + 1)}: reduz em $it km/h" }, 0, 200, S.down[i].toInt()) { S.down[i] = it.toFloat(); S.drive = 3 }
                }
            }
            1 -> {   // troca por rpm: as velocidades derivam do rpm
                gearBox.addView(note("As trocas acontecem quando o som chega ao rpm escolhido. As velocidades de troca são calculadas a partir das relações de marcha."))
                val sum = note("")
                fun refresh() {
                    sum.text = (0 until n - 1).joinToString("\n") {
                        "${ord(it + 1)} → ${ord(it + 2)}: sobe em ${S.up[it].toInt()} km/h  ·  reduz em ${S.down[it].toInt()} km/h"
                    }
                }
                row(gearBox, { "Trocar de marcha (subir) em: $it rpm" }, 2500, 9000, S.peak[0].toInt()) { applyShiftRpm(it.toFloat()); refresh() }
                row(gearBox, { "Reduzir marcha (freio motor) em: $it rpm" }, 1000, 6000, S.downRpm.toInt()) { applyDownRpm(it.toFloat()); refresh() }
                gearBox.addView(sum); refresh()
            }
            else -> {   // personalizado: tudo ajustavel
                gearBox.addView(note("Tudo é ajustável: velocidade e rpm de cada troca, marcha lenta e rpm da última marcha."))
                row(gearBox, { "Marcha lenta: $it rpm" }, 600, 1800, S.idleRpm.toInt()) { S.idleRpm = it.toFloat() }
                for (i in 0 until n - 1) {
                    row(gearBox, { "${ord(i + 1)} → ${ord(i + 2)}: sobe em $it km/h" }, 0, 200, S.up[i].toInt()) { S.up[i] = it.toFloat(); S.drive = 3 }
                    row(gearBox, { "${ord(i + 1)}: rpm no pico (na troca): $it" }, 2000, 10000, S.peak[i].toInt()) { S.peak[i] = it.toFloat() }
                    row(gearBox, { "${ord(i + 2)} → ${ord(i + 1)}: reduz em $it km/h" }, 0, 200, S.down[i].toInt()) { S.down[i] = it.toFloat(); S.drive = 3 }
                }
                row(gearBox, { "${ord(n)} marcha: rpm ao entrar: $it" }, 2000, 8000, S.lastStart.toInt()) { S.lastStart = it.toFloat() }
                row(gearBox, { "${ord(n)} marcha: rpm máximo (limitador): $it" }, 3000, 10000, S.rpmCap.toInt()) { S.rpmCap = it.toFloat() }
            }
        }
        row(gearBox, { "Velocidade máxima (rpm máximo na ${ord(n)}): $it km/h" }, 80, 260, S.topSpeed.toInt()) { S.topSpeed = it.toFloat() }
    }

    private fun applyTest() {
        val v = testSeek.progress
        testLabel.text = "Velocidade simulada: $v km/h"
        S.testKmh = if (testSwitch.isChecked) v.toFloat() else -1f
    }

    private fun startEngine() {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 1); return }
        startForegroundService(Intent(this, EngineSoundService::class.java))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, EngineSoundService::class.java))
        }
    }

    private fun updateUi() {
        val on = S.running
        toggle.text = if (on) "PARAR" else "INICIAR"
        if (!on) { big.text = "Desligado"; detail.text = ""; return }
        big.text = "${S.kmh.toInt()} km/h"
        val gps = when {
            S.testKmh >= 0f -> "modo teste"
            S.gpsFix -> "GPS ok"
            else -> "aguardando GPS..."
        }
        detail.text = "${S.gear}ª marcha  •  som ${S.rpm.toInt()} rpm  •  $gps"
    }

    override fun onResume() {
        super.onResume()
        // se o carro mudou algum ajuste enquanto esta tela estava aberta, recarrega
        if (Profiles.version != shownVersion) { shownVersion = Profiles.version; recreate(); return }
        handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh); save(); super.onPause() }
}
