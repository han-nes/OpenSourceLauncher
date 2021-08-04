package dev.tiltmann.opensourcelauncher

import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.*
import android.graphics.drawable.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.liveData
import dev.tiltmann.opensourcelauncher.ui.theme.OpenSourceLauncherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    @ExperimentalFoundationApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val installedAppsViewModel by viewModels<InstalledAppsViewModel>()

        setContent {
            OpenSourceLauncherTheme {
                Surface(color = MaterialTheme.colors.background) {
                    Row {
                        AppListComposable(installedAppsViewModel)
                    }
                }
            }
        }
    }

    @Composable
    private fun DraggableScrollbar() {
        var offset by remember { mutableStateOf(0f) }

        Box(
            Modifier
                .size(150.dp)
                .scrollable(
                    orientation = Orientation.Vertical,
                    // Scrollable state: describes how to consume
                    // scrolling delta and update offset
                    state = rememberScrollableState { delta ->
                        offset += delta
                        delta
                    }
                )
                .background(Color.LightGray),
            contentAlignment = Alignment.Center
        ) {
            Text(offset.toString())
        }

    }

    @Composable
    @ExperimentalFoundationApi
    private fun AppListComposable(appsViewModel: InstalledAppsViewModel) {

        val groupedAppsState = appsViewModel.groupedApps.observeAsState()
        val groupedApps = groupedAppsState.value

        Log.e("Hannes", "rendered app list newly")

        if (groupedApps == null) {
            Text(text = "Loading Apps")
            return
        }
        // Sadly, LazyColumn is having some problems, is being to laggy
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for ((char, list) in groupedApps) {
                Text(char.uppercase(), fontWeight = FontWeight.ExtraBold)
                list.forEach {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                if (it.intent == null) return@clickable
                                startActivity(it.intent)
                            }
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        Spacer(Modifier.width(16.dp))
                        Image(bitmap = it.icon, contentDescription = "")
                        Spacer(Modifier.width(16.dp))
                        Text(text = it.label)
                    }
                }
            }
        }
    }
}

class DraggableScrollBarViewModel(application: Application) : AndroidViewModel(application) {
    val lazyListState = LazyListState()
}


data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val intent: Intent?,
    val icon: ImageBitmap
)

class InstalledAppsViewModel(application: Application) : AndroidViewModel(application) {

    private val packageManager = application.packageManager

    val groupedApps = liveData {
        // This is the coroutine!
        val apps = loadApps()
        val appList = processApps(apps)
        val groupedList = appList.groupBy { it.label[0].lowercaseChar() }
        emit(groupedList)
    }

    /**
     * Load apps from system (IO intensive)
     */
    private suspend fun loadApps(): List<ResolveInfo> = withContext(Dispatchers.IO) {
        val template = Intent()
        // Query all activities that are a main entrypoint to an app
        template.action = Intent.ACTION_MAIN
        // and shall be shown in the launcher
        template.addCategory(Intent.CATEGORY_LAUNCHER)

        packageManager.queryIntentActivities(template, 0)
    }

    /**
     * Map system application objects to InstalledAppInfo objects and sort (CPU intensive)
     */
    private suspend fun processApps(apps: List<ResolveInfo>): List<InstalledAppInfo> =
        withContext(Dispatchers.Default) {

            apps.map {
                InstalledAppInfo(
                    it.loadLabel(packageManager).toString(),
                    it.activityInfo.packageName,
                    packageManager.getLaunchIntentForPackage(it.activityInfo.packageName),
                    packageManager.getApplicationIcon(it.activityInfo.packageName).asThemedAppIcon()
                )
            }.sortedBy { it.label.lowercase() }
        }

    /**
     * Apply theming on the loaded ApplicationIcon (CPU intensive)
     */
    private fun Drawable.asThemedAppIcon(): ImageBitmap {
        val iconSize = 96
        val iconShapePath = Path().apply {
            addRoundRect(
                RectF(0f, 0f, iconSize.toFloat(), iconSize.toFloat()),
                32f,
                32f,
                Path.Direction.CCW
            )
        }

        if (this is BitmapDrawable) {

            // Initialize bitmap and canvas with correct size and path
            val newBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(newBitmap)
            canvas.clipPath(iconShapePath)

            // Drawing background
            canvas.drawColor(android.graphics.Color.WHITE)

            // Drawing foreground
            val scale = 0.75
            val newSize = iconSize * scale
            val extraSpace = iconSize - newSize

            val foregroundBitmap = Bitmap.createScaledBitmap(
                this.bitmap,
                newSize.roundToInt(),
                newSize.roundToInt(),
                true
            )

            canvas.drawBitmap(
                foregroundBitmap,
                extraSpace.toFloat() / 2,
                extraSpace.toFloat() / 2,
                this.paint
            )

            return newBitmap.asImageBitmap()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this is AdaptiveIconDrawable) {

            // Initialize bitmap and canvas with correct size and path
            val newBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(newBitmap)
            canvas.clipPath(iconShapePath)

            // Drawing fore- and background
            val scale = 1.25
            val newSize = iconSize * scale
            val extraSpace = newSize - iconSize

            val layerDrawable = LayerDrawable(arrayOf(this.background, this.foreground))
            layerDrawable.setBounds(
                -extraSpace.roundToInt(),
                -extraSpace.roundToInt(),
                newSize.roundToInt(),
                newSize.roundToInt()
            )
            layerDrawable.draw(canvas)

            return newBitmap.asImageBitmap()
        }
        return this.toBitmap().asImageBitmap()
    }
}
