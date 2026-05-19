package com.example.appblocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*

// Custom Custom LifecycleOwner and SavedStateRegistryOwner implementation 
// This allows ComposeView inside WindowManager (which has no default lifecycle hosting)
// to render and update states flawlessly without crashing.
class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onPause() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

class AppBlockerService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var sharedPrefs: SharedPreferences
    private var overlayView: FrameLayout? = null
    private var isOverlayShown = false
    private var serviceLifecycleOwner: ServiceLifecycleOwner? = null

    // Focus Tracking Variables
    private var currentTrackedPackage: String? = null
    private var startTime: Long = 0L
    private var trackerJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sharedPrefs = getSharedPreferences("AppBlockerPrefs", MODE_PRIVATE)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Initialize our custom lifecycle host
        serviceLifecycleOwner = ServiceLifecycleOwner().apply {
            onCreate()
            onStart()
            onResume()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // We are looking for Window state modifications
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            // If user enters home, system UI or AppBlocker itself, bypass and dismiss active overlay if any
            if (packageName == "com.example.appblocker" || 
                packageName == "android" || 
                packageName.contains("launcher") || 
                packageName.contains("systemui")) {
                dismissBlockingOverlay()
                stopTracking()
                return
            }

            // Sync latest preferences
            val blockedApps = sharedPrefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()

            if (blockedApps.contains(packageName)) {
                // If we are already tracking this specific package, do not reset timer
                if (currentTrackedPackage != packageName) {
                    stopTracking()
                    startTracking(packageName)
                }
            } else {
                // Not a blocked app, reset tracking states and dismiss overlay
                dismissBlockingOverlay()
                stopTracking()
            }
        }
    }

    private fun startTracking(packageName: String) {
        currentTrackedPackage = packageName
        startTime = System.currentTimeMillis()

        // Fetch user permitted screen minutes
        val targetMinutes = sharedPrefs.getFloat("timer_limit_minutes", 4.0f)
        val targetMs = (targetMinutes * 60 * 1000).toLong()

        trackerJob = serviceScope.launch {
            while (isActive && currentTrackedPackage == packageName) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= targetMs) {
                    showBlockingOverlay(packageName)
                    break
                }
                delay(1000) // update checks every second
            }
        }
    }

    private fun stopTracking() {
        trackerJob?.cancel()
        trackerJob = null
        currentTrackedPackage = null
    }

    private fun showBlockingOverlay(packageName: String) {
        if (isOverlayShown) return

        // Ensure we are allowed to draw overlay
        if (!Settings.canDrawOverlays(this)) return

        // Set up overlay container layout
        val container = FrameLayout(this)
        val composeView = ComposeView(this).apply {
            // Set required Lifecycle providers for custom window drawing
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)

            setContent {
                AppBlockerTheme {
                    BlockingScreenOverlayContent(
                        packageName = packageName,
                        onExitClicked = {
                            // Close overlay and perform native BACK TO HOME action
                            dismissBlockingOverlay()
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    )
                }
            }
        }

        container.addView(composeView)

        // Overlay Window configurations
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(container, params)
            overlayView = container
            isOverlayShown = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissBlockingOverlay() {
        if (isOverlayShown && overlayView != null) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
            isOverlayShown = false
        }
    }

    override fun onInterrupt() {
        // Accessibility interrupts trigger cleanup
        dismissBlockingOverlay()
        stopTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissBlockingOverlay()
        stopTracking()
        serviceLifecycleOwner?.onPause()
        serviceLifecycleOwner?.onDestroy()
        serviceScope.cancel()
    }
}

@Composable
fun BlockingScreenOverlayContent(
    packageName: String,
    onExitClicked: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF90F172A)), // Deep premium dark background
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Minimal Academic Icon
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = "Study focus warning",
                tint = Color(0xFFEF4444),
                modifier = Modifier.size(56.dp)
            )

            Text(
                text = "Focused Study",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                text = "$packageName is locked by Study Guard to keep your attention on studies.",
                fontSize = 14.sp,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onExitClicked,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text(
                    text = "Close App",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
