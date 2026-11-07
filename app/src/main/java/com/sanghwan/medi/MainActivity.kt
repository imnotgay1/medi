package com.sanghwan.medi

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Notifier.ensure(this)
        Alarms.rescheduleAll(this)

        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(tick) { tick++ }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick++ // 알림에서 "먹었어요" 누른 것 반영
        MediWidget.updateAll(this)
    }
}

private val KOR = Locale.KOREAN
private fun fmtClock(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
fun App(tick: Int, refresh: () -> Unit) {
    val c = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Med?>(null) }
    val meds = remember(tick) { Store.meds(c) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("💊" to "오늘", "📋" to "약 관리", "📅" to "기록").forEachIndexed { i, (ic, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(ic, fontSize = 20.sp) },
                        label = { Text(label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 1) {
                ExtendedFloatingActionButton(onClick = { editing = null; editorOpen = true }) {
                    Text("+ 약 추가")
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayScreen(meds, tick, refresh) { tab = 1; editing = null; editorOpen = true }
                1 -> MedsScreen(
                    meds,
                    onEdit = { editing = it; editorOpen = true },
                    onDelete = { m ->
                        Alarms.cancelMed(c, m)
                        Store.delete(c, m.id)
                        refresh()
                    }
                )
                else -> HistoryScreen(meds, tick)
            }
        }
    }

    if (editorOpen) {
        EditorDialog(editing, onDismiss = { editorOpen = false }) { name, memo, times ->
            val old = editing
            if (old != null) Alarms.cancelMed(c, old)
            val med = Store.save(c, old?.id, name, memo, times)
            med.times.forEach { Alarms.schedule(c, med.id, it) }
            editorOpen = false
            refresh()
        }
    }
}

// ---------------- 오늘 ----------------

@Composable
fun TodayScreen(meds: List<Med>, tick: Int, refresh: () -> Unit, onAdd: () -> Unit) {
    val c = LocalContext.current
    val today = LocalDate.now()
    val now = LocalTime.now()
    val doses = meds.flatMap { m -> m.times.map { m to it } }.sortedBy { it.second }
    val done = doses.count { (m, t) -> Store.takenAt(c, today, m.id, t) != null }
    val cs = MaterialTheme.colorScheme

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text(
                    today.format(DateTimeFormatter.ofPattern("M월 d일 EEEE", KOR)),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurfaceVariant
                )
                Text(
                    when {
                        doses.isEmpty() -> "등록된 약이 없어요"
                        done == doses.size -> "오늘 다 먹었어요 👍"
                        else -> "${doses.size - done}번 남았어요"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                if (doses.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { done.toFloat() / doses.size },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                    )
                }
            }
        }

        item { PermissionBanner(tick) }

        if (doses.isEmpty()) {
            item {
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("약 추가하기") }
            }
        }

        items(doses, key = { "${it.first.id}|${it.second}" }) { (m, t) ->
            val takenAt = Store.takenAt(c, today, m.id, t)
            val late = takenAt == null && LocalTime.parse(t).isBefore(now)
            DoseRow(m, t, takenAt, late) {
                Store.setTaken(c, today, m.id, t, takenAt == null)
                if (takenAt == null) Notifier.cancel(c, m.id, t)
                refresh()
            }
        }

        if (doses.isNotEmpty()) {
            item {
                Text(
                    "카드를 누르면 체크 / 다시 누르면 취소",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun DoseRow(m: Med, t: String, takenAt: Long?, late: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val taken = takenAt != null
    Card(
        modifier = Modifier.fillMaxWidth().clip(CardDefaults.shape).clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = if (taken) cs.secondaryContainer else cs.surfaceContainerHigh
        )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                t,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (late) cs.error else cs.onSurface
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val status = when {
                    takenAt != null -> "${fmtClock(takenAt)}에 먹음"
                    late -> "아직 안 먹었어요"
                    else -> ""
                }
                val sub = listOf(status, m.memo).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (late) cs.error else cs.onSurfaceVariant
                    )
                }
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (taken) cs.primary else Color.Transparent)
                    .border(2.dp, if (taken) cs.primary else cs.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (taken) Text("✓", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }
}

/** 알림 권한·정확한 알람 권한이 꺼져 있으면 안내 */
@Composable
fun PermissionBanner(tick: Int) {
    val c = LocalContext.current
    val notiOn = remember(tick) { NotificationManagerCompat.from(c).areNotificationsEnabled() }
    val exactOn = remember(tick) { Alarms.canExact(c) }
    if (notiOn && exactOn) return

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (!notiOn) {
                Text("알림이 꺼져 있어서 복용 알림이 안 와요.")
                TextButton(onClick = {
                    c.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
                    )
                }) { Text("알림 켜기") }
            }
            if (!exactOn && Build.VERSION.SDK_INT >= 31) {
                Text("'정확한 알람' 권한이 꺼져 있어서 알림이 늦게 올 수 있어요.")
                TextButton(onClick = {
                    c.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}"))
                    )
                }) { Text("권한 켜기") }
            }
        }
    }
}

