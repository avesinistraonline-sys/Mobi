package com.ronco.mobi

import java.util.concurrent.ConcurrentLinkedQueue

/** Estado compartilhado entre a tela, o servico, o modelo do motor e o sintetizador. */
object SoundState {
    // saidas do modelo, lidas pelo sintetizador
    @Volatile var rpm = 1000f      // rpm do SOM
    @Volatile var real = 1000f     // rpm "real" estimado do carro
    @Volatile var thr = 0f
    @Volatile var boost = 0f
    @Volatile var scAmp = 0f
    @Volatile var bov = 0f
    /** Eventos de estouro: [tipo (0 estalo, 1 estouro grave, 2 burble), intensidade] */
    val popEvents = ConcurrentLinkedQueue<FloatArray>()

    // configuracoes da tela
    @Volatile var turbo = true
    @Volatile var sc = false
    @Volatile var pops = true
    @Volatile var volume = 0.7f
    @Volatile var power = 0.5f
    @Volatile var cylinders = 8
    @Volatile var engineOn = true      // false = so o som da aspiracao (turbo/supercharger), sem motor artificial
    @Volatile var relief = 2           // 0 BOV (chiado), 1 flutter (tu-tu-tu), 2 misto
    @Volatile var width = 1f           // largura estereo (0 = mono)
    @Volatile var monoOut = false      // true = mesmo sinal nos dois canais (para carros que tocam so um lado)
    @Volatile var chanTest = 0         // 0 = nada; 1 = inicia o teste de canais (esquerdo, direito, ambos)

    /** Camadas: Graves, Corpo, Escape, Admissao, Mecanico, Rasgo, Estouros, Turbo geral, Turbo assobio, Turbo sopro, Turbo alivio/flutter (1.0 = normal) */
    val mix = floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)

    // cambio configuravel (ate 8 marchas)
    @Volatile var gears = 5
    @Volatile var drive = 0            // 0 economico, 1 equilibrado, 2 corrida, 3 personalizado
    val up = floatArrayOf(10f, 30f, 40f, 50f, 70f, 90f, 110f)     // km/h em que SOBE da marcha i para i+1
    val down = floatArrayOf(5f, 21f, 28f, 35f, 49f, 63f, 77f)       // km/h em que REDUZ da marcha i+1 para i
    @Volatile var topSpeed = 152f                                    // km/h em que a ultima marcha chega ao rpm maximo

    // rpm configuravel (por perfil)
    val peak = floatArrayOf(6000f, 6000f, 6000f, 6000f, 6000f, 6000f, 6000f)  // rpm do som na velocidade de troca de cada marcha
    @Volatile var idleRpm = 1000f       // marcha lenta
    @Volatile var lastStart = 4000f     // rpm do som quando entra a ultima marcha
    @Volatile var rpmCap = 6000f        // rpm do som na velocidade maxima (limitador)
    @Volatile var downRpm = 2500f       // rpm em que reduz (usado no perfil 2)
    @Volatile var profile = 0
    @Volatile var aspiration = 1        // 0 aspirado, 1 turbo, 2 supercharger

    // informacoes para a tela
    @Volatile var running = false
    @Volatile var gpsFix = false
    @Volatile var kmh = 0f
    @Volatile var gear = 1
    @Volatile var testKmh = -1f    // >= 0 ativa o modo teste (ignora o GPS)
}
