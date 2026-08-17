package com.levaetraz

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.levaetraz.ui.theme.Haptics

class LevaeTrazApp : Application() {

    lateinit var haptics: Haptics
        private set

    override fun onCreate() {
        super.onCreate()
        haptics = Haptics(this)
        criarCanais()
    }

    private fun criarCanais() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CANAL_PROGRESSO,
                getString(R.string.canal_progresso),
                NotificationManager.IMPORTANCE_LOW,   // sem som: é uma barra de progresso
            ).apply {
                description = "Progresso das transferências em andamento"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CANAL_PROGRESSO = "transferencias"
    }
}
