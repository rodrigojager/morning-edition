package dobra.app.edition.paged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookFrameTest {
    @Test
    fun withoutAFoldTheSpineSitsInTheMiddle() {
        val f = BookFrame.book(820, 28)
        assertEquals(396, f.pageWidth)
        assertEquals(0, f.leftStart)
        assertEquals(396, f.spineStart)
        assertEquals(28, f.spineWidth)
        assertEquals(424, f.rightStart)
        assertEquals(410f, f.spineCenter)
        // an odd remainder goes to the spine, as the pages keep the same width
        val odd = BookFrame.book(821, 28)
        assertEquals(396, odd.pageWidth)
        assertEquals(29, odd.spineWidth)
    }

    @Test
    fun aFoldWiderThanTheGutterWidensTheSpineOverIt() {
        val f = BookFrame.book(820, 28, 380..440)
        assertEquals(380, f.spineStart)
        assertEquals(60, f.spineWidth)
        assertEquals(380, f.pageWidth)
        assertEquals(0, f.leftStart)
        assertEquals(440, f.rightStart)
    }

    @Test
    fun aFoldOffTheMiddleCarriesTheSpineAndThePagesKeepOneWidth() {
        val f = BookFrame.book(820, 28, 450..450)
        assertEquals(450f, f.spineCenter)
        assertTrue(f.spineStart <= 450 && f.spineStart + f.spineWidth >= 450)
        assertEquals(820 - 464, f.pageWidth)
        assertEquals(f.spineStart - f.pageWidth, f.leftStart)
        assertTrue(f.rightStart + f.pageWidth <= 820)
    }

    @Test
    fun aFoldOutsideTheAreaOrFarToOneSideIsIgnored() {
        val middle = BookFrame.book(820, 28)
        assertEquals(middle, BookFrame.book(820, 28, -40..-10))
        assertEquals(middle, BookFrame.book(820, 28, 900..940))
        assertEquals(middle, BookFrame.book(820, 28, 100..110))
    }

    @Test
    fun aSinglePageTakesTheWholeWidth() {
        val f = BookFrame.single(384)
        assertEquals(384, f.pageWidth)
        assertEquals(0, f.leftStart)
    }
}
