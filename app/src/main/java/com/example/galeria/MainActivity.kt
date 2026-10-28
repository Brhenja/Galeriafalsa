package com.example.galeria

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val Green = Color(0xFF5CD13F)

data class Item(val id: Long, val uri: Uri, val video: Boolean, val durMs: Long, val dateMs: Long)

sealed interface Screen {
    object Main : Screen
    object Trash : Screen
    data class View(val item: Item, val fromTrash: Boolean) : Screen
}

// Guarda en SharedPreferences: papelera (id:fecha) y "purgadas" (ocultas para siempre). NUNCA borra archivos reales.
class Store(c: Context) {
    private val p = c.getSharedPreferences("fake", Context.MODE_PRIVATE)
    fun trash(): Map<Long, Long> = (p.getStringSet("trash", emptySet()) ?: emptySet())
        .associate { val s = it.split(":"); s[0].toLong() to s[1].toLong() }
    fun purged(): Set<Long> = (p.getStringSet("purged", emptySet()) ?: emptySet()).map { it.toLong() }.toSet()
    fun save(t: Map<Long, Long>, g: Set<Long>) {
        p.edit()
            .putStringSet("trash", t.map { "${it.key}:${it.value}" }.toSet())
            .putStringSet("purged", g.map { it.toString() }.toSet())
            .apply()
    }
}

fun loadMedia(c: Context): List<Item> {
    val out = mutableListOf<Item>()
    val proj = arrayOf("_id", "media_type", "duration", "date_added")
    c.contentResolver.query(
        MediaStore.Files.getContentUri("external"), proj,
        "media_type IN (1,3)", null, "date_added DESC"
    )?.use { cur ->
        while (cur.moveToNext()) {
            val id = cur.getLong(0)
            val v = cur.getInt(1) == 3
            val base = if (v) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            out += Item(id, ContentUris.withAppendedId(base, id), v, cur.getLong(2), cur.getLong(3) * 1000)
        }
    }
    return out
}

fun dayLabel(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Hoy"
        today.minusDays(1) -> "Ayer"
        else -> d.format(DateTimeFormatter.ofPattern("EEE, d 'de' MMM", Locale("es")))
    }
}

