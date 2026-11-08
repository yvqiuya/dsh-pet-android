package com.dshpet.android.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dshpet.android.data.PetConfig
import com.dshpet.android.tts.TtsClient
import com.dshpet.android.tts.TtsPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 聊天界面状态管理：会话列表、当前会话、流式输出。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ChatRepo(app)
    private val config = PetConfig.get(app)

    private val _sessions = MutableStateFlow<List<ChatRepo.Session>>(emptyList())
    val sessions: StateFlow<List<ChatRepo.Session>> = _sessions.asStateFlow()

    private val _current = MutableStateFlow<ChatRepo.Session?>(null)
    val current: StateFlow<ChatRepo.Session?> = _current.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _streamText = MutableStateFlow("")
    val streamText: StateFlow<String> = _streamText.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** TTS 朗读状态：null = 空闲；否则是当前提示（"正在合成…" / 错误信息） */
    private val _ttsStatus = MutableStateFlow<String?>(null)
    val ttsStatus: StateFlow<String?> = _ttsStatus.asStateFlow()

    /** TTS 正在朗读（用于让按钮显示成"停止"） */
    private val _ttsSpeaking = MutableStateFlow(false)
    val ttsSpeaking: StateFlow<Boolean> = _ttsSpeaking.asStateFlow()

    private var streamJob: Job? = null
    private var ttsJob: Job? = null

    init {
        refresh()
        viewModelScope.launch { ensureSession() }
    }

    fun refresh() {
        viewModelScope.launch {
            _sessions.value = repo.list()
        }
    }

    private suspend fun ensureSession() {
        if (_current.value == null) {
            val list = repo.list()
            _current.value = list.firstOrNull() ?: repo.create()
            refresh()
        }
    }

    fun newSession() {
        viewModelScope.launch {
            val s = repo.create()
            _current.value = s
            refresh()
        }
    }

    fun selectSession(id: String) {
        viewModelScope.launch {
            _current.value = repo.get(id)
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            if (_current.value?.id == id) {
                val list = repo.list()
                _current.value = list.firstOrNull() ?: repo.create()
            }
            refresh()
        }
    }

    /** 当前会话的消息 + 正在流式的增量 */
    fun displayedMessages(): List<ChatRepo.Message> {
        val base = _current.value?.messages?.toList() ?: emptyList()
        val st = _streamText.value
        return if (st.isNotEmpty()) base + ChatRepo.Message("assistant", st) else base
    }

    fun send(text: String) {
        val session = _current.value ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        viewModelScope.launch {
            if (session.messages.none { it.role == "user" }) {
                repo.rename(session.id, repo.deriveTitle(trimmed))
            }
            val userMsg = ChatRepo.Message("user", trimmed)
            session.messages.add(userMsg)
            repo.save(session)
            refresh()

            _busy.value = true
            _streaming.value = true
            _streamText.value = ""

            val cfg = SseClient.ChatCfg(
                baseUrl = config.chatBaseUrl(),
                chatPath = config.chatPath(),
                model = config.chatModel(),
                apiKey = config.chatApiKey(),
                temperature = config.chatTemperature(),
                maxTokens = config.chatMaxTokens(),
                timeoutSec = config.chatTimeout(),
                verifySsl = config.chatVerifySsl(),
            )
            val history = session.messages.takeLast(20).map { SseClient.Msg(it.role, it.content) }
            streamJob = SseClient.stream(
                cfg = cfg,
                messages = history,
                onDelta = { _streamText.value += it },
                onError = { err ->
                    _streamText.value = if (_streamText.value.isBlank()) "（错误）$err" else _streamText.value + "\n\n（错误）$err"
                },
                onDone = {
                    val full = _streamText.value
                    if (full.isNotBlank()) {
                        session.messages.add(ChatRepo.Message("assistant", full))
                    }
                    repoSave(session)
                    _streamText.value = ""
                    _streaming.value = false
                    _busy.value = false
                    // AI 回复完成后自动朗读（开关打开时）
                    if (full.isNotBlank()) speak(full, auto = true)
                },
            )
        }
    }

    // ================ 语音合成（MiniMax TTS） ================

    /**
     * 朗读一段文本。
     * @param auto true 表示是"回复完成后自动朗读"，此时不打断用户正在进行的其它朗读
     */
    fun speak(text: String, auto: Boolean = false) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (!config.ttsEnabled()) {
                if (!auto) _ttsStatus.value = "语音合成未启用（设置 → AI 对话 → 语音合成）"
                return@launch
            }
            val key = config.ttsApiKey()
            if (key.isBlank()) {
                if (!auto) _ttsStatus.value = "未配置 MiniMax API Key"
                return@launch
            }
            val clean = TtsClient.cleanForSpeech(text)
            if (clean.isBlank()) return@launch
            if (!auto) TtsPlayer.stop()
            _ttsStatus.value = "正在合成语音…"
            ttsJob?.cancel()
            ttsJob = TtsClient.synthesize(
                app,
                TtsClient.TtsCfg(
                    baseUrl = config.ttsBaseUrl(),
                    apiKey = key,
                    model = config.ttsModel(),
                    voiceId = config.ttsVoice(),
                    speed = config.ttsSpeed(),
                    vol = config.ttsVol(),
                    pitch = config.ttsPitch(),
                    emotion = config.ttsEmotion(),
                ),
                clean,
            ) { r ->
                r.fold(
                    onSuccess = { file ->
                        _ttsSpeaking.value = true
                        TtsPlayer.play(file) {
                            _ttsSpeaking.value = false
                            _ttsStatus.value = null
                        }
                        _ttsStatus.value = null
                    },
                    onFailure = { e ->
                        _ttsSpeaking.value = false
                        _ttsStatus.value = e.message ?: "语音合成失败"
                    },
                )
            }
        }
    }

    /** 停止朗读 */
    fun stopSpeaking() {
        ttsJob?.cancel()
        ttsJob = null
        TtsPlayer.stop()
        _ttsSpeaking.value = false
        _ttsStatus.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopSpeaking()
    }

    fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        _streaming.value = false
        _busy.value = false
        val s = _current.value
        if (s != null) {
            val full = _streamText.value
            if (full.isNotBlank()) s.messages.add(ChatRepo.Message("assistant", full))
            repoSave(s)
        }
        _streamText.value = ""
    }

    private fun repoSave(session: ChatRepo.Session) {
        viewModelScope.launch {
            repo.save(session)
            refresh()
        }
    }
}
