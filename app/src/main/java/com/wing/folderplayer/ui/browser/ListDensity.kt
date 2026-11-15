package com.wing.folderplayer.ui.browser

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The sizes of one row of the file list; its text styles are picked from the theme's typography. */
@Immutable
data class RowMetrics(
    val vertical: Dp,
    val horizontal: Dp,
    /** Between the image and the text, and between the text and the length. */
    val gap: Dp,
    val thumb: Dp,
    /** The icon on a folder or image tile with no picture shown. */
    val thumbIcon: Dp,
    /** The image badge on the tile of an image file. */
    val badge: Dp,
    val headline: (Typography) -> TextStyle,
    val supporting: (Typography) -> TextStyle,
    val trailing: (Typography) -> TextStyle,
)

/**
 * How tall the rows of the file list are (a folder, the songs list, the favourites, search results; not the grid).
 * [STANDARD] is Material's list item, whose heights (56 / 72 dp) and padding cannot be made smaller; the others are rows
 * of their own with the same content in smaller text styles (the user's font size still applies).
 */
enum class ListDensity(val row: RowMetrics) {
    /** Material's own sizes; of these only the tiles and the length are read (the list item lays out the rest). */
    STANDARD(RowMetrics(8.dp, 16.dp, 16.dp, thumb = 40.dp, thumbIcon = 24.dp, badge = 14.dp, headline = { it.bodyLarge }, supporting = { it.bodyMedium }, trailing = { it.labelLarge })),
    COMPACT(RowMetrics(4.dp, 12.dp, 12.dp, thumb = 32.dp, thumbIcon = 20.dp, badge = 12.dp, headline = { it.bodyLarge }, supporting = { it.bodyMedium }, trailing = { it.labelLarge })),
    DENSE(RowMetrics(2.dp, 12.dp, 10.dp, thumb = 28.dp, thumbIcon = 18.dp, badge = 10.dp, headline = { it.bodyMedium }, supporting = { it.bodySmall }, trailing = { it.labelMedium })),
    ;

    companion object {
        /** The stored name; nothing stored, or a name this version does not know, is [STANDARD]. */
        fun of(name: String?): ListDensity = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}
