package com.ronco.mobi.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ronco.mobi.EngineSoundService
import com.ronco.mobi.Profiles
import com.ronco.mobi.SoundState
import kotlin.math.max
import kotlin.math.roundToInt

// ============ Entradas dos menus ============

sealed class Entry

/** Ajuste numerico: no celular e um slider; no carro vira uma tela com botoes − e +. */
class NumEntry(
    val title: String, val min: Int, val max: Int, val step: Int, val unit: String,
    val get: () -> Int, val set: (Int) -> Unit
) : Entry() {
    fun fmt(v: Int) = "$v$unit"
}

class ChoiceEntry(val title: String, val options: () -> List<String>, val get: () -> Int, val set: (Int) -> Unit) : Entry()
class SwitchEntry(val title: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : Entry()
class SubEntry(val title: String, val summary: () -> String, val open: (CarContext) -> Screen) : Entry()
class ActionEntry(val title: String, val text: String, val run: (CarContext) -> Unit) : Entry()

private fun bar(v: Int, min: Int, max: Int, n: Int): String {
    val f = (((v - min).toFloat() / max(1, max - min)) * n).roundToInt().coerceIn(0, n)
    return "█".repeat(f) + "░".repeat(n - f)
}

// ============ Definicao dos menus (espelha o app do celular) ============

object Menus {
    private val S = SoundState
    private val cyls = listOf(4, 6, 8, 10, 12)
    private var testSpeed = 60

    private val layerNames = listOf(
        "Graves (subwoofer)", "Corpo do motor", "Escape", "Admissão", "Mecânico / vibração", "Rasgo (agudos)",
        "Estouros", "Turbo / supercharger (geral)", "Turbo: assobio", "Turbo: sopro de ar", "Turbo: alívio e flutter"
    )

    private fun pct(title: String, get: () -> Float, set: (Float) -> Unit, max: Int = 100, step: Int = 5) =
        NumEntry(title, 0, max, step, "%", { (get() * 100f).roundToInt() }, { set(it / 100f) })

    private fun engineSummary() =
        (if (S.cylinders == 4) "4 cilindros" else "V${S.cylinders}") + " · " + listOf("Aspirado", "Turbo", "Supercharger")[S.aspiration.coerceIn(0, 2)]

    fun root(): List<Entry> = listOf(
        pct("Volume geral", { S.volume }, { S.volume = it }),
        SubEntry("Motor", { engineSummary() }) { MenuScreen(it, "Motor") { motor() } },
        SubEntry("Camadas do som", { "Graves, escape, turbo, estouros…" }) { MenuScreen(it, "Camadas do som") { layers() } },
        SubEntry("Estéreo e saída de áudio", { if (S.monoOut) "Mono duplicado" else "Estéreo" }) { MenuScreen(it, "Estéreo e saída") { stereo() } },
        SubEntry("Perfis e câmbio", { Profiles.idLabel(Profiles.curId) }) { MenuScreen(it, "Perfis e câmbio") { gearbox() } }
    )

    private fun motor(): List<Entry> = listOf(
        ChoiceEntry("Tipo de motor", { listOf("4 cilindros", "V6", "V8", "V10", "V12") },
            { cyls.indexOf(S.cylinders).coerceAtLeast(0) }, { S.cylinders = cyls[it] }),
        ChoiceEntry("Aspiração", { listOf("Aspirado", "Turbo (estilo GT-R)", "Supercharger (estilo Hellcat)") },
            { S.aspiration }, { Profiles.setAspiration(it) }),
        pct("Agressividade do motor", { S.power }, { S.power = it }),
        SwitchEntry("Estouros no escape", { S.pops }, { S.pops = it }),
        SwitchEntry("Só o som da aspiração", { !S.engineOn }, { S.engineOn = !it }),
        ChoiceEntry("Alívio ao tirar o pé", { listOf("BOV (chiado)", "Flutter (tu-tu-tu)", "Misto: flutter e depois chiado") },
            { S.relief }, { S.relief = it })
    )

    /** Camadas: 50% = normal (igual ao app do celular). */
    private fun layers(): List<Entry> = layerNames.indices.map { i ->
        NumEntry(layerNames[i], 0, 100, 5, "%", { (S.mix[i] * 50f).toInt().coerceIn(0, 100) }, { S.mix[i] = it / 50f })
    }

    private fun stereo(): List<Entry> = listOf(
        pct("Largura estéreo", { S.width }, { S.width = it }, max = 150, step = 10),
        ChoiceEntry("Saída de áudio", { listOf("Estéreo", "Mono duplicado (mesmo som nos dois lados)") },
            { if (S.monoOut) 1 else 0 }, { S.monoOut = it == 1; Profiles.saveOutput() }),
        ActionEntry("Testar canais", "Bip: esquerdo, depois direito, depois ambos") { ctx ->
            S.chanTest = 1
            if (!S.running) {
                try { EngineSoundService.start(ctx) } catch (_: Exception) { }
            }
        }
    )

    private fun gearbox(): List<Entry> {
        val l = ArrayList<Entry>()
        l += ChoiceEntry("Perfil sonoro", { Profiles.allIds().map { Profiles.idLabel(it) } },
            { Profiles.allIds().indexOf(Profiles.curId).coerceAtLeast(0) }, { Profiles.selectProfile(Profiles.allIds()[it]) })
        l += ChoiceEntry("Modo de condução",
            { listOf("Econômico: 10 / 30 / 40 / 50 km/h", "Equilibrado: 15 / 30 / 60 / 80 km/h", "Corrida: 15 / 40 / 70 / 90 km/h", "Personalizado") },
            { S.drive }, { if (it < 3) Profiles.applyDrive(it) else S.drive = 3 })
        l += ChoiceEntry("Número de marchas", { listOf("4", "5", "6", "7", "8") }, { S.gears - 4 }, { S.gears = it + 4 })
        when (S.profile) {
            0 -> l += NumEntry("Rpm máximo", 3000, 10000, 250, " rpm", { S.rpmCap.toInt() }, { Profiles.applyMaxRpm(it.toFloat()) })
            1 -> {
                l += NumEntry("Trocar de marcha em", 2500, 9000, 250, " rpm", { S.peak[0].toInt() }, { Profiles.applyShiftRpm(it.toFloat()) })
                l += NumEntry("Reduzir marcha em", 1000, 6000, 250, " rpm", { S.downRpm.toInt() }, { Profiles.applyDownRpm(it.toFloat()) })
            }
            else -> {
                l += NumEntry("Marcha lenta", 600, 1800, 50, " rpm", { S.idleRpm.toInt() }, { S.idleRpm = it.toFloat() })
                l += NumEntry("Rpm máximo (limitador)", 3000, 10000, 250, " rpm", { S.rpmCap.toInt() }, { S.rpmCap = it.toFloat() })
            }
        }
        l += NumEntry("Velocidade máxima", 80, 260, 5, " km/h", { S.topSpeed.toInt() }, { S.topSpeed = it.toFloat() })
        l += SwitchEntry("Modo teste (ignora o GPS)", { S.testKmh >= 0f }, { S.testKmh = if (it) testSpeed.toFloat() else -1f })
        l += NumEntry("Velocidade do teste", 0, 200, 5, " km/h", { testSpeed },
            { testSpeed = it; if (S.testKmh >= 0f) S.testKmh = it.toFloat() })
        return l
    }
}

// ============ Telas ============

/** Base: toda tela se atualiza ao voltar a ficar visivel (os valores podem ter mudado numa tela filha). */
abstract class CarScreen(ctx: CarContext) : Screen(ctx) {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { invalidate() }
        })
    }
}

