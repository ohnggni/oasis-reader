package com.oasisreader.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.oasisreader.app.ui.theme.einkBorder

/**
 * The app's drop-down menu: used like Material's `DropdownMenu(expanded, onDismissRequest) { … }`
 * (`DropdownMenuItem`s inside), placed next to whatever it sits beside (normally the button in the
 * same `Box`).
 *
 * Drawn in a dialog window rather than a popup window: on some phones (a Galaxy S25 on Android 17,
 * 1.9) Compose popups never appeared while dialogs did, so ☰, collection ⋮ and every chip menu were
 * dead. A transparent full-screen dialog with the menu card at the anchor looks the same.
 */
@Composable
fun OgMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // where we are: the parent's box (the button and this menu share a Box), in screen pixels
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val hostView = LocalView.current
    Spacer(
        Modifier.onGloballyPositioned { c ->
            val inWindow = c.parentLayoutCoordinates?.boundsInWindow() ?: c.boundsInWindow()
            val loc = IntArray(2).also { hostView.getLocationOnScreen(it) }
            anchor = inWindow.translate(loc[0].toFloat(), loc[1].toFloat())
        }
    )
    if (!expanded) return
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dialogView = LocalView.current
        // no dimming: it is a menu, not a dialog
        (dialogView.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        val density = LocalDensity.current
        val margin = with(density) { 8.dp.roundToPx() }
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest)
        ) {
            Layout(content = {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 3.dp,
                    tonalElevation = 3.dp,
                    border = einkBorder(),
                    // taps on the card itself must not close it
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                ) {
                    Column(
                        Modifier.widthIn(min = 112.dp, max = 280.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        content = content,
                    )
                }
            }) { measurables, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val loc = IntArray(2).also { dialogView.getLocationOnScreen(it) }
                val a = (anchor ?: Rect(0f, 0f, 0f, 0f)).translate(-loc[0].toFloat(), -loc[1].toFloat())
                // below the anchor if it fits, else above, else the taller side
                val below = (h - a.bottom.toInt() - margin).coerceAtLeast(0)
                val above = (a.top.toInt() - margin).coerceAtLeast(0)
                val card = measurables.first().measure(Constraints(maxWidth = (w - 2 * margin).coerceAtLeast(0), maxHeight = maxOf(below, above)))
                val y = when {
                    card.height <= below -> a.bottom.toInt()
                    card.height <= above -> a.top.toInt() - card.height
                    below >= above -> a.bottom.toInt()
                    else -> a.top.toInt() - card.height
                }.coerceIn(margin, (h - card.height - margin).coerceAtLeast(margin))
                // start at the anchor's left edge; if that runs off the right, end at its right edge
                val x = (if (a.left.toInt() + card.width <= w - margin) a.left.toInt() else a.right.toInt() - card.width)
                    .coerceIn(margin, (w - card.width - margin).coerceAtLeast(margin))
                layout(w, h) { card.place(x, y) }
            }
        }
    }
}
