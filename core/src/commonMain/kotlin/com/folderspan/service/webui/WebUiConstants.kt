package com.folderspan.service.webui

const val DEFAULT_WEB_UI_PORT: Int = 12043
const val WEB_UI_PROTOCOL_VERSION: Int = 1
const val WEB_UI_MAX_MESSAGE_BYTES: Long = 1024 * 1024
const val WEB_UI_SESSION_IDLE_TIMEOUT_MS: Long = 12L * 60L * 60L * 1000L
const val WEB_UI_MAX_SESSIONS: Int = 16
const val WEB_UI_COOKIE_NAME: String = "folderspan_web_ui_session"
const val WEB_UI_SESSION_HEADER: String = "X-FolderSpan-WebUi-Session"
const val WEB_UI_BOOTSTRAP_PATH: String = "/api/web-ui/bootstrap"
const val WEB_UI_LOGIN_PATH: String = "/api/web-ui/login"
const val WEB_UI_LOGOUT_PATH: String = "/api/web-ui/logout"
const val WEB_UI_SESSION_PATH: String = "/api/web-ui/session"
const val WEB_UI_FILES_PATH: String = "/api/web-ui/files"
const val WEB_UI_FILE_STREAM_CHUNK_BYTES: Int = 1024 * 1024
const val WEB_UI_PLACEHOLDER_INDEX_HTML: String =
    "<!doctype html><html><head><meta charset=\"utf-8\"><title>FolderSpan</title></head>" +
        "<body>FolderSpan Web UI</body></html>"
