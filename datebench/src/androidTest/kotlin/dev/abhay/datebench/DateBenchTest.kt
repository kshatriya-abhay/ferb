package dev.abhay.datebench

import android.os.SystemClock
import android.util.Log
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.nl.entityextraction.DateTimeEntity
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reads files/inputs.jsonl ({"i", "source", "text", "now"}, pushed by tool-check/phone.py) and
 * writes, per line, the first date or time each reader finds: files/reader-mlkit.jsonl and
 * files/reader-tc.jsonl ({"i", "source", "millis" or null, "timed", "ms", "found"}), read by
 * DateReadersEval on the laptop. "now" is each line's reference time, in Asia/Kolkata.
 */
@RunWith(AndroidJUnit4::class)
class DateBenchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun inputs() = File(context.filesDir, "inputs.jsonl").readLines().filter { it.isNotBlank() }.map(::JSONObject)

    private fun write(name: String, lines: List<JSONObject>) {
        File(context.filesDir, name).writeText(lines.joinToString("\n", postfix = "\n"))
        Log.i(TAG, "$name: ${lines.size} lines, ${lines.count { !it.isNull("millis") }} with a moment")
    }

    @Test
    fun mlKit() {
        val extractor = EntityExtraction.getClient(EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build())
        Tasks.await(extractor.downloadModelIfNeeded(), 5, TimeUnit.MINUTES)
        val out = inputs().map { o ->
            val now = LocalDateTime.parse(o.getString("now")).atZone(zone)
            val params = EntityExtractionParams.Builder(o.getString("text"))
                .setReferenceTime(now.toInstant().toEpochMilli())
                .setReferenceTimeZone(TimeZone.getTimeZone(zone))
                .setEntityTypesFilter(setOf(Entity.TYPE_DATE_TIME))
                .setPreferredLocale(Locale.ENGLISH)
                .build()
            val started = SystemClock.elapsedRealtime()
            val found = runCatching { Tasks.await(extractor.annotate(params), 30, TimeUnit.SECONDS) }.getOrDefault(emptyList())
            val ms = SystemClock.elapsedRealtime() - started
            val first = found.flatMap { it.entities }.firstNotNullOfOrNull { it as? DateTimeEntity ?: it.asDateTimeEntity() }
            JSONObject().put("i", o.getInt("i")).put("source", o.getString("source")).put("ms", ms)
                .put("millis", first?.timestampMillis ?: JSONObject.NULL)
                // Granularity below a day: a clock time was said.
                .put("timed", first != null && first.dateTimeGranularity < DateTimeEntity.GRANULARITY_DAY)
                .put("found", found.joinToString("; ") { a -> "${a.annotatedText}=${a.entities.joinToString { e -> (e.asDateTimeEntity()?.let { "${it.timestampMillis}/g${it.dateTimeGranularity}" }) ?: e.type.toString() }}" })
        }
        extractor.close()
        write("reader-mlkit.jsonl", out)
    }

    /**
     * The system TextClassifier: generateLinks for date and datetime spans, then classifyText on
     * the first, whose calendar action may carry the resolved time (content://com.android.calendar/time/<millis>).
     */
    @Test
    fun textClassifier() {
        val classifier = context.getSystemService(TextClassificationManager::class.java).textClassifier
        val out = inputs().map { o ->
            val text = o.getString("text")
            val now = LocalDateTime.parse(o.getString("now")).atZone(zone)
            val started = SystemClock.elapsedRealtime()
            val links = runCatching {
                classifier.generateLinks(TextLinks.Request.Builder(text)
                    .setEntityConfig(TextClassifier.EntityConfig.Builder().setIncludedTypes(listOf(TextClassifier.TYPE_DATE, TextClassifier.TYPE_DATE_TIME)).build())
                    .setReferenceTime(now).build())
            }.getOrNull()
            val span = links?.links?.firstOrNull { l -> (0 until l.entityCount).any { l.getEntity(it) in setOf(TextClassifier.TYPE_DATE, TextClassifier.TYPE_DATE_TIME) } }
            var millis: Long? = null
            var timed = false
            var found = links?.links?.joinToString("; ") { l -> "${text.substring(l.start, l.end)}=${(0 until l.entityCount).joinToString { l.getEntity(it) }}" } ?: "error"
            if (span != null) {
                val c = runCatching { classifier.classifyText(TextClassification.Request.Builder(text, span.start, span.end).setReferenceTime(now).build()) }.getOrNull()
                timed = (0 until span.entityCount).any { span.getEntity(it) == TextClassifier.TYPE_DATE_TIME }
                @Suppress("DEPRECATION")
                // The first action's intent (deprecated, but the only place the resolved time shows).
                val uris = listOfNotNull(c?.intent?.data)
                millis = uris.firstNotNullOfOrNull { u -> u.takeIf { it.toString().startsWith("content://com.android.calendar/time/") }?.lastPathSegment?.toLongOrNull() }
                found += " | intent ${c?.intent?.data} | actions ${c?.actions?.joinToString { it.title }} | extras ${c?.extras?.keySet()}"
            }
            JSONObject().put("i", o.getInt("i")).put("source", o.getString("source")).put("ms", SystemClock.elapsedRealtime() - started)
                .put("millis", millis ?: JSONObject.NULL).put("timed", timed).put("found", found)
        }
        write("reader-tc.jsonl", out)
    }

    companion object {
        const val TAG = "DateBench"
    }
}
