package com.joy.android.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.joy.android.data.ChatLlmClient
import com.joy.android.data.ChatSession
import com.joy.android.data.SessionRouter
import com.joy.android.data.ImageCaptureMode
import com.joy.android.data.ImageOcrHelper
import com.joy.android.data.ImageVisionEncoder
import com.joy.android.data.JoySettings
import com.joy.android.data.JoySettingsStore
import com.joy.android.data.SessionSnapshot
import com.joy.android.data.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReplyUiState(
    val chatText: String = "",
    val candidates: List<String> = emptyList(),
    val loading: Boolean = false,
    val ocrLoading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    /** 当前耗时任务阶段文案，如「等待模型…」 */
    val busyPhase: String? = null,
    /** 当前任务已用毫秒 */
    val busyElapsedMs: Long = 0L,
    /** 会话是否已从磁盘加载 */
    val sessionsReady: Boolean = false,
)

enum class ConnectionProbeTarget { REPLY, VISION }

data class ConnectionProbeUi(
    val busy: Boolean = false,
    val success: Boolean? = null,
    val target: ConnectionProbeTarget? = null,
    val message: String? = null,
)

data class SessionsUiState(
    val activeId: String = "",
    val sessions: List<ChatSession> = emptyList(),
) {
    val active: ChatSession?
        get() = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()
}

