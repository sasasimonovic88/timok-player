package rs.timok.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import rs.timok.player.R
import rs.timok.player.ui.theme.Brand

/**
 * Still background for the movie and series screens: dark brand gradient with a large, barely
 * visible logo on the right, so titles and posters stay easy to read. Static, so it costs nothing
 * after the first frame.
 */
@Composable
fun BrandBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brand.Background)
            .background(
                Brush.radialGradient(
                    listOf(Brand.Primary.copy(alpha = 0.22f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(1600f, 250f),
                    radius = 1100f,
                ),
            )
            .background(
                Brush.radialGradient(
                    listOf(Brand.Accent.copy(alpha = 0.07f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(200f, 1100f),
                    radius = 900f,
                ),
            ),
    ) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 90.dp)
                .size(520.dp)
                .alpha(0.05f),
        )
    }
}
