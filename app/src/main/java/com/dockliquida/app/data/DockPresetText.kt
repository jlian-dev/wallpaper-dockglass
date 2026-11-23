package com.dockliquida.app.data

import java.io.Reader

/** Lê apenas o texto permitido e um caractere adicional para detectar um preset grande demais. */
internal fun readDockPresetText(reader: Reader): String {
    val maxChars = 64_000
    val buffer = CharArray(4096)
    val text = StringBuilder()
    while (true) {
        val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - text.length + 1))
        if (count < 0) return text.toString()
        require(text.length + count <= maxChars) { "Arquivo muito grande para um preset." }
        text.append(buffer, 0, count)
    }
}
