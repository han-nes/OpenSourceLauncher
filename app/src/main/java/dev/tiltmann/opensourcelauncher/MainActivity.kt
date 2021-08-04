package dev.tiltmann.opensourcelauncher

import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.*
import android.graphics.drawable.*
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val installedAppsViewModel: InstalledAppsViewModel by viewModels()

    @ExperimentalFoundationApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OpenSourceLauncherTheme {
                Surface(color = MaterialTheme.colors.background) {
                    MainScreen()
                }
            }
        }
    }

    @Composable
    private fun MainScreen() {
        val appListScrollState = rememberScrollState()

        val groupedApps = installedAppsViewModel.groupedApps.observeAsState().value
        if (groupedApps == null) {
            Text(modifier = Modifier.padding(8.dp), text = "Loading Apps")
            return
        }

        Row {
            ScrollableAppList(Modifier.weight(1f), groupedApps, appListScrollState)
            DraggableScrollbar(appListScrollState)
        }
    }

    @Composable
    private fun DraggableScrollbar(appListScrollState: ScrollState) {

        val scope = rememberCoroutineScope()

        Box(
            Modifier
                .width(50.dp)
                .fillMaxHeight()
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            appListScrollState.scrollBy(delta * 6)
                        }
                    },
                )
                .background(Color.White.copy(alpha = 0.2f)),
        )

    }

    @Composable
    private fun ScrollableAppList(
        modifier: Modifier = Modifier,
        groupedApps: Map<Char, List<InstalledAppInfo>>,
        scrollState: ScrollState
    ) {

        // Sadly, LazyColumn is having some problems, is being too laggy
        Column(modifier.verticalScroll(scrollState)) {

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