fun dur(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Green, surface = Color.Black, background = Color.Black)) {
                Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
                    App()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    var trash by remember { mutableStateOf(store.trash()) }
    var purged by remember { mutableStateOf(store.purged()) }
    var items by remember { mutableStateOf(emptyList<Item>()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var tab by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(false) }

    val perms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    fun granted() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var ok by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = granted() }
    LaunchedEffect(ok) {
        if (ok) items = withContext(Dispatchers.IO) { loadMedia(ctx) } else launcher.launch(perms)
    }

    fun update(t: Map<Long, Long>, g: Set<Long>) { trash = t; purged = g; store.save(t, g) }
    val live = items.filter { it.id !in purged }
    val main = live.filter { it.id !in trash }
    val bin = live.filter { it.id in trash }

    BackHandler(screen != Screen.Main) {
        screen = if ((screen as? Screen.View)?.fromTrash == true) Screen.Trash else Screen.Main
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        when (val s = screen) {
            Screen.Main -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Imágenes", color = Green, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.CloudOff, null, tint = Green); Spacer(Modifier.width(16.dp))
                    Icon(Icons.Filled.Search, null, tint = Green); Spacer(Modifier.width(16.dp))
                    Icon(Icons.Filled.MoreVert, null, tint = Green)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (tab == 0) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            main.groupBy { dayLabel(it.dateMs) }.forEach { (label, list) ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(label, color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                                }
                                items(list, key = { it.id }) { m -> Thumb(m, loader) { screen = Screen.View(m, false) } }
                            }
                        }
                    } else {
                        Text("Próximamente", Modifier.align(Alignment.Center), color = Color.Gray)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    listOf("Imágenes", "Álbumes", "Historias").forEachIndexed { i, t ->
                        Text(t, color = if (tab == i) Green else Color.LightGray, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { tab = i })
                    }
                    Icon(Icons.Filled.Menu, null, tint = Green, modifier = Modifier.clickable { sheet = true })
                }
            }

            Screen.Trash -> {
                val imgs = bin.count { !it.video }
                val vids = bin.count { it.video }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                                IconButton({ screen = Screen.Main }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Green) }
                                Column(Modifier.weight(1f)) {
                                    Text("Papelera", color = Green, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                    Text("$imgs imágenes $vids videos", color = Color.Gray)
                                }
                                Text("Editar", color = Green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            Text(
                                "Las imágenes y los videos eliminados de Galería, Mis archivos y algunas aplicaciones de terceros permanecen en la Papelera durante 30 días antes de eliminarse de forma permanente.\n\nRecuerde vaciar la Papelera antes de retirar la tarjeta de memoria para asegurarse de que se eliminen los elementos de la Papelera.",
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    items(bin, key = { it.id }) { m ->
                        val days = (30 - (System.currentTimeMillis() - (trash[m.id] ?: 0L)) / 86_400_000L).toInt().coerceIn(0, 30)
                        Thumb(m, loader, "$days días") { screen = Screen.View(m, true) }
                    }
                }
            }

            is Screen.View -> Viewer(
                s.item, loader, s.fromTrash,
                onBack = { screen = if (s.fromTrash) Screen.Trash else Screen.Main },
                onDelete = { update(trash + (s.item.id to System.currentTimeMillis()), purged); screen = Screen.Main },
                onRestore = { update(trash - s.item.id, purged); screen = Screen.Trash },
                onPurge = { update(trash - s.item.id, purged + s.item.id); screen = Screen.Trash }
            )
        }
    }

    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        listOf(
            "Videos" to Icons.Outlined.PlayCircle, "Favoritos" to Icons.Outlined.FavoriteBorder,
            "Recientes" to Icons.Outlined.Schedule, "Sugerencias" to Icons.Outlined.Lightbulb,
            "Ubicaciones" to Icons.Outlined.LocationOn, "Álbumes comp." to Icons.Outlined.People,
            "Papelera" to Icons.Outlined.Delete, "Ajustes" to Icons.Outlined.Settings
        ).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                row.forEach { (name, ic) ->
                    Column(
                        Modifier.weight(1f).clickable { sheet = false; if (name == "Papelera") screen = Screen.Trash },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(ic, null); Text(name, textAlign = TextAlign.Center, fontSize = 14.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Thumb(m: Item, loader: ImageLoader, label: String? = null, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        AsyncImage(model = m.uri, contentDescription = null, imageLoader = loader,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (m.video) Row(
            Modifier.align(Alignment.BottomStart).padding(4.dp)
                .background(Color(0x66000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp)); Text(dur(m.durMs), fontSize = 12.sp)
        }
        if (label != null) Text(label, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color(0xAA000000)).padding(vertical = 6.dp), textAlign = TextAlign.Center)
    }
}

@Composable
fun Act(label: String, icon: ImageVector, onClick: () -> Unit) =
    Column(Modifier.clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null); Text(label)
    }

@Composable
fun Viewer(
    m: Item, loader: ImageLoader, fromTrash: Boolean,
    onBack: () -> Unit, onDelete: () -> Unit, onRestore: () -> Unit, onPurge: () -> Unit
) {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AsyncImage(model = m.uri, contentDescription = null, imageLoader = loader,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            if (m.video) Button(
                onClick = {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, "video/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) {}
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) { Text("Reproducir video") }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceAround) {
            if (fromTrash) {
                Act("Restaurar", Icons.Filled.Restore, onRestore)
                Act("Eliminar", Icons.Filled.Delete) { ask = true }
            } else Act("Eliminar", Icons.Filled.Delete, onDelete)
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        text = { Text("¿Desea eliminar definitivamente ${if (m.video) "este video" else "esta imagen"}?") },
        dismissButton = { TextButton({ ask = false }) { Text("Cancelar") } },
        confirmButton = { TextButton({ ask = false; onPurge() }) { Text("Eliminar", color = Color.Red) } }
    )
}
