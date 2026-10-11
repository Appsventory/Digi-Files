package com.appsventory.digiapps.nemo.ui

import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.zIndex
import com.appsventory.digiapps.nemo.R
import com.appsventory.digiapps.nemo.ui.theme.CardDark
import com.appsventory.digiapps.nemo.ui.theme.FocusShadow
import com.appsventory.digiapps.nemo.ui.theme.FocusStroke
import com.appsventory.digiapps.nemo.ui.theme.IdleDashed
import com.appsventory.digiapps.nemo.ui.theme.MarkStroke

/**
 * Efek focus kartu Main/Explore: stroke + drop shadow (warna sama dgn stroke).
 * Nilai posisi/blur/spread/lebar ada di res/values/dimens.xml,
 * warna di res/values/colors.xml.
 */
@Composable
fun Modifier.focusGlow(corner: Dp): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .dropShadow(
            shape = shape,
            shadow = Shadow(
                radius = dimensionResource(R.dimen.focus_shadow_blur),
                spread = dimensionResource(R.dimen.focus_shadow_spread),
                color = FocusShadow,
                offset = DpOffset(
                    x = dimensionResource(R.dimen.focus_shadow_x),
                    y = dimensionResource(R.dimen.focus_shadow_y)
                )
            )
        )
        .border(dimensionResource(R.dimen.focus_stroke_width), FocusStroke, shape)
}

/** Idle Create Folder: dashed stroke saja. */
@Composable
fun Modifier.dashedBorder(
    corner: Dp,
    color: Color = IdleDashed
): Modifier {
    val strokeWidth = dimensionResource(R.dimen.dashed_stroke_width)
    return drawBehind {
        drawRoundRect(
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            ),
            cornerRadius = CornerRadius(corner.toPx())
        )
    }
}

/**
 * Open / context-menu interaction for touch, mouse, and D-pad OK.
 *
 * - Touch/mouse: [combinedClickable] (short → onOpen, long → onMenu)
 * - D-pad OK / Enter: [onPreviewKeyEvent] + [ViewConfiguration.getLongPressTimeout]
 *   - KeyDown → record time
 *   - KeyUp → if held ≥ timeout → onMenu(), else → onOpen()
 *   - KeyUp is always consumed so a newly shown Dialog does not also receive OK
 *     (avoids auto-selecting the first menu item).
 *
 * When [onMenu] is null, falls back to a plain clickable (no key interception).
 */
@Composable
fun Modifier.openOrMenuClickable(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    enabled: Boolean = true,
    onOpen: () -> Unit,
    onMenu: (() -> Unit)? = null
): Modifier {
    if (onMenu == null) {
        return this.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onOpen
        )
    }

    var downAt by remember { mutableLongStateOf(0L) }
    val longPressTimeout = remember { ViewConfiguration.getLongPressTimeout().toLong() }

    return this
        .onPreviewKeyEvent { e ->
            if (!enabled) return@onPreviewKeyEvent false
            val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            if (!isOk) return@onPreviewKeyEvent false
            when (e.type) {
                KeyEventType.KeyDown -> {
                    // Only the first down of a hold; ignore auto-repeats.
                    if (e.nativeKeyEvent.repeatCount == 0) {
                        downAt = SystemClock.uptimeMillis()
                    }
                    // Consume KeyDown so the platform / clickable does not start its own
                    // long-press or fire a click on the matching KeyUp.
                    true
                }
                KeyEventType.KeyUp -> {
                    val held = SystemClock.uptimeMillis() - downAt
                    if (held >= longPressTimeout) onMenu() else onOpen()
                    // Always consume KeyUp so a Dialog opened by onMenu does not also
                    // receive OK and auto-select the first item.
                    true
                }
                else -> false
            }
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onOpen,
            onLongClick = onMenu
        )
}

/**
 * Satu-satunya base kartu fokusable di Main/Explore.
 * - 1 focus target (clickable), tanpa focusable() ganda
 * - focus  -> stroke + drop shadow
 * - idle   -> polos (atau dashed jika [dashedWhenIdle])
 * - open / menu: [openOrMenuClickable] (touch + D-pad OK long-press)
 *
 * Taruh focusRequester / focusProperties SEBELUM modifier ini.
 */
@Composable
fun Modifier.focusCard(
    corner: Dp = dimensionResource(R.dimen.card_corner),
    dashedWhenIdle: Boolean = false,
    marked: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(corner)

    val edge = when {
        focused -> Modifier.focusGlow(corner)
        marked -> Modifier.border(dimensionResource(R.dimen.focus_stroke_width), MarkStroke, shape)
        dashedWhenIdle -> Modifier.dashedBorder(corner)
        else -> Modifier
    }

    return this
        .zIndex(if (focused) 1f else 0f) // glow tidak ketutup kartu tetangga
        .then(edge)
        .clip(shape)
        .background(CardDark)
        .openOrMenuClickable(
            interactionSource = interactionSource,
            onOpen = onClick,
            onMenu = onLongClick
        )
}
