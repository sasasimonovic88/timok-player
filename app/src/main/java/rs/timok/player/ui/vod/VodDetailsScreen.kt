package rs.timok.player.ui.vod

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import rs.timok.player.player.ExternalPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import rs.timok.player.R
import rs.timok.player.data.db.WatchProgressEntity
import rs.timok.player.domain.Episode
import rs.timok.player.domain.VodDetails
import rs.timok.player.domain.VodKind
import rs.timok.player.ui.components.ProgramProgress
import rs.timok.player.ui.components.TvButton
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand

@Composable
fun VodDetailsScreen(onPlay: (PlayTarget) -> Unit, vm: VodDetailsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Brand.Background)) {
        when (val s = state) {
            DetailsState.Loading -> Text(stringResource(R.string.vod_loading), color = Brand.TextSecondary, modifier = Modifier.align(Alignment.Center))
            is DetailsState.Error -> Text(s.message, color = Brand.Error, modifier = Modifier.align(Alignment.Center))
            is DetailsState.Ready -> Details(s.details, vm, onPlay)
        }
    }
}

@Composable
private fun Details(d: VodDetails, vm: VodDetailsViewModel, onPlay: (PlayTarget) -> Unit) {
    val movieProgress by vm.movieProgress.collectAsStateWithLifecycle()
    val episodeProgress by vm.episodeProgress.collectAsStateWithLifecycle()
    val season by vm.season.collectAsStateWithLifecycle()
    val onWatchlist by vm.onWatchlist.collectAsStateWithLifecycle()
    val firstButton = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val externalAvailable = remember { ExternalPlayer.available(context) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstButton.requestFocus() }
    }

    // Backdrop dimmed towards the text side.
    (d.backdrop ?: d.poster)?.let {
        AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.35f, modifier = Modifier.fillMaxSize())
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(listOf(Brand.Background, Brand.Background.copy(alpha = 0.85f), Color.Transparent)),
        ),
    )

    val isSeries = d.kind == VodKind.SERIES
    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp)) {
        Poster(d.poster, d.name, Modifier.width(if (isSeries) 180.dp else 230.dp))
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 36.dp)) {
            Text(d.name, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                d.year,
                d.rating?.let { "★ $it" },
                d.durationSecs?.takeIf { it > 0 }?.let { stringResource(R.string.vod_minutes, it / 60) },
                d.genre,
            ).joinToString("   ·   ")
            if (meta.isNotEmpty()) Text(meta, color = Brand.TextSecondary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            d.plot?.let {
                Text(it, color = Brand.TextPrimary, fontSize = 16.sp, maxLines = if (isSeries) 3 else 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
            }
            if (!isSeries) {
                d.director?.let { Text(stringResource(R.string.vod_director, it), color = Brand.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
                d.cast?.let { Text(stringResource(R.string.vod_cast, it), color = Brand.TextSecondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            }

            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // The same item the first button plays, handed to another installed player.
                val external: PlayTarget? = if (isSeries) vm.seriesResume(d) else vm.movieTarget(d, resume = true)
                if (isSeries) {
                    val target = vm.seriesResume(d)
                    if (target != null) {
                        val ep = target.episode!!
                        val label = stringResource(R.string.vod_episode_label, ep.season, ep.number)
                        val text = if (target.startMs > 0) stringResource(R.string.vod_resume, formatDuration(target.startMs)) + "  ($label)"
                        else stringResource(R.string.vod_play) + "  ($label)"
                        TvButton(text, onClick = { onPlay(target) }, modifier = Modifier.focusRequester(firstButton))
                    }
                } else {
                    val p = movieProgress
                    if (p != null && !p.finished) {
                        TvButton(
                            stringResource(R.string.vod_resume, formatDuration(p.positionMs)),
                            onClick = { onPlay(vm.movieTarget(d, resume = true)) },
                            modifier = Modifier.focusRequester(firstButton),
                        )
                        TvButton(stringResource(R.string.vod_from_start), onClick = { onPlay(vm.movieTarget(d, resume = false)) })
                    } else {
                        TvButton(stringResource(R.string.vod_play), onClick = { onPlay(vm.movieTarget(d, resume = false)) }, modifier = Modifier.focusRequester(firstButton))
                    }
                }
                TvButton(
                    stringResource(if (onWatchlist) R.string.my_list_on else R.string.my_list_add),
                    onClick = { vm.toggleWatchlist(d) },
                )
                if (external != null && externalAvailable) {
                    TvButton(
                        stringResource(R.string.external_player_open),
                        onClick = {
                            scope.launch {
                                if (!vm.openExternal(external)) {
                                    Toast.makeText(context, R.string.external_player_none, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    )
                }
            }

            if (isSeries && d.seasons.isNotEmpty()) {
                LazyRow(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
                    items(d.seasons, key = { it.number }) { s ->
                        TvClickable(onClick = { vm.selectSeason(s.number) }, selected = s.number == season) {
                            Text(
                                stringResource(R.string.vod_season, s.number),
                                fontSize = 15.sp,
                                color = if (s.number == season) Brand.Accent else Brand.TextPrimary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                val episodes = d.seasons.firstOrNull { it.number == season }?.episodes.orEmpty()
                LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
                    items(episodes, key = { it.id }) { ep ->
                        EpisodeRow(ep, episodeProgress[ep.id]) { onPlay(vm.episodeTarget(d, ep)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, progress: WatchProgressEntity?, onClick: () -> Unit) {
    TvClickable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ep.number.toString(),
                fontSize = 16.sp,
                color = Brand.TextSecondary,
                modifier = Modifier.width(36.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    ep.title.ifBlank { "E${ep.number}" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ep.plot?.let { Text(it, fontSize = 13.sp, color = Brand.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (progress != null && !progress.finished && progress.durationMs > 0) {
                    ProgramProgress(progress.positionMs.toFloat() / progress.durationMs, Modifier.fillMaxWidth(0.5f).padding(top = 4.dp))
                }
            }
            when {
                progress?.finished == true -> Text("✓", color = Brand.Accent, fontSize = 18.sp, modifier = Modifier.padding(start = 12.dp))
                ep.durationSecs != null && ep.durationSecs > 0 ->
                    Text(stringResource(R.string.vod_minutes, ep.durationSecs / 60), color = Brand.TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}
