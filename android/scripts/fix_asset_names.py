#!/usr/bin/env python3
# =============================================================================
# fix_asset_names.py — 修复素材文件名里的 "#UXXXX" 转义残留。
#
# 为什么需要这一步：
#   上游发布 zip 时，部分打包/传输工具会把非 ASCII 文件名转义成
#   "#U" + 4 位十六进制码点的形式，例如
#       点击回应-俏皮生气.mp4  →  #U70b9#U51fb#U56de#U5e94-#U50b2#U5a07#U751f#U6c14.mp4
#   仓库里的 text_clips.json 用的是真实中文名，两者对不上，
#   "含文字动画转向不镜像"的名单（no_mirror）会全部匹配失败。
#
#   更致命的是 Android 侧：应用用 Uri.parse("asset:///pet/videos/<file>") 取流，
#   而 '#' 在 URI 里是 fragment 分隔符 —— 路径会在第一个 '#' 处被截断，
#   ExoPlayer 拿到的是目录而不是文件，最终抛出
#       IllegalStateException / "Source error"（动画加载失败）。
#
# 规则：
#   - "#U" + 4 位十六进制  → 该码点字符（CJK 全部在 BMP 内）
#   - "#U" + 6 位十六进制  → 若为合法的非 BMP 码点则整体解码（emoji 等）
#   - 更长的连续十六进制串 → 前 4 位为码点，其余按字面 ASCII（源码里的
#     "360" 这类纯数字会被打包工具并入转义串，属正常情况）
#
# 用法：
#   python3 fix_asset_names.py                      # 处理默认的 assets/pet 目录
#   python3 fix_asset_names.py <assets/pet 目录>     # 处理指定的产物目录
#   python3 fix_asset_names.py --tree <目录>         # 递归还原整棵源码树里的转义文件名
# 幂等：已是真实中文名的文件不会被改动。
# =============================================================================
import json
import os
import re
import sys

ESCAPE = re.compile(r"#U([0-9a-fA-F]+)")


def decode_name(name: str) -> str:
    """把文件名里的 #UXXXX 转义还原成真实字符。"""

    def repl(m):
        h = m.group(1)
        if len(h) == 4:
            return chr(int(h, 16))
        if len(h) == 6 and int(h, 16) >= 0x10000:
            return chr(int(h, 16))
        # 超过 4 位：前 4 位是码点，后面是字面 ASCII（例如 "...#U5730360..." → "地360"）
        return chr(int(h[:4], 16)) + h[4:]

    return ESCAPE.sub(repl, name)


def fix_tree(root: str) -> int:
    """递归还原整棵目录树里含 #U 转义的文件名（用于源码树）。"""
    renamed = 0
    for dirpath, dirs, files in os.walk(root):
        # 跳过构建产物与缓存
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".git", "node_modules", "__pycache__")]
        for fname in list(files):
            if "#U" not in fname:
                continue
            new = decode_name(fname)
            if new == fname:
                continue
            src = os.path.join(dirpath, fname)
            dst = os.path.join(dirpath, new)
            if os.path.exists(dst):
                print("  冲突，跳过：%s" % fname)
                continue
            os.rename(src, dst)
            renamed += 1
    return renamed


def main() -> int:
    args = sys.argv[1:]
    if args and args[0] == "--tree":
        root = args[1] if len(args) > 1 else "."
        if not os.path.isdir(root):
            print("跳过：%s 不存在" % root)
            return 0
        renamed = fix_tree(root)
        print("  源码树文件重命名：%d 个" % renamed)
        return 0

    root = args[0] if args else "assets/pet"
    if not os.path.isdir(root):
        print("跳过：%s 不存在" % root)
        return 0

    renamed = 0
    for sub in ("videos", "thumbs"):
        base = os.path.join(root, sub)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirs, files in os.walk(base):
            for fname in files:
                if "#U" not in fname:
                    continue
                new = decode_name(fname)
                if new == fname:
                    continue
                src = os.path.join(dirpath, fname)
                dst = os.path.join(dirpath, new)
                if os.path.exists(dst):
                    print("  冲突，跳过：%s" % fname)
                    continue
                os.rename(src, dst)
                renamed += 1

    # manifest.json：键名与 file 路径同样需要还原
    manifest_path = os.path.join(root, "manifest.json")
    manifest_fixed = 0
    if os.path.exists(manifest_path):
        with open(manifest_path, encoding="utf-8") as fp:
            manifest = json.load(fp)
        out = {}
        for key, val in manifest.items():
            new_key = decode_name(key)
            new_val = dict(val)
            if isinstance(val.get("file"), str):
                new_val["file"] = decode_name(val["file"])
            if new_key != key or new_val != val:
                manifest_fixed += 1
            out[new_key] = new_val
        with open(manifest_path, "w", encoding="utf-8") as fp:
            json.dump(out, fp, ensure_ascii=False, indent=1)

    # 校验：manifest 里引用的文件必须真实存在
    missing = []
    if os.path.exists(manifest_path):
        with open(manifest_path, encoding="utf-8") as fp:
            manifest = json.load(fp)
        for key, val in manifest.items():
            p = os.path.join(root, "videos", val.get("file", ""))
            if not os.path.exists(p):
                missing.append((key, val.get("file")))

    print("  文件重命名：%d 个" % renamed)
    print("  manifest 条目还原：%d 条" % manifest_fixed)
    if missing:
        print("  错误：manifest 引用了 %d 个不存在的文件" % len(missing))
        for k, f in missing[:5]:
            print("    MISS %s -> %s" % (k, f))
        return 1
    print("  校验通过：manifest 与 videos/ 完全一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
