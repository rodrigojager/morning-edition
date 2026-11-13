package dobra

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun platformNameIsKnown() {
        assertTrue(platformName() in setOf("Android", "JVM"), "unexpected platform: ${platformName()}")
    }
}
