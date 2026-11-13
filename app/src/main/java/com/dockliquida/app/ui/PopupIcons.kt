package com.dockliquida.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Ícones pequenos para os menus; não dependem de material-icons-extended. */
object PopupIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()

    val Download by lazy { icon("Download", "M5,20h14v-2H5v2zM11,3v9.17l-3.59,-3.58L6,10l6,6 6,-6 -1.41,-1.41L13,12.17V3h-2z") }
    val Phone by lazy { icon("Phone", "M17,1H7C5.9,1 5,1.9 5,3v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-2 -2,-2zM17,19H7V5h10v14zM12,22c-0.55,0 -1,-0.45 -1,-1s0.45,-1 1,-1 1,0.45 1,1 -0.45,1 -1,1z") }
    val Edit by lazy { icon("Edit", "M3,17.25V21h3.75l11.06,-11.06 -3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z") }
    val Delete by lazy { icon("Delete", "M16,9v10H8V9h8m-1.5,-6h-5l-1,1H5v2h14V4h-3.5l-1,-1zM18,7H6v12c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7z") }
    val Check by lazy { icon("Check", "M9,16.17l-3.88,-3.88L3.71,13.7 9,19l12,-12 -1.41,-1.41L9,16.17z") }

    // Ícones exclusivos para as ações do editor e do seletor de destino.
    val Lock by lazy { icon("Lock", "M18,8h-1V6a5,5 0,0 0,-10 0v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM9,6a3,3 0,0 1,6 0v2H9V6zM18,20H6V10h12v10z") }
    val Save by lazy { icon("Save", "M17,3H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V7l-4,-4zM12,19a3,3 0,1 1,0 -6a3,3 0,0 1,0 6zM6,4h9v5H6V4z") }
    val Folder by lazy { icon("Folder", "M10,4l2,2h8c1.1,0 2,0.9 2,2v11c0,1.1 -0.9,2 -2,2H4c-1.1,0 -2,-0.9 -2,-2V6c0,-1.1 0.9,-2 2,-2h6z") }
}
