@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.said.soundoffline

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import java.util.Locale

private val Orange = Color(0xFFFF5500)

private val QUICK_SEARCHES = listOf(
    "كتاب مسموع كامل",
    "رواية مسموعة",
    "ملخص كتاب",
    "كتب تطوير الذات مسموعة",
    "تاريخ كتاب مسموع",
    "بودكاست عربي",
)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        vm.download(text)
    }
}

@Composable
fun App(vm: MainViewModel) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = Orange, secondary = Orange, primaryContainer = Color(0xFF5A2600))
    else
        lightColorScheme(primary = Orange, secondary = Orange, primaryContainer = Color(0xFFFFE3D1))
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<List<Track>?>(null) }

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("ساوند أوفلاين") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Orange, titleContentColor = Color.White,
                        ),
                    )
                },
                bottomBar = { vm.currentTrack?.let { PlayerBar(vm, it) } },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("مكتبتي") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("اقتراحات") })
                    }
                    DownloadStatus(vm)
                    when (tab) {
                        0 -> LibraryTab(vm, onDelete = { toDelete = it })
                        else -> SuggestionsTab(vm)
                    }
                }
            }

            toDelete?.let { list ->
                AlertDialog(
                    onDismissRequest = { toDelete = null },
                    title = { Text(if (list.size > 1) "حذف ${list.size} جزء؟" else "حذف المقطع؟") },
                    text = { Text(list.first().collection ?: list.first().title) },
                    confirmButton = {
                        TextButton(onClick = { vm.delete(list); toDelete = null }) { Text("حذف") }
                    },
                    dismissButton = { TextButton(onClick = { toDelete = null }) { Text("إلغاء") } },
                )
            }
        }
    }
}

// ======================= shared bits =======================

