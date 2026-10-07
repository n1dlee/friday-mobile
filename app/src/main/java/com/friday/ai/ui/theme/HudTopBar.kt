package com.friday.ai.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The top of every screen: navigation on the left, a tracked title and a
 * lit status line in the middle, actions on the right, and a hairline that
 * fades out toward the edges.
 */
@Composable
fun HudTopBar(
    title: String,
    modifier: Modifier = Modifier,
    status: HudStatus? = null,
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
        Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) { navigation() }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = HudBrandStyle, color = OnBackground)
                if (status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        StatusDot(status.color, live = status.live, size = 6.dp)
                        Spacer(Modifier.width(2.dp))
                        Text(status.text.uppercase(), style = HudLabelStyle, color = status.color)
                    }
                }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, ArcCyan.copy(alpha = 0.35f), Color.Transparent)
                    )
                )
        )
    }
}
