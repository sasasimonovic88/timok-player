package rs.timok.player.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import rs.timok.player.R
import rs.timok.player.domain.VodItem
import rs.timok.player.domain.VodSort
import rs.timok.player.ui.components.Chip
import rs.timok.player.domain.VodKind
import rs.timok.player.ui.components.Clock
import rs.timok.player.ui.components.NavDest
import rs.timok.player.ui.components.NavRail
import rs.timok.player.ui.components.TvClickable
import rs.timok.player.ui.theme.Brand

private const val COLUMNS = 5

@Composable
fun VodBrowseScreen(
    onNavigate: (NavDest) -> Unit,
    onOpen: (VodItem) -> Unit,
    vm: VodBrowseViewModel = hiltViewModel(),
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val categoriesError by vm.categoriesError.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val continueEntries by vm.continueEntries.collectAsStateWithLifecycle()
    val myListEntries by vm.myListEntries.collectAsStateWithLifecycle()
    val lockedCategories by vm.lockedCategories.collectAsStateWithLifecycle()
    val categoryFocus = remember { FocusRequester() }
    var restoreFocus by remember { mutableStateOf(true) }

    val continueTitle = stringResource(R.string.vod_continue)
    val myListTitle = stringResource(R.string.my_list)
    val categoryRows = buildList {
        if (continueEntries.isNotEmpty()) add(CONTINUE_KEY to continueTitle)
        if (myListEntries.isNotEmpty()) add(MY_LIST_KEY to myListTitle)
        categories?.forEach { add(it.id to it.name) }
    }
    val title = categoryRows.firstOrNull { it.first == selected }?.second.orEmpty()

    // First entry into the screen: start on the selected category unless a poster gets focus back.
    // Waits for the selection too: the focus target is the selected category row.
    LaunchedEffect(categoryRows.isNotEmpty(), selected) {
        if (!restoreFocus || categoryRows.isEmpty() || selected == null || vm.focusedItemId != null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { categoryFocus.requestFocus() }
        restoreFocus = false
    }

    // The last title removed from "My List" (or finished from "continue watching") hides that row:
    // move to the first remaining category so focus has somewhere to land.
    LaunchedEffect(selected, categoryRows.isNotEmpty(), myListEntries.isEmpty(), continueEntries.isEmpty()) {
        val sel = selected ?: return@LaunchedEffect
        if ((sel == MY_LIST_KEY || sel == CONTINUE_KEY) && categoryRows.none { it.first == sel }) {
            categoryRows.firstOrNull()?.let { vm.select(it.first, immediate = true) }
        }
    }

    // No fill of its own: the still brand backdrop behind the navigation host shows through.
    val sort by vm.sort.collectAsStateWithLifecycle()
    var choosingSort by remember { mutableStateOf(false) }
    if (choosingSort) {
        SortDialog(
            current = sort,
            onPick = {
                choosingSort = false
                vm.setSort(it)
            },
            onDismiss = { choosingSort = false },
        )
    }
    Row(Modifier.fillMaxSize().padding(vertical = 24.dp)) {
        NavRail(selected = if (vm.kind == VodKind.MOVIE) NavDest.MOVIES else NavDest.SERIES, onNavigate = onNavigate)

        Column(Modifier.width(270.dp).fillMaxHeight().padding(start = 12.dp)) {
            Text(
                stringResource(if (vm.kind == VodKind.MOVIE) R.string.nav_movies else R.string.nav_series),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.Accent,
                modifier = Modifier.padding(start = 14.dp, bottom = 16.dp),
            )
            when {
                categoriesError != null -> Text(categoriesError!!, color = Brand.Error, modifier = Modifier.padding(14.dp))
                categories == null -> Text(stringResource(R.string.vod_loading), color = Brand.TextSecondary, modifier = Modifier.padding(14.dp))
                else -> CategoryList(categoryRows, selected, categoryFocus, lockedCategories, onFocus = vm::select, onUnlock = vm::unlock)
            }
        }
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f).fillMaxHeight().padding(end = 32.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (selected != CONTINUE_KEY && selected != MY_LIST_KEY && items is ItemsState.Ready) {
                    // One button that steps through the orders; up from the first poster row reaches it.
                    Chip(
                        text = "⇅  " + stringResource(sortLabel(sort)),
                        selected = sort != VodSort.DEFAULT,
                        onClick = { choosingSort = true },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                    )
                }
                (items as? ItemsState.Ready)?.let {
                    Text(
                        pluralStringResource(if (vm.kind == VodKind.MOVIE) R.plurals.movie_count else R.plurals.series_count, it.entries.size, it.entries.size),
                        color = Brand.TextSecondary,
                    )
                }
                androidx.compose.foundation.layout.Box(
                    Modifier.padding(horizontal = 18.dp).width(1.dp).height(28.dp).background(Brand.TextSecondary.copy(alpha = 0.4f)),
                )
                Clock(color = Brand.TextPrimary, large = true)
            }
            when (val s = items) {
                ItemsState.Loading -> Text(stringResource(R.string.vod_loading), color = Brand.TextSecondary, modifier = Modifier.padding(top = 24.dp))
                is ItemsState.Error -> Text(s.message, color = Brand.Error, modifier = Modifier.padding(top = 24.dp))
                ItemsState.Locked -> Text(stringResource(R.string.category_locked_hint), color = Brand.TextSecondary, modifier = Modifier.padding(top = 24.dp))
                is ItemsState.Ready ->
                    if (s.entries.isEmpty()) {
                        Text(stringResource(R.string.vod_empty), color = Brand.TextSecondary, modifier = Modifier.padding(top = 24.dp))
                    } else key(selected, sort) {
                        // A new order starts at the top; the grid would otherwise stay on the focused poster.
                        PosterGrid(
                            entries = s.entries,
                            categoryFocus = categoryFocus,
                            restoreId = if (restoreFocus) vm.focusedItemId else null,
                            onRestored = { found ->
                                restoreFocus = false
                                if (!found) runCatching { categoryFocus.requestFocus() }
                            },
                            onFocused = { vm.focusedItemId = it },
                            onOpen = onOpen,
                        )
                    }
            }
        }
    }
}

