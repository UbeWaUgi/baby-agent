package com.yugainanal.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yugainanal.app.media.GalleryMedia
import com.yugainanal.app.media.GalleryMediaKind
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GalleryUiState(
    val media: List<GalleryMedia> = emptyList(),
    val hasReference: Boolean = false,
    val processing: Boolean = false,
    val automaticEnabled: Boolean = false,
    val accessLabel: String = "",
    val scanDetail: String = "",
    val canScan: Boolean = false,
    val reviewItems: List<GalleryMedia> = emptyList(),
    val showAccessAction: Boolean = false,
)

@Composable
internal fun GallerySetupCard(
    state: GalleryUiState,
    onChooseReference: () -> Unit,
    onScanGallery: () -> Unit,
    onToggleAutomatic: (Boolean) -> Unit,
    onAllowGallery: () -> Unit,
    onReviewMedia: (GalleryMedia, Boolean) -> Unit,
    onClearReference: (() -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var reviewOpen by rememberSaveable { mutableStateOf(false) }
    var clearOpen by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("갤러리 자동 정리", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            state.processing -> "사진과 영상을 정리하고 있어요."
                            state.automaticEnabled -> "자동 정리 켜짐"
                            state.hasReference -> "기준 사진 등록됨"
                            else -> "아이 얼굴이 한 명만 나온 사진을 골라 주세요."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.processing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (state.hasReference) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "설정") }
                }
            }
            if (state.scanDetail.isNotBlank()) {
                Text(state.scanDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.hasReference) {
                Button(onClick = onChooseReference, enabled = !state.processing) { Text("기준 사진 선택") }
            } else if (expanded) {
                if (state.accessLabel.isNotBlank()) {
                    Text(state.accessLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!state.canScan || state.showAccessAction) {
                    TextButton(onClick = onAllowGallery) { Text("갤러리 접근 설정") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("새 항목 자동 정리", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.automaticEnabled,
                        onCheckedChange = onToggleAutomatic,
                        enabled = state.canScan || state.automaticEnabled,
                        modifier = Modifier.semantics { contentDescription = "새 항목 자동 정리" },
                    )
                }
                TextButton(onClick = onChooseReference, enabled = !state.processing) { Text("기준 사진 바꾸기") }
                if (onClearReference != null) {
                    TextButton(onClick = { clearOpen = true }, enabled = !state.processing) { Text("기준 사진 삭제") }
                }
            }
            if (state.hasReference && !state.canScan && !expanded) {
                TextButton(onClick = onAllowGallery) { Text("갤러리 접근 설정") }
            }
            if (state.hasReference && state.canScan) {
                OutlinedButton(onClick = onScanGallery, enabled = !state.processing) { Text("지금 정리") }
            }
            if (state.reviewItems.isNotEmpty()) {
                TextButton(onClick = { reviewOpen = true }) { Text("확인할 사진·영상 ${state.reviewItems.size}건") }
            }
        }
    }
    if (reviewOpen) {
        GalleryReviewDialog(state.reviewItems, onReviewMedia, onClose = { reviewOpen = false })
    }
    if (clearOpen && onClearReference != null) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("기준 사진을 삭제할까요?") },
            text = { Text("아이 갤러리 자동 정리가 꺼지고 자동으로 정리한 항목이 달력에서 사라져요.") },
            confirmButton = {
                TextButton(onClick = {
                    clearOpen = false
                    onClearReference()
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("취소") } },
        )
    }
}

