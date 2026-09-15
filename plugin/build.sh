#!/usr/bin/env bash
set -euo pipefail

plugin_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

is_jdk21() {
    [[ -x "$1/bin/java" ]] && "$1/bin/java" -version 2>&1 | head -n 1 | grep -q 'version "21\.'
}

if [[ -z "${JAVA_HOME:-}" ]] || ! is_jdk21 "$JAVA_HOME"; then
    studio_jdk='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
    if is_jdk21 "$studio_jdk"; then
        export JAVA_HOME="$studio_jdk"
    elif [[ -x /usr/libexec/java_home ]]; then
        detected_jdk="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        if [[ -n "$detected_jdk" ]] && is_jdk21 "$detected_jdk"; then
            export JAVA_HOME="$detected_jdk"
        else
            printf '%s\n' 'Set JAVA_HOME to a JDK 21 installation.' >&2
            exit 1
        fi
    else
        printf '%s\n' 'Set JAVA_HOME to a JDK 21 installation.' >&2
        exit 1
    fi
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && ! -f "$plugin_dir/local.properties" ]]; then
    if [[ -d "$HOME/Library/Android/sdk" ]]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    elif [[ -d "$HOME/Android/Sdk" ]]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    else
        printf '%s\n' 'Set ANDROID_HOME to an Android SDK with platform 34 and build-tools 34.0.0.' >&2
        exit 1
    fi
fi

cd "$plugin_dir"
if [[ "$#" -eq 0 ]]; then
    set -- :probe:assembleDebug :probe:testDebugUnitTest :probe:lintDebug
fi
exec ./gradlew --no-daemon "$@"
