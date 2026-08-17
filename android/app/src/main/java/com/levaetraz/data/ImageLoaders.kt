package com.levaetraz.data

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

/**
 * ImageLoader do Coil que injeta o token nas requisições de miniatura.
 *
 * As rotas `/api/media/thumb` são autenticadas como todo o resto, então o Coil
 * não pode simplesmente buscar a URL — precisa do header. Um interceptor
 * resolve isso sem espalhar o token pela UI.
 */
object ImageLoaders {

    @Volatile
    private var cache: Pair<String, ImageLoader>? = null

    fun para(context: Context, token: String): ImageLoader {
        cache?.let { (tokenAtual, loader) ->
            if (tokenAtual == token) return loader
        }
        val novo = construir(context.applicationContext, token)
        cache = token to novo
        return novo
    }

    private fun construir(context: Context, token: String): ImageLoader {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("X-Auth-Token", token)
                        .build()
                )
            }
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(http)
            .memoryCache {
                MemoryCache.Builder(context).maxSizePercent(0.20).build()
            }
            .diskCache {
                // As miniaturas vêm do PC pela rede; cachear em disco evita
                // rebuscar a grade inteira toda vez que a pasta reabre.
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("thumbs"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
