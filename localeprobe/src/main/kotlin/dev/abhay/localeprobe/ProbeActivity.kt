package dev.abhay.localeprobe

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** In front, with the screen kept on, while the probe runs: AICore refuses apps in the background. */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Locale probe running: leave this in front."; setPadding(48, 160, 48, 48) })
    }
}
