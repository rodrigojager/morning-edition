package dobra.reader.turn

/**
 * The settings of the page turn, kept in one place: how fine the sheet's mesh is, how sharp the page pictures are and
 * how long a turn played by itself lasts.
 *
 * Lengths of the turn's geometry are density-independent pixels (the CSS pixels of the prototype), so the roll and the
 * release speeds are the same on every screen; the app converts finger travel to that unit before asking for a pose.
 */
object TurnSpec {
    /** Columns of the single sheet's mesh (along the width). */
    const val GRID_X: Int = 72

    /** Rows of the single sheet's mesh (along the height). */
    const val GRID_Y: Int = 24

    /** The page pictures are drawn with at most this many pixels per density-independent pixel. */
    const val PICTURE_MAX_DENSITY: Float = 2f

    /** How long a turn played by itself (a button, a key) lasts, in milliseconds. */
    const val PLAY_MS: Int = 820

    /** How long the book's page takes to swing over the fold by itself, in milliseconds. */
    const val BOOK_PLAY_MS: Int = 720

    /** Segments of the book's page strip, from the fold to the free edge. */
    const val BOOK_SEGMENTS: Int = 40

    /** The share of the screen's pixels a page picture keeps on a screen with [density] pixels per dp. */
    fun pictureScale(density: Float): Float =
        if (density <= PICTURE_MAX_DENSITY) 1f else PICTURE_MAX_DENSITY / density
}
