#!/usr/bin/env bash
# 打包脚本：编译 -> 按版本号命名 -> 复制到 dist/
#
# 用法：
#   ./pack.sh            只出 debug（默认，日常就用这个）
#   ./pack.sh release    只出 release（要分发时才需要，签名配置已备好）
#   ./pack.sh all        两个都出
#
# ⚠️ 约定（Terry 2026-11-20）：**发布到 GitHub Releases 只上传 release 变体。**
#    debug 包只用于本机调试，不再上传。
#    注意：release 和 debug 签名不同，不能互相覆盖安装；从 debug 切到 release
#    必须先卸载，而卸载会丢日程数据。日常迭代仍可用 debug 包在本机验证。
#
# 为什么要脚本：APK 文件名不带版本号的话，手机上留下好几个同名文件，
# 装完根本分不清是哪一个、装的是新版还是旧版。
set -e
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-D:/WorkBuddy/toolchain/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-D:/WorkBuddy/toolchain/android-sdk}"

VER=$(grep -oE 'versionName = "[0-9.]+"' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/')
CODE=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | head -1 | grep -oE '[0-9]+')

case "${1:-debug}" in
  debug)   TASKS="assembleDebug";                 WANT="debug" ;;
  release) TASKS="assembleRelease";               WANT="release" ;;
  all)     TASKS="assembleDebug assembleRelease"; WANT="debug release" ;;
  *)       echo "未知参数「$1」，可用：debug（默认）/ release / all"; exit 1 ;;
esac

echo "==> 构建 v$VER (versionCode $CODE)"
./gradlew $TASKS --console=plain

mkdir -p dist
# dist 里的历史包是要留着的 —— 回滚、对比、查"哪一版开始出的问题"都得靠它。
# 但堆到十几个之后，光看文件名分不清哪个是哪个，所以由脚本自动维护一份索引。
if [ ! -f dist/HISTORY.md ]; then
  cat > dist/HISTORY.md <<'HEADER'
# 迭代包历史

每一次迭代的 debug 包都留在这个目录里，**不要删**。

回滚、对比、定位"哪一版开始出的问题"都要靠它们。索引由 `./pack.sh` 自动追加，
同一版本同一变体重复打包不会重复记一行。

| 版本 | versionCode | 变体 | 时间 | 文件 | 大小 |
|---|---|---|---|---|---|
HEADER
fi

FOUND=0
for t in $WANT; do
  for cand in "app/build/outputs/apk/$t/app-$t.apk" "app/build/outputs/apk/$t/app-$t-unsigned.apk"; do
    [ -f "$cand" ] || continue
    dst="dist/smart-schedule-v$VER-$t.apk"
    cp "$cand" "$dst"
    size=$(du -h "$dst" | cut -f1)
    echo "==> $dst  ($size)"
    case "$cand" in
      *unsigned*) echo "    !! 这是未签名包，装不上。检查 keystore.properties 是否存在且密码正确" ;;
    esac
    # 记一笔迭代历史
    if ! grep -qF "| $VER | $CODE | $t |" dist/HISTORY.md; then
      printf '| %s | %s | %s | %s | %s | %s |\n' \
        "$VER" "$CODE" "$t" "$(date '+%Y-%m-%d %H:%M')" "$(basename "$dst")" "$size" \
        >> dist/HISTORY.md
    fi
    FOUND=1
    break
  done
done

if [ "$FOUND" -eq 0 ]; then
  echo "!! 没找到构建产物，构建可能失败了"
  exit 1
fi
