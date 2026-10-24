package com.wing.folderplayer.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.wing.folderplayer.R
import com.wing.folderplayer.data.source.MusicFile
import com.wing.folderplayer.data.source.SourceException
import com.wing.folderplayer.data.viewer.TextEncoding
import com.wing.folderplayer.data.viewer.TextPreview
import com.wing.folderplayer.data.viewer.ViewerCache
import com.wing.folderplayer.data.viewer.ViewerKind
import com.wing.folderplayer.data.viewer.ViewerText
import com.wing.folderplayer.data.viewer.ViewerTypes
import com.wing.folderplayer.ui.player.PlaybackErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/** A file open in the viewer: [files] are what it can swipe through (the images of the list for an image, else the file alone). */
data class ViewerRequest(val files: List<MusicFile>, val index: Int) {
    val file: MusicFile get() = files[index]
}

/** Reads the beginning of a text file (bytes, and whether the file goes on after them). */
typealias TextLoader = suspend (MusicFile) -> Pair<ByteArray, Boolean>

/** The local copy of a file ([ViewerCache]); the callback gets the bytes copied so far. */
typealias FileLoader = suspend (MusicFile, (Long) -> Unit) -> File

private sealed interface Load<out T> {
    object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

private fun describeFailure(context: Context, e: Throwable): String = when (e) {
    is SourceException.TooLarge -> context.getString(R.string.viewer_too_large, formatSize(ViewerCache.MAX_FILE))
    is SecurityException -> context.getString(R.string.viewer_pdf_protected)
    else -> context.getString(R.string.viewer_failed, PlaybackErrorText.describe(e))
}

/**
 * The browser's viewer for files beside the songs: text (encoding chosen automatically or by hand, optional fixed-width
 * font), images (swipe to the other images of the folder, pinch or double tap to zoom) and PDF (pages one below the
 * other, zoom likewise). Files of network sources are read from the source; "open in another app" hands a local copy over.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileViewerDialog(
    request: ViewerRequest,
    loadText: TextLoader,
    loadFile: FileLoader,
    onOpenExternal: (MusicFile) -> Unit,
    /** Rename, move or delete of the file shown (asked in the ⋮ menu; [overlay] shows its dialog). */
    onFileOp: (FileOp) -> Unit,
    onDismiss: () -> Unit,
    overlay: @Composable () -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = request.index) { request.files.size }
        val current = request.files[pager.currentPage.coerceIn(0, request.files.lastIndex)]
        val kind = ViewerTypes.kind(current.name)
        var encoding by remember(current) { mutableStateOf(TextEncoding.AUTO) }
        var usedEncoding by remember(current) { mutableStateOf<TextEncoding?>(null) }
        var monospace by remember(current) { mutableStateOf(ViewerTypes.monospaceByDefault(current.name)) }
        var pdfPage by remember(current) { mutableStateOf<Pair<Int, Int>?>(null) }
        var menuOpen by remember { mutableStateOf(false) }

        Surface(modifier = Modifier.fillMaxSize().testTag("file_viewer"), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    TopAppBar(
                        windowInsets = WindowInsets(0),
                        navigationIcon = {
                            IconButton(onClick = onDismiss, modifier = Modifier.testTag("viewer_close")) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                            }
                        },
                        title = {
                            Column {
                                Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.testTag("viewer_title"))
                                val subtitle = when (kind) {
                                    ViewerKind.IMAGE -> if (request.files.size > 1) stringResource(R.string.viewer_image_count, pager.currentPage + 1, request.files.size) else null
                                    ViewerKind.PDF -> pdfPage?.let { stringResource(R.string.viewer_pdf_page, it.first, it.second) }
                                    ViewerKind.TEXT -> usedEncoding?.let { if (encoding == TextEncoding.AUTO) stringResource(R.string.viewer_encoding_auto, it.label) else it.label }
                                    null -> null
                                }
                                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, modifier = Modifier.testTag("viewer_subtitle"))
                            }
                        },
                        actions = {
                            IconButton(onClick = { onOpenExternal(current) }, modifier = Modifier.testTag("viewer_open_external")) {
                                Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.viewer_open_external))
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("viewer_menu")) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.browser_more))
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_rename)) },
                                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                                        onClick = { menuOpen = false; onFileOp(FileOp.Rename(current)) },
                                        modifier = Modifier.testTag("viewer_rename"),
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.file_move)) },
                                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                                        onClick = { menuOpen = false; onFileOp(FileOp.Move(current)) },
                                        modifier = Modifier.testTag("viewer_move"),
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_delete)) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                        onClick = { menuOpen = false; onFileOp(FileOp.Delete(current)) },
                                        modifier = Modifier.testTag("viewer_delete"),
                                    )
                                    if (kind == ViewerKind.TEXT) {
                                        Divider()
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.viewer_monospace)) },
                                            leadingIcon = { if (monospace) Icon(Icons.Default.Check, contentDescription = null) },
                                            onClick = { monospace = !monospace; menuOpen = false },
                                            modifier = Modifier.testTag("viewer_monospace"),
                                        )
                                        Divider()
                                        Text(stringResource(R.string.viewer_encoding), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                        TextEncoding.values().forEach { enc ->
                                            DropdownMenuItem(
                                                text = { Text(if (enc == TextEncoding.AUTO) usedEncoding?.takeIf { encoding == TextEncoding.AUTO }?.let { stringResource(R.string.viewer_encoding_auto, it.label) } ?: stringResource(R.string.viewer_encoding_automatic) else enc.label) },
                                                leadingIcon = { if (enc == encoding) Icon(Icons.Default.Check, contentDescription = null) },
                                                onClick = { encoding = enc; menuOpen = false },
                                                modifier = Modifier.testTag("viewer_encoding_${enc.name}"),
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (kind) {
                        ViewerKind.IMAGE -> HorizontalPager(
                            state = pager,
                            key = { request.files[it].sourceId + request.files[it].path },
                            modifier = Modifier.fillMaxSize().testTag("viewer_pager"),
                        ) { i -> ImageContent(request.files[i], loadFile) }
                        ViewerKind.TEXT -> TextContent(current, encoding, monospace, loadText, onDecoded = { usedEncoding = it.encoding })
                        ViewerKind.PDF -> PdfContent(current, loadFile, onPage = { page, count -> pdfPage = page to count })
                        null -> Unit
                    }
                }
            }
        }
        overlay()
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun LoadingIndicator(file: MusicFile, copied: Long) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.testTag("viewer_loading"))
            if (copied > 0) Text(
                if (file.size > 0) stringResource(R.string.viewer_progress_of, formatSize(copied), formatSize(file.size)) else formatSize(copied),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailureText(message: String) {
    Centered {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("viewer_error"))
    }
}

