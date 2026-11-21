#!/bin/bash
set -euo pipefail

destination="${TARGET_BUILD_DIR}/${FRAMEWORKS_FOLDER_PATH}/WebRTC.framework"
if [ "$FOLDERSPAN_INCLUDE_PRO" != YES ]; then
    # A reused build directory can still contain files from the Pro edition.
    if [ -d "$destination" ]; then
        rm -rf -- "$destination"
    fi
    for plist in "$TARGET_BUILD_DIR/$INFOPLIST_PATH" \
        "$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"/{en,zh-Hans}.lproj/InfoPlist.strings; do
        if plutil -extract NSCameraUsageDescription raw -o - "$plist" >/dev/null 2>&1; then
            plutil -remove NSCameraUsageDescription "$plist"
        fi
    done
    exit 0
fi

framework="$FOLDERSPAN_WEBRTC_SEARCH/WebRTC.framework"
if [ ! -f "$framework/WebRTC" ]; then
    echo "error: WebRTC.framework is missing. Run pod install in app/iosApp for a Pro build." >&2
    exit 1
fi
mkdir -p "$destination"
rsync --delete -a "$framework/" "$destination/"
if [ -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ] && [ "${CODE_SIGNING_REQUIRED:-}" != NO ] &&
    [ "$CODE_SIGNING_ALLOWED" != NO ]; then
    /usr/bin/codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" ${OTHER_CODE_SIGN_FLAGS:-} \
        --preserve-metadata=identifier,entitlements "$destination"
fi
