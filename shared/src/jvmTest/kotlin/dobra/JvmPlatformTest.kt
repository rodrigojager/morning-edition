package dobra

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmPlatformTest {
    @Test
    fun jvmTargetReportsJvm() {
        assertEquals("JVM", platformName())
    }
}
