package com.levaetraz.ui.theme

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibração como feedback tátil.
 *
 * O app antigo tocava um .wav no PC a cada clique — o que no modo remoto não
 * faz sentido nenhum. No celular o equivalente é o haptic.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private val disponivel: Boolean get() = vibrator?.hasVibrator() == true

    /** Toque leve: seleção de chip, troca de aba. */
    fun tick() = predefinido(VibrationEffect.EFFECT_TICK, 8, 40)

    /** Confirmação: iniciar download, aplicar uma escolha. */
    fun confirm() = predefinido(VibrationEffect.EFFECT_CLICK, 18, 120)

    /** Sucesso: dois toques curtos. */
    fun success() = padrao(longArrayOf(0, 22, 70, 34), intArrayOf(0, 140, 0, 200))

    /** Erro: pulso longo e mais grave. */
    fun error() = padrao(longArrayOf(0, 45, 90, 90), intArrayOf(0, 200, 0, 255))

    private fun predefinido(efeito: Int, fallbackMs: Long, amplitude: Int) {
        if (!disponivel) return
        runCatching {
            val ve = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(efeito)
            } else {
                VibrationEffect.createOneShot(fallbackMs, amplitude)
            }
            vibrator?.vibrate(ve)
        }
    }

    private fun padrao(tempos: LongArray, amplitudes: IntArray) {
        if (!disponivel) return
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(tempos, amplitudes, -1))
        }
    }
}
