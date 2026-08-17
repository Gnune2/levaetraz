package com.levaetraz.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.levaetraz.model.Servidor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Um DataStore corrompido (queda de energia no meio da escrita, por exemplo)
 * lança CorruptionException e derruba o app no boot, sem saída além de limpar
 * os dados pelas configurações do Android. O handler abaixo troca o arquivo
 * ruim por preferências vazias: perde-se o pareamento, mas o app abre e dá pra
 * conectar de novo.
 */
private val Context.dataStore by preferencesDataStore(
    name = "levaetraz",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Guarda o servidor pareado e as preferências locais do app. */
class Prefs(private val context: Context) {

    private object K {
        val HOST = stringPreferencesKey("host")
        val PORTA = intPreferencesKey("porta")
        val TOKEN = stringPreferencesKey("token")
        val TRAVA = booleanPreferencesKey("trava_biometrica")
        val DESTINO = stringPreferencesKey("destino_no_pc")
        val PASTA_CELULAR = stringPreferencesKey("pasta_no_celular")
        val SO_WIFI = booleanPreferencesKey("so_wifi")
        val PULAR_JA_BAIXADOS = booleanPreferencesKey("pular_ja_baixados")
    }

    val servidor: Flow<Servidor?> = context.dataStore.data.map { p ->
        Servidor(
            host = p[K.HOST].orEmpty(),
            porta = p[K.PORTA] ?: 8765,
            token = p[K.TOKEN].orEmpty(),
        ).takeIf { it.configurado }
    }

    suspend fun salvarServidor(s: Servidor) {
        context.dataStore.edit { p ->
            p[K.HOST] = s.host
            p[K.PORTA] = s.porta
            p[K.TOKEN] = s.token
        }
    }

    suspend fun esquecerServidor() {
        context.dataStore.edit { p ->
            p.remove(K.HOST); p.remove(K.PORTA); p.remove(K.TOKEN)
        }
    }

    /** Trava biométrica ao abrir. Ligada por padrão quando o aparelho suporta. */
    val travaBiometrica: Flow<Boolean> = context.dataStore.data.map { p -> p[K.TRAVA] ?: true }

    suspend fun salvarTrava(ativa: Boolean) {
        context.dataStore.edit { p -> p[K.TRAVA] = ativa }
    }

    val locais: Flow<PrefsLocais> = context.dataStore.data.map { p ->
        PrefsLocais(
            destinoNoPc = p[K.DESTINO].orEmpty(),
            pastaNoCelular = p[K.PASTA_CELULAR] ?: "levaetraz",
            soWifi = p[K.SO_WIFI] ?: false,
            pularJaBaixados = p[K.PULAR_JA_BAIXADOS] ?: true,
        )
    }

    suspend fun salvarLocais(l: PrefsLocais) {
        context.dataStore.edit { p ->
            p[K.DESTINO] = l.destinoNoPc
            p[K.PASTA_CELULAR] = l.pastaNoCelular
            p[K.SO_WIFI] = l.soWifi
            p[K.PULAR_JA_BAIXADOS] = l.pularJaBaixados
        }
    }
}

data class PrefsLocais(
    /** Pasta do PC onde os envios caem. Vazio = a pasta padrão do servidor. */
    val destinoNoPc: String = "",
    /** Subpasta de Downloads no celular onde as baixas caem. */
    val pastaNoCelular: String = "levaetraz",
    val soWifi: Boolean = false,
    val pularJaBaixados: Boolean = true,
)
