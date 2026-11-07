# dsh-pet · Android 移植版（修复版）

桌宠 [dsh-pet](https://github.com/MerZlin/dsh-pet-indesktop) 的 Android 移植版，
基于上游 Android 工程 [`OTFiles/dsh-pet-inAndroid`](https://github.com/OTFiles/dsh-pet-inAndroid) 修复而成。

- **包名**：`com.dshpet.android`
- **版本**：4.3.7（versionCode 437）
- **SDK**：minSdk 28 / targetSdk 34 / compileSdk 34
- **许可**：MIT（见 [LICENSE](LICENSE)，Copyright (c) 2026 MerZlin）

桌面端源码保留在本仓库的 `pet/` 目录下（上游 Python 实现），Android 工程在 `android/`。

---

## 下载

预编译 APK 挂在仓库的 **Releases** 页面（Release assets，不占用 git 历史）：

| 文件 | 说明 |
|---|---|
| `dsh-pet-android-4.3.7-release.apk` | Release 签名，直接安装（推荐） |
| `dsh-pet-android-4.3.7-debug.apk` | Debug 构建，同一 release 密钥签名 |

签名信息：

```
CN=dsh-pet-android
SHA-256 (证书) = df1c7d166c39804b538cbcd226e476e495983681b87cf36fa7fccd7afaa8bfef
```

> 仓库 `android/keystore/dshpet-release.keystore` 随源码提供，口令 `dshpet123`（alias `dshpet`），
> 便于自签构建出可覆盖升级的包。**公开仓库请自行更换密钥。**

---

## 本仓库相对上游修的三处问题

### 1. 聊天回复开头出现成片「nullnullnull…」

**现象**：AI 回复正文前出现几十上百个 `null`，连成好几行，然后才是正常回答。

**根因**：Android 框架的 `JSONObject.optString()` 在字段值是 JSON `null` 时，
返回的是**字符串 `"null"`**，而不是 fallback：

```java
// core/java/org/json/JSONObject.java (Android 34)
public String optString(String name, String fallback) {
    Object object = opt(name);          // JSON null → JSONObject.NULL（不是 Java null！）
    String result = JSON.toString(object);
    return result != null ? result : fallback;
}
// JSON.toString(): 非 String 且非 null → String.valueOf(value)
// JSONObject.NULL.toString() → "null"   ⇒  返回 "null"，fallback 根本用不上
```

桌面版 `org.json` 在同样情况下返回 fallback，两边行为并不一致，所以「1:1 移植」的
SSE 解析逻辑在 Android 上跑偏了。

上游 Python 版 `pet/chat/providers.py` 用的是 `dict.get('content')`，JSON null → `None`
→ `if content:` 为假 → 跳过，**桌面端一直是正确的**。

带推理能力的模型（DeepSeek-R1 等）在**推理阶段**下发的每个分片，`delta.content` 都是
JSON `null`（正文在 `content`，推理在 `reasoning_content`）。于是每个推理分片都往正文里
塞一个字面量 `"null"`，推理越长前面的 null 越多 —— 这也解释了为什么每次回复的 null 数量都不一样。

**修复**：新增 `android/app/src/main/java/com/dshpet/android/util/JsonUtil.kt`：

```kotlin
internal fun jsonOptString(obj: JSONObject?, key: String, fallback: String = ""): String {
    if (obj == null) return fallback
    val raw = obj.opt(key) ?: return fallback
    if (raw === JSONObject.NULL) return fallback      // ← 显式识别 JSON null
    return if (raw is String) raw else raw.toString()
}
```

并替换了全部 8 处 `optString` 调用点：

| 文件 | 位置 |
|---|---|
| `chat/SseClient.kt` | `SseParser.feedLine`（★主因）、`safeErrorDetail`、流内 `payload.error`、`fetchModels` |
| `chat/ChatRepo.kt` | `Session.fromJson` 的 role / content / id / title |
| `pet/Balance.kt` | `total_balance` 等余额字段 |
| `pet/Updater.kt` | `tag_name` / `html_url` |
| `plugin/AgentEventBus.kt` | `agent` / `state` / `event` |

**历史会话自动去噪**：此前产生的脏数据已经写进了 `filesDir/chat_sessions/*.json`，
只修解析器不够。所以 `ChatRepo.Session.fromJson` 读取时额外清理开头的连续 `null`：

```kotlin
internal val NULL_NOISE_PREFIX = Regex("^(?:null){2,}")
```

规则是**只清理开头**且**至少连续 2 个**才处理，避免误伤正常回答里真正的 `null` 字样。
装上新版后，原来那段对话直接就干净了，不需要删会话重聊。

### 2. 动画加载失败（Source error）

**现象**：桌宠浮窗无法播放动画，气泡提示「动画加载失败：Source error」。

**根因**：上游 zip 的打包工具把中文素材文件名转义成了 `#U` + 4 位十六进制：

```
点击回应-傲娇生气.mp4  →  #U70b9#U51fb#U56de#U5e94-#U50b2#U5a07#U751f#U6c14.mp4
```

应用用 `Uri.parse("asset:///pet/videos/<file>")` 取流，而 `#` 在 URI 里是 **fragment 分隔符**，
路径会在第一个 `#` 处被截断成 `pet/videos/click/`（一个**目录**）。
media3 的 `AssetDataSource.open()` 拿到目录去 `AssetManager.open()` → `FileNotFoundException`
→ ExoPlayer 包装成 IO 异常 → `PlaybackException.getMessage()` 返回 `"Source error"`。

**修复**：

1. 新增 `android/scripts/fix_asset_names.py` 还原转义文件名，并接入 `prepare-assets.sh` 末尾，
   CI 构建同样生效；幂等，可重复执行。另支持 `--tree <目录>` 模式还原整棵源码树。
2. `PetVideoView.doPlay()` 改为逐段百分号编码，杜绝 `#` / `?` 再次截断路径：

   ```kotlin
   val encodedPath = assetPath.split('/').joinToString("/") { Uri.encode(it) }
   val uri = Uri.parse("asset:///pet/videos/$encodedPath")
   ```

**附带修好的隐藏 bug**：`text_clips.json` 的 `no_mirror` 名单用的是**真实中文名**
（是啊，吃什么 / 写代码 / 写福字 / 收红包 / 吃Token / 深度思考碎碎念），
与转义后的文件名对不上，导致「含文字动画转向不镜像」功能 **100% 失效**。
还原后 **6/6 条全部命中**。

### 3. ComposeHostOwner 单例崩溃

**现象**：悬浮窗复用生命周期时抛
`IllegalStateException: You can consumeRestoredStateForKey only once per instance`。

**根因**：`internal object ComposeHostOwner` 是单例，多个悬浮窗 `ComposeView` 共用同一个
`SavedStateRegistry`。这些 `ComposeView` 由代码创建、没有 XML id，Compose 取
`parent.getId()` 作为 registry key，全部落到同一个 `"-1"`；第二个组合再消费同一个 key
就在同一 registry 实例上抛异常。

**修复**：

- `internal object ComposeHostOwner` → **`internal class ComposeHostOwner`**
- `View.attachComposeHost()` 每次调用新建实例：

  ```kotlin
  fun View.attachComposeHost() {
      val owner = ComposeHostOwner()
      setViewTreeLifecycleOwner(owner)
      setViewTreeSavedStateRegistryOwner(owner)
  }
  ```

- `ProvideComposeHost()` 改为从 ViewTree 解析出**同一个**实例
  （`findViewTreeLifecycleOwner()` / `findViewTreeSavedStateRegistryOwner()`），
  保证 CompositionLocal 与 ViewTree 指向同一个 registry。

> `ViewTreeLifecycleOwner` / `ViewTreeSavedStateRegistryOwner` 这两个类在 Kotlin 里是
> internal（字节码 public 但 Kotlin 不可见），公开 API 是
> `androidx.lifecycle.findViewTreeLifecycleOwner()` 与
> `androidx.savedstate.findViewTreeSavedStateRegistryOwner()` 扩展函数。

---

## 从源码构建

```bash
# 依赖：JDK 17、Android SDK 34、build-tools 34.0.0、ffmpeg（仅构建机需要）
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > android/local.properties

# 素材预处理：透明 WebM → 旁路 alpha h264（91 个动画 + 缩略图 + 音效 + 彩蛋）
bash android/scripts/prepare-assets.sh

cd android
./gradlew assembleRelease      # 或 assembleDebug
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

### 构建环境注意

**构建机的 locale 必须是 UTF-8**。JVM 的 `sun.jnu.encoding` 会跟随 locale，
POSIX/ASCII 下中文文件名对 JVM 直接不可见（`File.exists()` 返回 false），Gradle 会报：

```
Failed to create MD5 hash for file '.../assets/pet/thumbs/click/….webp'
as it does not exist.
```

```bash
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
```

GitHub Actions 的 `ubuntu-latest` 默认就是 UTF-8，所以 CI 不受影响。

### CI

`.github/workflows/build-android.yml` 会跑 `compileReleaseKotlin` + `assembleRelease`，
**只做编译期验证，不做运行时验证**。上面的三处修复都需要在真机上回归：

- 长按桌宠开菜单 → 关闭 → 再开（验证第 3 处）
- 让桌宠冒个气泡、播放动画（验证第 2 处）
- 打开 AI 对话发一条消息，看回复开头还有没有 null（验证第 1 处）

---

## 目录结构

```
android/                  Android 工程（Kotlin + Compose + ExoPlayer）
  app/src/main/java/com/dshpet/android/
    chat/                 AI 对话（SSE 流式、聊天窗口、会话存储）
    pet/                  桌宠引擎、视频播放、悬浮窗、菜单
    plugin/               Agent 联动事件总线
    util/                 ComposeHost、JSON 安全取值等
  scripts/
    prepare-assets.sh     素材预处理（WebM → 旁路 alpha h264）
    fix_asset_names.py    还原 "#UXXXX" 转义文件名
assets/                   桌面端素材（透明 WebM 原片、聊天背景、音效、彩蛋）
pet/                      桌面端 Python 实现（上游）
docs/  integrations/  packaging/  scripts/  tests/
```

---

## 致谢与许可

- 桌宠桌面版：[MerZlin/dsh-pet-indesktop](https://github.com/MerZlin/dsh-pet-indesktop)
- Android 工程上游：[OTFiles/dsh-pet-inAndroid](https://github.com/OTFiles/dsh-pet-inAndroid)
- MIT License，Copyright (c) 2026 MerZlin —— 见 [LICENSE](LICENSE)

本仓库的改动同样以 MIT 许可发布。发布预编译 APK 时请保留 LICENSE 与本文件中的致谢。
