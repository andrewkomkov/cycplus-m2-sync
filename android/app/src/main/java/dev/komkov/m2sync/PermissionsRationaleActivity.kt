package dev.komkov.m2sync

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Экран обоснования доступа — его требует Health Connect. */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.rationale)
            textSize = 16f
            setPadding(48, 48, 48, 48)
        })
    }
}
