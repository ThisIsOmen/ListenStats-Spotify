package com.listenstats

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

val Bg = Color(0xFF0E1621)
val CardC = Color(0xFF17212B)
val Card2 = Color(0xFF1F2C3A)
val Accent = Color(0xFF2AABEE)
val SubC = Color(0xFF7F91A4)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, background = Bg, surface = CardC,
            onBackground = Color.White, onSurface = Color.White
        ),
        content = content
    )
}

enum class Period(val label: String) { Day("День"), Week("Неделя"), Month("Месяц"), All("Всё") }

fun sinceOf(p: Period): Long {
    val now = System.currentTimeMillis()
    return when (p) {
        Period.Day -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        Period.Week -> now - 7L * 86_400_000
        Period.Month -> now - 30L * 86_400_000
        Period.All -> 0L
    }
}

data class Data(
    val total: Int = 0,
    val minutes: Int = 0,
    val artistCount: Int = 0,
    val artists: List<Stat> = emptyList(),
    val tracks: List<Stat> = emptyList(),
    val genres: List<Stat> = emptyList()
)

fun load(db: Db, p: Period): Data {
    val s = sinceOf(p)
    val (c, ms) = db.total(s)
    val a = db.topArtists(s)
    return Data(c, (ms / 60_000).toInt(), a.size, a, db.topTracks(s), db.topGenres(s))
}

fun fmtMin(m: Int): String = if (m >= 60) "${m / 60} ч ${m % 60} м" else "$m м"

fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> "${s / 3600} ч ${(s % 3600) / 60} м"
        s >= 60 -> "${s / 60} м ${s % 60} с"
        else -> "$s с"
    }
}

fun Modifier.bounceClick(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    this.scale(sc).clickable(interactionSource = src, indication = null, onClick = onClick)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    var period by remember { mutableStateOf(Period.Week) }
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val tick by Bus.tick.collectAsState()
    val resume by Bus.resume.collectAsState()
    val now by Bus.now.collectAsState()
    var data by remember { mutableStateOf(Data()) }

    LaunchedEffect(period, tick) {
        data = withContext(Dispatchers.IO) { load(db, period) }
    }

    val listenerOk = remember(resume) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    val batteryOk = remember(resume) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }

    val list = when (tab) { 0 -> data.artists; 1 -> data.tracks; else -> data.genres }
    val maxC = (list.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Header { showSettings = true } }
        item {
            AnimatedVisibility(
                !listenerOk,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Banner(
                    "Нужен доступ к уведомлениям",
                    "Без него приложение не видит, что играет",
                    "Открыть"
                ) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        item {
            AnimatedVisibility(
                listenerOk && !batteryOk,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Banner(
                    "Отключи экономию батареи",
                    "Выбери «Без ограничений» для Listen Stats, иначе сервис могут убить",
                    "Настроить"
                ) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        item {
            AnimatedVisibility(
                now != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) { NowCard(now ?: Now("", "", false)) }
        }
        item { Summary(data) }
        item { PeriodChips(period) { period = it } }
        item { TabPill(tab) { tab = it } }

        if (list.isEmpty()) {
            item {
                Text(
                    "Пока пусто. Включи трек в Spotify и послушай больше 30 секунд.",
                    color = SubC, modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            itemsIndexed(list, key = { _, s -> "$tab-${s.name}-${s.sub}" }) { i, s ->
                RankRow(i, s, maxC, Modifier.animateItemPlacement())
            }
        }
    }

    if (showSettings) SettingsDialog { showSettings = false }
}

@Composable
fun Header(onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Сводка", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("всё хранится на телефоне", color = SubC, fontSize = 13.sp)
        }
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(CardC).bounceClick(onSettings),
            contentAlignment = Alignment.Center
        ) { Text("⚙", fontSize = 20.sp) }
    }
}

@Composable
fun Banner(title: String, text: String, button: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card2).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, color = SubC, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        Button(onClick = onClick) { Text(button) }
    }
}

@Composable
fun Equalizer(playing: Boolean) {
    val t = rememberInfiniteTransition(label = "eq")
    Row(
        Modifier.height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(420, 560, 700).forEach { d ->
            val f by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(d, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar"
            )
            val h = if (playing) f else 0.25f
            Box(
                Modifier.width(4.dp).height(22.dp * h)
                    .clip(RoundedCornerShape(2.dp)).background(Accent)
            )
        }
    }
}

@Composable
fun NowCard(n: Now) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardC).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Equalizer(n.playing)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(n.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(n.artist, color = SubC, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (n.playing) "играет" else "пауза",
            color = if (n.playing) Accent else SubC, fontSize = 12.sp
        )
    }
}

