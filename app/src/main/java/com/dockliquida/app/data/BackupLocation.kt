package com.dockliquida.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** Destino local deste aparelho: não exportar permissões/URIs para outros aparelhos no .dckl. */
object BackupLocation {
    private const val PREFS = "backup_location"
    private const val KEY = "tree"
    private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun folder(ctx: Context): Uri? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY, null)?.let(Uri::parse)

    fun canWrite(ctx: Context, uri: Uri): Boolean = ctx.contentResolver.persistedUriPermissions
        .any { it.uri == uri && it.isWritePermission }

    /** Chamar após o usuário escolher uma pasta no seletor do Android. */
    fun rememberFolder(ctx: Context, uri: Uri) {
        val old = folder(ctx)
        ctx.contentResolver.takePersistableUriPermission(uri, FLAGS)
        check(canWrite(ctx, uri)) { "A pasta não concedeu permissão para salvar backups." }
        check(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, uri.toString()).commit()) {
            "Não foi possível guardar a pasta de backup."
        }
        if (old != null && old != uri) {
            runCatching { ctx.contentResolver.releasePersistableUriPermission(old, FLAGS) }
        }
    }

    fun label(uri: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "Pasta selecionada" }
    }.getOrDefault("Pasta selecionada")

    /** Cada exportação cria um arquivo novo. Remove o arquivo incompleto se a exportação falhar. */
    suspend fun export(ctx: Context): Int = withContext(Dispatchers.IO) {
        val tree = folder(ctx) ?: throw IllegalStateException("Selecione a pasta de backup.")
        check(canWrite(ctx, tree)) { "Selecione novamente a pasta de backup para permitir o acesso." }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = Backup.suggestedName().removeSuffix(".${Backup.EXT}") + "_${UUID.randomUUID().toString().take(8)}.${Backup.EXT}"
        val document = DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", name)
            ?: throw IllegalStateException("Não foi possível criar o backup. Altere a pasta de backup e tente novamente.")
        var completed = false
        try {
            val count = Backup.export(ctx, document)
            completed = true
            count
        } finally {
            if (!completed) withContext(NonCancellable) {
                runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, document) }
            }
        }
    }
}
