#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${project_root}/build"
generated_dir="${build_dir}/generated"
stub_classes="${build_dir}/stub-classes"
app_classes="${build_dir}/app-classes"
vendor_jar="${project_root}/vendor/bmmcamera.jar"
phone_ui_root="${project_root}/phone-ui"
signing_mode="${BYD_CAMERA_SIGNING_MODE:-debug}"
release_signing_dir="${BYD_CAMERA_RELEASE_SIGNING_DIR:-${project_root}_build_secret}"
release_keystore="${release_signing_dir}/byd-camera-release.jks"
release_password_file="${release_signing_dir}/byd-camera-release.password"
release_key_alias="byd-camera-release"

# --------------------------------------------------------------------------
# 툴체인 자동 감지: Linux 번들 → macOS Android SDK 순서로 탐색
# --------------------------------------------------------------------------
tool_bundle_root="${BYD_CAMERA_TOOLCHAIN_ROOT:-${project_root}/.tools}"
tool_root="${tool_bundle_root}/runtime"
java_home_bundle="${tool_bundle_root}/java-home"

USE_BUNDLE_TOOLCHAIN=false
if [[ -d "${tool_root}" && -d "${java_home_bundle}" ]]; then
    USE_BUNDLE_TOOLCHAIN=true
fi

if [[ "${USE_BUNDLE_TOOLCHAIN}" == "true" ]]; then
    # 원본 Linux 번들 툴체인 사용
    android_jar="${BYD_CAMERA_ANDROID_JAR:-${tool_root}/usr/lib/android-sdk/platforms/android-23/android.jar}"
    build_tools_dir="${tool_root}/usr/lib/android-sdk/build-tools/debian"
    dx_jar="${BYD_CAMERA_DX_JAR:-${build_tools_dir}/lib/dx.jar}"
    d8_jar=""
    apksigner_jar="${BYD_CAMERA_APKSIGNER_JAR:-${tool_root}/usr/share/java/apksigner.jar}"
    export JAVA_HOME="${java_home_bundle}"
    export PATH="${java_home_bundle}/bin:${build_tools_dir}:${tool_root}/usr/bin:${PATH}"
    export LD_LIBRARY_PATH="${tool_root}/usr/lib/x86_64-linux-gnu/android:${tool_root}/usr/lib/x86_64-linux-gnu:${tool_root}/usr/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
else
    # macOS Android SDK 자동 감지
    ANDROID_SDK="${ANDROID_HOME:-${HOME}/Library/Android/sdk}"
    if [[ ! -d "${ANDROID_SDK}" ]]; then
        printf 'Android SDK를 찾을 수 없습니다. ANDROID_HOME을 설정하거나 Android Studio를 설치하세요.\n' >&2
        exit 1
    fi

    # android.jar: 환경변수 > 최신 플랫폼 자동 선택
    if [[ -n "${BYD_CAMERA_ANDROID_JAR:-}" ]]; then
        android_jar="${BYD_CAMERA_ANDROID_JAR}"
    else
        android_jar="$(find "${ANDROID_SDK}/platforms" -name "android.jar" | sort -V | tail -1)"
        if [[ -z "${android_jar}" ]]; then
            printf 'android.jar를 찾을 수 없습니다. Android SDK 플랫폼을 설치하세요.\n' >&2
            exit 1
        fi
    fi

    # build-tools: 환경변수 > 최신 버전 자동 선택
    build_tools_dir="${BYD_CAMERA_BUILD_TOOLS_DIR:-$(find "${ANDROID_SDK}/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)}"
    if [[ -z "${build_tools_dir}" || ! -d "${build_tools_dir}" ]]; then
        printf 'Android build-tools를 찾을 수 없습니다.\n' >&2
        exit 1
    fi

    dx_jar=""
    d8_jar="${build_tools_dir}/lib/d8.jar"
    apksigner_jar="${BYD_CAMERA_APKSIGNER_JAR:-${build_tools_dir}/lib/apksigner.jar}"
    export PATH="${build_tools_dir}:${PATH}"

    printf 'Android SDK: %s\n' "${ANDROID_SDK}"
    printf 'android.jar: %s\n' "${android_jar}"
    printf 'build-tools: %s\n' "${build_tools_dir}"
fi

# --------------------------------------------------------------------------
# APK 출력 경로
# --------------------------------------------------------------------------
case "${signing_mode}" in
    debug)
        signed_apk="${build_dir}/byd-dashcam-debug.apk"
        ;;
    release)
        signed_apk="${build_dir}/byd-dashcam.apk"
        if [[ ! -f "${release_keystore}" ]]; then
            printf 'Missing release keystore: %s\n' "${release_keystore}" >&2
            exit 1
        fi
        if [[ ! -f "${release_password_file}" ]]; then
            printf 'Missing release password file: %s\n' "${release_password_file}" >&2
            exit 1
        fi
        ;;
    *)
        printf 'Unsupported BYD_CAMERA_SIGNING_MODE: %s (expected debug or release)\n' \
            "${signing_mode}" >&2
        exit 1
        ;;
