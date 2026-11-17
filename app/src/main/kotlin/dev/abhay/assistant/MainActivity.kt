package dev.abhay.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Ferb's front door (`.MainActivity`, the launcher icon's component, kept by that name): it shows
 * nothing and hands each intent on at once, the assist trigger to the sheet ([AssistActivity]) and
 * everything else to the chat ([ChatActivity]). Android saved this component as the assistant when
 * the role was granted before the sheet existed, and SystemUI starts ACTION_ASSIST at it by name,
 * so it routes rather than shows (2026-10-24). It has a task of its own, out of Recents, so the
 * assist trigger doesn't bring the chat forward over the app in front; no display, no animation.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = when (Routing.of(intent?.action)) {
            Routing.Sheet -> AssistActivity::class.java
            Routing.Chat -> ChatActivity::class.java
        }
        val routed = Intent(intent).setClass(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Who asked, as Android recorded it: the sheet listens at once only for the system's trigger (review P2-156).
        if (target == AssistActivity::class.java) routed.putExtra(AssistTrigger.FROM_SYSTEM, AssistTrigger.fromSystem(this))
        startActivity(routed)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

/** Where [MainActivity] hands an intent: the assistant's sheet for the assist trigger, else the chat. */
enum class Routing {
    Chat, Sheet;

    companion object {
        fun of(action: String?) = if (action == Intent.ACTION_ASSIST) Sheet else Chat
    }
}

/**
 * Whether the assist trigger came from the system (the power button, the corner swipe), not from
 * another app: only then does the sheet listen at once (review P2-156: any app in front could open
 * it with ACTION_ASSIST and have it hear and run what that app played). The caller is the one
 * Android recorded ([Activity.getReferrer]), unless the intent names one itself (EXTRA_REFERRER),
 * which any app can: then Ferb can't tell, and the sheet opens with its box focused instead.
 */
object AssistTrigger {
    /** On the intent [MainActivity] hands the sheet: its own verdict, trusted only from Ferb itself. */
    const val FROM_SYSTEM = "dev.abhay.assistant.extra.ASSIST_FROM_SYSTEM"
    /** The packages that start the assistant (SystemUI's assist gesture, the system server). */
    val SYSTEM = setOf("android", "com.android.systemui")
    const val TAG = "FerbAssist"

    /** [caller]: the referrer's package, [named]: the intent names its own referrer. */
    fun fromSystem(caller: String?, named: Boolean): Boolean = !named && caller in SYSTEM

    fun fromSystem(activity: Activity): Boolean {
        val intent = activity.intent
        val named = intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME)
        val caller = activity.referrer?.host
        return fromSystem(caller, named).also { android.util.Log.i(TAG, "assist from $caller${if (named) " (named by the intent)" else ""}: ${if (it) "listen" else "box"}") }
    }

    /** For [AssistActivity]: [MainActivity]'s verdict when Ferb itself handed it on, else its own. */
    fun listens(activity: Activity): Boolean {
        val intent = activity.intent
        val named = intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME)
        return if (!named && activity.referrer?.host == activity.packageName) intent.getBooleanExtra(FROM_SYSTEM, false)
        else fromSystem(activity)
    }
}