@Composable
private fun CategoryList(
    rows: List<Pair<String, String>>,
    selected: String?,
    selectedRequester: FocusRequester,
    locked: Set<String>,
    onFocus: (String) -> Unit,
    onUnlock: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected, rows.size) {
        val idx = rows.indexOfFirst { it.first == selected }
        if (idx >= 0 && idx !in listState.layoutInfo.visibleItemsInfo.map { it.index }) listState.scrollToItem((idx - 3).coerceAtLeast(0))
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(4.dp)) {
        items(rows, key = { it.first }) { (id, name) ->
            val isSelected = id == selected
            var mod = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus(id) }
            if (isSelected) mod = mod.focusRequester(selectedRequester)
            val isLocked = id in locked
            TvClickable(onClick = { if (isLocked) onUnlock() else onFocus(id) }, modifier = mod) {
                Text(
                    if (isLocked) "\uD83D\uDD12 $name" else name,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 16.sp,
                    color = if (isSelected) Brand.Accent else Brand.TextPrimary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PosterGrid(
    entries: List<GridEntry>,
    categoryFocus: FocusRequester,
    restoreId: Long?,
    onRestored: (found: Boolean) -> Unit,
    onFocused: (Long) -> Unit,
    onOpen: (VodItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val restoreRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (restoreId == null) return@LaunchedEffect
        val idx = entries.indexOfFirst { it.item.id == restoreId }
        if (idx < 0) {
            onRestored(false)
            return@LaunchedEffect
        }
        gridState.scrollToItem(idx)
        withFrameNanos { }
        runCatching { restoreRequester.requestFocus() }
        onRestored(true)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(6.dp),
    ) {
        itemsIndexed(entries, key = { _, e -> "${e.item.kind}_${e.item.id}" }) { index, entry ->
            var mod = Modifier.onFocusChanged { if (it.isFocused) onFocused(entry.item.id) }
            // Left from the first column goes back to the selected category.
            if (index % COLUMNS == 0) mod = mod.focusProperties { left = categoryFocus }
            if (entry.item.id == restoreId) mod = mod.focusRequester(restoreRequester)
            TvClickable(onClick = { onOpen(entry.item) }, modifier = mod, focusedScale = 1.06f) {
                Column(Modifier.padding(4.dp)) {
                    Poster(entry.item.poster, entry.item.name, Modifier.fillMaxWidth(), entry.progress)
                    Text(
                        entry.item.name,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                    )
                    val meta = entry.subtitle ?: listOfNotNull(entry.item.year, entry.item.rating?.let { "★ $it" }).joinToString("  ")
                    if (meta.isNotEmpty()) {
                        Text(meta, fontSize = 11.sp, color = Brand.TextSecondary, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
                    }
                }
            }
        }
    }
}