esac

mkdir -p "${build_dir}" "${generated_dir}" "${stub_classes}" "${app_classes}"

if [[ ! -f "${vendor_jar}" ]]; then
    printf 'Missing vendor API jar: %s\n' "${vendor_jar}" >&2
    exit 1
fi

# --------------------------------------------------------------------------
# Phone UI 빌드
# --------------------------------------------------------------------------
if [[ -d "${phone_ui_root}/node_modules" ]]; then
    npm --prefix "${phone_ui_root}" run build
elif [[ ! -f "${project_root}/assets/phone/index.html" ]]; then
    printf 'Missing compiled phone UI. Run: npm --prefix %s install\n' \
        "${phone_ui_root}" >&2
    exit 1
fi

# --------------------------------------------------------------------------
# aapt: 리소스 패키징 + R.java 생성
# --------------------------------------------------------------------------
aapt package \
    -f \
    -m \
    -M "${project_root}/AndroidManifest.xml" \
    -S "${project_root}/res" \
    -A "${project_root}/assets" \
    -I "${android_jar}" \
    -J "${generated_dir}" \
    -F "${build_dir}/app-unsigned.apk"

# --------------------------------------------------------------------------
# javac: stub 컴파일
# --------------------------------------------------------------------------
javac \
    -source 8 \
    -target 8 \
    -classpath "${android_jar}" \
    -d "${stub_classes}" \
    "${project_root}/stubs/android/hardware/AVMCamera.java"

# --------------------------------------------------------------------------
# javac: 앱 소스 컴파일
# --------------------------------------------------------------------------
# @argfile 방식으로 파일 목록 전달 (bash 3.2 호환, 경로 공백 안전)
find "${project_root}/src" "${generated_dir}" -name '*.java' | sort \
    > "${build_dir}/sources.txt"

javac \
    -source 8 \
    -target 8 \
    -classpath "${android_jar}:${stub_classes}" \
    -d "${app_classes}" \
    @"${build_dir}/sources.txt"

# --------------------------------------------------------------------------
# DEX 변환: dx (Linux 번들) 또는 d8 (macOS SDK)
# --------------------------------------------------------------------------
if [[ -n "${dx_jar}" && -f "${dx_jar}" ]]; then
    java -jar "${dx_jar}" \
        --dex \
        --output="${build_dir}/classes.dex" \
        "${app_classes}"
elif [[ -n "${d8_jar}" && -f "${d8_jar}" ]]; then
    find "${app_classes}" -name '*.class' | sort > "${build_dir}/classes.txt"
    java -cp "${d8_jar}" com.android.tools.r8.D8 \
        --output "${build_dir}" \
        --lib "${android_jar}" \
        @"${build_dir}/classes.txt"
else
    printf 'DEX 변환 도구(dx.jar 또는 d8.jar)를 찾을 수 없습니다.\n' >&2
    exit 1
fi

# --------------------------------------------------------------------------
# vendor DEX 추가 + APK 패키징
# --------------------------------------------------------------------------
unzip -p "${vendor_jar}" classes.dex > "${build_dir}/classes2.dex"
zip -j -u "${build_dir}/app-unsigned.apk" \
    "${build_dir}/classes.dex" \
    "${build_dir}/classes2.dex"

# --------------------------------------------------------------------------
# zipalign
# --------------------------------------------------------------------------
zipalign \
    -f \
    4 \
    "${build_dir}/app-unsigned.apk" \
    "${build_dir}/app-aligned.apk"

# --------------------------------------------------------------------------
# APK 서명
# --------------------------------------------------------------------------
if [[ "${signing_mode}" == "debug" ]]; then
    if [[ ! -f "${build_dir}/debug.keystore" ]]; then
        keytool \
            -genkeypair \
            -keystore "${build_dir}/debug.keystore" \
            -storepass android \
            -alias androiddebugkey \
            -keypass android \
            -dname "CN=Android Debug,O=Android,C=US" \
            -keyalg RSA \
            -validity 10000
    fi

    java -jar "${apksigner_jar}" sign \
        --ks "${build_dir}/debug.keystore" \
        --ks-pass pass:android \
        --key-pass pass:android \
        --out "${signed_apk}" \
        "${build_dir}/app-aligned.apk"
else
    java -jar "${apksigner_jar}" sign \
        --ks "${release_keystore}" \
        --ks-key-alias "${release_key_alias}" \
        --ks-pass "file:${release_password_file}" \
        --debuggable-apk-permitted false \
        --out "${signed_apk}" \
        "${build_dir}/app-aligned.apk"
fi

java -jar "${apksigner_jar}" verify \
    --verbose \
    --print-certs \
    "${signed_apk}"

printf 'Built %s APK: %s\n' "${signing_mode}" "${signed_apk}"