/** Loads the local copy of [file] for display, with the bytes copied so far for the progress text. */
@Composable
private fun rememberLocalCopy(file: MusicFile, loadFile: FileLoader): Pair<Load<File>, Long> {
    val context = LocalContext.current
    var copied by remember(file) { mutableLongStateOf(0L) }
    val state by produceState<Load<File>>(Load.Loading, file) {
        value = try {
            var shown = 0L
            Load.Done(loadFile(file) { n -> if (n - shown >= 256 * 1024) { shown = n; copied = n } })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(describeFailure(context, e))
        }
    }
    return state to copied
}

// ---------------- text ----------------

@Composable
private fun TextContent(file: MusicFile, encoding: TextEncoding, monospace: Boolean, loadText: TextLoader, onDecoded: (TextPreview) -> Unit) {
    val context = LocalContext.current
    val raw by produceState<Load<Pair<ByteArray, Boolean>>>(Load.Loading, file) {
        value = try { Load.Done(loadText(file)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Load.Failed(describeFailure(context, e)) }
    }
    val preview by produceState<TextPreview?>(null, raw, encoding) {
        val r = raw as? Load.Done ?: return@produceState
        value = withContext(Dispatchers.Default) { ViewerText.decode(r.value.first, r.value.second, encoding) }
    }
    when (val r = raw) {
        is Load.Failed -> FailureText(r.message)
        Load.Loading -> LoadingIndicator(file, 0)
        is Load.Done -> preview?.let { p ->
            LaunchedEffect(p) { onDecoded(p) }
            // One item per line: a long log stays quick to scroll. Tabs become spaces (columns of rip logs).
            val lines = remember(p) { p.text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n') }
            val family = if (monospace) FontFamily.Monospace else null
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().testTag("viewer_text"), contentPadding = PaddingValues(16.dp)) {
                    if (p.truncated) item {
                        Text(stringResource(R.string.viewer_text_truncated, formatSize(ViewerText.LIMIT)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(bottom = 12.dp).testTag("viewer_truncated"))
                    }
                    items(lines.size) { i ->
                        Text(lines[i], fontFamily = family, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } ?: LoadingIndicator(file, 0)
    }
}

// ---------------- zoom ----------------

/** Zoom and offset of a picture or of the PDF pages, applied with graphicsLayer around the centre of the area. */
@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size = IntSize.Zero

    fun reset() { scale = 1f; offset = Offset.Zero }

    /** Applies a gesture step; returns the vertical part of the pan that the zoomed content could not follow. */
    fun transform(zoom: Float, pan: Offset, centroid: Offset): Float {
        val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        val k = newScale / scale
        val center = Offset(size.width / 2f, size.height / 2f)
        // The point under the fingers stays under them.
        val wanted = (centroid - center) * (1 - k) + offset * k + pan
        scale = newScale
        val clamped = clamp(wanted)
        offset = clamped
        return wanted.y - clamped.y
    }

    fun clamp(o: Offset): Offset {
        val maxX = size.width * (scale - 1) / 2f
        val maxY = size.height * (scale - 1) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun doubleTap(at: Offset) {
        if (scale > 1f) { reset(); return }
        transform(DOUBLE_TAP_ZOOM, Offset.Zero, at)
    }

    companion object {
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}

/**
 * Pinch to zoom, drag while zoomed, double tap to zoom in or back out. A single finger at normal size is left to what is
 * below (the image pager swipes, the PDF list scrolls). [onOverflowY] gets the vertical drag the zoomed content could not
 * follow (the PDF list scrolls by it).
 */
private fun Modifier.zoomable(state: ZoomState, onOverflowY: ((Float) -> Unit)? = null): Modifier = this
    // The zoomed content is drawn larger than its area: it must not cover the top bar.
    .clipToBounds()
    .onSizeChanged { state.size = it }
    .pointerInput(state) { detectTapGestures(onDoubleTap = { state.doubleTap(it) }) }
    .pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1 || state.scale > 1f) {
                    val overflow = state.transform(event.calculateZoom(), event.calculatePan(), event.calculateCentroid(useCurrent = true))
                    if (overflow != 0f) onOverflowY?.invoke(overflow / state.scale)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (state.scale <= 1f) state.reset()
        }
    }

private fun Modifier.zoomed(state: ZoomState): Modifier = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}

// ---------------- image ----------------

@Composable
private fun ImageContent(file: MusicFile, loadFile: FileLoader) {
    val (state, copied) = rememberLocalCopy(file, loadFile)
    when (state) {
        Load.Loading -> LoadingIndicator(file, copied)
        is Load.Failed -> FailureText(state.message)
        is Load.Done -> {
            val context = LocalContext.current
            val zoom = remember(file) { ZoomState() }
            var failed by remember(file) { mutableStateOf(false) }
            if (failed) FailureText(stringResource(R.string.viewer_image_failed))
            else Box(Modifier.fillMaxSize().zoomable(zoom)) {
                AsyncImage(
                    // Large enough to zoom into, small enough for the memory of any phone (a 50 MP photo is scaled down).
                    model = remember(state.value) {
                        ImageRequest.Builder(context).data(state.value).size(MAX_IMAGE_PX).scale(Scale.FIT).precision(Precision.INEXACT).build()
                    },
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize().zoomed(zoom).testTag("viewer_image"),
                )
            }
        }
    }
}

private const val MAX_IMAGE_PX = 2560

// ---------------- PDF ----------------

/**
 * A PDF opened with [PdfRenderer]. The renderer allows one page at a time, so opening, rendering and closing all run on
 * one thread of their own, in order (a page still rendering when the viewer closes finishes before the file is closed).
 */
private class PdfDocument(file: File) : Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
    @Volatile private var closed = false

    /** Width and height of each page in points. */
    val pageSizes: List<IntSize> = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { IntSize(it.width, it.height) } }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(dispatcher) {
        if (closed) return@withContext null
        renderer.openPage(index).use { page ->
            val w = widthPx.coerceIn(1, MAX_PDF_PX)
            val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, MAX_PDF_PX * 2)
            val bmp = createBitmap(w, h)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    override fun close() {
        closed = true
        executor.execute { runCatching { renderer.close() }; runCatching { fd.close() } }
        executor.shutdown()
    }
}

private const val MAX_PDF_PX = 2048

@Composable
private fun PdfContent(file: MusicFile, loadFile: FileLoader, onPage: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val (copy, copied) = rememberLocalCopy(file, loadFile)
    val doc by produceState<Load<PdfDocument>>(Load.Loading, copy) {
        val c = copy
        value = when (c) {
            is Load.Done -> try {
                // Not cancelled half-way: a document opened after the viewer closed is closed here, not leaked.
                val pdf = withContext(Dispatchers.IO + NonCancellable) { PdfDocument(c.value) }
                if (!isActive) { pdf.close(); return@produceState }
                Load.Done(pdf)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(describeFailure(context, e))
            }
            is Load.Failed -> c
            Load.Loading -> Load.Loading
        }
    }
    when (val d = doc) {
        Load.Loading -> LoadingIndicator(file, copied)
        is Load.Failed -> FailureText(d.message)
        is Load.Done -> {
            val pdf = d.value
            DisposableEffect(pdf) { onDispose { pdf.close() } }
            val listState = rememberLazyListState()
            LaunchedEffect(pdf, listState) {
                snapshotFlow { listState.firstVisibleItemIndex }.collect { onPage(it + 1, pdf.pageSizes.size) }
            }
            PdfPages(pdf, listState)
        }
    }
}

@Composable
private fun PdfPages(pdf: PdfDocument, listState: LazyListState) {
    val zoom = remember(pdf) { ZoomState() }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            .zoomable(zoom, onOverflowY = { dy -> listState.dispatchRawDelta(-dy) })
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        // Rendered somewhat sharper than the screen, so that zoomed text stays readable.
        val renderWidth = (widthPx * 3 / 2).coerceAtMost(MAX_PDF_PX)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().zoomed(zoom).testTag("viewer_pdf"),
        ) {
            items(pdf.pageSizes.size) { i ->
                val size = pdf.pageSizes[i]
                val bitmap by produceState<Bitmap?>(null, pdf, i, renderWidth) { value = pdf.render(i, renderWidth) }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(size.width.coerceAtLeast(1).toFloat() / size.height.coerceAtLeast(1)).background(Color.White)
                        .testTag("viewer_pdf_page_$i"),
                    contentAlignment = Alignment.Center,
                ) {
                    val b = bitmap
                    if (b == null) CircularProgressIndicator()
                    else Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

// ---------------- another app ----------------

/** "Open in another app": gets the whole file (with a progress dialog that can be cancelled), then asks Android for an app. */
@Stable
class ExternalOpener internal constructor(private val context: Context, private val loadFile: FileLoader, private val scope: kotlinx.coroutines.CoroutineScope) {
    var busy by mutableStateOf<MusicFile?>(null)
        private set
    var copied by mutableLongStateOf(0L)
        private set
    private var job: Job? = null

    fun open(file: MusicFile) {
        job?.cancel()
        busy = file
        copied = 0
        job = scope.launch {
            try {
                var shown = 0L
                val local = loadFile(file) { n -> if (n - shown >= 256 * 1024) { shown = n; copied = n } }
                launchViewer(context, file, local)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, describeFailure(context, e), Toast.LENGTH_LONG).show()
            } finally {
                if (busy == file) busy = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
        busy = null
    }
}

@Composable
fun rememberExternalOpener(loadFile: FileLoader): ExternalOpener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) { ExternalOpener(context, loadFile, scope) }
}

/** The progress dialog while [opener] gets a file. */
@Composable
fun ExternalOpenProgress(opener: ExternalOpener) {
    val file = opener.busy ?: return
    AlertDialog(
        onDismissRequest = { opener.cancel() },
        title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("viewer_external_progress")) {
                Text(stringResource(R.string.viewer_getting_file))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = if (file.size > 0) (opener.copied.toFloat() / file.size).coerceIn(0f, 1f) else 0f,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (file.size > 0) Text(stringResource(R.string.viewer_progress_of, formatSize(opener.copied), formatSize(file.size)),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { opener.cancel() }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private fun launchViewer(context: Context, file: MusicFile, local: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".viewer", local)
    val mime = ViewerTypes.mimeType(file.name) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.viewer_no_app), Toast.LENGTH_LONG).show()
    }
}
