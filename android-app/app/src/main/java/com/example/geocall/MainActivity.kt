package com.example.geocall

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GeoCallMain"
    }

    private lateinit var webView: WebView

    private val requiredPermissions = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            Log.d(TAG, "Permissions result: allGranted=$allGranted, details=$results")
            runOnUiThread {
                webView.evaluateJavascript("if(window.onPermissionsResult) window.onPermissionsResult($allGranted);", null)
            }
        }

    // Receive broadcasts from GeoWatchService
    private val geofenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                GeoWatchService.ACTION_GEOFENCE_TRIGGERED -> {
                    val id = intent.getStringExtra(GeoWatchService.EXTRA_GEOFENCE_ID) ?: return
                    val name = intent.getStringExtra(GeoWatchService.EXTRA_CONTACT_NAME) ?: ""
                    Log.d(TAG, "Geofence triggered broadcast received: id=$id, name=$name")
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.onGeofenceTriggered) window.onGeofenceTriggered('${escapeJs(id)}', '${escapeJs(name)}');",
                            null
                        )
                    }
                }
                GeoWatchService.ACTION_SERVICE_STOPPED -> {
                    Log.d(TAG, "Service stopped broadcast received")
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.onServiceStopped) window.onServiceStopped();",
                            null
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Make status bar and navigation bar transparent for immersive UI
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // Create and configure WebView
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setGeolocationEnabled(true)
                mediaPlaybackRequiresUserGesture = false
            }

            webViewClient = WebViewClient()

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                ) {
                    // Auto-grant geolocation to our local asset pages
                    callback.invoke(origin, true, false)
                }
            }

            addJavascriptInterface(NativeBridge(), "NativeBridge")

            loadUrl("file:///android_asset/geocall.html")
        }

        setContentView(webView)

        // Register broadcast receiver for geofence events
        val filter = IntentFilter().apply {
            addAction(GeoWatchService.ACTION_GEOFENCE_TRIGGERED)
            addAction(GeoWatchService.ACTION_SERVICE_STOPPED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(geofenceReceiver, filter)
    }

    @Deprecated("Use OnBackPressedCallback", ReplaceWith("onBackPressedDispatcher"))
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(geofenceReceiver)
        webView.removeJavascriptInterface("NativeBridge")
        webView.destroy()
        super.onDestroy()
    }

    // ─── JavaScript Bridge ───────────────────────────────────────────────

    inner class NativeBridge {

        @JavascriptInterface
        fun startWatching(geofencesJson: String, autoCall: Boolean, simSlot: Int) {
            Log.d(TAG, "NativeBridge.startWatching called with ${geofencesJson.length} chars, autoCall=$autoCall, simSlot=$simSlot")
            val intent = Intent(this@MainActivity, GeoWatchService::class.java).apply {
                putExtra(GeoWatchService.EXTRA_GEOFENCES, geofencesJson)
                putExtra(GeoWatchService.EXTRA_AUTO_CALL, autoCall)
                putExtra(GeoWatchService.EXTRA_SIM_SLOT, simSlot)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        @JavascriptInterface
        fun stopWatching() {
            Log.d(TAG, "NativeBridge.stopWatching called")
            stopService(Intent(this@MainActivity, GeoWatchService::class.java))
        }

        @JavascriptInterface
        fun hasCallPermission(): Boolean {
            return ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun hasLocationPermission(): Boolean {
            return ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun requestPermissions() {
            Log.d(TAG, "NativeBridge.requestPermissions called")
            val permsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requiredPermissions + Manifest.permission.POST_NOTIFICATIONS
            } else {
                requiredPermissions
            }
            permissionLauncher.launch(permsToRequest)
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d("GeoCallJS", message)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun escapeJs(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
