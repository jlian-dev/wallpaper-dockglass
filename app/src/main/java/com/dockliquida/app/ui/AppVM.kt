package com.dockliquida.app.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dockliquida.app.data.Kind
import com.dockliquida.app.data.Prefs
import com.dockliquida.app.data.Store
import com.dockliquida.app.data.Wall
import kotlinx.coroutines.launch

/** ViewModel geral: dá às telas acesso à biblioteca e às preferências, e faz a importação de fotos. */
class AppVM(app: Application) : AndroidViewModel(app) {
    val store = Store.get(app)
    val prefs = Prefs.get(app)

    /** Copia wallpapers da galeria fixa para a automática (ou o contrário). */
    fun copyTo(ids: Collection<String>, kind: Kind, albumId: String?, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch { onDone(store.copyTo(ids, kind, albumId)) }
    }

    /** Copia as fotos escolhidas para dentro do app (sem recomprimir) e avisa quando terminar. */
    fun importUris(uris: List<Uri>, kind: Kind, albumId: String?, onDone: (List<Wall>) -> Unit = {}) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = store.import(uris, kind, albumId) { w, h -> prefs.initialDock(w, h) }
            if (added.size < uris.size) {
                Toast.makeText(getApplication<Application>(), "Imagem já adicionada ou não pôde ser importada.", Toast.LENGTH_SHORT).show()
            }
            onDone(added)
        }
    }
}
