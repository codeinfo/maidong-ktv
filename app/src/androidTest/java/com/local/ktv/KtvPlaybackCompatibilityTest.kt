package com.local.ktv

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.ktv.player.KtvPlaybackEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class KtvPlaybackCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var engine: KtvPlaybackEngine

    @Test
    fun bundledIjkPlaysDualAudioAndSupportsPauseSeekAndCompletion() {
        val media = File(instrumentation.targetContext.cacheDir, "compatibility-dual-track.mp4")
        instrumentation.context.assets.open("dual-track.mp4").use { input ->
            media.outputStream().use { input.copyTo(it) }
        }
        val ready = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        try {
            onMain {
                engine = KtvPlaybackEngine(instrumentation.targetContext)
                engine.setOnPreparedListener { ready.countDown() }
                engine.setOnCompletionListener { completed.countDown() }
                engine.setOnErrorListener { _, what, extra ->
                    error.set("IJK error $what/$extra")
                    ready.countDown()
                    completed.countDown()
                    true
                }
                engine.setVideoUri(Uri.fromFile(media))
            }
            assertTrue("IJK preparation timed out", ready.await(120, TimeUnit.SECONDS))
            assertNull(error.get())
            onMain {
                assertTrue("Expected 12-second fixture", engine.duration in 11_000..13_000)
                assertTrue("Missing accompaniment track", engine.selectAudioTrack(false))
                engine.start()
            }
            await("Playback position did not advance") { onMain { engine.currentPosition > 500 } }
            onMain {
                assertTrue(engine.isPlaying)
                engine.pause()
            }
            await("Pause did not stop the player") { onMain { !engine.isPlaying } }
            onMain {
                assertTrue("Missing original track", engine.selectAudioTrack(true))
                engine.seekTo(7_000)
                engine.start()
            }
            await("Seek did not reach the second half") { onMain { engine.currentPosition >= 6_000 } }
            assertTrue("Playback did not complete", completed.await(120, TimeUnit.SECONDS))
            assertNull(error.get())
            onMain { assertFalse(engine.isPlaying) }
        } finally {
            if (::engine.isInitialized) onMain { engine.release() }
            media.delete()
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!condition()) {
            assertTrue(message, SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