/** Lista com paginacao automatica: o carro limita quantas linhas cabem (em geral 6). */
abstract class PagedScreen(ctx: CarContext, private val title: String) : CarScreen(ctx) {
    protected abstract fun rows(): List<Row>
    private var page = 0

    private fun limit(): Int = try {
        carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    } catch (e: Exception) { 6 }

    override fun onGetTemplate(): Template {
        val all = rows()
        val lim = max(3, limit())
        val list = ItemList.Builder()
        if (all.size <= lim) {
            for (r in all) list.addItem(r)
        } else {
            val per = lim - 1
            val pages = (all.size + per - 1) / per
            page = page.coerceIn(0, pages - 1)
            for (r in all.subList(page * per, minOf(all.size, (page + 1) * per))) list.addItem(r)
            list.addItem(
                Row.Builder().setTitle("Próxima página ›  (${page + 1}/$pages)")
                    .setOnClickListener { page = (page + 1) % pages; invalidate() }.build()
            )
        }
        return ListTemplate.Builder().setSingleList(list.build()).setTitle(title).setHeaderAction(Action.BACK).build()
    }
}

class MenuScreen(ctx: CarContext, title: String, private val entries: () -> List<Entry>) : PagedScreen(ctx, title) {
    override fun rows(): List<Row> = entries().map { e ->
        when (e) {
            is NumEntry -> {
                val v = e.get()
                Row.Builder().setTitle(e.title).addText(e.fmt(v) + "   " + bar(v, e.min, e.max, 12))
                    .setBrowsable(true).setOnClickListener { screenManager.push(StepScreen(carContext, e)) }.build()
            }
            is ChoiceEntry -> Row.Builder().setTitle(e.title).addText(e.options().getOrElse(e.get()) { "" })
                .setBrowsable(true).setOnClickListener { screenManager.push(ChoiceScreen(carContext, e)) }.build()
            is SwitchEntry -> Row.Builder().setTitle(e.title)
                .setToggle(Toggle.Builder { on -> e.set(on); Profiles.saveFromCar(); invalidate() }.setChecked(e.get()).build()).build()
            is SubEntry -> Row.Builder().setTitle(e.title).addText(e.summary())
                .setBrowsable(true).setOnClickListener { screenManager.push(e.open(carContext)) }.build()
            is ActionEntry -> Row.Builder().setTitle(e.title).addText(e.text)
                .setOnClickListener { e.run(carContext) }.build()
        }
    }
}

