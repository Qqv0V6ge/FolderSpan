package com.folderspan.service.http.server.templates

import kotlinx.html.FlowOrPhrasingContent

/**
 * 内联 SVG 图标集合，避免依赖外部字体图标。
 */
enum class SvgIcon {
    Search,
    ChevronRight,
    Home,
    Folder,
    File,
    OpenInNew,
    Download,
    Upload,
    Refresh,
    Cancel,
    Info,
    Lock,
    Clock,
    MoreVert
}

data class SvgIconDefinition(
    val viewBox: String,
    val paths: List<String>
)

private data class IconDefinition(
    val viewBox: String,
    val paths: List<String>
)

private val ICON_DEFINITIONS = mapOf(
    SvgIcon.Search to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M784-120 532-372q-30 24-69 38t-83 14q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l252 252-56 56ZM380-400q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z"
        )
    ),
    SvgIcon.ChevronRight to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M504-480 320-664l56-56 240 240-240 240-56-56 184-184Z"
        )
    ),
    SvgIcon.Home to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M160-120v-480l320-240 320 240v480H560v-280H400v280H160Z"
        )
    ),
    SvgIcon.Folder to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h240l80 80h320q33 0 56.5 23.5T880-640v400q0 33-23.5 56.5T800-160H160Z"
        )
    ),
    SvgIcon.File to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M320-240h320v-80H320v80Zm0-160h320v-80H320v80ZM240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h320l240 240v480q0 33-23.5 56.5T720-80H240Zm280-520h200L520-800v200Z"
        )
    ),
    SvgIcon.OpenInNew to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h280v80H200v560h560v-280h80v280q0 33-23.5 56.5T760-120H200Zm188-212-56-56 372-372H560v-80h280v280h-80v-144L388-332Z"
        )
    ),
    SvgIcon.Download to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M160-80v-80h640v80H160Zm320-160L200-600h160v-280h240v280h160L480-240Z"
        )
    ),
    SvgIcon.Upload to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M160-80v-80h640v80H160Zm200-200v-280H200l280-360 280 360H600v280H360Z"
        )
    ),
    SvgIcon.Refresh to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z"
        )
    ),
    SvgIcon.Cancel to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M256-200 200-256l224-224-224-224 56-56 224 224 224-224 56 56-224 224 224 224-56 56-224-224-224 224Z"
        )
    ),
    SvgIcon.Info to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M440-280h80v-240h-80v240Zm40-320q17 0 28.5-11.5T520-640q0-17-11.5-28.5T480-680q-17 0-28.5 11.5T440-640q0 17 11.5 28.5T480-600Zm0 520q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Z"
        )
    ),
    SvgIcon.Lock to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M240-80q-33 0-56.5-23.5T160-160v-400q0-33 23.5-56.5T240-640h40v-80q0-83 58.5-141.5T480-920q83 0 141.5 58.5T680-720v80h40q33 0 56.5 23.5T800-560v400q0 33-23.5 56.5T720-80H240Zm240-200q33 0 56.5-23.5T560-360q0-33-23.5-56.5T480-440q-33 0-56.5 23.5T400-360q0 33 23.5 56.5T480-280ZM360-640h240v-80q0-50-35-85t-85-35q-50 0-85 35t-35 85v80Z"
        )
    ),
    SvgIcon.Clock to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M480-80q-75 0-140.5-28.5t-114-77q-48.5-48.5-77-114T120-440q0-75 28.5-140.5t77-114q48.5-48.5 114-77T480-800q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-440q0 75-28.5 140.5t-77 114q-48.5 48.5-114 77T480-80Zm112-192 56-56-128-128v-184h-80v216l152 152ZM224-866l56 56-170 170-56-56 170-170Zm512 0 170 170-56 56-170-170 56-56Z"
        )
    ),
    SvgIcon.MoreVert to IconDefinition(
        viewBox = "0 -960 960 960",
        paths = listOf(
            "M480-160q-33 0-56.5-23.5T400-240q0-33 23.5-56.5T480-320q33 0 56.5 23.5T560-240q0 33-23.5 56.5T480-160Zm0-240q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm0-240q-33 0-56.5-23.5T400-720q0-33 23.5-56.5T480-800q33 0 56.5 23.5T560-720q0 33-23.5 56.5T480-640Z"
        )
    )
)

fun svgIconDefinition(icon: SvgIcon): SvgIconDefinition? {
    val definition = ICON_DEFINITIONS[icon] ?: return null
    return SvgIconDefinition(
        viewBox = definition.viewBox,
        paths = definition.paths
    )
}

fun svgIconMarkup(
    icon: SvgIcon,
    fill: String = "currentColor",
    width: String = "1em",
    height: String = "1em",
    extraClasses: List<String> = emptyList(),
    ariaHidden: Boolean = true
): String? {
    val definition = ICON_DEFINITIONS[icon] ?: return null
    val classAttr = extraClasses.filter { item ->  item.isNotBlank() }
        .joinToString(" ")
        .takeIf { item ->  item.isNotEmpty() }

    return buildString {
        append("<svg")
        if (classAttr != null) {
            append(" class=\"")
            append(classAttr)
            append("\"")
        }
        append(" viewBox=\"")
        append(definition.viewBox)
        append("\" xmlns=\"http://www.w3.org/2000/svg\" fill=\"")
        append(fill)
        append("\" role=\"img\"")
        if (ariaHidden) {
            append(" aria-hidden=\"true\"")
        }
        append(" focusable=\"false\" width=\"")
        append(width)
        append("\" height=\"")
        append(height)
        append("\">")

        definition.paths.forEach { pathData ->
            append("<path d=\"")
            append(pathData)
            append("\"/>")
        }

        append("</svg>")
    }
}

fun FlowOrPhrasingContent.svgIcon(
    icon: SvgIcon,
    vararg extraClasses: String
) {
    val classes = buildList {
        add("icon")
        addAll(extraClasses.filter { item ->  item.isNotBlank() })
    }
    val markup = svgIconMarkup(
        icon = icon,
        extraClasses = classes
    ) ?: return
    consumer.onTagContentUnsafe { +markup }
}