@Composable
fun Tile(label: String, value: Int, format: (Int) -> String, modifier: Modifier) {
    val v by animateIntAsState(value, tween(800, easing = FastOutSlowInEasing), label = "num")
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(CardC).padding(12.dp)
    ) {
        Text(format(v), fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = SubC, fontSize = 12.sp)
    }
}

@Composable
fun Summary(d: Data) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("прослушиваний", d.total, { it.toString() }, Modifier.weight(1f))
        Tile("артистов", d.artistCount, { it.toString() }, Modifier.weight(1f))
        Tile("времени", d.minutes, { fmtMin(it) }, Modifier.weight(1.2f))
    }
}

@Composable
fun PeriodChips(sel: Period, onSel: (Period) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Period.values().forEach { p ->
            val bg by animateColorAsState(if (p == sel) Accent else CardC, label = "chip")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(bg)
                    .bounceClick { onSel(p) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { Text(p.label, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
fun TabPill(tab: Int, onTab: (Int) -> Unit) {
    val labels = listOf("Артисты", "Треки", "Жанры")
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardC).padding(4.dp)
    ) {
        val w = maxWidth / 3
        val x by animateDpAsState(
            w * tab,
            spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
            label = "tabx"
        )
        Box(
            Modifier.offset(x = x).width(w).height(38.dp)
                .clip(RoundedCornerShape(11.dp)).background(Accent)
        )
        Row {
            labels.forEachIndexed { i, l ->
                val col by animateColorAsState(if (tab == i) Color.White else SubC, label = "tabc")
                Box(
                    Modifier.weight(1f).height(38.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onTab(i) },
                    contentAlignment = Alignment.Center
                ) { Text(l, color = col, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
fun RankRow(i: Int, s: Stat, maxC: Int, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(i.coerceAtMost(12) * 45L)
        visible = true
    }
    val frac by animateFloatAsState(
        if (visible) s.count.toFloat() / maxC else 0f,
        tween(700, easing = FastOutSlowInEasing),
        label = "frac"
    )
    AnimatedVisibility(
        visible,
        modifier = modifier,
        enter = fadeIn(tween(300)) +
            slideInVertically(spring(0.75f, Spring.StiffnessMediumLow)) { it / 3 } +
            scaleIn(initialScale = 0.94f)
    ) {
        Column(
            Modifier.fillMaxWidth().animateContentSize()
                .clip(RoundedCornerShape(16.dp)).background(CardC)
                .bounceClick { expanded = !expanded }.padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(Card2),
                    contentAlignment = Alignment.Center
                ) { Text("${i + 1}", fontSize = 13.sp, color = SubC) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.sub.isNotEmpty()) {
                        Text(s.sub, color = SubC, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("${s.count}", color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Card2)) {
                Box(
                    Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp)).background(Accent)
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text("Время прослушивания: ${fmtMs(s.ms)}", color = SubC, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun SettingsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var sid by remember { mutableStateOf(Prefs.get(ctx, "sid")) }
    var ssec by remember { mutableStateOf(Prefs.get(ctx, "ssec")) }
    var lfm by remember { mutableStateOf(Prefs.get(ctx, "lfm")) }
    var chrome by remember { mutableStateOf(Prefs.bool(ctx, "chrome", true)) }
    var all by remember { mutableStateOf(Prefs.bool(ctx, "all", false)) }
    var split by remember { mutableStateOf(Prefs.bool(ctx, "split", true)) }
    var exc by remember { mutableStateOf(Prefs.get(ctx, "exc")) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = CardC,
        title = { Text("Настройки") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Ключи для жанров (необязательно)", color = SubC, fontSize = 13.sp)
                OutlinedTextField(sid, { sid = it }, label = { Text("Spotify Client ID") }, singleLine = true)
                OutlinedTextField(ssec, { ssec = it }, label = { Text("Spotify Client Secret") }, singleLine = true)
                OutlinedTextField(lfm, { lfm = it }, label = { Text("Last.fm API key") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Считать артистов раздельно", Modifier.weight(1f))
                    Switch(split, { split = it })
                }
                if (split) {
                    OutlinedTextField(
                        exc, { exc = it },
                        label = { Text("Имена с запятой (по одному в строке)") },
                        minLines = 2
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Учитывать Chrome (Web Player)", Modifier.weight(1f))
                    Switch(chrome, { chrome = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Учитывать все остальные плееры", Modifier.weight(1f))
                    Switch(all, { all = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Prefs.put(ctx, "sid", sid); Prefs.put(ctx, "ssec", ssec); Prefs.put(ctx, "lfm", lfm)
                Prefs.putBool(ctx, "chrome", chrome); Prefs.putBool(ctx, "all", all)
                Prefs.putBool(ctx, "split", split); Prefs.put(ctx, "exc", exc)
                Thread { Db.get(ctx).retryEmpty(); Genres.kick(ctx); Bus.bump() }.start()
                onClose()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } }
    )
}
