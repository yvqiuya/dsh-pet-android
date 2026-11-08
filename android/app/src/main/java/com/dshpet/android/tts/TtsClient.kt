package com.dshpet.android.tts

import android.content.Context
import com.dshpet.android.util.jsonOptString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MiniMax 语音合成（TTS）客户端 —— 1:1 对接 MiniMax 官方同步合成接口。
 *
 * 接口：POST {baseUrl}/v1/t2a_v2
 *   中国站：https://api.minimaxi.com
 *   国际站：https://api.minimax.io
 *
 * 请求体：
 * ```json
 * {
 *   "model": "speech-2.6-hd",
 *   "text": "要合成的文本",
 *   "stream": false,
 *   "language_boost": "auto",
 *   "output_format": "hex",
 *   "voice_setting": { "voice_id": "female-shaonv", "speed": 1.0, "vol": 1.0, "pitch": 0, "emotion": "neutral" },
 *   "audio_setting": { "sample_rate": 32000, "bitrate": 128000, "format": "mp3", "channel": 1 }
 * }
 * ```
 *
 * 响应体：
 * ```json
 * { "data": { "audio": "<hex 编码的音频>", "status": 2 },
 *   "extra_info": { "audio_length": 11124, "usage_characters": 163, ... },
 *   "base_resp": { "status_code": 0, "status_msg": "success" } }
 * ```
 *
 * 注意 base_resp.status_code 才是真正的业务状态码：HTTP 200 也可能带非 0 的业务错误。
 */
object TtsClient {

    /** 单次合成文本长度上限（官方限制 10000，这里留足余量） */
    const val MAX_CHARS = 2000

    data class TtsCfg(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val voiceId: String,
        val speed: Float,
        val vol: Float,
        val pitch: Int,
        val emotion: String,
        val format: String = "mp3",
        val sampleRate: Int = 32000,
        val bitrate: Int = 128000,
        val timeoutSec: Int = 60,
    )