@Composable
private fun DownloadStatus(vm: MainViewModel) {
    val p by vm.progress.collectAsState()
    val status = vm.status ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val extra = if (vm.queued > 0) "  •  في الانتظار: ${vm.queued}" else ""
        Text(status + extra, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (vm.busy) {
            Spacer(Modifier.height(4.dp))
            val pv = p
            if (pv != null) LinearProgressIndicator(progress = { pv }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Thumb(model: Any?, size: Dp = 56.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = model, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("♪", color = Orange, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun Track.thumbModel(): Any? = thumbPath?.let { File(it) }

// ======================= library =======================

@Composable
private fun LibraryTab(vm: MainViewModel, onDelete: (List<Track>) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var filter by rememberSaveable { mutableStateOf("") }
    val expanded = remember { mutableStateOf(setOf<String>()) }

    val filtered = vm.tracks.filter {
        filter.isBlank() || it.title.contains(filter, true) || it.artist.contains(filter, true) ||
            (it.collection?.contains(filter, true) == true)
    }
    // A "group" is either a multi-part book (playlist) or a single item.
    val groups = filtered.groupBy { it.collection ?: ("\u0000" + it.key) }
        .values.sortedByDescending { g -> g.maxOf { vm.lastPlayed[it.key] ?: it.addedAt } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                OutlinedTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    label = { Text("رابط يوتيوب أو ساوند كلاود (فيديو أو قائمة)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.download(vm.input) }),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.getText()?.text?.let { vm.input = it } }) { Text("لصق") }
                    Button(onClick = { vm.download(vm.input) }, enabled = vm.input.isNotBlank()) {
                        Text("تحميل أوفلاين")
                    }
                }
            }
        }

        vm.continueTrack?.let { t ->
            item(key = "continue") { ContinueCard(vm, t) }
        }

        if (vm.tracks.size > 6) {
            item {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    placeholder = { Text("دوّر في مكتبتك") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (vm.tracks.isEmpty()) {
            item {
                Text(
                    "المكتبة فاضية. حط رابط فوق، أو اعمل Share من يوتيوب/ساوند كلاود واختار التطبيق، أو شوف تبويب «اقتراحات».",
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        groups.forEach { g ->
            val first = g.first()
            if (first.collection != null) {
                val name = first.collection
                val parts = g.sortedBy { it.order }
                val isOpen = name in expanded.value
                item(key = "col:$name") {
                    CollectionCard(
                        vm, name, parts, isOpen,
                        onToggle = { expanded.value = if (isOpen) expanded.value - name else expanded.value + name },
                        onDelete = { onDelete(vm.collectionTracks(name)) },
                    )
                }
                if (isOpen) {
                    items(parts, key = { "part:" + it.key }) { t ->
                        TrackRow(vm, t, compact = true, onDelete = { onDelete(listOf(t)) })
                    }
                }
            } else {
                item(key = "t:" + first.key) {
                    TrackRow(vm, first, compact = false, onDelete = { onDelete(listOf(first)) })
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(vm: MainViewModel, t: Track) {
    Card(
        onClick = { vm.play(t) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(t.thumbModel(), 64.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("كمّل الاستماع", style = MaterialTheme.typography.labelMedium, color = Orange)
                Text(t.collection ?: t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                if (t.collection != null) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { vm.fraction(t) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CollectionCard(
    vm: MainViewModel, name: String, parts: List<Track>, isOpen: Boolean,
    onToggle: () -> Unit, onDelete: () -> Unit,
) {
    val total = parts.sumOf { it.durationMs }
    val heard = parts.sumOf { (vm.fraction(it) * it.durationMs).toLong() }
    val frac = if (total > 0) heard.toFloat() / total else 0f
    val doneCount = parts.count { vm.done[it.key] == true }
    Card(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(parts.first().thumbModel())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(
                    "${parts.size} جزء • ${fmt(total)} • خلصت $doneCount",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (isOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "حذف") }
            }
        }
    }
}

@Composable
private fun TrackRow(vm: MainViewModel, t: Track, compact: Boolean, onDelete: () -> Unit) {
    val isCurrent = vm.currentKey == t.key
    val isDone = vm.done[t.key] == true
    Card(
        onClick = { vm.play(t) },
        modifier = Modifier.fillMaxWidth().padding(start = if (compact) 24.dp else 0.dp),
        colors = if (isCurrent) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!compact) { Thumb(t.thumbModel()); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(
                    t.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                )
                val sub = buildString {
                    if (!compact && t.artist.isNotBlank()) append(t.artist).append(" • ")
                    append(fmt(t.durationMs))
                    if (isDone) append(" • خلصت ✓")
                    if (t.chapters.isNotEmpty()) append(" • ${t.chapters.size} فصل")
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val f = vm.fraction(t)
                if (f > 0f && !isDone) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "حذف") }
        }
    }
}

// ======================= suggestions =======================

@Composable
private fun SuggestionsTab(vm: MainViewModel) {
    val recommended = vm.recommended
    LaunchedEffect(Unit) {
        if (vm.results.isEmpty() && recommended.isEmpty()) vm.search(QUICK_SEARCHES.first())
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = { vm.query = it },
                    placeholder = { Text("دوّر على كتاب أو موضوع…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search(vm.query) }),
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_SEARCHES.forEach { q ->
                        AssistChip(onClick = { vm.search(q) }, label = { Text(q) })
                    }
                }
            }
        }

        if (recommended.isNotEmpty()) {
            item { SectionTitle("مقترح ليك (على حسب اللي حمّلته)") }
            items(recommended.take(10), key = { "rec:" + it.url }) { SuggestionRow(vm, it) }
        }

        item {
            SectionTitle(if (vm.results.isNotEmpty()) "نتايج: ${vm.query}" else "بحث")
        }
        if (vm.searching) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        vm.searchError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        items(vm.results, key = { "res:" + it.url }) { SuggestionRow(vm, it) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SuggestionRow(vm: MainViewModel, s: Suggestion) {
    val downloaded = vm.isDownloaded(s)
    val pending = s.url in vm.pendingUrls
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(s.thumbUrl, 64.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                val info = buildString {
                    if (s.artist.isNotBlank()) append(s.artist).append(" • ")
                    if (s.isPlaylist) append("قائمة" + if (s.count > 0) " • ${s.count} جزء" else "")
                    else append(fmt(s.durationMs))
                }
                Text(info, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            when {
                downloaded -> Text("✓", color = Orange, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                pending -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> FilledTonalButton(onClick = { vm.download(s.url) }) { Text("تحميل") }
            }
        }
    }
}

// ======================= player =======================

@Composable
private fun PlayerBar(vm: MainViewModel, t: Track) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showChapters by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    val dur = (if (vm.duration > 0) vm.duration else t.durationMs).coerceAtLeast(1L)
    val chIndex = vm.currentChapterIndex(t)

    Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(t.thumbModel(), 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.collection ?: t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    val sub = when {
                        chIndex >= 0 -> t.chapters[chIndex].title
                        t.collection != null -> t.title
                        else -> t.artist
                    }
                    Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column {
                    Slider(
                        value = dragging ?: (vm.position.toFloat() / dur).coerceIn(0f, 1f),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { vm.seekTo((it * dur).toLong()) }
                            dragging = null
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val shown = dragging?.let { (it * dur).toLong() } ?: vm.position
                        Text(fmt(shown), style = MaterialTheme.typography.labelSmall)
                        val left = ((dur - shown) / vm.speed).toLong()
                        Text("-${fmt(left)} (${fmtSpeed(vm.speed)}x)", style = MaterialTheme.typography.labelSmall)
                        Text(fmt(dur), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { vm.seekBy(-15_000) }) { Text("⟲ 15s") }
                        FilledIconButton(onClick = { vm.togglePlay() }, modifier = Modifier.size(52.dp)) {
                            if (vm.isPlaying) Text("❚❚", fontWeight = FontWeight.Bold)
                            else Icon(Icons.Default.PlayArrow, contentDescription = "تشغيل")
                        }
                        TextButton(onClick = { vm.seekBy(30_000) }) { Text("30s ⟳") }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SPEEDS.forEach { s ->
                            FilterChip(
                                selected = vm.speed == s,
                                onClick = { vm.setSpeed(s) },
                                label = { Text(fmtSpeed(s) + "x") },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.chapters.isNotEmpty()) {
                    OutlinedButton(onClick = { showChapters = true }) { Text("الفصول (${t.chapters.size})") }
                }
                Box {
                    OutlinedButton(onClick = { sleepMenu = true }) {
                        Text(vm.sleepLabel?.let { "⏾ $it" } ?: "⏾ مؤقت النوم")
                    }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        SLEEP_OPTIONS.forEach { m ->
                            DropdownMenuItem(text = { Text("$m دقيقة") }, onClick = { vm.setSleep(m); sleepMenu = false })
                        }
                        DropdownMenuItem(
                            text = { Text(if (t.chapters.isNotEmpty()) "آخر الفصل" else "آخر الجزء") },
                            onClick = { vm.setSleep(-1); sleepMenu = false },
                        )
                        if (vm.sleepLabel != null) {
                            DropdownMenuItem(text = { Text("إلغاء المؤقت") }, onClick = { vm.setSleep(0); sleepMenu = false })
                        }
                    }
                }
            }
        }
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                itemsIndexed(t.chapters) { i, ch ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.seekTo(ch.startMs); showChapters = false }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Text(
                            ch.title, modifier = Modifier.weight(1f),
                            fontWeight = if (i == chIndex) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == chIndex) Orange else Color.Unspecified,
                        )
                        Text(fmt(ch.startMs), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private fun fmtSpeed(s: Float): String =
    String.format(Locale.US, "%.2f", s).trimEnd('0').trimEnd('.')
