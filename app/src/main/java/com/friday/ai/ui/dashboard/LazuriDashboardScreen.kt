package com.friday.ai.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.core.LazuriGraph
import com.friday.ai.core.LazuriGraph.NodeKind
import kotlin.math.hypot
import org.koin.androidx.compose.koinViewModel

/**
 * Node interiors are darker than the canvas so the rim reads as an emitted
 * edge and the label stays legible over the halo behind it.
 */
private val NodeBody = Color(0xFF06111A)

/**
 * A map of what Friday knows: you in the middle, what you've told it around
 * you, and dates branching into the conversations held on them.
 *
 * Tap a bubble to open it; tap a leaf to read the full value. Drag to pan,
 * pinch to zoom — with enough history the map outgrows the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LazuriDashboardScreen(
    onNavigateBack: () -> Unit,
    viewModel: LazuriDashboardViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Rebuilt on every draw, then used to turn a tap into a node.
    val hitTargets = remember { mutableListOf<Triple<LazuriGraph.Node, Offset, Float>>() }

    val colors = NodeColors(
        root = MaterialTheme.colorScheme.primary,
        category = MaterialTheme.colorScheme.tertiary,
        fact = MaterialTheme.colorScheme.secondary,
        date = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
        session = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
        note = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        edge = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
        label = MaterialTheme.colorScheme.onSurface
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ПАМЯТЬ", style = com.friday.ai.ui.theme.HudBrandStyle)
                        Text(
                            "ФАКТОВ ${state.factCount} · РАЗГОВОРОВ ${state.sessionCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.onCollapseAll()
                        scale = 1f
                        offset = Offset.Zero
                    }) {
                        Icon(Icons.Filled.UnfoldLess, contentDescription = "Collapse all")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val root = state.root
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                root == null || root.children.isEmpty() -> EmptyMap(Modifier.align(Alignment.Center))

                else -> androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.4f, 3f)
                                offset += pan
                            }
                        }
                        .pointerInput(state.expanded) {
                            detectTapGestures { tap ->
                                hitTargets
                                    .firstOrNull { (_, centre, r) ->
                                        hypot(tap.x - centre.x, tap.y - centre.y) <= r
                                    }
                                    ?.let { (node, _, _) -> viewModel.onNodeTapped(node) }
                            }
                        }
                ) {
                    hitTargets.clear()
                    val centre = Offset(
                        size.width / 2f + offset.x,
                        size.height / 2f + offset.y
                    )
                    val baseRadius = 46f * scale

                    drawBranch(
                        node = root,
                        centre = centre,
                        depth = 0,
                        baseRadius = baseRadius,
                        scale = scale,
                        expanded = state.expanded,
                        colors = colors,
                        hitTargets = hitTargets
                    )
                }
            }

            state.selected?.let { node ->
                NodeDetailCard(
                    node = node,
                    onDismiss = viewModel::onDismissDetail,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }
}

private data class NodeColors(
    val root: Color,
    val category: Color,
    val fact: Color,
    val date: Color,
    val session: Color,
    val note: Color,
    val edge: Color,
    val label: Color
)

private fun NodeColors.forKind(kind: NodeKind): Color = when (kind) {
    NodeKind.ROOT -> root
    NodeKind.CATEGORY -> category
    NodeKind.FACT -> fact
    NodeKind.DATE -> date
    NodeKind.SESSION -> session
    NodeKind.NOTE -> note
}

/**
 * Draws a node and, when it's open, its children on a ring around it.
 * Recursion depth is bounded by how much the user has expanded.
 */
