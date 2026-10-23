package com.ronco.mobi.car

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ronco.mobi.EngineSoundService
import com.ronco.mobi.Profiles
import com.ronco.mobi.SoundState
import kotlin.math.roundToInt

/**
 * Tela principal no carro: velocimetro grande (desenhado pelo [SpeedRenderer]) e 4 botoes:
 * LIGAR/PARAR, Ajustes, Volume - e Volume +.
 */
class SpeedScreen(ctx: CarContext, private val renderer: SpeedRenderer) : Screen(ctx) {
    private val S = SoundState
    private val handler = Handler(Looper.getMainLooper())
    private var shownRunning = false

    private val poll = object : Runnable {
        override fun run() {
            if (S.running != shownRunning) invalidate()      // o botao LIGAR/PARAR precisa mudar de texto
            handler.postDelayed(this, 700)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                renderer.active = true
                invalidate()
                handler.removeCallbacks(poll)
                handler.postDelayed(poll, 700)
            }
            override fun onPause(owner: LifecycleOwner) {
                renderer.active = false
                handler.removeCallbacks(poll)
            }
            override fun onDestroy(owner: LifecycleOwner) { handler.removeCallbacks(poll) }
        })
    }

    private fun bumpVolume(delta: Int) {
        val v = ((S.volume * 100).roundToInt() + delta).coerceIn(0, 100)
        S.volume = v / 100f
        Profiles.saveFromCar()
        renderer.flash("Volume $v%")
    }

    /** Liga/para o motor. Se faltar permissao de localizacao, pede no celular. */
    private fun toggleEngine() {
        if (S.running) { EngineSoundService.stop(carContext); return }
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { carContext.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { startEngineSafely(); return }
        carContext.requestPermissions(missing) { approved, _ ->
            if (approved.contains(Manifest.permission.ACCESS_FINE_LOCATION)) startEngineSafely()
            else CarToast.makeText(carContext, "Sem permissão de localização, o motor não liga.", CarToast.LENGTH_LONG).show()
        }
    }

    private fun startEngineSafely() {
        try {
            EngineSoundService.start(carContext)
        } catch (e: Exception) {
            CarToast.makeText(carContext, "Não consegui ligar. Abra o app Ronco Mobi no celular uma vez.", CarToast.LENGTH_LONG).show()
        }
    }

    override fun onGetTemplate(): Template {
        val running = S.running
        shownRunning = running
        val power = Action.Builder()
            .setTitle(if (running) "PARAR" else "LIGAR")
            .setBackgroundColor(if (running) CarColor.RED else CarColor.GREEN)
            .setFlags(Action.FLAG_PRIMARY)
            .setOnClickListener { toggleEngine() }
            .build()
        val settings = Action.Builder()
            .setTitle("Ajustes")
            .setOnClickListener { screenManager.push(MenuScreen(carContext, "Ajustes de som") { Menus.root() }) }
            .build()
        val volDown = Action.Builder().setTitle("Vol −").setOnClickListener { bumpVolume(-5) }.build()
        val volUp = Action.Builder().setTitle("Vol +").setOnClickListener { bumpVolume(5) }.build()
        val strip = ActionStrip.Builder().addAction(power).addAction(settings).addAction(volDown).addAction(volUp).build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}
