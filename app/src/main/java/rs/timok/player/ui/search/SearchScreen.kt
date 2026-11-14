package rs.timok.player.ui.search

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.ExperimentalComposeUiApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rs.timok.player.R
import rs.timok.player.data.repo.GroupKey
import rs.timok.player.ui.components.Chip
import rs.timok.player.ui.components.ChannelRowContent
import rs.timok.player.ui.components.ChannelRowModifier
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.components.NavDest
import rs.timok.player.ui.components.NavRail
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.components.TvTextField
import rs.timok.player.ui.theme.Brand
import rs.timok.player.ui.vod.Poster
import rs.timok.player.domain.VodItem
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextOverflow

private val scrim = Brush.horizontalGradient(
    0f to Brand.Background.copy(alpha = 0.97f),
    1f to Brand.Background.copy(alpha = 0.85f),
)
private val panelEdge = Brush.horizontalGradient(
    0f to Brand.Background.copy(alpha = 0.85f),
    1f to Color.Transparent,
)
private val PanelMaxWidth = 760.dp
private val RailExpansion = 148.dp

/** Search: results narrow down while typing, split into channel, movie and series tabs; the live picture stays visible on the right. */
@Composable
fun SearchScreen(
    onPlay: (group: String, streamId: Long) -> Unit,
    onOpenVod: (VodItem) -> Unit,
    onNavigate: (NavDest) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val playingId by vm.playingId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Hoisted so the scroll position survives the hint/list switch and the trip to the details screen.
    val listState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }
    val restoreKey = remember { vm.openedKey }
    LaunchedEffect(Unit) {
        if (restoreKey == null) return@LaunchedEffect
        withTimeoutOrNull(2_000) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == restoreKey } }.first { it }
            runCatching { restoreFocus.requestFocus() }
        }
        vm.openedKey = null
    }
    val open: (Any) -> Unit = { key -> vm.openedKey = key }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    var railFocused by remember { mutableStateOf(false) }
    val panelWidth by animateDpAsState(PanelMaxWidth + if (railFocused) RailExpansion else 0.dp, rs.timok.player.ui.theme.motion(tween(150)), label = "panel")
    Row(Modifier.fillMaxSize()) {
        Row(Modifier.widthIn(max = panelWidth).fillMaxHeight().background(scrim).padding(vertical = 24.dp)) {
            Box(Modifier.onFocusChanged { railFocused = it.hasFocus }) {
                NavRail(selected = NavDest.SEARCH, onNavigate = onNavigate)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = 32.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.nav_search),
                        style = MaterialTheme.typography.titleLarge,
                        color = Brand.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    results?.let { Text(it.count.toString(), color = Brand.TextSecondary) }
                    Clock(color = Brand.TextSecondary, modifier = Modifier.padding(start = 20.dp))
                }
                TvTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    label = stringResource(R.string.search_hint),
                    imeAction = ImeAction.Search,
                    startEditing = query.isEmpty(),
                )
                Spacer(Modifier.padding(top = 12.dp))
                val found = results
                val tab by vm.tab.collectAsStateWithLifecycle()
                // An empty tab jumps to the first one with results, unless its items are still loading.
                LaunchedEffect(found) {
                    if (found == null || found.isEmpty) return@LaunchedEffect
                    val vodTab = tab != SearchTab.CHANNELS
                    if (found.countFor(tab) == 0 && !(vodTab && found.vodLoading)) {
                        SearchTab.entries.firstOrNull { found.countFor(it) > 0 }?.let(vm::selectTab)
                    }
                }
                // A new tab starts at the top; the first composition keeps the restored position.
                var shownTab by remember { mutableStateOf(tab) }
                LaunchedEffect(tab) {
                    if (tab != shownTab) {
                        shownTab = tab
                        listState.scrollToItem(0)
                    }
                }
                when {
                    found == null -> Hint(stringResource(R.string.search_prompt))
                    found.isEmpty && !found.vodLoading -> Hint(
                        stringResource(if (found.vodFailed) R.string.search_vod_failed else R.string.search_no_results, query.trim()),
                    )
                    else -> {
                        SearchTabs(tab, found, vm::selectTab)
                        LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
                    ) {
                        val vodNote = when {
                            found.vodLoading -> R.string.search_vod_loading
                            found.vodFailed -> R.string.search_vod_failed
                            else -> null
                        }
                        when (tab) {
                        SearchTab.CHANNELS -> if (found.channels.isEmpty()) {
                            item(key = "note:channels", contentType = "header") { Header(stringResource(R.string.search_no_results, query.trim())) }
                        } else items(found.channels, key = { it.streamId }, contentType = { "channel" }) { row ->
                            TvClickable(
                                onClick = {
                                    scope.launch {
                                        if (vm.onChannelClick(row)) {
                                            open(row.streamId)
                                            onPlay(GroupKey.All.key, row.streamId)
                                        }
                                    }
                                },
                                modifier = ChannelRowModifier.restoreFocus(row.streamId, restoreKey, restoreFocus),
                            ) {
                                ChannelRowContent(row, now, playing = row.streamId == playingId)
                            }
                        }
                        SearchTab.MOVIES -> vodSection("movies", found.movies, vodNote, query.trim(), restoreKey, restoreFocus) { key, item -> open(key); onOpenVod(item) }
                        SearchTab.SERIES -> vodSection("series", found.series, vodNote, query.trim(), restoreKey, restoreFocus) { key, item -> open(key); onOpenVod(item) }
                        }
                    }
                    }
                }
            }
        }
        Box(Modifier.width(140.dp).fillMaxHeight().background(panelEdge))
    }
}

