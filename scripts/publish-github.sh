#!/usr/bin/env bash
# =============================================================================
# publish-github.sh — 把修复后的源码与预编译 APK 发布到 GitHub。
#
# 需要一个 GitHub Personal Access Token（classic，勾选 repo 权限即可）：
#   https://github.com/settings/tokens
#
# 用法：
#   export GITHUB_TOKEN=ghp_xxxxxxxxxxxx
#   export GITHUB_OWNER=<你的用户名>
#   bash scripts/publish-github.sh
#
# 注意：GitHub 自 2021-08 起不再支持用账号密码做 git 推送，
#      2020-11 起 REST API 也不再接受密码 Basic Auth —— 必须用 token。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${GITHUB_TOKEN:?请先设置 GITHUB_TOKEN 环境变量}"
: "${GITHUB_OWNER:?请先设置 GITHUB_OWNER 环境变量（你的 GitHub 用户名）}"

REPO_NAME="${GITHUB_REPO_NAME:-dsh-pet-android}"
DESCRIPTION="${GITHUB_DESCRIPTION:-dsh-pet 桌宠的 Android 移植版 —— 修复聊天 null 噪音、动画 Source error、ComposeHostOwner 单例崩溃}"
VISIBILITY="${GITHUB_VISIBILITY:-public}"
TAG="${GITHUB_TAG:-v4.3.7}"
APK_DIR="${APK_DIR:-builds}"

API="https://api.github.com"
AUTH=(-H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")

echo "== 1/5 检查 token =="
curl -fsS "${AUTH[@]}" "$API/user" | python3 -c "
import json,sys
u=json.load(sys.stdin)
print('  已登录：%s (%s)' % (u['login'], u.get('name') or '-'))
"

echo "== 2/5 创建仓库（已存在则跳过）==="
if curl -fsS "${AUTH[@]}" "$API/repos/${GITHUB_OWNER}/${REPO_NAME}" >/dev/null 2>&1; then
  echo "  仓库已存在：${GITHUB_OWNER}/${REPO_NAME}"
else
  curl -fsS -X POST "${AUTH[@]}" "$API/user/repos" \
    -d "$(python3 -c "
import json,sys
print(json.dumps({'name': sys.argv[1], 'description': sys.argv[2], 'private': sys.argv[3]=='private', 'auto_init': False}))
" "$REPO_NAME" "$DESCRIPTION" "$VISIBILITY")" >/dev/null
  echo "  已创建：${GITHUB_OWNER}/${REPO_NAME}（$VISIBILITY）"
fi

echo "== 3/5 推送代码 =="
git init -q -b main 2>/dev/null || true
git config user.name "${GIT_AUTHOR_NAME:-Mavis}"
git config user.email "${GIT_AUTHOR_EMAIL:-${GITHUB_OWNER}@users.noreply.github.com}"
git add -A
if git diff --cached --quiet; then
  echo "  没有待提交的变更"
else
  git commit -q -m "$(cat <<EOF
修复三处问题：聊天 null 噪音 / 动画 Source error / ComposeHostOwner 单例

- util/JsonUtil.kt: 新增 jsonOptString()，绕开 Android JSONObject.optString()
  在 JSON null 时返回字符串 "null" 的语义差异（桌面版 org.json 返回 fallback）
- chat/SseClient.kt: SSE 正文 delta.content 改用 jsonOptString()（★主因）
- chat/ChatRepo.kt: 安全读取 + 历史会话开头的连续 null 自动去噪
- pet/Balance.kt / pet/Updater.kt / plugin/AgentEventBus.kt: 同类取值修正
- scripts/fix_asset_names.py: 还原上游 zip 的 "#UXXXX" 转义文件名（新增 --tree 模式）
- scripts/prepare-assets.sh: 接入上面的还原步骤
- pet/PetVideoView.kt: 素材 URI 逐段百分号编码，杜绝 '#' 截断路径
- util/ComposeHost.kt: ComposeHostOwner 由 object 改为 class，每宿主独立 owner

Co-Authored-By: Mavis <noreply@mcode.space>
EOF
)"
fi
git remote remove origin 2>/dev/null || true
git remote add origin "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_OWNER}/${REPO_NAME}.git"
git push -q -u origin main
echo "  已推送 main"

echo "== 4/5 创建 Release $TAG =="
if curl -fsS "${AUTH[@]}" "$API/repos/${GITHUB_OWNER}/${REPO_NAME}/releases/tags/${TAG}" >/dev/null 2>&1; then
  echo "  Release ${TAG} 已存在，更新它"
  RELEASE_ID=$(curl -fsS "${AUTH[@]}" "$API/repos/${GITHUB_OWNER}/${REPO_NAME}/releases/tags/${TAG}" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
else
  RELEASE_ID=$(curl -fsS -X POST "${AUTH[@]}" "$API/repos/${GITHUB_OWNER}/${REPO_NAME}/releases" \
    -d "$(python3 -c "
import json,sys
print(json.dumps({'tag_name': sys.argv[1], 'name': 'dsh-pet Android ' + sys.argv[1], 'body': open(sys.argv[2], encoding='utf-8').read(), 'draft': False, 'prerelease': False}))
" "$TAG" "$ROOT/scripts/release-notes.md")" | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
  echo "  已创建 Release $TAG"
fi

echo "== 5/5 上传 APK =="
for apk in "$APK_DIR"/dsh-pet-android-4.3.7-*.apk; do
  [ -f "$apk" ] || continue
  name="$(basename "$apk")"
  # 已存在同名附件则先删
  asset_id=$(curl -fsS "${AUTH[@]}" "$API/repos/${GITHUB_OWNER}/${REPO_NAME}/releases/${RELEASE_ID}/assets" | python3 -c "
import json,sys
try: a=json.load(sys.stdin)
except Exception: a=[]
print(next((x['id'] for x in a if x['name']==sys.argv[1]), ''))
" "$name")
  if [ -n "$asset_id" ]; then
    curl -fsS -X DELETE "${AUTH[@]}" "$API/repos/${GITHUB_OWNER}/${REPO_NAME}/releases/assets/${asset_id}" >/dev/null
  fi
  echo "  上传 $name（$(du -h "$apk" | cut -f1)）…"
  curl -fsS -X POST "${AUTH[@]}" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@${apk}" \
    "$API/repos/${GITHUB_OWNER}/${REPO_NAME}/releases/${RELEASE_ID}/assets?name=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$name")" \
    >/dev/null
  echo "    ✓"
done

echo
echo "完成：https://github.com/${GITHUB_OWNER}/${REPO_NAME}"
echo "Release：https://github.com/${GITHUB_OWNER}/${REPO_NAME}/releases/tag/${TAG}"
