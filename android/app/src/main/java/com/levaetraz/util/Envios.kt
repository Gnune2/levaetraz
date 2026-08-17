package com.levaetraz.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.levaetraz.data.ApiClient
import com.levaetraz.model.PedidoAbrirEnvio
import com.levaetraz.model.Servidor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Subir um arquivo do celular para o PC.
 *
 * Os três passos do protocolo (ver servidor/transferencias.py) moram aqui, e
 * não no ViewModel, porque o worker de segundo plano usa exatamente os mesmos.
 */
object Envios {

    data class Resultado(val duplicado: Boolean, val mensagem: String)

    /**
     * Nome e tamanho de um arquivo que o Android nos entregou.
     *
     * Três caminhos, porque as URIs chegam de três jeitos:
     *   `content://` do seletor ou de um app moderno -> ContentResolver
     *   `file://` de um app antigo                   -> java.io.File
     *   qualquer coisa sem metadados                 -> abre e conta os bytes
     *
     * Sem o último, um provider que não expõe SIZE derrubaria o envio: o
     * servidor recusa no fim porque o tamanho declarado não bate.
     */
    fun descrever(contexto: Context, uri: Uri): Pair<String, Long>? {
        if (uri.scheme == "file") {
            val f = uri.path?.let(::File) ?: return null
            return if (f.isFile) f.name to f.length() else null
        }

        val doProvider = runCatching {
            contexto.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val iNome = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val iTam = c.getColumnIndex(OpenableColumns.SIZE)
                val nome = (if (iNome >= 0) c.getString(iNome) else null)
                    ?: uri.lastPathSegment ?: "arquivo"
                val tam = if (iTam >= 0 && !c.isNull(iTam)) c.getLong(iTam) else -1L
                nome to tam
            }
        }.getOrNull()

        val nome = doProvider?.first ?: uri.lastPathSegment ?: "arquivo"
        val tamanho = doProvider?.second ?: -1L
        if (tamanho >= 0) return nome to tamanho

        val contado = runCatching { contarBytes(contexto, uri) }.getOrNull() ?: return null
        return nome to contado
    }

    private fun contarBytes(contexto: Context, uri: Uri): Long {
        var total = 0L
        contexto.contentResolver.openInputStream(uri)?.use { entrada ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val lidos = entrada.read(buffer)
                if (lidos < 0) break
                total += lidos
            }
        } ?: throw IOException("não consegui abrir o arquivo")
        return total
    }

    /**
     * Sobe o arquivo inteiro, retomando se o servidor disser que já tem parte.
     *
     * O sha256 é calculado antes de abrir o envio. Custa um passe de leitura no
     * arquivo local — muito mais barato do que mandar 300 MB pela rede para o
     * servidor descobrir no fim que já tinha aquele conteúdo.
     */
    suspend fun subir(
        contexto: Context,
        api: ApiClient,
        servidor: Servidor,
        uri: Uri,
        nome: String,
        tamanho: Long,
        destino: String?,
        cancelado: () -> Boolean = { false },
        progresso: (Long) -> Unit = {},
    ): Resultado = withContext(Dispatchers.IO) {
        val sha = runCatching { sha256(contexto, uri) }.getOrNull()

        val abertura = api.abrirEnvio(
            servidor,
            PedidoAbrirEnvio(nome = nome, tamanho = tamanho, destino = destino, sha256 = sha),
        )
        if (abertura.duplicado) {
            progresso(tamanho)
            return@withContext Resultado(true, abertura.mensagem)
        }

        val entrada = contexto.contentResolver.openInputStream(uri)
            ?: throw IOException("não consegui abrir $nome")

        // Pular o que o servidor já tem. skip() pode pular menos que o pedido,
        // então insiste até chegar lá — senão o arquivo sairia deslocado.
        var pulados = 0L
        while (pulados < abertura.offset) {
            val n = entrada.skip(abertura.offset - pulados)
            if (n <= 0) break
            pulados += n
        }
        if (pulados != abertura.offset) {
            entrada.close()
            throw IOException("não consegui retomar de ${abertura.offset}")
        }

        api.mandarBytes(
            s = servidor,
            envioId = abertura.id,
            offset = abertura.offset,
            entrada = entrada,
            restante = tamanho - abertura.offset,
            cancelado = cancelado,
            progresso = progresso,
        )

        val fim = api.fecharEnvio(servidor, abertura.id)
        Resultado(fim.estado == "duplicado", fim.mensagem)
    }

    private fun sha256(contexto: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        contexto.contentResolver.openInputStream(uri)?.use { entrada ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val lidos = entrada.read(buffer)
                if (lidos < 0) break
                digest.update(buffer, 0, lidos)
            }
        } ?: throw IOException("não consegui ler o arquivo")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