private fun SearchResults.countFor(tab: SearchTab) = when (tab) {
    SearchTab.CHANNELS -> channels.size
    SearchTab.MOVIES -> movies.size
    SearchTab.SERIES -> series.size
}

/** Channels / Movies / Series with their counts; moving onto a tab selects it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchTabs(selected: SearchTab, found: SearchResults, onSelect: (SearchTab) -> Unit) {
    val selectedFocus = remember { FocusRequester() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // Coming down from the search field lands on the current tab, not the nearest one.
        modifier = Modifier.padding(bottom = 8.dp).focusRestorer(selectedFocus),
    ) {
        listOf(
            SearchTab.CHANNELS to R.string.search_channels,
            SearchTab.MOVIES to R.string.nav_movies,
            SearchTab.SERIES to R.string.nav_series,
        ).forEach { (t, label) ->
            val loading = t != SearchTab.CHANNELS && found.vodLoading
            Chip(
                text = stringResource(label) + "  " + if (loading) "…" else found.countFor(t).toString(),
                selected = t == selected,
                onClick = { onSelect(t) },
                modifier = (if (t == selected) Modifier.focusRequester(selectedFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) onSelect(t) },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.vodSection(
    section: String,
    items: List<VodItem>,
    note: Int?,
    query: String,
    restoreKey: Any?,
    restoreFocus: FocusRequester,
    onOpen: (key: String, VodItem) -> Unit,
) {
    if (items.isEmpty()) {
        item(key = "note:$section", contentType = "header") {
            Header(stringResource(note ?: R.string.search_no_results, query))
        }
        return
    }
    items(items, key = { "$section:${it.id}" }, contentType = { "vod" }) { item ->
        val key = "$section:${item.id}"
        TvClickable(
            onClick = { onOpen(key, item) },
            modifier = Modifier.fillMaxWidth().height(VodRowHeight).restoreFocus(key, restoreKey, restoreFocus),
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Poster(item.poster, item.name, Modifier.fillMaxHeight())
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    val meta = listOfNotNull(item.year, item.rating?.let { "★ $it" }).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, color = Brand.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private val VodRowHeight = 84.dp

/** Attaches [requester] only to the row the viewer opened before leaving the screen. */
private fun Modifier.restoreFocus(key: Any, restoreKey: Any?, requester: FocusRequester) =
    if (key == restoreKey) focusRequester(requester) else this

@Composable
private fun Header(text: String) {
    Text(
        text,
        color = Brand.Accent,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Brand.TextSecondary, modifier = Modifier.padding(top = 16.dp, start = 4.dp))
}
