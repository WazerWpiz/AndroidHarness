package com.androidharness.app.data.env

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P10 — the JVM redirect: the Termux-built JDK ships java.io.tmpdir
 * and user.home baked to /data/data/com.termux/…, so Gradle's native
 * loader cannot create its extraction file. The redirect points both
 * properties inside the app's own prefix — and nowhere else.
 */
class JvmRedirectEnvTest {

    private val prefix = File("/data/data/com.androidharness.app/files/linux")

    @Test
    fun `the redirect carries both properties inside the prefix`() {
        val env = jvmRedirectEnvOf(prefix)
        val options = env["JDK_JAVA_OPTIONS"]
        assertTrue(options != null)
        // Same paths processEnv uses for TMPDIR and HOME — no second
        // hardcoded path.
        assertTrue(options!!.contains("-Djava.io.tmpdir=${prefix.absolutePath}/tmp"))
        assertTrue(options.contains("-Duser.home=${prefix.absolutePath}/home"))
        assertEquals("${prefix.absolutePath}/tmp", options.substringAfter("-Djava.io.tmpdir=").substringBefore(" "))
        assertEquals("${prefix.absolutePath}/home", options.substringAfter("-Duser.home="))
    }

    @Test
    fun `no redirected path ever leaves the app's own prefix`() {
        val env = jvmRedirectEnvOf(prefix)
        for ((key, value) in env) {
            // Every absolute path in the value must be under the prefix.
            for (token in value.split(" ", "=")) {
                if (token.startsWith("/")) {
                    assertTrue("$key points outside the prefix: $token", token.startsWith(prefix.absolutePath + "/"))
                }
            }
            assertFalse(value.contains("com.termux"))
            assertFalse(value.contains("/sdcard"))
            assertFalse(value.contains("Android/data"))
            assertFalse(value.contains("external"))
        }
        // And the mechanism itself is one key, not a scatter of flags.
        assertEquals(setOf("JDK_JAVA_OPTIONS"), env.keys)
    }
}