    /**
     * 合成语音。结果在主线程回调：成功时给出缓存的音频文件，失败时给出可读的中文错误。
     * 返回的 Job 可 cancel()。
     */
    fun synthesize(
        ctx: Context,
        cfg: TtsCfg,
        text: String,
        onResult: (Result<File>) -> Unit,
    ): Job {
        val appCtx = ctx.applicationContext
        return kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val r = synthesizeToFile(appCtx, cfg, text)
            withContext(Dispatchers.Main) { onResult(r) }
        }
    }

    /**
     * 挂起版合成。调用方自己决定线程（设置页的"试听"按钮用 withContext(IO) 包一层即可）。
     */
    suspend fun synthesizeToFile(ctx: Context, cfg: TtsCfg, text: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (cfg.apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("未配置 MiniMax API Key"))
            }
            val clean = cleanForSpeech(text)
            if (clean.isBlank()) {
                return@withContext Result.failure(IllegalStateException("没有可朗读的内容"))
            }

            val endpoint = cfg.baseUrl.trim().trimEnd('/') + "/v1/t2a_v2"
            val payload = JSONObject().apply {
                put("model", cfg.model)
                put("text", clean)
                put("stream", false)
                put("language_boost", "auto")
                // hex：一次请求直接拿到音频字节，不用再下载 CDN 链接
                put("output_format", "hex")
                put("voice_setting", JSONObject().apply {
                    put("voice_id", cfg.voiceId)
                    put("speed", cfg.speed)
                    put("vol", cfg.vol)
                    put("pitch", cfg.pitch)
                    put("emotion", cfg.emotion)
                })
                put("audio_setting", JSONObject().apply {
                    put("sample_rate", cfg.sampleRate)
                    put("bitrate", cfg.bitrate)
                    put("format", cfg.format)
                    put("channel", 1)
                })
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(cfg.timeoutSec.toLong(), TimeUnit.SECONDS)
                .readTimeout(cfg.timeoutSec.toLong(), TimeUnit.SECONDS)
                .writeTimeout(cfg.timeoutSec.toLong(), TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(endpoint)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .header("Content-Type", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("语音合成失败（HTTP ${resp.code}）：${describeHttpError(resp.code)}")
                    )
                }
                try {
                    val bytes = parseAudio(body)
                    Result.success(writeCache(ctx, bytes, cfg.format))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        } catch (e: Exception) {
            val msg = when (e) {
                is javax.net.ssl.SSLException -> "TLS 证书校验失败：${e.message}"
                is java.net.UnknownHostException -> "网络连接失败：无法解析 ${cfg.baseUrl}"
                else -> e.message ?: "语音合成失败"
            }
            Result.failure(IllegalStateException(msg))
        }
    }

    /** 解析响应体 → 音频字节；业务错误码在这里翻译成中文。 */
    private fun parseAudio(body: String): ByteArray {
        val root = JSONObject(body)
        val base = root.optJSONObject("base_resp")
        val code = base?.optInt("status_code", 0) ?: 0
        if (code != 0) {
            // 注意：Android 的 optString 对 JSON null 会返回字面量 "null"，必须用 jsonOptString
            val statusMsg = jsonOptString(base, "status_msg", "").takeIf { it.isNotBlank() }
                ?: describeBizError(code)
            throw IllegalStateException("语音合成失败（$code）：$statusMsg")
        }
        val data = root.optJSONObject("data")
            ?: throw IllegalStateException("语音合成失败：响应中没有音频数据")
        val hex = jsonOptString(data, "audio", "")
        if (hex.isBlank()) {
            throw IllegalStateException("语音合成失败：返回的音频为空")
        }
        val bytes = hexToBytes(hex)
        if (bytes.isEmpty()) throw IllegalStateException("语音合成失败：音频解码后为空")
        return bytes
    }

    /** hex → 字节数组。MiniMax 返回的是纯 hex 字符串，逐字节解析即可。 */
    internal fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val n = len / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) throw IllegalStateException("语音合成失败：音频数据不是合法的 hex")
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** 缓存到 cacheDir/tts/，同一段文本复用同一个文件。 */
    private fun writeCache(ctx: Context, bytes: ByteArray, format: String): File {
        val dir = File(ctx.cacheDir, "tts").apply { mkdirs() }
        val name = "tts_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val file = File(dir, "$name.${if (format.isBlank()) "mp3" else format}")
        file.writeBytes(bytes)
        // 简单清理：缓存目录超过 8MB 时删掉最旧的
        runCatching {
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= 8L * 1024 * 1024) break
                total -= f.length()
                f.delete()
            }
        }
        return file
    }

    /** 把 AI 回复里的 Markdown 噪声去掉，念出来才自然。 */
    internal fun cleanForSpeech(raw: String): String {
        var s = raw.trim()
        // 代码块整体丢弃
        s = Regex("```[\\s\\S]*?```").replace(s, " ")
        s = s.replace("~~~", " ")
        // 行内代码保留内容但去掉反引号
        s = s.replace("`", "")
        // 标题符号、列表符号、引用符号
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        s = s.replace(Regex("(?m)^\\s*>\\s*"), "")
        s = s.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), " ")
        // 链接只念文字部分
        s = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        // 图片
        s = s.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        // 加粗/斜体标记
        s = s.replace("**", "").replace("__", "").replace("*", "").replace("_", " ")
        // 表格分隔线
        s = s.replace(Regex("(?m)^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$"), " ")
        // 连续空白压成一个空格
        s = s.replace(Regex("[ \\t\\u00a0]+"), " ")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        s = s.trim()
        // 截断后总长度必须仍 <= MAX_CHARS（省略号占 2 个字符）
        if (s.length > MAX_CHARS) s = s.take(MAX_CHARS - 2).trimEnd() + "……"
        return s
    }

    /** MiniMax 业务错误码 → 中文提示（取自官方错误码表里常见的几类）。 */
    private fun describeBizError(code: Int): String = when (code) {
        1002, 1045, 2045, 2062 -> "请求过于频繁，请稍后再试"
        1004, 2049 -> "API Key 无效，请检查语音合成的 API Key"
        1008, 2053, 2056, 2067 -> "账户余额不足，请充值后重试"
        1013, 2013 -> "请求参数不合法"
        1027 -> "文本过长，请缩短后再试"
        1039 -> "Token 超限，请检查模型与文本长度"
        1058, 1059 -> "音频参数不合法"
        else -> "服务返回错误码 $code"
    }

    private fun describeHttpError(code: Int): String = when (code) {
        401 -> "HTTP 401：认证失败，请检查 API Key"
        403 -> "HTTP 403：没有访问权限"
        404 -> "HTTP 404：接口地址不正确，请检查站点与模型名"
        429 -> "HTTP 429：请求过于频繁，请稍后再试"
        in 500..599 -> "HTTP $code：MiniMax 服务端异常，请稍后再试"
        else -> "HTTP $code"
    }

    /** 可选音色（中文普通话 + 粤语），供设置页下拉选择。 */
    object Voices {
        val CHINESE: List<Pair<String, String>> = listOf(
            "female-shaonv" to "少女音色",
            "female-tianmei" to "甜美女性音色",
            "female-chengshu" to "成熟女性音色",
            "female-yujie" to "御姐音色",
            "male-qn-qingse" to "青涩青年音色",
            "male-qn-jingying" to "精英青年音色",
            "male-qn-badao" to "霸道青年音色",
            "male-qn-daxuesheng" to "青年大学生音色",
            "lovely_girl" to "萌萌女童",
            "clever_boy" to "聪明男童",
            "cute_boy" to "可爱男童",
            "cartoon_pig" to "卡通猪小琪",
            "qiaopi_mengmei" to "俏皮萌妹",
            "wumei_yujie" to "妩媚御姐",
            "tianxin_xiaoling" to "甜心小玲",
            "badao_shaoye" to "霸道少爷",
            "lengdan_xiongzhang" to "冷淡学长",
            "chunzhen_xuedi" to "纯真学弟",
            "junlang_nanyou" to "俊朗男友",
            "danya_xuejie" to "淡雅学姐",
            "diadia_xuemei" to "嗲嗲学妹",
            "bingjiao_didi" to "病娇弟弟",
            "presenter_female" to "女性主持人",
            "presenter_male" to "男性主持人",
            "audiobook_female_1" to "女性有声书 1",
            "audiobook_male_1" to "男性有声书 1",
            "Chinese (Mandarin)_Sweet_Lady" to "甜美女声",
            "Chinese (Mandarin)_News_Anchor" to "新闻女声",
            "Chinese (Mandarin)_Gentleman" to "温润男声",
            "Chinese (Mandarin)_Warm_Girl" to "温暖少女",
            "Chinese (Mandarin)_Warm_Bestie" to "温暖闺蜜",
            "Chinese (Mandarin)_Crisp_Girl" to "清脆少女",
            "Chinese (Mandarin)_Soft_Girl" to "柔和少女",
            "Chinese (Mandarin)_Reliable_Executive" to "沉稳高管",
            "Chinese (Mandarin)_Mature_Woman" to "傲娇御姐",
            "Chinese (Mandarin)_Unrestrained_Young_Man" to "不羁青年",
            "Chinese (Mandarin)_Gentle_Youth" to "温润青年",
            "Chinese (Mandarin)_Southern_Young_Man" to "南方小哥",
            "Chinese (Mandarin)_Pure-hearted_Boy" to "清澈邻家弟弟",
            "Chinese (Mandarin)_Stubborn_Friend" to "嘴硬竹马",
            "Chinese (Mandarin)_Gentle_Senior" to "温柔学姐",
            "Chinese (Mandarin)_Cute_Spirit" to "憨憨萌兽",
            "Chinese (Mandarin)_Kind-hearted_Elder" to "花甲奶奶",
            "Chinese (Mandarin)_Kind-hearted_Antie" to "热心大婶",
            "Chinese (Mandarin)_Wise_Women" to "阅历姐姐",
            "Chinese (Mandarin)_Lyrical_Voice" to "抒情男声",
            "Chinese (Mandarin)_Straightforward_Boy" to "率真弟弟",
            "Chinese (Mandarin)_Sincere_Adult" to "真诚青年",
            "Chinese (Mandarin)_Radio_Host" to "电台男主播",
            "Chinese (Mandarin)_Male_Announcer" to "播报男声",
            "Arrogant_Miss" to "嚣张小姐",
            "Robot_Armor" to "机械战甲",
        )

        val CANTONESE: List<Pair<String, String>> = listOf(
            "Cantonese_ProfessionalHost(F)" to "粤语 · 专业女主持",
            "Cantonese_ProfessionalHost(M)" to "粤语 · 专业男主持",
            "Cantonese_GentleLady" to "粤语 · 温柔女声",
            "Cantonese_PlayfulMan" to "粤语 · 活泼男声",
            "Cantonese_CuteGirl" to "粤语 · 可爱女孩",
            "Cantonese_KindWoman" to "粤语 · 善良女声",
        )

        val ALL: List<Pair<String, String>> = CHINESE + CANTONESE

        /** 显示名 → voice_id */
        fun labelOf(voiceId: String): String =
            ALL.firstOrNull { it.first == voiceId }?.second ?: voiceId
    }

    /** 可选模型 */
    val MODELS: List<Pair<String, String>> = listOf(
        "speech-2.6-hd" to "speech-2.6-hd（高质量）",
        "speech-2.6-turbo" to "speech-2.6-turbo（低延迟）",
        "speech-02-hd" to "speech-02-hd",
        "speech-02-turbo" to "speech-02-turbo",
        "speech-01-hd" to "speech-01-hd",
        "speech-01-turbo" to "speech-01-turbo",
    )

    /** 可选情感 */
    val EMOTIONS: List<Pair<String, String>> = listOf(
        "neutral" to "中性",
        "happy" to "开心",
        "sad" to "难过",
        "angry" to "生气",
        "fearful" to "害怕",
        "disgusted" to "厌恶",
        "surprised" to "惊讶",
    )
}
