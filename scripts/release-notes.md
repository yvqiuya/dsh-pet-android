## dsh-pet Android 4.3.7（修复版）

桌宠 [dsh-pet](https://github.com/MerZlin/dsh-pet-indesktop) 的 Android 移植版。

| 资产 | 说明 |
|---|---|
| `dsh-pet-android-4.3.7-release.apk` | Release 签名，直接安装（推荐） |
| `dsh-pet-android-4.3.7-debug.apk` | Debug 构建，同一 release 密钥签名 |

**安装**：下载 APK → 传到手机 → 允许「未知来源」安装 → 首次打开在设置里授权「悬浮窗权限」→ 打开桌宠开关。
建议同时开启「忽略电池优化」与「开机自启」（Android 13+ 还需允许通知）。

**签名**：`CN=dsh-pet-android`，证书 SHA-256 `df1c7d166c39804b538cbcd226e476e495983681b87cf36fa7fccd7afaa8bfef`。

---

### 本次修复的三处问题

**1. 聊天回复开头出现成片「nullnullnull…」**

Android 框架的 `JSONObject.optString()` 在字段值是 JSON `null` 时返回的是**字符串 `"null"`**，
而不是 fallback（桌面版 org.json 则返回 fallback，两边语义不一致）。
上游 Python 版用 `dict.get()`，`None` 会被跳过，所以桌面端一直正常 —— 这是「1:1 移植」在 Android 上跑偏的地方。

带推理能力的模型（DeepSeek-R1 等）在推理阶段的每个分片 `delta.content` 都是 JSON `null`
（正文在 `content`，推理在 `reasoning_content`），于是每个推理分片都往正文塞一个字面量 `"null"`，
推理越长前面的 null 越多。

新增 `util/JsonUtil.kt` 的 `jsonOptString()`（`opt()` + 显式识别 `JSONObject.NULL`），
替换全部 8 处 `optString` 调用点。历史会话里已经产生的脏数据在读取时会自动去噪
（只清理开头、且至少连续 2 个才处理），**不需要删会话重聊**。

**2. 动画加载失败（Source error）**

上游 zip 把中文素材文件名转义成了 `#U` + 4 位十六进制，而 `#` 在 URI 里是 fragment 分隔符，
`Uri.parse("asset:///pet/videos/<file>")` 的路径会在第一个 `#` 处被截断成目录，
ExoPlayer 拿到目录去 `AssetManager.open()` → `FileNotFoundException` → "Source error"。

新增 `scripts/fix_asset_names.py` 还原转义文件名（并接入 `prepare-assets.sh`），
`PetVideoView.doPlay()` 改为逐段百分号编码。
附带修好一个隐藏 bug：`text_clips.json` 的 `no_mirror` 名单用的是真实中文名，
与转义后的文件名对不上，导致「含文字动画转向不镜像」功能 100% 失效 —— 还原后 6/6 全部命中。

**3. ComposeHostOwner 单例崩溃**

`internal object ComposeHostOwner` 是单例，多个悬浮窗 ComposeView 共用同一个 `SavedStateRegistry`；
这些 ComposeView 由代码创建、无 XML id，registry key 全部落到同一个 `"-1"`，
第二个组合再消费该 key 即抛 `IllegalStateException`。
改为 `internal class`，`attachComposeHost()` 每次新建实例。

完整说明见仓库 README。

---

MIT License · Copyright (c) 2026 MerZlin
桌面版 [MerZlin/dsh-pet-indesktop](https://github.com/MerZlin/dsh-pet-indesktop) ·
Android 上游 [OTFiles/dsh-pet-inAndroid](https://github.com/OTFiles/dsh-pet-inAndroid)
