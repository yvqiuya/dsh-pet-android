package com.dshpet.android.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * 语音播放：进程内单例，保证同一时刻只有一段 TTS 在播。
 *
 * 桌宠的三个聊天界面（全屏对话 / 悬浮聊天窗 / 快捷聊天）可能同时存在，
 * 各自持有一个 ChatViewModel；用单例 + 播前打断，就不会出现两段语音叠在一起。
 */
object TtsPlayer {

    private const val TAG = "TtsPlayer"

    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    /** 是否正在播放 */
    @Volatile
    var isPlaying: Boolean = false
        private set

    /** 播放一个本地音频文件（mp3）。同一时刻只保留一段。 */
    @Synchronized
    fun play(file: File, onEnd: (() -> Unit)? = null) {
        stop()
        if (!file.exists() || file.length() == 0L) {
            onEnd?.invoke()
            return
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener {
                isPlaying = false
                currentFile = null
                releaseQuietly(it)
                onEnd?.invoke()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "播放失败 what=$what extra=$extra")
                isPlaying = false
                currentFile = null
                releaseQuietly(mp)
                onEnd?.invoke()
                true
            }
            mp.prepareAsync()
            player = mp
            currentFile = file
            isPlaying = true
        } catch (e: Exception) {
            Log.w(TAG, "播放异常", e)
            isPlaying = false
            currentFile = null
            releaseQuietly(mp)
            onEnd?.invoke()
        }
    }

    /** 停止当前播放（幂等） */
    @Synchronized
    fun stop() {
        val p = player
        player = null
        currentFile = null
        isPlaying = false
        if (p != null) {
            runCatching { if (p.isPlaying) p.stop() }
            releaseQuietly(p)
        }
    }

    private fun releaseQuietly(mp: MediaPlayer) {
        runCatching { mp.reset() }
        runCatching { mp.release() }
    }
}
