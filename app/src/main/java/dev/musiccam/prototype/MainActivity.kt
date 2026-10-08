package dev.musiccam.prototype

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.TextView

/** Phase 0 launch check. Capture permissions and recording belong to Phase 1. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val spacing = (24 * resources.displayMetrics.density).toInt()
        val statusView = TextView(this).apply {
            setText(R.string.phase_zero_message)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(spacing, spacing, spacing, spacing)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                    )
                    view.setPadding(
                        spacing + bars.left, spacing + bars.top,
                        spacing + bars.right, spacing + bars.bottom,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        spacing + insets.systemWindowInsetLeft,
                        spacing + insets.systemWindowInsetTop,
                        spacing + insets.systemWindowInsetRight,
                        spacing + insets.systemWindowInsetBottom,
                    )
                }
                insets
            }
        }
        setContentView(statusView)
        statusView.requestApplyInsets()
    }
}
