package com.yugainanal.app

import android.content.Context
import android.net.Uri
import com.yugainanal.app.media.ChildFaceMatcher
import com.yugainanal.app.media.ChildFaceProfileStore
import com.yugainanal.app.media.GalleryAccess
import com.yugainanal.app.media.GalleryIndex
import com.yugainanal.app.media.GalleryMedia
import com.yugainanal.app.media.GalleryScanStatus
import com.yugainanal.app.media.GalleryScanSummary
import com.yugainanal.app.media.GalleryScanWorker
import com.yugainanal.app.ui.GalleryUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class GalleryController(
    context: Context,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val profiles = ChildFaceProfileStore(appContext)
    private val index = GalleryIndex(appContext)
    private val settingsChanges = MutableStateFlow(0)
    private val registering = MutableStateFlow(emptySet<Long>())

    fun observe(childId: Long?): Flow<GalleryUiState> {
        if (childId == null) return flowOf(GalleryUiState())
        val state = combine(index.matches(childId), index.reviewItems(childId), index.summary(childId), registering) {
                matches, review, summary, pending ->
            val hasReference = profiles.load(childId) != null
            GalleryUiState(
                media = matches,
                reviewItems = review,
                hasReference = hasReference,
                automaticEnabled = summary.automaticEnabled,
                processing = childId in pending || summary.status in setOf(GalleryScanStatus.PENDING, GalleryScanStatus.SCANNING),
                scanDetail = if (childId in pending) "기준 사진에서 얼굴을 확인하고 있어요." else statusText(summary, hasReference, matches.size),
            )
        }
        return combine(state, settingsChanges) { value, _ ->
            val access = GalleryAccess.current(appContext)
            value.copy(
                media = if (access == GalleryAccess.NONE) emptyList() else value.media,
                reviewItems = if (access == GalleryAccess.NONE) emptyList() else value.reviewItems,
                hasReference = profiles.load(childId) != null,
                canScan = access != GalleryAccess.NONE,
                showAccessAction = access != GalleryAccess.FULL,
                accessLabel = when (access) {
                    GalleryAccess.NONE -> "갤러리 접근 권한이 필요해요."
                    GalleryAccess.SELECTED -> "허용한 사진과 영상에서 찾아요."
                    GalleryAccess.FULL -> "갤러리 사진과 영상에서 찾아요."
                },
            )
        }.flowOn(Dispatchers.IO)
    }

    fun refreshAccess() {
        index.accessChanged()
        settingsChanges.update { it + 1 }
    }

    fun registerReference(childId: Long, uri: Uri) {
        if (childId in registering.value) return
        registering.update { it + childId }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val embedding = ChildFaceMatcher(appContext).use { it.createReference(uri) }
                    profiles.save(childId, embedding)
                    index.profileChanged()
                }
                settingsChanges.update { it + 1 }
                notify("기준 사진을 등록했어요.")
                scan(childId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                notify(invalid.message?.takeIf { it.any { char -> char in '가'..'힣' } } ?: "아이 얼굴이 선명하게 하나만 보이는 사진을 골라 주세요.")
            } catch (_: Exception) {
                notify("기준 사진을 읽지 못했어요. 다른 사진을 골라 주세요.")
            } finally {
                registering.update { it - childId }
            }
        }
    }

    fun scan(childId: Long) = run("갤러리 정리를 시작하지 못했어요.") {
        if (profiles.load(childId) != null && GalleryAccess.current(appContext) != GalleryAccess.NONE) {
            GalleryScanWorker.enqueueNow(appContext, childId)
        }
    }

    fun setAutomatic(childId: Long, enabled: Boolean) = run("자동 정리 설정을 저장하지 못했어요.") {
        if (enabled && (profiles.load(childId) == null || GalleryAccess.current(appContext) == GalleryAccess.NONE)) return@run
        GalleryScanWorker.setAutomatic(appContext, childId, enabled)
    }

    fun clearReference(childId: Long) = run("기준 사진 정보를 삭제하지 못했어요.") {
        if (childId in registering.value) return@run
        withContext(Dispatchers.IO) {
            profiles.clear(childId)
            index.profileChanged()
        }
        settingsChanges.update { it + 1 }
        GalleryScanWorker.cancel(appContext, childId)
        index.clearChild(childId)
        notify("기준 사진 정보와 자동 정리 결과를 삭제했어요.")
    }

    fun review(media: GalleryMedia, isChild: Boolean) = run("사진·영상의 확인 결과를 저장하지 못했어요.") {
        index.review(media.childId, media.uri, isChild)
    }

    fun matches(childId: Long): Flow<List<GalleryMedia>> = index.matches(childId)

    private fun run(failure: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notify(failure)
            }
        }
    }

    private fun statusText(summary: GalleryScanSummary, hasReference: Boolean, visibleMatches: Int): String = when (summary.status) {
        GalleryScanStatus.IDLE -> if (hasReference) "정리를 시작하면 새 사진과 영상을 찾아요." else ""
        GalleryScanStatus.PENDING -> "정리를 기다리고 있어요."
        GalleryScanStatus.SCANNING -> "아직 처리하지 않은 사진과 영상을 확인 중이에요."
        GalleryScanStatus.COMPLETE -> buildString {
            append("모은 항목 ${visibleMatches}개 · 이번에 분석 ${summary.lastRunAnalyzed}개")
            if (summary.unreadable > 0) append(" · 읽기 어려운 항목 ${summary.unreadable}개")
        }
        GalleryScanStatus.NO_PERMISSION -> "갤러리 권한을 허용하면 정리를 계속할 수 있어요."
        GalleryScanStatus.NEEDS_REFERENCE -> ""
        GalleryScanStatus.ERROR -> "정리를 일시 중단했어요. 다시 누르면 남은 항목부터 확인해요."
    }
}
