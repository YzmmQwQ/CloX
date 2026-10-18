package com.yzmm.clox.settings.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScrollProgress
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.TransformedContainerPainterScope
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.yzmm.clox.ui.theme.isRoundScreen

/**
 * Standard Wear Material 3 settings page: [ScreenScaffold] + [TransformingLazyColumn]
 * with snap fling/rotary behavior. Rows are [FilledTonalButton]s by default (neutral
 * containers, accent reserved for selected/primary elements), as in MaterialNCM.
 * On square screens the round-edge height/scale transformation is skipped.
 */
@Composable
fun WearSettingsScreen(
    title: String? = null,
    content: SettingsListScope.() -> Unit,
) {
    val context = LocalContext.current
    val round = isRoundScreen()
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(
        scrollState = columnState,
        scrollIndicator = {
            if (!LocalScrollCaptureInProgress.current) {
                ScrollIndicator(columnState)
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding,
            flingBehavior = TransformingLazyColumnDefaults.snapFlingBehavior(columnState),
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(columnState),
            modifier = Modifier.fillMaxSize(),
        ) {
            val scope = SettingsListScope(this, transformationSpec, context, round)
            if (!title.isNullOrBlank()) scope.Title(title)
            scope.content()
        }
    }
}

/** List DSL with the M3 item transformations (round screens only) and content padding. */
class SettingsListScope internal constructor(
    private val list: TransformingLazyColumnScope,
    private val spec: TransformationSpec,
    private val context: android.content.Context,
    private val round: Boolean,
) {
    /** Resolve a string outside composition (the list DSL is not composable). */
    fun res(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    fun resArray(id: Int): Array<String> = context.resources.getStringArray(id)

    @Composable
    private fun TransformingLazyColumnItemScope.itemModifier(): Modifier =
        Modifier
            .fillMaxWidth()
            .then(if (round) Modifier.transformedHeight(this, spec) else Modifier)
            .minimumVerticalContentPadding(
                ButtonDefaults.minimumVerticalListContentPadding)

    private fun TransformingLazyColumnItemScope.surface(): SurfaceTransformation? =
        if (round) SurfaceTransformation(spec) else null

    /** Centered page title (ListHeader). */
    fun Title(text: String) {
        list.item {
            ListHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (round) Modifier.transformedHeight(this, spec) else Modifier)
                    .minimumVerticalContentPadding(
                        ListHeaderDefaults.minimumTopListContentPadding),
                transformation = surface(),
            ) {
                Text(
                    text = text,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /** Left-aligned section label (ListSubHeader). */
    fun SectionHeader(text: String) {
        list.item {
            ListSubHeader(
                modifier = itemModifier(),
                transformation = surface(),
            ) {
                Text(text = text)
            }
        }
    }

    fun SettingsButton(
        title: String,
        summary: String? = null,
        enabled: Boolean = true,
        primary: Boolean = false,
        icon: (@Composable () -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        list.item {
            val modifier = itemModifier()
            val transformation = surface()
            val iconSlot: (@Composable BoxScope.() -> Unit)? = icon?.let { { it() } }
            val secondary: (@Composable RowScope.() -> Unit)? = summary?.let { s ->
                {
                    Text(
                        text = s,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (primary) {
                Button(
                    onClick = onClick,
                    modifier = modifier,
                    enabled = enabled,
                    transformation = transformation,
                    onLongClick = onLongClick,
                    icon = iconSlot,
                    secondaryLabel = secondary,
                    label = { Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            } else {
                FilledTonalButton(
                    onClick = onClick,
                    modifier = modifier,
                    enabled = enabled,
                    transformation = transformation,
                    onLongClick = onLongClick,
                    icon = iconSlot,
                    secondaryLabel = secondary,
                    label = { Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }

    fun SettingsSwitch(
        title: String,
        summary: String? = null,
        checked: Boolean,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        list.item {
            SwitchButton(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = itemModifier(),
                enabled = enabled,
                transformation = surface(),
                secondaryLabel = summary?.let { s ->
                    @Composable {
                        Text(
                            text = s,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                label = { Text(text = title) },
            )
        }
    }

    fun CreatorButton(
        name: String,
        summary: String,
        avatarRes: Int,
        state: CreatorEasterEggState,
        onOpen: () -> Unit,
    ) {
        list.item {
            CreatorButtonItem(
                modifier = itemModifier(),
                transformation = surface(),
                name = name,
                summary = summary,
                avatarRes = avatarRes,
                state = state,
                onOpen = onOpen,
            )
        }
    }

    fun SettingsRadio(title: String, selected: Boolean, onSelect: () -> Unit) {
        list.item {
            RadioButton(
                selected = selected,
                onSelect = onSelect,
                modifier = itemModifier(),
                transformation = surface(),
                label = { Text(text = title) },
            )
        }
    }

    fun SettingsSlider(
        title: String,
        value: Int,
        range: IntRange,
        valueText: String,
        onChange: (Int) -> Unit,
    ) {
        list.item {
            Column(modifier = itemModifier().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    text = "$title — $valueText",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = value.coerceIn(range.first, range.last),
                    onValueChange = onChange,
                    valueProgression = range,
                )
            }
        }
    }

    fun SettingsSliderFloat(
        title: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        steps: Int,
        valueText: String,
        onChange: (Float) -> Unit,
    ) {
        list.item {
            Column(modifier = itemModifier().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    text = "$title — $valueText",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = value.coerceIn(range.start, range.endInclusive),
                    onValueChange = onChange,
                    steps = steps,
                    valueRange = range,
                )
            }
        }
    }

    /** Small explanatory text between rows. */
    fun SettingsNote(text: String) {
        list.item {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = itemModifier().padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }

    /** Escape hatch for custom rows (keeps the standard height transformation/padding). */
    fun SettingsItem(content: @Composable () -> Unit) {
        list.item {
            Column(modifier = itemModifier()) {
                content()
            }
        }
    }
}

/** Leaves items at their measured size on square screens. */
private object NoTransformationSpec : TransformationSpec {
    override fun getTransformedHeight(
        measuredHeight: Int,
        scrollProgress: TransformingLazyColumnItemScrollProgress,
    ): Int = measuredHeight

    override fun GraphicsLayerScope.applyContentTransformation(
        scrollProgress: TransformingLazyColumnItemScrollProgress,
    ) = Unit

    override fun GraphicsLayerScope.applyContainerTransformation(
        scrollProgress: TransformingLazyColumnItemScrollProgress,
    ) = Unit

    override fun TransformedContainerPainterScope.createTransformedContainerPainter(
        painter: Painter,
        shape: Shape,
        border: BorderStroke?,
    ): Painter = painter
}

/** Dialogs keep Wear M3's edge scaling on round screens and stay flat on square ones. */
@Composable
fun rememberDialogTransformationSpec(): TransformationSpec {
    val spec = rememberTransformationSpec()
    return if (isRoundScreen()) spec else NoTransformationSpec
}

/** Single-choice Wear M3 dialog used by every picker row. */
@Composable
fun SettingsPickerDialog(
    title: String,
    labels: Array<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        transformationSpec = rememberDialogTransformationSpec(),
    ) {
        labels.forEachIndexed { index, label ->
            item {
                Button(
                    onClick = { onSelect(index) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (index == selectedIndex) {
                        ButtonDefaults.filledTonalButtonColors()
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    label = { Text(text = label) },
                )
            }
        }
    }
}

@Composable
fun SettingsTextDialog(
    title: String,
    initial: String,
    numeric: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        transformationSpec = rememberDialogTransformationSpec(),
    ) {
        item {
            BasicTextField(
                value = text,
                onValueChange = { text = if (numeric) it.filter(Char::isDigit) else it },
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        item {
            Button(
                onClick = { onConfirm(text) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = stringResource(android.R.string.ok)) },
            )
        }
    }
}

@Composable
fun SettingsConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        transformationSpec = rememberDialogTransformationSpec(),
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                label = { Text(text = confirmLabel) },
            )
        },
    )
}

@Composable
fun SettingsInfoDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = message) },
        transformationSpec = rememberDialogTransformationSpec(),
        confirmButton = {
            Button(
                onClick = onDismiss,
                label = { Text(text = stringResource(android.R.string.ok)) },
            )
        },
    )
}
