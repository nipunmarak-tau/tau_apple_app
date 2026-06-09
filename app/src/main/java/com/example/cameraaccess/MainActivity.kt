package com.example.cameraaccess

import android.app.PictureInPictureParams
import android.os.*
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import com.example.cameraaccess.controller.Camera2Controller
import com.example.cameraaccess.monitoring.AppHealthMonitor
import com.example.cameraaccess.monitoring.AppIssue
import com.example.cameraaccess.monitoring.IssueSeverity
import com.example.cameraaccess.navigation.AppNavGraph
import com.newrelic.agent.android.NewRelic

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppHealthMonitor.recordMetric(
            name = "app_launch",
            attributes = mapOf("sdk" to Build.VERSION.SDK_INT.toString())
        )
        try {
            NewRelic.withApplicationToken(
                "AA4174e9fa02e73552e9dd2761c01ea6b0a5ca0606-NRMA"
            ).start(this.applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "New Relic start failed", t)
            AppHealthMonitor.captureException("startup.new_relic", t)
        }

        setContent {
            MaterialTheme {
                AppNavGraph()
            }
        }
    }

    override fun onUserLeaveHint() {
        val controller = Camera2Controller.getInstance(applicationContext)
        if (controller.recordingActive) {
            AppHealthMonitor.reportIssue(
                AppIssue(
                    key = "pip_entered_while_recording",
                    message = "Entered PiP while recording",
                    severity = IssueSeverity.LOW,
                    area = "recording.lifecycle"
                )
            )
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}
