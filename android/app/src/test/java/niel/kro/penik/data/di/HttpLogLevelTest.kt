package niel.kro.penik.data.di

import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards against leaking secrets into system logs: HTTP body logging must be
 * disabled in release builds and enabled only in debug builds.
 */
class HttpLogLevelTest {

    @Test
    fun releaseBuildDisablesBodyLogging() {
        assertEquals(
            HttpLoggingInterceptor.Level.NONE,
            NetworkModule.httpLogLevel(isDebug = false)
        )
    }

    @Test
    fun debugBuildEnablesBasicLogging() {
        assertEquals(
            HttpLoggingInterceptor.Level.BASIC,
            NetworkModule.httpLogLevel(isDebug = true)
        )
    }

    @Test
    fun releaseBuildNeverLeaksBodies() {
        // BODY is the only level that serializes request/response bodies
        // (and thus the bearer token, passwords and key material).
        assertNotEquals(
            HttpLoggingInterceptor.Level.BODY,
            NetworkModule.httpLogLevel(isDebug = false)
        )
    }
}