class ReplyViewModel(
    private val store: JoySettingsStore,
    private val sessionStore: SessionStore,
    private val client: ChatLlmClient = ChatLlmClient(),
    private val ocr: ImageOcrHelper = ImageOcrHelper(),
) : ViewModel() {
    val settings: StateFlow<JoySettings> = store.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        JoySettings(),
    )

    private val _ui = MutableStateFlow(ReplyUiState())
    val ui: StateFlow<ReplyUiState> = _ui.asStateFlow()

    private val _sessions = MutableStateFlow(SessionsUiState())
    val sessions: StateFlow<SessionsUiState> = _sessions.asStateFlow()

    private val _probe = MutableStateFlow(ConnectionProbeUi())
    val connectionProbe: StateFlow<ConnectionProbeUi> = _probe.asStateFlow()

    private var elapsedJob: Job? = null
    private var recognizeJob: Job? = null
    private var generateJob: Job? = null
    private var probeJob: Job? = null
    private var persistJob: Job? = null

    init {
        viewModelScope.launch {
            val snap = withContext(Dispatchers.IO) { sessionStore.read() }
            applySnapshot(snap, syncUi = true)
            _ui.update { it.copy(sessionsReady = true) }
        }
    }

    fun onChatTextChange(value: String) {
        _ui.update { it.copy(chatText = value, error = null, info = null) }
        schedulePersistActive()
    }

    fun clearChat() {
        _ui.update {
            it.copy(
                chatText = "",
                candidates = emptyList(),
                error = null,
                info = "已清空当前会话内容",
            )
        }
        schedulePersistActive(immediate = true)
    }

    fun createSession(title: String = "新会话") {
        if (_ui.value.loading || _ui.value.ocrLoading) return
        viewModelScope.launch {
            flushActiveNow()
            val created = ChatSession.new(title)
            val next = SessionSnapshot(
                activeId = created.id,
                sessions = listOf(created) + _sessions.value.sessions,
            )
            applySnapshot(next, syncUi = true)
            persistSnapshot(next)
            _ui.update { it.copy(info = "已新建「${created.title}」", error = null) }
        }
    }

    fun switchSession(id: String) {
        if (id == _sessions.value.activeId) return
        if (_ui.value.loading || _ui.value.ocrLoading) return
        viewModelScope.launch {
            flushActiveNow()
            val list = _sessions.value.sessions
            if (list.none { it.id == id }) return@launch
            val next = SessionSnapshot(activeId = id, sessions = list)
            applySnapshot(next, syncUi = true)
            persistSnapshot(next)
            _ui.update { it.copy(info = null, error = null) }
        }
    }

    fun renameSession(id: String, title: String) {
        val trimmed = title.trim().ifEmpty { return }
        viewModelScope.launch {
            flushActiveNow()
            val list = _sessions.value.sessions.map { s ->
                if (s.id == id) {
                    s.copy(title = trimmed, updatedAt = System.currentTimeMillis())
                } else {
                    s
                }
            }
            val next = SessionSnapshot(
                activeId = _sessions.value.activeId,
                sessions = list,
            )
            applySnapshot(next, syncUi = false)
            persistSnapshot(next)
        }
    }

    /**
     * 识图落到会话库：新对象建档，已有对象切回并接上历史。
     */
    private suspend fun applyRecognizedSession(
        rawName: String?,
        incomingChat: String,
    ): SessionRouter.Result {
        flushActiveNow()
        val routed = SessionRouter.resolve(
            snap = SessionSnapshot(
                activeId = _sessions.value.activeId,
                sessions = _sessions.value.sessions,
            ),
            peerName = rawName,
            incomingChat = incomingChat,
        )
        applySnapshot(routed.snapshot, syncUi = true)
        persistSnapshot(routed.snapshot)
        return routed
    }

    fun deleteSession(id: String) {
        if (_ui.value.loading || _ui.value.ocrLoading) return
        viewModelScope.launch {
            flushActiveNow()
            var list = _sessions.value.sessions.filterNot { it.id == id }
            if (list.isEmpty()) {
                list = listOf(ChatSession.new("默认会话"))
            }
            val activeId =
                if (_sessions.value.activeId == id || list.none { it.id == _sessions.value.activeId }) {
                    list.first().id
                } else {
                    _sessions.value.activeId
                }
            val next = SessionSnapshot(activeId = activeId, sessions = list)
            applySnapshot(next, syncUi = true)
            persistSnapshot(next)
            _ui.update { it.copy(info = "已删除会话", error = null) }
        }
    }

    fun recognizeImage(context: Context, uri: Uri) {
        if (_ui.value.ocrLoading || _ui.value.loading) return
        val appContext = context.applicationContext
        val mode = settings.value.imageCaptureMode
        recognizeJob?.cancel()
        client.cancelActive()
        lateinit var job: Job
        job = viewModelScope.launch {
            val startHint = when (mode) {
                ImageCaptureMode.OCR -> "正在 OCR 识别…"
                ImageCaptureMode.VISION -> "正在视觉识别…"
            }
            startBusyTimer(
                ocrLoading = true,
                loading = false,
                phase = startHint,
                info = startHint,
            )
            try {
                val extracted = when (mode) {
                    ImageCaptureMode.OCR -> {
                        setBusyPhase("本地 OCR 识字…")
                        withContext(Dispatchers.IO) {
                            ocr.recognize(appContext, uri)
                        }
                    }
                    ImageCaptureMode.VISION -> {
                        setBusyPhase("压缩图片…")
                        val dataUrl = withContext(Dispatchers.IO) {
                            ensureActive()
                            ImageVisionEncoder.toDataUrl(appContext, uri)
                        }
                        ensureActive()
                        setBusyPhase("等待多模态模型…")
                        withContext(Dispatchers.IO) {
                            client.extractChatFromImage(settings.value, dataUrl)
                        }
                    }
                }
                ensureActive()
                val elapsed = _ui.value.busyElapsedMs
                val routed = applyRecognizedSession(extracted.peerName, extracted.chatText)
                val merged = routed.session.chatText
                val doneInfo = buildString {
                    append(
                        when (mode) {
                            ImageCaptureMode.OCR ->
                                "OCR 完成（${formatElapsed(elapsed)}）"
                            ImageCaptureMode.VISION ->
                                "视觉识别完成（${formatElapsed(elapsed)}）"
                        },
                    )
                    append(
                        when (routed.kind) {
                            SessionRouter.Kind.CREATE -> " · 已新建「${routed.session.title}」"
                            SessionRouter.Kind.SWITCH -> " · 已切回「${routed.session.title}」"
                            SessionRouter.Kind.RENAME -> " · 会话已命名「${routed.session.title}」"
                            SessionRouter.Kind.SAME -> " · 会话「${routed.session.title}」"
                        },
                    )
                }
                finishBusyIdle(
                    chatText = merged,
                    info = doneInfo,
                    error = null,
                    clearCandidates = true,
                )
                schedulePersistActive(immediate = true)
            } catch (e: CancellationException) {
                if (_ui.value.ocrLoading) {
                    finishCancelled(kind = CancelKind.RECOGNIZE)
                }
                throw e
            } catch (e: Exception) {
                if (isCancelMessage(e)) {
                    finishCancelled(kind = CancelKind.RECOGNIZE)
                } else {
                    val elapsed = _ui.value.busyElapsedMs
                    finishBusyIdle(
                        info = null,
                        error = "${e.message ?: e.javaClass.simpleName}（已用时 ${formatElapsed(elapsed)}）",
                    )
                }
            } finally {
                if (recognizeJob === job) {
                    recognizeJob = null
                }
            }
        }
        recognizeJob = job
    }

    fun cancelImageRecognition() {
        if (!_ui.value.ocrLoading) return
        cancelBusy(kind = CancelKind.RECOGNIZE)
    }

    fun cancelGenerate() {
        if (!_ui.value.loading) return
        cancelBusy(kind = CancelKind.GENERATE)
    }

    fun cancelBusyTask() {
        when {
            _ui.value.ocrLoading -> cancelBusy(kind = CancelKind.RECOGNIZE)
            _ui.value.loading -> cancelBusy(kind = CancelKind.GENERATE)
        }
    }

    fun generate() {
        if (_ui.value.loading || _ui.value.ocrLoading) return
        generateJob?.cancel()
        client.cancelActive()
        lateinit var job: Job
        job = viewModelScope.launch {
            startBusyTimer(
                ocrLoading = false,
                loading = true,
                phase = "正在生成回复…",
                info = null,
            )
            try {
                val list = withContext(Dispatchers.IO) {
                    client.generateReplies(settings.value, _ui.value.chatText)
                }
                ensureActive()
                val elapsed = _ui.value.busyElapsedMs
                finishBusyIdle(
                    info = "已生成 ${list.size} 条（${formatElapsed(elapsed)}）",
                    error = null,
                    candidates = list,
                )
                schedulePersistActive(immediate = true)
            } catch (e: CancellationException) {
                if (_ui.value.loading) {
                    finishCancelled(kind = CancelKind.GENERATE)
                }
                throw e
            } catch (e: Exception) {
                val elapsed = _ui.value.busyElapsedMs
                if (isCancelMessage(e)) {
                    finishCancelled(kind = CancelKind.GENERATE)
                } else {
                    finishBusyIdle(
                        info = null,
                        error = "${e.message ?: e.javaClass.simpleName}（已用时 ${formatElapsed(elapsed)}）",
                    )
                }
            } finally {
                if (generateJob === job) {
                    generateJob = null
                }
            }
        }
        generateJob = job
    }

    fun saveSettings(next: JoySettings, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            store.save(next)
            onDone()
        }
    }

    fun testConnection(
        draft: JoySettings,
        target: ConnectionProbeTarget = ConnectionProbeTarget.REPLY,
    ) {
        if (_probe.value.busy || _ui.value.loading || _ui.value.ocrLoading) return
        probeJob?.cancel()
        lateinit var job: Job
        val label = when (target) {
            ConnectionProbeTarget.REPLY -> "回复"
            ConnectionProbeTarget.VISION -> "视觉"
        }
        val effective = when (target) {
            ConnectionProbeTarget.REPLY -> draft
            ConnectionProbeTarget.VISION -> draft.copy(
                baseUrl = draft.resolvedVisionBaseUrl(),
                apiKey = draft.resolvedVisionApiKey(),
                model = draft.resolvedVisionModel(),
            )
        }
        job = viewModelScope.launch {
            _probe.value = ConnectionProbeUi(
                busy = true,
                target = target,
                message = "正在测试${label}通道…",
            )
            try {
                val msg = withContext(Dispatchers.IO) {
                    client.testConnection(effective)
                }
                _probe.value = ConnectionProbeUi(
                    busy = false,
                    success = true,
                    target = target,
                    message = "$label · $msg",
                )
            } catch (e: CancellationException) {
                _probe.value = ConnectionProbeUi()
                throw e
            } catch (e: Exception) {
                _probe.value = ConnectionProbeUi(
                    busy = false,
                    success = false,
                    target = target,
                    message = "$label · ${e.message ?: e.javaClass.simpleName}",
                )
            } finally {
                if (probeJob === job) {
                    probeJob = null
                }
            }
        }
        probeJob = job
    }

    fun clearConnectionProbe() {
        probeJob?.cancel()
        probeJob = null
        _probe.value = ConnectionProbeUi()
    }

    fun onAppBackground() {
        viewModelScope.launch {
            flushActiveNow()
        }
    }

    /** 从磁盘重载会话（悬浮球可能已改候选）。 */
    fun onAppForeground() {
        viewModelScope.launch {
            persistJob?.cancel()
            val snap = withContext(Dispatchers.IO) { sessionStore.read() }
            applySnapshot(snap, syncUi = true)
        }
    }

    private fun applySnapshot(snap: SessionSnapshot, syncUi: Boolean) {
        val normalized = SessionStore.normalize(snap)
        _sessions.value = SessionsUiState(
            activeId = normalized.activeId,
            sessions = normalized.sortedByRecent(),
        )
        if (syncUi) {
            val active = normalized.active()
            _ui.update {
                it.copy(
                    chatText = active.chatText,
                    candidates = active.candidates,
                )
            }
        }
    }

    private fun schedulePersistActive(immediate: Boolean = false) {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            if (!immediate) delay(450)
            flushActiveNow()
        }
    }

    private suspend fun flushActiveNow() {
        val state = _sessions.value
        val activeId = state.activeId
        if (activeId.isEmpty()) return
        val ui = _ui.value
        val now = System.currentTimeMillis()
        val list = state.sessions.map { s ->
            if (s.id == activeId) {
                s.copy(
                    chatText = ui.chatText,
                    candidates = ui.candidates,
                    updatedAt = now,
                )
            } else {
                s
            }
        }
        val next = SessionSnapshot(activeId = activeId, sessions = list)
        applySnapshot(next, syncUi = false)
        persistSnapshot(next)
    }

    private suspend fun persistSnapshot(snapshot: SessionSnapshot) {
        withContext(Dispatchers.IO) {
            sessionStore.write(snapshot)
        }
    }

    private enum class CancelKind { RECOGNIZE, GENERATE }

    private fun cancelBusy(kind: CancelKind) {
        client.cancelActive()
        when (kind) {
            CancelKind.RECOGNIZE -> {
                recognizeJob?.cancel()
                recognizeJob = null
            }
            CancelKind.GENERATE -> {
                generateJob?.cancel()
                generateJob = null
            }
        }
        finishCancelled(kind)
    }

    private fun finishCancelled(kind: CancelKind = CancelKind.RECOGNIZE) {
        val elapsed = _ui.value.busyElapsedMs
        val label = when (kind) {
            CancelKind.RECOGNIZE -> "已取消识别"
            CancelKind.GENERATE -> "已取消生成"
        }
        finishBusyIdle(
            info = "$label（已用时 ${formatElapsed(elapsed)}）",
            error = null,
        )
    }

    private fun finishBusyIdle(
        chatText: String? = null,
        info: String?,
        error: String?,
        clearCandidates: Boolean = false,
        candidates: List<String>? = null,
    ) {
        stopBusyTimer()
        _ui.update {
            it.copy(
                ocrLoading = false,
                loading = false,
                busyPhase = null,
                busyElapsedMs = 0L,
                chatText = chatText ?: it.chatText,
                info = info,
                error = error,
                candidates = when {
                    candidates != null -> candidates
                    clearCandidates -> emptyList()
                    else -> it.candidates
                },
            )
        }
    }

    private fun startBusyTimer(
        ocrLoading: Boolean,
        loading: Boolean,
        phase: String,
        info: String?,
    ) {
        elapsedJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        _ui.update {
            it.copy(
                ocrLoading = ocrLoading,
                loading = loading,
                busyPhase = phase,
                busyElapsedMs = 0L,
                error = null,
                info = info,
                candidates = if (loading) emptyList() else it.candidates,
            )
        }
        elapsedJob = viewModelScope.launch {
            while (isActive) {
                delay(200)
                _ui.update {
                    it.copy(busyElapsedMs = SystemClock.elapsedRealtime() - startedAt)
                }
            }
        }
    }

    private fun setBusyPhase(phase: String) {
        _ui.update { it.copy(busyPhase = phase, info = phase) }
    }

    private fun stopBusyTimer() {
        elapsedJob?.cancel()
        elapsedJob = null
    }

    companion object {
        fun formatElapsed(ms: Long): String {
            val totalSec = (ms.coerceAtLeast(0) / 1000.0)
            return if (totalSec < 60) {
                String.format("%.1fs", totalSec)
            } else {
                val m = (totalSec / 60).toInt()
                val s = totalSec - m * 60
                String.format("%d分%.0fs", m, s)
            }
        }

        private fun isCancelMessage(e: Exception): Boolean {
            val msg = e.message.orEmpty()
            return msg == "已取消" ||
                msg.contains("Canceled", ignoreCase = true) ||
                msg.contains("cancelled", ignoreCase = true)
        }

        fun factory(
            store: JoySettingsStore,
            sessionStore: SessionStore,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ReplyViewModel(store, sessionStore) as T
                }
            }
    }
}
