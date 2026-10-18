package com.yzmm.clox.settings.compose

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import com.yzmm.clox.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Not persisted: leaving the About page keeps it armed, restarting the app resets it. */
class CreatorEasterEggState {
    var enabled by mutableStateOf(false)
    private val running = mutableStateListOf<Long>()
    val runs: List<Long> get() = running
    private var nextId = 0L

    fun trigger() {
        if (!enabled) return
        running.add(++nextId)
    }

    fun finish(id: Long) { running.remove(id) }
}

@Composable
fun CreatorButtonItem(
    modifier: Modifier,
    transformation: SurfaceTransformation?,
    name: String,
    summary: String,
    avatarRes: Int,
    state: CreatorEasterEggState,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    var showDisclaimer by remember { mutableStateOf(false) }

    FilledTonalButton(
        onClick = {
            if (state.enabled) {
                state.trigger()
            } else {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yz-mm.top")))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.creator_no_browser, Toast.LENGTH_SHORT).show()
                }
            }
        },
        onLongClick = { showDisclaimer = true },
        onLongClickLabel = stringResource(R.string.creator_disclaimer_title),
        modifier = modifier,
        transformation = transformation,
        label = { Text(name) },
        secondaryLabel = { Text(summary) },
        icon = {
            Image(
                painter = painterResource(avatarRes),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.LargeIconSize).clip(CircleShape),
            )
        },
    )

    AlertDialog(
        visible = showDisclaimer,
        onDismissRequest = { showDisclaimer = false },
        title = { Text(stringResource(R.string.creator_disclaimer_title)) },
        text = { Text(stringResource(R.string.creator_disclaimer_text)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                state.enabled = true
                showDisclaimer = false
                Toast.makeText(context, R.string.creator_easter_egg_enabled, Toast.LENGTH_SHORT).show()
            })
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { showDisclaimer = false })
        },
    )
}

@Composable
fun CreatorEasterEggOverlay(state: CreatorEasterEggState) {
    state.runs.forEach { id ->
        // Each tap starts its own animation and player; older runs keep playing and fade on their own.
        key(id) { SixSevenOverlay(seed = id, onDismiss = { state.finish(id) }) }
    }
}

private const val FADE_OUT_MS = 650

@Composable
private fun SixSevenOverlay(seed: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var durationMs by remember { mutableLongStateOf(5_000) }
    val opacity = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var ending by remember { mutableStateOf(false) }
    val dismiss: () -> Unit = {
        if (!ending) {
            ending = true
            scope.launch {
                opacity.animateTo(0f, tween(FADE_OUT_MS))
                onDismiss()
            }
        }
    }
    BackHandler(onBack = dismiss)

    DisposableEffect(context, lifecycle) {
        val sound = runCatching {
            MediaPlayer().apply {
                try {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    context.resources.openRawResourceFd(R.raw.six_seven).use { fd ->
                        setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    }
                    setVolume(0.65f, 0.65f)
                    prepare()
                    durationMs = maxOf(3_600L, duration.toLong() + 700L)
                    start()
                } catch (failure: Exception) {
                    release()
                    throw failure
                }
            }
        }.getOrNull()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                runCatching { sound?.pause() }
                onDismiss()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            sound?.release()
        }
    }

    LaunchedEffect(durationMs) {
        delay((durationMs - FADE_OUT_MS).coerceAtLeast(0))
        dismiss()
    }

    FallingSixSeven(
        Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }.clearAndSetSemantics { },
        seed = seed,
    )
}

private data class FallingItem(
    val kind: Int,
    val x: Float,
    val delayMs: Long,
    val fallMs: Long,
    val size: Int,
    val tilt: Float,
    val drift: Float,
)

@Composable
private fun FallingSixSeven(modifier: Modifier = Modifier, seed: Long) {
    val items = remember(seed) {
        val random = Random(67L + seed)
        List(24) { index ->
            FallingItem(
                kind = index % 3,
                x = ((index % 6) + random.nextFloat()) / 6f,
                delayMs = (index / 6) * 450L + random.nextLong(250),
                fallMs = random.nextLong(1_800, 3_000),
                size = random.nextInt(32, 51),
                tilt = random.nextFloat() * 30f - 15f,
                drift = random.nextFloat() * 24f - 12f,
            )
        }
    }
    var elapsedMs by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { elapsedMs = (it - start) / 1_000_000 }
        }
    }
    val density = LocalDensity.current
    val firstImage = painterResource(R.drawable.easter_egg_67_1)
    val secondImage = painterResource(R.drawable.easter_egg_67_2)
    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        items.forEach { item ->
            val sizePx = with(density) { item.size.dp.toPx() }
            val driftPx = with(density) { item.drift.dp.toPx() }
            val falling = Modifier.graphicsLayer {
                // Everything starts off-screen and enters in batches until the audio ends.
                val age = elapsedMs - item.delayMs
                val progress = if (age < 0) 0f else (age % item.fallMs).toFloat() / item.fallMs
                alpha = if (age < 0) 0f else ((1f - progress) / 0.3f).coerceIn(0f, 1f)
                translationX = item.x * (widthPx - sizePx).coerceAtLeast(0f) + driftPx * progress
                translationY = -sizePx * 1.5f + (heightPx + sizePx * 3f) * progress
                rotationZ = item.tilt * (progress * 2f - 1f)
            }
            if (item.kind == 2) {
                Text(
                    "⁶🤷⁷",
                    modifier = falling,
                    color = Color.White,
                    fontSize = (item.size * 0.55f).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            } else {
                Image(
                    painter = if (item.kind == 0) firstImage else secondImage,
                    contentDescription = null,
                    modifier = falling.size(item.size.dp),
                )
            }
        }
    }
}
