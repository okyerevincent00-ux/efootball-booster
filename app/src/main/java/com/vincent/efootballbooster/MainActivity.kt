package com.vincent.efootballbooster

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

const val GAME = "jp.konami.pesam"

val Bg = Color(0xFF15112B)
val Card = Color(0xFF211B45)
val Gold = Color(0xFFFAC775)
val Purple = Color(0xFF7F77DD)
val Green = Color(0xFF1D9E75)
val Orange = Color(0xFFD85A30)
val Blue = Color(0xFF378ADD)
val Pink = Color(0xFFD4537E)
val Red = Color(0xFFE24B4A)

data class Stats(
    val ramGb: Double = 0.0, val temp: Double = 0.0, val battery: Int = 0,
    val charging: Boolean = false, val ping: Int = -1
)

fun readStats(c: Context, ping: Int): Stats {
    val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val st = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val ch = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    return Stats(mi.availMem / 1e9, temp, lvl * 100 / scale, ch, ping)
}

fun measurePing(): Int = try {
    val t = System.nanoTime()
    Socket().use { it.connect(InetSocketAddress("8.8.8.8", 53), 1500) }
    ((System.nanoTime() - t) / 1_000_000).toInt()
} catch (e: Exception) { -1 }

fun score(s: Stats): Int {
    var x = 100
    if (s.temp > 38) x -= ((s.temp - 38) * 6).toInt()
    if (s.ramGb < 2) x -= 15
    if (s.ping < 0) x -= 20 else if (s.ping > 80) x -= 15 else if (s.ping > 50) x -= 8
    if (s.charging) x -= 5
    return x.coerceIn(0, 100)
}

fun heat(t: Double): Pair<String, Color> = when {
    t < 38 -> "Cool and safe" to Green
    t < 40 -> "Warm, keep an eye on it" to Gold
    t < 42 -> "Hot, lower graphics or pause" to Orange
    else -> "Too hot, take a break now" to Red
}

fun setDnd(c: Context, on: Boolean): Boolean {
    val nm = c.getSystemService(NotificationManager::class.java)
    if (!nm.isNotificationPolicyAccessGranted) {
        if (on) c.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return false
    }
    nm.setInterruptionFilter(
        if (on) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL
    )
    return true
}

fun su(cmd: String): String = try {
    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    out.trim()
} catch (e: Exception) { "" }

var savedGov = ""
const val GOV = "/sys/devices/system/cpu/cpu*/cpufreq/scaling_governor"

fun rootBoost() {
    if (savedGov.isEmpty()) savedGov = su("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
    su("for f in $GOV; do echo performance > \$f; done")
    su("am kill-all")
}

fun rootRestore() {
    if (savedGov.isNotEmpty()) su("for f in $GOV; do echo $savedGov > \$f; done")
}

fun clearApps(c: Context): Int {
    val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    var n = 0
    c.packageManager.getInstalledApplications(0).forEach {
        if (it.packageName != c.packageName && it.packageName != GAME &&
            (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        ) { am.killBackgroundProcesses(it.packageName); n++ }
    }
    return n
}

fun launchGame(c: Context): Boolean {
    val i = c.packageManager.getLaunchIntentForPackage(GAME) ?: return false
    c.startActivity(i)
    return true
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(this) }
    }
}