class ChoiceScreen(ctx: CarContext, private val c: ChoiceEntry) : PagedScreen(ctx, c.title) {
    override fun rows(): List<Row> {
        val cur = c.get()
        return c.options().mapIndexed { i, name ->
            Row.Builder().setTitle((if (i == cur) "●  " else "○  ") + name)
                .setOnClickListener { c.set(i); Profiles.saveFromCar(); screenManager.pop() }.build()
        }
    }
}

/** O "slider" do carro: mostra o valor com uma barra e deixa mudar com − e +. "Passo" aumenta o tamanho do toque. */
class StepScreen(ctx: CarContext, private val n: NumEntry) : CarScreen(ctx) {
    private val mults = intArrayOf(1, 2, 5, 10)
    private var mi = 0

    private fun change(dir: Int) {
        val v = (n.get() + dir * n.step * mults[mi]).coerceIn(n.min, n.max)
        n.set(v)
        Profiles.saveFromCar()
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val v = n.get()
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle(n.fmt(v)).addText(bar(v, n.min, n.max, 20)).build())
            .addRow(Row.Builder().setTitle("Faixa: ${n.fmt(n.min)} a ${n.fmt(n.max)}").addText("Cada toque: ${n.fmt(n.step * mults[mi])}").build())
            .addAction(Action.Builder().setTitle("−").setOnClickListener { change(-1) }.build())
            .addAction(Action.Builder().setTitle("+").setFlags(Action.FLAG_PRIMARY).setOnClickListener { change(1) }.build())
            .build()
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Passo ×${mults[mi]}").setOnClickListener { mi = (mi + 1) % mults.size; invalidate() }.build())
            .build()
        return PaneTemplate.Builder(pane).setTitle(n.title).setHeaderAction(Action.BACK).setActionStrip(strip).build()
    }
}
