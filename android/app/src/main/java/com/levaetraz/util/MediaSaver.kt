package com.levaetraz.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.levaetraz.data.ApiClient
import com.levaetraz.data.IndiceBaixados
import com.levaetraz.model.ItemArquivo
import com.levaetraz.model.Servidor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Puxa um arquivo do PC e salva na galeria do celular via MediaStore com
 * escopo — sem precisar de permissão de armazenamento no Android 10+.
 *
 * Portado do `compartilhar-midia`. Foto vai pra Pictures e vídeo pra Movies
 * (aparecem na galeria); áudio pra Music; o resto pra Download.
 */
object MediaSaver {

    private const val SUBPASTA = "levaetraz"

    suspend fun salvar(
        context: Context,
        api: ApiClient,
        alvo: Servidor,
        item: ItemArquivo,
    ): Uri {
        val uri = if (usaCaminhoLegado(item.tipo)) {
            withContext(Dispatchers.IO) {
                val destino = File(pastaLegada(), item.nome)
                destino.outputStream().use { api.baixar(alvo, item.caminho, it) }
                Uri.fromFile(destino)
            }
        } else {
            inserirEEscrever(context, item) { saida ->
                api.baixar(alvo, item.caminho, saida)
            }
        }
        // registra só depois de gravar inteiro: se falhar no meio, o item
        // continua marcado como "não baixado" e pode ser tentado de novo
        IndiceBaixados.de(context).registrar(item, uri)
        return uri
    }

    /** Já existe um arquivo com esse nome onde ele cairia? Evita rebaixar por cima. */
    suspend fun existeNoDispositivo(context: Context, item: ItemArquivo): Boolean =
        withContext(Dispatchers.IO) {
            if (usaCaminhoLegado(item.tipo)) {
                return@withContext File(pastaLegada(), item.nome).exists()
            }
            val (colecao, pastaRelativa) = colecaoEPasta(item.tipo)
            val projecao = arrayOf(MediaStore.MediaColumns._ID)
            val selecao = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            // o MediaStore normaliza o caminho relativo salvo com "/" no final
            val args = arrayOf(item.nome, "$pastaRelativa/")
            runCatching {
                context.contentResolver.query(colecao, projecao, selecao, args, null)
                    ?.use { it.count > 0 } ?: false
            }.getOrDefault(false)
        }

    private fun mimeDe(nome: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(nome.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    // MediaStore.Downloads só existe do Android 10 em diante; antes disso um
    // arquivo genérico escreve direto na pasta pública de downloads.
    private fun usaCaminhoLegado(tipo: String): Boolean =
        tipo !in setOf("imagem", "video", "audio") &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    private fun pastaLegada(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBPASTA,
        )
        dir.mkdirs()
        return dir
    }

    private fun colecaoEPasta(tipo: String): Pair<Uri, String> {
        val moderno = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        return when (tipo) {
            "video" -> Pair(
                if (moderno) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MOVIES}/$SUBPASTA",
            )
            "imagem" -> Pair(
                if (moderno) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_PICTURES}/$SUBPASTA",
            )
            "audio" -> Pair(
                if (moderno) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MUSIC}/$SUBPASTA",
            )
            else -> Pair(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${Environment.DIRECTORY_DOWNLOADS}/$SUBPASTA",
            )
        }
    }

    private suspend fun inserirEEscrever(
        context: Context,
        item: ItemArquivo,
        escrever: suspend (OutputStream) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (colecao, pastaRelativa) = colecaoEPasta(item.tipo)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.nome)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeDe(item.nome))
            put(MediaStore.MediaColumns.RELATIVE_PATH, pastaRelativa)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(colecao, values)
            ?: throw IOException("não deu pra criar o arquivo no celular")

        try {
            val saida = resolver.openOutputStream(uri)
                ?: throw IOException("não deu pra abrir o destino no celular")
            saida.use { escrever(it) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null,
        )
        uri
    }
}
