package dev.abhay.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.abhay.assistant.notes.NoteText
import dev.abhay.assistant.tools.AddToList
import dev.abhay.assistant.tools.PrepareEnv
import dev.abhay.assistant.tools.Prepared
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A list survives the app's process being killed: [writeBeforeKill] runs, the process is killed
 * (`am force-stop`), then [readAfterKill] runs in a new one. Run in that order by the device-test
 * script (scripts/device-check.sh); otherwise both are skipped.
 */
@RunWith(AndroidJUnit4::class)
class ListsPersistDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notes = NotesDeviceTest.testNotes(context)
    private val dir = File(context.cacheDir, "testdocs")

    @Test
    fun writeBeforeKill() {
        org.junit.Assume.assumeTrue("run by the device-check script", InstrumentationRegistry.getArguments().getString("persist") == "write")
        dir.deleteRecursively()
        (AddToList.prepare(mapOf("list" to "persist", "items" to "kept item"), PrepareEnv(notes = notes)) as Prepared.Ready).run(context)
        File(context.cacheDir, "persist-pid").writeText(android.os.Process.myPid().toString())
    }

    @Test
    fun readAfterKill() {
        val marker = File(context.cacheDir, "persist-pid")
        org.junit.Assume.assumeTrue("writeBeforeKill first", marker.exists())
        assertThat(marker.readText()).isNotEqualTo(android.os.Process.myPid().toString())
        val list = notes.list().single { it.name == "Persist" }
        assertThat(NoteText.tasks(notes.read(list)!!).map { it.text }).containsExactly("kept item")
        marker.delete()
        dir.deleteRecursively()
    }
}
