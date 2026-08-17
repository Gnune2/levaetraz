package com.levaetraz.data

import android.content.ContentUris
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.core.content.contentValuesOf
import com.levaetraz.model.ItemArquivo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Registro do que já foi puxado do PC para este celular.
 *
 * Sem isso, o mesmo arquivo é baixado de novo toda vez que aparece na seleção
 * — e, pior, um reinício do celular no meio de um lote fazia o worker refazer
 * a lista inteira, duplicando o que já tinha chegado.
 *
 * SQLite direto em vez de Room: não precisa de gerador de código nem de outro
 * plugin no build, e a tabela é uma só.
 */
class IndiceBaixados private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NOME, null, VERSAO) {

    companion object {
        private const val NOME = "baixados.db"
        private const val VERSAO = 1

        @Volatile
        private var instancia: IndiceBaixados? = null

        fun de(context: Context): IndiceBaixados =
            instancia ?: synchronized(this) {
                instancia ?: IndiceBaixados(context).also { instancia = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE puxados (
                caminho_pc TEXT PRIMARY KEY,
                nome       TEXT NOT NULL,
                tamanho    INTEGER NOT NULL,
                uri        TEXT,
                quando     INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, antiga: Int, nova: Int) {
        db.execSQL("DROP TABLE IF EXISTS puxados")
        onCreate(db)
    }

    // ── consulta ─────────────────────────────────────────────
    /**
     * Já está no celular?
     *
     * O tamanho entra na chave porque o arquivo pode ter sido trocado no PC
     * (re-baixado em outra qualidade, por exemplo) mantendo o mesmo caminho.
     * E a URI é verificada de verdade: se você apagou o arquivo da galeria,
     * o registro não vale mais e ele volta a ser baixável.
     */
    suspend fun jaTem(context: Context, item: ItemArquivo): Boolean = withContext(Dispatchers.IO) {
        val linha = readableDatabase.query(
            "puxados", arrayOf("tamanho", "uri"),
            "caminho_pc = ?", arrayOf(item.caminho),
            null, null, null,
        ).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
        } ?: return@withContext false

        val (tamanho, uri) = linha
        if (item.tamanho > 0 && tamanho != item.tamanho) {
            esquecer(item.caminho)          // mudou no PC: vale baixar de novo
            return@withContext false
        }
        if (uri.isNullOrBlank()) return@withContext true

        if (uriExiste(context, uri)) true
        else {
            esquecer(item.caminho)          // apagado da galeria: pode rebaixar
            false
        }
    }

    /** Versão em lote — uma consulta só, para pintar a grade. */
    suspend fun quaisJaTem(caminhos: Collection<String>): Set<String> =
        withContext(Dispatchers.IO) {
            if (caminhos.isEmpty()) return@withContext emptySet()
            val marcas = caminhos.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT caminho_pc FROM puxados WHERE caminho_pc IN ($marcas)",
                caminhos.toTypedArray(),
            ).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
        }

    private fun uriExiste(context: Context, uri: String): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    // ── escrita ──────────────────────────────────────────────
    suspend fun registrar(item: ItemArquivo, uri: Uri?) = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict(
            "puxados", null,
            contentValuesOf(
                "caminho_pc" to item.caminho,
                "nome" to item.nome,
                "tamanho" to item.tamanho,
                "uri" to uri?.toString(),
                "quando" to System.currentTimeMillis(),
            ),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    suspend fun esquecer(caminhoPc: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("puxados", "caminho_pc = ?", arrayOf(caminhoPc))
        Unit
    }

    suspend fun esquecerTodos(): Int = withContext(Dispatchers.IO) {
        writableDatabase.delete("puxados", null, null)
    }

    suspend fun total(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM puxados", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
