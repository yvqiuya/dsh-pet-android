package com.dshpet.android.util

import org.json.JSONObject

/**
 * 安全读取 JSON 字符串字段。
 *
 * 【为什么不能直接用 JSONObject.optString()】
 *
 * Android 框架（core/java/org/json/JSONObject.java）的实现是：
 *
 *     public String optString(String name, String fallback) {
 *         Object object = opt(name);
 *         String result = JSON.toString(object);
 *         return result != null ? result : fallback;
 *     }
 *
 * 而 JSON.toString() 对「非 String 且非 Java null」的值执行 String.valueOf(value)，
 * JSONObject.NULL.toString() 又正好返回 "null"。于是当字段值是 JSON null 时：
 *
 *     opt(name)        → JSONObject.NULL   （不是 Java null！）
 *     JSON.toString()  → "null"            （非 null 的 String）
 *     optString(...)   → "null"            （fallback 永远用不上）
 *
 * 也就是说 optString() 会把 JSON null 变成字符串 "null"，而不是返回 fallback。
 * 这与桌面版 org.json 的行为不一致（桌面版此时会返回 fallback），
 * 所以 1:1 移植过来的解析逻辑在 Android 上会出偏差。
 *
 * 典型症状：SSE 流里 Provider 在推理阶段下发 {"content": null}，
 * 用 optString("content") 读取就会拿到字符串 "null"，
 * 于是整段推理分片被当成正文，聊天界面显示成成片的 "nullnullnull…" 噪音。
 *
 * 这里统一用 opt() 取原始值，并显式识别 JSONObject.NULL。
 *
 * @param obj     JSON 对象，可为 null
 * @param key     字段名
 * @param fallback 字段缺失 / 值为 JSON null 时的返回值
 */
internal fun jsonOptString(obj: JSONObject?, key: String, fallback: String = ""): String {
    if (obj == null) return fallback
    val raw = obj.opt(key) ?: return fallback
    if (raw === JSONObject.NULL) return fallback
    return if (raw is String) raw else raw.toString()
}

/**
 * 清理旧版本写入的 "nullnullnull…" 前缀噪音。
 *
 * 旧版 SseParser 用 optString("content") 读取 delta.content，
 * Provider 在推理阶段下发 {"content": null} 时会被转成字符串 "null"，
 * 于是一整段 "null" 被当作正文写进了会话文件。
 *
 * 这里只在**开头**做清理，且要求至少连续 2 个 "null" 才处理，
 * 避免误伤正常回答里真正的 "null" 字样。
 */
internal val NULL_NOISE_PREFIX = Regex("^(?:null){2,}")

internal fun stripNullNoise(text: String): String = NULL_NOISE_PREFIX.replace(text, "")