@Composable
fun App(act: ComponentActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var stats by remember { mutableStateOf(Stats()) }
    var hist by remember { mutableStateOf(listOf<Double>()) }
    var profile by remember { mutableStateOf(1) }
    var matchOn by remember { mutableStateOf(false) }
    var rootOn by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var lastPing by remember { mutableStateOf(-1) }
    var netAlert by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val p = withContext(Dispatchers.IO) { measurePing() }
            if (lastPing >= 0 && p < 0) netAlert = "Connection dropped"
            else if (p >= 0) netAlert = ""
            lastPing = p
            stats = readStats(ctx, p)
            hist = (hist + stats.temp).takeLast(30)
            delay(3000)
        }
    }

    LaunchedEffect(matchOn) {
        if (matchOn) {
            if (!setDnd(ctx, true)) { matchOn = false; msg = "Allow Do Not Disturb access, then try again" }
            else {
                act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                delay(600_000)
                matchOn = false
            }
        } else {
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setDnd(ctx, false)
        }
    }

    if (warn) AlertDialog(
        onDismissRequest = { warn = false },
        title = { Text("Root Mode warning") },
        text = {
            Text("Root Mode changes CPU settings. eFootball may detect root and flag your account. Higher clocks add heat and drain battery. Settings reset when you turn it off. Use at your own risk.")
        },
        confirmButton = {
            TextButton(onClick = {
                warn = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { su("id").contains("uid=0") }
                    rootOn = ok
                    msg = if (ok) "Root Mode on" else "Root access not available on this phone"
                }
            }) { Text("Enable") }
        },
        dismissButton = { TextButton(onClick = { warn = false }) { Text("Cancel") } }
    )

    Column(Modifier.fillMaxSize().background(Bg)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            when (tab) {
                0 -> { HomeTab(stats, msg) {
                    scope.launch {
                        val n = withContext(Dispatchers.Default) { clearApps(ctx) }
                        if (profile >= 1) setDnd(ctx, true)
                        if (profile == 2) {
                            act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            if (rootOn) withContext(Dispatchers.IO) { rootBoost() }
                        }
                        msg = "Boost done, cleared $n apps"
                    }
                }
                    Spacer(Modifier.height(16.dp))
                    Btn("Launch eFootball", Pink) {
                        if (!launchGame(ctx)) msg = "eFootball is not installed"
                    }
                }
                1 -> CoolTab(stats, hist) {
                    scope.launch {
                        val n = withContext(Dispatchers.Default) { clearApps(ctx) }
                        msg = "Cool-down: cleared $n apps. Remove the case and avoid charging while playing."
                    }
                }
                2 -> NetTab(stats, netAlert)
                else -> ToolsTab(profile, { profile = it }, matchOn, { matchOn = it }, rootOn) {
                    if (rootOn) { rootOn = false; scope.launch(Dispatchers.IO) { rootRestore() }; msg = "Root settings restored" }
                    else warn = true
                }
            }
            if (tab != 0 && msg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(msg, color = Gold, fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth().background(Card).padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("Home" to Gold, "Cooling" to Green, "Network" to Blue, "Tools" to Pink).forEachIndexed { i, (n, c) ->
                Text(
                    n, color = if (tab == i) c else Color(0xFFA79FD6),
                    fontWeight = if (tab == i) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.clickable { tab = i }.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun Btn(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color).clickable { onClick() }.padding(14.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Medium) }
}

@Composable
fun StatCard(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(color).padding(14.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun HomeTab(s: Stats, msg: String, onBoost: () -> Unit) {
    Text("eFootball booster", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    Text("Readiness ${score(s)} / 100", color = Color(0xFF5DCAA5), fontSize = 13.sp)
    Spacer(Modifier.height(24.dp))
    Box(Modifier.size(150.dp).clip(CircleShape).background(Purple).clickable { onBoost() }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(110.dp).clip(CircleShape).background(Gold), contentAlignment = Alignment.Center) {
            Text("BOOST", color = Color(0xFF412402), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
    if (msg.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(msg, color = Gold, fontSize = 13.sp) }
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("RAM free", "%.1f GB".format(s.ramGb), Green, Modifier.weight(1f))
        StatCard("Temp", "%.1f°C".format(s.temp), Orange, Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard("Ping", if (s.ping < 0) "--" else "${s.ping} ms", Blue, Modifier.weight(1f))
        StatCard("Battery", "${s.battery}%", Pink, Modifier.weight(1f))
    }
}

@Composable
fun CoolTab(s: Stats, hist: List<Double>, onCool: () -> Unit) {
    val (label, color) = heat(s.temp)
    Text("Cooling", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(12.dp))
    Text("%.1f°C".format(s.temp), color = color, fontSize = 40.sp, fontWeight = FontWeight.Medium)
    Text(label, color = color, fontSize = 14.sp)
    Spacer(Modifier.height(16.dp))
    Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(Card)) {
        if (hist.size < 2) return@Canvas
        val path = Path()
        hist.forEachIndexed { i, v ->
            val x = size.width * i / (hist.size - 1)
            val y = (size.height * (1 - (v.coerceIn(25.0, 50.0) - 25.0) / 25.0)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 6f))
    }
    if (s.charging) {
        Spacer(Modifier.height(12.dp))
        Text("Charging while playing adds heat. Unplug if it gets warm.", color = Orange, fontSize = 13.sp)
    }
    Spacer(Modifier.height(16.dp))
    Btn("Cool-down mode", Green, onCool)
}

@Composable
fun NetTab(s: Stats, alert: String) {
    val (label, color) = when {
        s.ping < 0 -> "No connection" to Red
        s.ping <= 50 -> "Great for online matches" to Green
        s.ping <= 80 -> "OK, small delays possible" to Gold
        else -> "High ping, expect lag" to Orange
    }
    Text("Network", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(12.dp))
    Text(if (s.ping < 0) "--" else "${s.ping} ms", color = color, fontSize = 40.sp, fontWeight = FontWeight.Medium)
    Text(label, color = color, fontSize = 14.sp)
    if (alert.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Text(alert, color = Red, fontSize = 14.sp) }
    Spacer(Modifier.height(16.dp))
    val cause = when {
        s.ping < 0 || s.ping > 80 -> "Lag cause: network. Try Wi-Fi closer to the router, or switch connection."
        s.temp >= 40 -> "Lag cause: heat. Your phone is throttling, cool it down."
        s.ramGb < 2 -> "Lag cause: low RAM. Run a boost before playing."
        else -> "No lag cause detected right now."
    }
    Text(cause, color = Color(0xFFD9D4FF), fontSize = 14.sp)
}

@Composable
fun ToolsTab(
    profile: Int, onProfile: (Int) -> Unit, match: Boolean, onMatch: (Boolean) -> Unit,
    root: Boolean, onRoot: () -> Unit
) {
    val names = listOf("Light" to Green, "Balanced" to Purple, "Max" to Orange)
    val desc = listOf(
        "Clears background apps.",
        "Clears apps and turns on Do Not Disturb.",
        "Clears apps, Do Not Disturb, screen stays on, plus root boost if Root Mode is on."
    )
    Text("Tools", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(16.dp))
    Text("Boost profile", color = Color(0xFFA79FD6), fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { i, (n, c) ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(999.dp))
                    .background(if (profile == i) c else Card).clickable { onProfile(i) }.padding(10.dp),
                contentAlignment = Alignment.Center
            ) { Text(n, color = Color.White, fontSize = 13.sp) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(desc[profile], color = Color(0xFFD9D4FF), fontSize = 13.sp)
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Match mode", color = Color.White, fontWeight = FontWeight.Medium)
            Text("Do Not Disturb and screen-on for 10 minutes", color = Color(0xFFA79FD6), fontSize = 12.sp)
        }
        Switch(checked = match, onCheckedChange = onMatch)
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Root Mode", color = Color.White, fontWeight = FontWeight.Medium)
            Text("Needs a rooted phone. CPU governor lock.", color = Color(0xFFA79FD6), fontSize = 12.sp)
        }
        Switch(checked = root, onCheckedChange = { onRoot() })
    }
}
