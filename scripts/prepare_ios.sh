#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
if [[ "$(uname -s)" != "Darwin" ]]; then
    echo 'iOS 工程生成与构建需要 macOS / Xcode。' >&2
    exit 1
fi
command -v xcodegen >/dev/null || { echo '请先在 macOS 安装 XcodeGen。' >&2; exit 1; }
mkdir -p .tmp/xcode .tmp/runtime
export TMPDIR="$PROJECT_ROOT/.tmp/runtime"
xcodegen generate --spec iosApp/project.yml --project .tmp/xcode
printf '工程：%s/.tmp/xcode/DeepSeek.xcodeproj\n' "$PROJECT_ROOT"
printf '构建：xcodebuild -project .tmp/xcode/DeepSeek.xcodeproj -scheme DeepSeek -sdk iphonesimulator -configuration Debug -derivedDataPath .tmp/xcode-derived CODE_SIGNING_ALLOWED=NO build\n'