private fun DrawScope.drawBranch(
    node: LazuriGraph.Node,
    centre: Offset,
    depth: Int,
    baseRadius: Float,
    scale: Float,
    expanded: Set<String>,
    colors: NodeColors,
    hitTargets: MutableList<Triple<LazuriGraph.Node, Offset, Float>>,
    startAngle: Float = -Math.PI.toFloat() / 2f,
    sweep: Float = (2 * Math.PI).toFloat()
) {
    val radius = LazuriGraph.radiusForDepth(baseRadius, depth)
    val isOpen = expanded.contains(node.id)

    if (isOpen && node.hasChildren) {
        val childRadius = LazuriGraph.radiusForDepth(baseRadius, depth + 1)
        val ring = LazuriGraph.ringRadiusFor(
            count = node.children.size,
            nodeRadius = childRadius,
            minRadius = radius + childRadius * 3.2f
        )

        val placed = LazuriGraph.layoutRing(
            children = node.children,
            centreX = centre.x,
            centreY = centre.y,
            ringRadius = ring,
            nodeRadius = childRadius,
            startAngle = startAngle,
            sweep = sweep
        )

        placed.forEachIndexed { index, positioned ->
            val childCentre = Offset(positioned.x, positioned.y)
            drawLine(
                color = colors.edge,
                start = centre,
                end = childCentre,
                strokeWidth = 1.5f * scale
            )
            // Children fan outward from their parent rather than wrapping
            // back over the centre, which keeps deep branches readable.
            val angleToChild = Math.atan2(
                (childCentre.y - centre.y).toDouble(),
                (childCentre.x - centre.x).toDouble()
            ).toFloat()
            drawBranch(
                node = positioned.node,
                centre = childCentre,
                depth = depth + 1,
                baseRadius = baseRadius,
                scale = scale,
                expanded = expanded,
                colors = colors,
                hitTargets = hitTargets,
                startAngle = angleToChild - (Math.PI / 3).toFloat(),
                sweep = (2 * Math.PI / 3).toFloat()
            )
        }
    }

    val fill = colors.forKind(node.kind)
    // Three layers so a node reads as a lit bead on the graph rather than a
    // flat disc: a soft halo, a dark body to keep the label legible, and a
    // bright rim. The root gets a second halo — it is the light source.
    if (depth == 0) {
        drawCircle(color = fill.copy(alpha = 0.10f), radius = radius * 1.9f, center = centre)
    }
    drawCircle(color = fill.copy(alpha = 0.16f), radius = radius * 1.35f, center = centre)
    drawCircle(color = NodeBody, radius = radius, center = centre)
    drawCircle(
        color = fill,
        radius = radius,
        center = centre,
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = (if (depth == 0) 2.6f else 1.8f) * scale
        )
    )

    hitTargets += Triple(node, centre, radius)

    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            color = colors.label.toArgb()
            textSize = (if (depth == 0) 15f else 11f) * scale
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = depth == 0
        }
        val label = LazuriGraph.truncate(node.label, if (depth == 0) 18 else 14)
        canvas.nativeCanvas.drawText(
            label, centre.x, centre.y + paint.textSize / 3f, paint
        )

        node.detail?.takeIf { depth > 0 && node.kind != NodeKind.NOTE }?.let { detail ->
            val small = android.graphics.Paint(paint).apply {
                textSize = 9f * scale
                alpha = 160
                isFakeBoldText = false
            }
            canvas.nativeCanvas.drawText(
                LazuriGraph.truncate(detail, 12),
                centre.x,
                centre.y + radius + small.textSize + 2f,
                small
            )
        }
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

@Composable
private fun NodeDetailCard(
    node: LazuriGraph.Node,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    node.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    node.kind.name.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            node.detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (node.hasChildren) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${node.children.size} inside — tap the bubble to open",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Dismiss",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(Color.Transparent)
                    .padding(4.dp)
                    .let { m ->
                        m.then(
                            Modifier.pointerInput(Unit) {
                                detectTapGestures { onDismiss() }
                            }
                        )
                    }
            )
        }
    }
}

@Composable
private fun EmptyMap(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Nothing mapped yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Talk to Friday and it will start filling this in — what you like, " +
                "what you've told it, and the conversations it came from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