// ---------------- 약 관리 ----------------

@Composable
fun MedsScreen(meds: List<Med>, onEdit: (Med) -> Unit, onDelete: (Med) -> Unit) {
    var confirm by remember { mutableStateOf<Med?>(null) }
    val cs = MaterialTheme.colorScheme

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("약 관리", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (meds.isEmpty()) {
            item { Text("오른쪽 아래 '+ 약 추가'로 시작하세요", color = cs.onSurfaceVariant) }
        }
        items(meds, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("⏰ " + m.times.joinToString("   "), style = MaterialTheme.typography.bodyLarge)
                    if (m.memo.isNotBlank()) {
                        Text(m.memo, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onEdit(m) }) { Text("수정") }
                        TextButton(onClick = { confirm = m }) { Text("삭제", color = cs.error) }
                    }
                }
            }
        }
    }

    confirm?.let { m ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${m.name} 삭제") },
            text = { Text("이 약의 알림도 같이 꺼져요.") },
            confirmButton = { TextButton(onClick = { onDelete(m); confirm = null }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } }
        )
    }
}

@Composable
fun EditorDialog(med: Med?, onDismiss: () -> Unit, onSave: (String, String, List<String>) -> Unit) {
    val c = LocalContext.current
    var name by remember { mutableStateOf(med?.name ?: "") }
    var memo by remember { mutableStateOf(med?.memo ?: "") }
    val times = remember { mutableStateListOf<String>().apply { addAll(med?.times ?: emptyList()) } }

    fun pickTime() {
        TimePickerDialog(c, { _, h, mi ->
            val t = String.format(Locale.US, "%02d:%02d", h, mi)
            if (t !in times) {
                val sorted = (times + t).sorted()
                times.clear()
                times.addAll(sorted)
            }
        }, 8, 0, true).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (med == null) "약 추가" else "약 수정") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("약 이름") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = memo, onValueChange = { memo = it },
                    label = { Text("메모 (예: 식후 1알)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("복용 시간", style = MaterialTheme.typography.titleSmall)
                times.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { times.remove(t) }) { Text("빼기") }
                    }
                }
                OutlinedButton(onClick = { pickTime() }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ 시간 추가")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && times.isNotEmpty(),
                onClick = { onSave(name.trim(), memo.trim(), times.toList()) }
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

// ---------------- 기록 ----------------

@Composable
fun HistoryScreen(meds: List<Med>, tick: Int) {
    val c = LocalContext.current
    val today = LocalDate.now()
    val now = LocalTime.now()
    val cs = MaterialTheme.colorScheme
    val days = remember(tick) { (0 until 14).map { today.minusDays(it.toLong()) } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("최근 2주 기록", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }

        items(days, key = { it.toString() }) { d ->
            val doses = meds
                .filter { !d.isBefore(LocalDate.parse(it.since)) }
                .flatMap { m -> m.times.map { m to it } }
                .filter { (_, t) -> d.isBefore(today) || !LocalTime.parse(t).isAfter(now) }
                .sortedBy { it.second }
            if (doses.isNotEmpty()) {
                val missed = doses.filter { (m, t) -> Store.takenAt(c, d, m.id, t) == null }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (d == today) "오늘" else d.format(DateTimeFormatter.ofPattern("M/d (E)", KOR)),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                (if (missed.isEmpty()) "✅ " else "") + "${doses.size - missed.size}/${doses.size}",
                                color = if (missed.isEmpty()) cs.primary else cs.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        missed.forEach { (m, t) ->
                            Text("✗ $t  ${m.name}", style = MaterialTheme.typography.bodyMedium, color = cs.error)
                        }
                    }
                }
            }
        }
    }
}
