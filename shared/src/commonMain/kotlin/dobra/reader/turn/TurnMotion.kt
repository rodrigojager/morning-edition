package dobra.reader.turn

/** A pose of a turning page, in the geometry of one mode: the rolled single sheet ([CurlPose]) or the book's page
 *  hinged on the fold ([BookPose]). */
sealed interface TurnPose

/** A pose under a finger and how far the turn has gone ([progress], 0 to 1), which the release rule reads. */
data class DragPose<P : TurnPose>(val pose: P, val progress: Float)

/**
 * How a turn moves, whatever its geometry: the poses a turn played by itself goes through, the pose under a finger and
 * the way a released page settles. Lengths are density-independent pixels. The release rule is
 * [SheetCurl.commits], the same for every mode.
 */
interface TurnMotion<P : TurnPose> {
    /** How long a turn played by itself lasts, in milliseconds. */
    val playMs: Int

    /** The pose a turn played by itself goes through at [t]: 0 shows the screen being left, 1 the screen reached. */
    fun at(t: Float): P

    /** The pose under a finger: [dir] 1 turning forward, -1 bringing the previous page back; [y] where the finger
     *  went down (from mid-height, up positive); [mx] how far it went in the direction of the turn, [my] how far down. */
    fun drag(dir: Int, y: Float, mx: Float, my: Float): DragPose<P>

    /** Where a released turn of direction [dir] lands. */
    fun restingPose(dir: Int, commit: Boolean): P

    /** How long the page takes from pose [from] to the resting pose [to] once let go, in milliseconds. */
    fun settleMillis(from: P, to: P): Int

    /** The pose at [k] (0 to 1, eased) of the way from the pose under the finger to the resting pose. */
    fun settle(from: P, to: P, k: Float): P
}
