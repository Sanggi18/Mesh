package com.example.ui.player.style

import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.model.NowPlayingTheme
import com.example.core.model.Song
import com.example.core.util.ArtworkPaletteExtractor
import com.example.ui.common.components.AlbumArtwork
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
fun NowPlayingThemeContainer(
    theme: NowPlayingTheme,
    song: Song?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val bgColor = MaterialTheme.colorScheme.background
    val defaultPrimary = MaterialTheme.colorScheme.primary
    val isDark = bgColor.luminance() <= 0.5f

    val initialColors = remember(song?.id, isDark, defaultPrimary, bgColor) {
        ArtworkPaletteExtractor.getCachedColors(song?.id, isDark)
            ?: ArtworkPaletteExtractor.defaultColors(
                primary = defaultPrimary,
                darkBg = bgColor,
                lightBg = bgColor,
                isDark = isDark
            )
    }

    val artworkColors = produceState(
        initialValue = initialColors,
        key1 = song?.id,
        key2 = theme,
        key3 = isDark
    ) {
        if ((theme == NowPlayingTheme.COLOR || theme == NowPlayingTheme.MESH_GRADIENT) && song != null) {
            value = ArtworkPaletteExtractor.extractColors(context, song, isDarkTheme = isDark)
        }
    }

    val currentContainerBg = when (theme) {
        NowPlayingTheme.DEFAULT -> Color.Transparent
        NowPlayingTheme.COLOR -> artworkColors.value.getSurface(isDark)
        NowPlayingTheme.MESH_GRADIENT -> Color(0xFF09090B)
        NowPlayingTheme.BLUR,
        NowPlayingTheme.BLUR_2 -> bgColor
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(currentContainerBg)
    ) {
        when (theme) {
            NowPlayingTheme.DEFAULT, NowPlayingTheme.COLOR -> Unit

            NowPlayingTheme.BLUR -> {
                if (song != null) {
                    val primaryUri = song.contentUri.takeIf { it != Uri.EMPTY } ?: song.artworkUri.takeIf { it != Uri.EMPTY }
                    val fallbackUri = if (primaryUri == song.contentUri) song.artworkUri.takeIf { it != Uri.EMPTY } else null

                    val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier.fillMaxSize().blur(32.dp)
                    } else {
                        Modifier.fillMaxSize()
                    }

                    AlbumArtwork(
                        artworkUri = primaryUri,
                        fallbackUri = fallbackUri,
                        dataPath = song.dataPath,
                        contentDescription = null,
                        modifier = blurModifier,
                        targetSizePx = 500,
                        shape = RectangleShape
                    )

                    val scrimAlpha = if (isDark) 0.55f else 0.60f

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(bgColor.copy(alpha = scrimAlpha))
                    )
                }
            }

            NowPlayingTheme.BLUR_2 -> {
                if (song != null) {
                    val primaryUri = song.contentUri.takeIf { it != Uri.EMPTY } ?: song.artworkUri.takeIf { it != Uri.EMPTY }
                    val fallbackUri = if (primaryUri == song.contentUri) song.artworkUri.takeIf { it != Uri.EMPTY } else null

                    val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.45f
                                scaleY = 1.45f
                            }
                            .blur(65.dp)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.45f
                                scaleY = 1.45f
                            }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AlbumArtwork(
                            artworkUri = primaryUri,
                            fallbackUri = fallbackUri,
                            dataPath = song.dataPath,
                            contentDescription = null,
                            modifier = blurModifier,
                            targetSizePx = 500,
                            shape = RectangleShape
                        )
                    }

                    val scrimColors = if (isDark) {
                        listOf(
                            bgColor.copy(alpha = 0.10f),
                            bgColor.copy(alpha = 0.30f),
                            bgColor.copy(alpha = 0.65f)
                        )
                    } else {
                        listOf(
                            bgColor.copy(alpha = 0.15f),
                            bgColor.copy(alpha = 0.35f),
                            bgColor.copy(alpha = 0.65f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(scrimColors))
                    )
                }
            }

            NowPlayingTheme.MESH_GRADIENT -> {
                AnimatedArtworkMeshGradient(
                    song = song,
                    colors = artworkColors.value.meshColors,
                    baseSurface = artworkColors.value.getSurface(isDark)
                )
            }
        }

        content()
    }
}

