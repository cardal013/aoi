package eu.kanade.tachiyomi.ui.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

enum class TutorialStep { Start, More, Settings, Backup, Account, Done }

enum class TutorialTarget { More, Backup, Account }

object Tutorial {
    val targets = mutableStateMapOf<TutorialTarget, Rect>()
    val actions = mutableMapOf<TutorialTarget, () -> Unit>()
}

fun Modifier.tutorialTarget(target: TutorialTarget): Modifier = composed {
    DisposableEffect(target) {
        onDispose { Tutorial.targets.remove(target) }
    }
    onGloballyPositioned { Tutorial.targets[target] = it.boundsInWindow() }
}

@Composable
fun TutorialAction(target: TutorialTarget, action: () -> Unit) {
    val currentAction by rememberUpdatedState(action)
    DisposableEffect(target) {
        Tutorial.actions[target] = { currentAction() }
        onDispose { Tutorial.actions.remove(target) }
    }
}

private const val STEP_COUNT = 3

@Composable
fun HomeTutorial(
    step: TutorialStep,
    onStepChange: (TutorialStep) -> Unit,
    isMoreTab: Boolean,
    onOpenLibrary: () -> Unit,
    onOpenMore: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LaunchedEffect(step, isMoreTab) {
        when (step) {
            TutorialStep.Start -> {
                onOpenLibrary()
                onStepChange(TutorialStep.More)
            }
            TutorialStep.Settings -> onStepChange(TutorialStep.Backup)
            TutorialStep.Backup, TutorialStep.Account -> if (!isMoreTab) onOpenMore()
            else -> Unit
        }
    }

    when (step) {
        TutorialStep.More -> TutorialOverlay(
            target = Tutorial.targets[TutorialTarget.More],
            title = stringResource(if (isMoreTab) MR.strings.tutorial_more_again else MR.strings.tutorial_more),
            text = stringResource(
                if (isMoreTab) MR.strings.tutorial_more_again_summary else MR.strings.tutorial_more_summary,
            ),
            progress = 1,
            onTargetClick = {
                if (isMoreTab) {
                    onOpenSettings()
                    onStepChange(TutorialStep.Settings)
                } else {
                    onOpenMore()
                }
            },
        )
        TutorialStep.Backup -> if (isMoreTab) {
            TutorialOverlay(
                target = Tutorial.targets[TutorialTarget.Backup],
                title = stringResource(MR.strings.tutorial_backup),
                text = stringResource(MR.strings.tutorial_backup_summary),
                progress = 2,
                primaryText = stringResource(MR.strings.more_backup_import),
                onTargetClick = {
                    onStepChange(TutorialStep.Account)
                    Tutorial.actions[TutorialTarget.Backup]?.invoke()
                },
                onSkip = { onStepChange(TutorialStep.Account) },
            )
        }
        TutorialStep.Account -> if (isMoreTab) {
            TutorialOverlay(
                target = Tutorial.targets[TutorialTarget.Account],
                title = stringResource(MR.strings.tutorial_account),
                text = stringResource(MR.strings.tutorial_account_summary),
                progress = 3,
                primaryText = stringResource(MR.strings.tutorial_account_open),
                onTargetClick = {
                    onStepChange(TutorialStep.Done)
                    Tutorial.actions[TutorialTarget.Account]?.invoke()
                },
                onSkip = { onStepChange(TutorialStep.Done) },
            )
        }
        else -> Unit
    }
}

@Composable
private fun TutorialOverlay(
    target: Rect?,
    title: String,
    text: String,
    progress: Int,
    onTargetClick: () -> Unit,
    primaryText: String? = null,
    onSkip: (() -> Unit)? = null,
) {
    BackHandler(enabled = onSkip != null) { onSkip?.invoke() }

    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val hole = target?.translate(-origin)?.inflate(with(density) { 6.dp.toPx() })
    val currentHole by rememberUpdatedState(hole)
    val currentOnTargetClick by rememberUpdatedState(onTargetClick)

    val pulse by rememberInfiniteTransition(label = "tutorialPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "pulse",
    )
    val ringColor = MaterialTheme.colorScheme.primary

    Layout(
        content = {
            TutorialCard(
                title = title,
                text = text,
                progress = progress,
                primaryText = primaryText,
                onPrimary = onTargetClick,
                onSkip = onSkip,
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (currentHole?.contains(offset) == true) currentOnTargetClick()
                }
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                drawRect(Color.Black.copy(alpha = 0.72f))
                hole?.let {
                    val radius = 16.dp.toPx()
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = it.topLeft,
                        size = it.size,
                        cornerRadius = CornerRadius(radius),
                        blendMode = BlendMode.Clear,
                    )
                    val grow = 12.dp.toPx() * pulse
                    val ring = it.inflate(grow)
                    drawRoundRect(
                        color = ringColor.copy(alpha = 1f - pulse),
                        topLeft = ring.topLeft,
                        size = ring.size,
                        cornerRadius = CornerRadius(radius + grow),
                        style = Stroke(3.dp.toPx()),
                    )
                }
            },
    ) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val cardWidth = minOf(constraints.maxWidth - margin * 2, 420.dp.roundToPx())
        val card = measurables.first().measure(Constraints.fixedWidth(cardWidth))
        val maxY = (constraints.maxHeight - card.height - margin).coerceAtLeast(margin)
        val y = when {
            hole == null -> (constraints.maxHeight - card.height) / 2
            hole.center.y > constraints.maxHeight / 2 -> hole.top.toInt() - margin - card.height
            else -> hole.bottom.toInt() + margin
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            card.place((constraints.maxWidth - card.width) / 2, y.coerceIn(margin, maxY))
        }
    }
}

@Composable
private fun TutorialCard(
    title: String,
    text: String,
    progress: Int,
    primaryText: String?,
    onPrimary: () -> Unit,
    onSkip: (() -> Unit)?,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(MR.strings.tutorial_progress, progress, STEP_COUNT),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (primaryText != null || onSkip != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (onSkip != null) {
                        TextButton(onClick = onSkip) {
                            Text(stringResource(MR.strings.tutorial_skip))
                        }
                    }
                    if (primaryText != null) {
                        Button(onClick = onPrimary) {
                            Text(primaryText)
                        }
                    }
                }
            }
        }
    }
}
