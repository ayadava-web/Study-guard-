package com.example.appblocker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Data class representation of installed packages
data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable? = null
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppBlockerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppBlockerDashboardScreen()
                }
            }
        }
    }
}

@Composable
fun AppBlockerDashboardScreen() {
    val context = LocalContext.current
    val pm = context.packageManager

    // Settings States
    val sharedPrefs = remember { context.getSharedPreferences("AppBlockerPrefs", Context.MODE_PRIVATE) }
    var blockedApps by remember {
        mutableStateOf(sharedPrefs.getStringSet("blocked_apps", emptySet()) ?: emptySet())
    }
    var limitMinutes by remember {
        mutableStateOf(sharedPrefs.getFloat("timer_limit_minutes", 4.0f))
    }

    // Permission monitoring
    var isAccessibilityGranted by remember { mutableStateOf(false) }
    var isOverlayGranted by remember { mutableStateOf(false) }

    // Search query
    var searchQuery by remember { mutableStateOf("") }

    // Read installed launcher apps
    val appList = remember { getInstalledLauncherApps(pm) }
    val filteredAppList = remember(searchQuery, appList) {
        if (searchQuery.isBlank()) {
            appList
        } else {
            appList.filter { it.name.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
        }
    }

    // Checking permissions periodically when app returns to foreground
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityGranted = isAccessibilityServiceEnabled(context, AppBlockerService::class.java)
            isOverlayGranted = Settings.canDrawOverlays(context)
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp)
    ) {
        // App Header - Plain Clean Typo
        Text(
            text = "Study Guard",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A)
        )
        Text(
            text = "Set limit and select apps to stay focused.",
            fontSize = 14.sp,
            color = Color(0xFF64748B),
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // Simple Permissions warning: Only show compact notice if setup is needed
        if (!isAccessibilityGranted || !isOverlayGranted) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFEF2F2), shape = RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "Requires Setup",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF991B1B)
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (!isAccessibilityGranted) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("• Accessibility Service", fontSize = 13.sp, color = Color(0xFF7F1D1D))
                        Text("Setup ➔", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF991B1B))
                    }
                }
                if (!isOverlayGranted) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("• Draw Over Other Apps", fontSize = 13.sp, color = Color(0xFF7F1D1D))
                        Text("Setup ➔", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF991B1B))
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Timer Slider panel: very minimal
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Screen Time Limit",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = Color(0xFF1E293B)
            )
            val roundedMin = Math.round(limitMinutes)
            Text(
                text = if (roundedMin == 1) "1 minute" else "$roundedMin mins",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2563EB)
            )
        }
        
        Slider(
            value = limitMinutes,
            onValueChange = { minutes ->
                limitMinutes = minutes
                sharedPrefs.edit().putFloat("timer_limit_minutes", minutes).apply()
            },
            valueRange = 1.0f..60.0f,
            steps = 59,
            colors = SliderDefaults.colors(
                activeTrackColor = Color(0xFF2563EB),
                inactiveTrackColor = Color(0xFFF1F5F9),
                thumbColor = Color(0xFF1E3A8A)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Search text field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search apps...", fontSize = 14.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFFF8FAFC),
                unfocusedContainerColor = Color(0xFFF8FAFC),
                focusedBorderColor = Color(0xFFCBD5E1),
                unfocusedBorderColor = Color(0xFFE2E8F0)
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // App list count and Clear All option
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${blockedApps.size} blocked",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B)
            )
            if (blockedApps.isNotEmpty()) {
                Text(
                    text = "Clear All",
                    color = Color(0xFFDC2626),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        blockedApps = emptySet()
                        sharedPrefs.edit().putStringSet("blocked_apps", emptySet()).apply()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Lazy List for visible applications
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredAppList) { app ->
                val isBlocked = blockedApps.contains(app.packageName)
                AppSelectionRow(
                    app = app,
                    isBlocked = isBlocked,
                    onToggle = {
                        val newBlocked = if (isBlocked) {
                            blockedApps - app.packageName
                        } else {
                            blockedApps + app.packageName
                        }
                        blockedApps = newBlocked
                        sharedPrefs.edit().putStringSet("blocked_apps", newBlocked).apply()
                    }
                )
            }

            if (filteredAppList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No apps found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppSelectionRow(
    app: AppInfo,
    isBlocked: Boolean,
    onToggle: () -> Unit
) {
    val imageBitmap = remember(app.icon) {
        app.icon?.let { drawableToBitmap(it).asImageBitmap() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFFF1F5F9)),
            contentAlignment = Alignment.Center
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "${app.name} icon",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.HourglassEmpty,
                    contentDescription = "Default app",
                    tint = Color.LightGray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = app.name,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = Color(0xFF0F172A)
            )
            Text(
                text = app.packageName,
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )
        }

        Switch(
            checked = isBlocked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF10B981)
            )
        )
    }
}

// Utility to fetch visible application launcher entry points on the Android device
private fun getInstalledLauncherApps(pm: PackageManager): List<AppInfo> {
    val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolveInfoList = pm.queryIntentActivities(launcherIntent, 0)
    return resolveInfoList.map { info ->
        AppInfo(
            name = info.loadLabel(pm).toString(),
            packageName = info.activityInfo.packageName,
            icon = info.loadIcon(pm)
        )
    }.distinctBy { it.packageName }.sortedBy { it.name }
}

// Draw a portable bitmap representation of standard Android app icon drawables
private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is android.graphics.drawable.BitmapDrawable) {
        if (drawable.bitmap != null) {
            return drawable.bitmap
        }
    }
    val bitmap = if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    } else {
        Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
    }
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

// Validate whether our application blocker service has been actively mapped in system Settings
fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val expectedComponentName = ComponentName(context, serviceClass)
    val enabledSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = ComponentName.unflattenFromString(componentNameString)
        if (expectedComponentName == enabledService) {
            return true
        }
    }
    return false
}

// Theme wrapper to structure styling properties
@Composable
fun AppBlockerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1E3A8A),
            background = Color(0xFFF8FAFC),
            onBackground = Color(0xFF1E293B)
        ),
        content = content
    )
}