@Composable
private fun AnimatedArtworkMeshGradient(
    song: Song?,
    colors: List<Color>,
    baseSurface: Color
) {
    val c0Target = colors.getOrElse(0) { MaterialTheme.colorScheme.primary }
    val c1Target = colors.getOrElse(1) { MaterialTheme.colorScheme.secondary }
    val c2Target = colors.getOrElse(2) { MaterialTheme.colorScheme.tertiary }
    val c3Target = colors.getOrElse(3) { baseSurface }

    val color0 by animateColorAsState(c0Target, tween(700), label = "mesh_c0")
    val color1 by animateColorAsState(c1Target, tween(700), label = "mesh_c1")
    val color2 by animateColorAsState(c2Target, tween(700), label = "mesh_c2")
    val color3 by animateColorAsState(c3Target, tween(700), label = "mesh_c3")
    val animatedBase by animateColorAsState(baseSurface, tween(700), label = "mesh_base")

    val infiniteTransition = rememberInfiniteTransition(label = "mesh_gradient_motion")
    val baseRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 42_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mesh_base_rot"
    )
    val counterRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mesh_counter_rot"
    )
    val driftPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mesh_drift_phase"
    )

    val containerBlurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier
            .fillMaxSize()
            .blur(
                radius = 64.dp,
                edgeTreatment = BlurredEdgeTreatment.Rectangle
            )
    } else {
        Modifier.fillMaxSize()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08080A))
    ) {
        Box(
            modifier = containerBlurModifier,
            contentAlignment = Alignment.Center
        ) {
            if (song != null) {
                val primaryUri = song.contentUri.takeIf { it != Uri.EMPTY } ?: song.artworkUri.takeIf { it != Uri.EMPTY }
                val fallbackUri = if (primaryUri == song.contentUri) song.artworkUri.takeIf { it != Uri.EMPTY } else null

                // Layer 1: Zoomed, silky-blurred base artwork wave gliding slowly clockwise (140px for zero lag)
                AlbumArtwork(
                    artworkUri = primaryUri,
                    fallbackUri = fallbackUri,
                    dataPath = song.dataPath,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.72f
                            scaleY = 1.72f
                            rotationZ = baseRotation
                            translationX = cos(driftPhase) * 24f
                            translationY = sin(driftPhase) * 24f
                        },
                    targetSizePx = 140,
                    shape = RectangleShape
                )

                // Layer 2: Larger counter-rotating diagonal fold layer matching Apple Music fluid mesh
                AlbumArtwork(
                    artworkUri = primaryUri,
                    fallbackUri = fallbackUri,
                    dataPath = song.dataPath,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 2.05f
                            scaleY = 2.05f
                            rotationZ = counterRotation
                            translationX = sin(driftPhase) * -48f
                            translationY = cos(driftPhase) * 48f
                            alpha = 0.58f
                        },
                    targetSizePx = 140,
                    shape = RectangleShape
                )
            } else {
                // Fallback when no song is loaded
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val maxDim = max(w, h)
                    drawRect(color = animatedBase)
                    val p0 = Offset(w * (0.30f + 0.16f * cos(driftPhase)), h * (0.28f + 0.14f * sin(driftPhase)))
                    val p1 = Offset(w * (0.72f + 0.14f * sin(driftPhase)), h * (0.68f + 0.14f * cos(driftPhase)))
                    drawCircle(Brush.radialGradient(listOf(color0, Color.Transparent), p0, maxDim * 0.68f), maxDim * 0.68f, p0)
                    drawCircle(Brush.radialGradient(listOf(color1, Color.Transparent), p1, maxDim * 0.68f), maxDim * 0.68f, p1)
                    drawCircle(Brush.radialGradient(listOf(color2.copy(alpha = 0.65f), Color.Transparent), Offset(w * 0.5f, h * 0.5f), maxDim * 0.72f), maxDim * 0.72f, Offset(w * 0.5f, h * 0.5f))
                    drawCircle(Brush.radialGradient(listOf(color3.copy(alpha = 0.55f), Color.Transparent), Offset(w * 0.2f, h * 0.8f), maxDim * 0.6f), maxDim * 0.6f, Offset(w * 0.2f, h * 0.8f))
                }
            }
        }

        // Balanced dark vignette scrim so controls, track info, timestamps, and lyrics stay crisp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.22f),
                        0.45f to Color.Black.copy(alpha = 0.30f),
                        1.0f to Color.Black.copy(alpha = 0.46f)
                    )
                )
        )
    }
}
