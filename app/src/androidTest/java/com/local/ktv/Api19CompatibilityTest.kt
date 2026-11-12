package com.local.ktv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Switch
import androidx.appcompat.content.res.AppCompatResources
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Api19CompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme)
    private val singer = arrayOf<String?>("test-singer", "测试歌手", "男", "大陆", "0", null)

    @Test
    fun appCompatLoadsBusinessIcons() = onMain {
        assertNotNull(AppCompatResources.getDrawable(context, R.drawable.ott_ic_more))
    }

    @Test
    fun databaseLoadingSpinnerInflates() = onMain {
        val bar = ProgressBar(ContextThemeWrapper(context, R.style.LoadingProgressBarTheme))
        assertNotNull("Loading spinner drawable missing", bar.indeterminateDrawable)
    }

    @Test
    fun allLayoutsInflateOnApi19() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val failures = mutableListOf<String>()
                R.layout::class.java.fields.forEach { field ->
                    try {
                        activity.layoutInflater.inflate(field.getInt(null), FrameLayout(activity), false)
                    } catch (e: Exception) {
                        var cause: Throwable = e
                        while (cause.cause != null) cause = cause.cause!!
                        failures += "${field.name}: ${cause.javaClass.simpleName}: ${cause.message}"
                    }
                }
                assertTrue("Layouts failed to inflate:\n${failures.joinToString("\n")}", failures.isEmpty())
            }
        }
    }

    @Test
    fun singerGridMeasuresInsideListView() = onMain {
        val list = ListView(context)
        list.adapter = SingerGridAdapter(context, listOf(singer)) {}
        list.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertTrue("Singer grid row did not measure", list.measuredHeight > 0)
    }

    @Test
    fun singerGridRendersCircularAvatarAndDispatchesSelection() = onMain {
        var selected: Array<String?>? = null
        val adapter = SingerGridAdapter(context, listOf(singer)) { selected = it }
        val row = adapter.getView(0, null, FrameLayout(context)) as ViewGroup
        assertEquals(4, row.childCount)
        val tile = row.getChildAt(0) as ViewGroup
        assertCircularAvatar(tile.getChildAt(0) as ImageView)
        assertTrue(tile.performClick())
        assertSame(singer, selected)
        assertEquals(0, SingerGridAdapter(context, emptyList()) {}.count)
    }

    @Test
    fun quickSearchRendersCircularAvatarAndRetainsSongActions() = onMain {
        var selectedSinger: Array<String?>? = null
        var selectedSong: Song? = null
        var moreSong: Song? = null
        val song = Song().apply { id = "test-song"; title = "测试歌曲"; singer = "测试歌手" }
        val adapter = QuickSearchAdapter(context, listOf(singer), listOf(song),
            { selectedSinger = it }, { selectedSong = it }, { moreSong = it })
        assertEquals(2, adapter.count)
        val singers = adapter.getView(0, null, FrameLayout(context)) as ViewGroup
        val tile = singers.getChildAt(0) as ViewGroup
        assertCircularAvatar(tile.getChildAt(0) as ImageView)
        tile.performClick()
        assertSame(singer, selectedSinger)
        val songs = adapter.getView(1, null, FrameLayout(context)) as ViewGroup
        val cell = songs.getChildAt(0)
        cell.performClick()
        cell.performLongClick()
        assertSame(song, selectedSong)
        assertSame(song, moreSong)
        assertEquals(0, QuickSearchAdapter(context, emptyList(), emptyList(), {}, {}, {}).count)
    }

    @Test
    fun settingsSwitchTogglesAndDispatchesActionOnce() = onMain {
        var clicks = 0
        val adapter = SettingsListAdapter(context, listOf(
            SettingsEntry(R.drawable.ic_pause, "测试开关", checked = false) { clicks++ },
        ))
        val row = adapter.getView(0, null, FrameLayout(context)) as ViewGroup
        val toggle = row.getChildAt(row.childCount - 1) as CompoundButton
        assertFalse(toggle.isChecked)
        toggle.performClick()
        assertTrue(toggle.isChecked)
        assertEquals(1, clicks)
        assertEquals(adapter.firstActionId, toggle.id)
    }

    @Test
    fun settingsSwitchHidesOnOffText() = onMain {
        val adapter = SettingsListAdapter(context, listOf(
            SettingsEntry(R.drawable.ic_pause, "测试开关", checked = true) {},
        ))
        val row = adapter.getView(0, null, FrameLayout(context)) as ViewGroup
        val toggle = row.getChildAt(row.childCount - 1) as Switch
        row.measure(
            View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertEquals("Switch should not render clipped ON text", "", toggle.textOn)
        assertEquals("Switch should not render clipped OFF text", "", toggle.textOff)
    }

    @Test
    fun downloadRowShowsProgressAndDispatchesPause() = onMain {
        val song = Song().apply { id = "download-test"; title = "下载测试" }
        var pausedSong: Song? = null
        val adapter = SongListAdapter(context, listOf(song), "downloads", 0, View.NO_ID, View.NO_ID,
            { SongRowState(downloading = true, progress = 42) },
            SongListCallbacks({}, {}, {}, {}, {}, { pausedSong = it }))
        val row = adapter.getView(0, null, FrameLayout(context)) as ViewGroup
        val progress = row.getChildAt(2) as ProgressBar
        assertEquals(42, progress.progress)
        assertNotNull(progress.progressDrawable)
        row.getChildAt(3).performClick()
        assertSame(song, pausedSong)
    }

    @Test
    fun importDirectoryIsWritableOnPrimaryExternalStorage() {
        AppPaths.ensureDirectories()
        val file = File.createTempFile("api19-storage-", ".txt", AppPaths.importDir)
        try {
            file.writeText("offline import")
            assertEquals("offline import", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test
    fun downloadManagerRecognizesAnUnqueuedSong() {
        val song = Song().apply { id = "never-enqueued-api19-test" }
        assertFalse(SongOkDownloadManager.isDownloading(song))
    }

    private fun assertCircularAvatar(image: ImageView) {
        image.measure(View.MeasureSpec.makeMeasureSpec(88, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(88, View.MeasureSpec.EXACTLY))
        image.layout(0, 0, 88, 88)
        val bitmap = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888)
        try {
            image.draw(Canvas(bitmap))
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(0, Color.alpha(bitmap.getPixel(87, 87)))
            assertTrue(Color.alpha(bitmap.getPixel(44, 44)) > 0)
        } finally {
            bitmap.recycle()
        }
    }

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        result!!.getOrThrow()
    }
}
