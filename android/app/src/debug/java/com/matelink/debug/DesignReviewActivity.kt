package com.matelink.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Internal P0 comparison surface. It is compiled only into the debug test
 * package and has no launcher intent, deep link or Release source-set entry.
 */
class DesignReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DesignReviewScreen() }
    }
}