@Composable
internal fun GalleryMediaSection(
    media: List<GalleryMedia>,
    featured: Boolean,
    onExclude: ((GalleryMedia) -> Unit)? = null,
) {
    if (media.isEmpty()) return
    var openedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var excludedUri by rememberSaveable { mutableStateOf<String?>(null) }
    val count = buildList {
        val photos = media.count { it.kind == GalleryMediaKind.PHOTO }
        val videos = media.size - photos
        if (photos > 0) add("사진 ${photos}장")
        if (videos > 0) add("영상 ${videos}개")
    }.joinToString(" · ")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("자동 정리 · $count", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (featured) {
                val pager = rememberPagerState(pageCount = { media.size })
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(10.dp)),
                    key = { media[it].uri.toString() },
                ) { index ->
                    val item = media[index]
                    GalleryMediaThumbnail(item, Modifier.fillMaxSize(), sidePixels = 768) { openedUri = item.uri.toString() }
                }
                val current = media.getOrNull(pager.currentPage)
                if (media.size > 1 || (current != null && onExclude != null)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (media.size > 1) "${pager.currentPage.coerceAtMost(media.lastIndex) + 1} / ${media.size}" else "",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (current != null && onExclude != null) {
                            TextButton(onClick = { excludedUri = current.uri.toString() }) { Text("정리에서 제외") }
                        }
                    }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(media, key = { it.uri.toString() }) { item ->
                        GalleryMediaThumbnail(
                            item,
                            Modifier.width(86.dp).height(68.dp).clip(RoundedCornerShape(9.dp)),
                        ) { openedUri = item.uri.toString() }
                    }
                }
            }
        }
    }
    val opened = media.firstOrNull { it.uri.toString() == openedUri }
    if (opened != null) {
        GalleryMediaViewer(
            opened,
            onExclude = onExclude?.let {
                {
                    openedUri = null
                    excludedUri = opened.uri.toString()
                }
            },
            onClose = { openedUri = null },
        )
    }
    val excluded = media.firstOrNull { it.uri.toString() == excludedUri }
    if (excluded != null && onExclude != null) {
        AlertDialog(
            onDismissRequest = { excludedUri = null },
            title = { Text("이 항목을 정리에서 제외할까요?") },
            text = { Text("이 항목이 아이의 사진 달력과 리포트에서 빠져요.") },
            confirmButton = {
                TextButton(onClick = {
                    excludedUri = null
                    onExclude(excluded)
                }) { Text("제외") }
            },
            dismissButton = { TextButton(onClick = { excludedUri = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun GalleryReviewDialog(
    media: List<GalleryMedia>,
    onReview: (GalleryMedia, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var openedUri by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("확인할 사진·영상") },
        text = {
            if (media.isEmpty()) {
                Text("확인할 항목이 없어요.")
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Text("우리 아이가 나온 항목인지 확인해 주세요.", style = MaterialTheme.typography.bodySmall) }
                    items(media, key = { it.uri.toString() }) { item ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            GalleryMediaThumbnail(item, Modifier.size(66.dp).clip(RoundedCornerShape(9.dp))) {
                                openedUri = item.uri.toString()
                            }
                            Column(Modifier.weight(1f)) {
                                Text(item.date.format(dayLabel), style = MaterialTheme.typography.titleSmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    TextButton(
                                        onClick = { onReview(item, true) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 3.dp),
                                    ) { Text("맞아요") }
                                    TextButton(
                                        onClick = { onReview(item, false) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 3.dp),
                                    ) { Text("아니에요") }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
    val selected = media.firstOrNull { it.uri.toString() == openedUri }
    if (selected != null) GalleryMediaViewer(selected) { openedUri = null }
}

@Composable
private fun GalleryMediaThumbnail(
    media: GalleryMedia,
    modifier: Modifier,
    sidePixels: Int = 320,
    onOpen: () -> Unit,
) {
    val kind = if (media.kind == GalleryMediaKind.VIDEO) "영상 재생" else "사진 크게 보기"
    Box(modifier.clickable(onClickLabel = kind, onClick = onOpen)) {
        GalleryMediaImage(media, Modifier.fillMaxSize(), sidePixels, useThumbnail = true)
        if (media.kind == GalleryMediaKind.VIDEO) {
            Text(
                "▶ 영상",
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 5.dp, vertical = 3.dp),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun GalleryMediaImage(
    media: GalleryMedia,
    modifier: Modifier,
    sidePixels: Int,
    useThumbnail: Boolean,
) {
    val context = LocalContext.current.applicationContext
    val image by produceState<GalleryImage>(GalleryImage.Loading, media.uri, media.kind, sidePixels, useThumbnail) {
        value = GalleryImage.Loading
        val bitmap = withContext(Dispatchers.IO) { decodeGalleryImage(context, media, sidePixels, useThumbnail) }
        value = bitmap?.let { GalleryImage.Ready(it.asImageBitmap()) } ?: GalleryImage.Unavailable
    }
    when (val result = image) {
        is GalleryImage.Ready -> Image(
            result.image,
            if (media.kind == GalleryMediaKind.VIDEO) "영상 미리 보기" else "갤러리 사진",
            modifier,
            contentScale = if (useThumbnail) ContentScale.Crop else ContentScale.Fit,
        )
        GalleryImage.Loading, GalleryImage.Unavailable -> Box(
            modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (result == GalleryImage.Loading) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
            else Text("열 수 없어요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private sealed interface GalleryImage {
    data object Loading : GalleryImage
    data object Unavailable : GalleryImage
    data class Ready(val image: ImageBitmap) : GalleryImage
}

private fun decodeGalleryImage(context: Context, media: GalleryMedia, sidePixels: Int, thumbnail: Boolean): Bitmap? {
    val resolver = context.contentResolver
    if (thumbnail && Build.VERSION.SDK_INT >= 29) {
        // Thumbnail providers may reject individual files. Try the original once in that case.
        try {
            return resolver.loadThumbnail(media.uri, Size(sidePixels, sidePixels), null)
        } catch (_: IOException) {
        } catch (_: SecurityException) {
            return null
        } catch (_: RuntimeException) {
        }
    }
    return try {
        if (media.kind == GalleryMediaKind.VIDEO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, media.uri)
                if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sidePixels, sidePixels)
                } else {
                    retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                        val scale = minOf(1f, sidePixels.toFloat() / maxOf(frame.width, frame.height))
                        val reduced = Bitmap.createScaledBitmap(
                            frame,
                            maxOf(1, (frame.width * scale).toInt()),
                            maxOf(1, (frame.height * scale).toInt()),
                            true,
                        )
                        if (reduced !== frame) frame.recycle()
                        reduced
                    }
                }
            } finally {
                retriever.release()
            }
        } else if (Build.VERSION.SDK_INT >= 28) {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(resolver, media.uri)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                val scale = minOf(1f, sidePixels.toFloat() / longest)
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            }
        } else {
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(media.uri)?.use { BitmapFactory.decodeStream(it, null, dimensions) }
            val longest = maxOf(dimensions.outWidth, dimensions.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / sample > sidePixels * 2) sample *= 2
            resolver.openInputStream(media.uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

@Composable
private fun GalleryMediaViewer(media: GalleryMedia, onExclude: (() -> Unit)? = null, onClose: () -> Unit) {
    val video = media.kind == GalleryMediaKind.VIDEO
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(media.date.format(shortDate) + if (video) " 영상" else " 사진") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (video) {
                    key(media.uri) { GalleryVideoPlayer(media.uri) }
                } else {
                    GalleryMediaImage(media, Modifier.fillMaxWidth().height(300.dp), sidePixels = 1280, useThumbnail = false)
                }
                Text(
                    media.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (media.dateEstimated) {
                    Text("촬영일 정보가 없어 파일 날짜로 분류했어요.", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        dismissButton = onExclude?.let { exclude -> { TextButton(onClick = exclude) { Text("정리에서 제외") } } },
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
    )
}

@Composable
private fun GalleryVideoPlayer(uri: Uri) {
    var unavailable by remember(uri) { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (unavailable) {
            Text("영상을 열 수 없어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        val controller = MediaController(context)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setOnErrorListener { _, _, _ ->
                            unavailable = true
                            true
                        }
                        setOnPreparedListener { start() }
                        try {
                            setVideoURI(uri)
                        } catch (_: RuntimeException) {
                            unavailable = true
                        }
                    }
                },
                onReset = null,
                onRelease = { it.stopPlayback() },
                update = {},
            )
        }
    }
}
