package com.folderspan.service.http.server.templates

import strings.AppStrings

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.text.equals
import kotlin.text.lowercase

/**
 * 构建与 Compose 主题保持一致的运行时配置样式。
 *
 * 生成的样式表通过 CSS 变量提供明暗模式的颜色配置，确保 HTML 模板与应用的 Material 3 主题保持同步。
 */
object ThemeConfigScriptBuilder {
    private const val LIGHTEN_FRACTION = 0.35f
    private const val DARKEN_FRACTION = 0.25f

    fun build(lightScheme: ColorScheme, darkScheme: ColorScheme): String {
        val lightVariables = buildVariableAssignments(lightColorGroups(lightScheme))
        val darkOverrides = buildVariableAssignments(lightColorGroups(darkScheme))
        val darkVariables = buildVariableAssignments(darkColorGroups(darkScheme))

        return buildString {
            appendLine(AppStrings.ui_generated_material_3_theme_variables_synchronized_with_compose)
            appendLine(":root {")
            appendLine("    color-scheme: light;")
            lightVariables.forEach { (name, value) ->
                appendLine("    $name: $value;")
            }
            darkVariables.forEach { (name, value) ->
                appendLine("    $name: $value;")
            }
            appendLine("}")
            appendLine()
            appendLine(":root.dark {")
            appendLine("    color-scheme: dark;")
            darkOverrides.forEach { (name, value) ->
                appendLine("    $name: $value;")
            }
            darkVariables.forEach { (name, value) ->
                appendLine("    $name: $value;")
            }
            appendLine("}")
            appendLine()
            appendLine("@media (prefers-color-scheme: dark) {")
            appendLine("    :root {")
            appendLine("        color-scheme: dark;")
            darkOverrides.forEach { (name, value) ->
                appendLine("        $name: $value;")
            }
            darkVariables.forEach { (name, value) ->
                appendLine("        $name: $value;")
            }
            appendLine("    }")
            appendLine("}")
        }
    }

    private fun lightColorGroups(scheme: ColorScheme): List<ColorGroup> = listOf(
        ColorGroup(
            key = "primary",
            entries = listOf(
                "DEFAULT" to scheme.primary.toHex(),
                "light" to lighten(scheme.primary).toHex(),
                "dark" to darken(scheme.primary).toHex(),
                "container" to scheme.primaryContainer.toHex(),
                "on" to scheme.onPrimary.toHex(),
                "on-container" to scheme.onPrimaryContainer.toHex()
            )
        ),
        ColorGroup(
            key = "secondary",
            entries = listOf(
                "DEFAULT" to scheme.secondary.toHex(),
                "light" to lighten(scheme.secondary).toHex(),
                "dark" to darken(scheme.secondary).toHex(),
                "container" to scheme.secondaryContainer.toHex(),
                "on" to scheme.onSecondary.toHex(),
                "on-container" to scheme.onSecondaryContainer.toHex()
            )
        ),
        ColorGroup(
            key = "tertiary",
            entries = listOf(
                "DEFAULT" to scheme.tertiary.toHex(),
                "light" to lighten(scheme.tertiary).toHex(),
                "dark" to darken(scheme.tertiary).toHex(),
                "container" to scheme.tertiaryContainer.toHex(),
                "on" to scheme.onTertiary.toHex(),
                "on-container" to scheme.onTertiaryContainer.toHex()
            )
        ),
        ColorGroup(
            key = "error",
            entries = listOf(
                "DEFAULT" to scheme.error.toHex(),
                "light" to lighten(scheme.error).toHex(),
                "dark" to darken(scheme.error).toHex(),
                "container" to scheme.errorContainer.toHex(),
                "on" to scheme.onError.toHex(),
                "on-container" to scheme.onErrorContainer.toHex()
            )
        ),
        ColorGroup(
            key = "background",
            entries = listOf(
                "DEFAULT" to scheme.background.toHex(),
                "on" to scheme.onBackground.toHex()
            )
        ),
        ColorGroup(
            key = "surface",
            entries = listOf(
                "DEFAULT" to scheme.surface.toHex(),
                "variant" to scheme.surfaceVariant.toHex(),
                "dim" to scheme.surfaceDim.toHex(),
                "bright" to scheme.surfaceBright.toHex(),
                "container-lowest" to scheme.surfaceContainerLowest.toHex(),
                "container-low" to scheme.surfaceContainerLow.toHex(),
                "container" to scheme.surfaceContainer.toHex(),
                "container-high" to scheme.surfaceContainerHigh.toHex(),
                "container-highest" to scheme.surfaceContainerHighest.toHex(),
                "on" to scheme.onSurface.toHex(),
                "on-variant" to scheme.onSurfaceVariant.toHex()
            )
        ),
        ColorGroup(
            key = "outline",
            entries = listOf(
                "DEFAULT" to scheme.outline.toHex(),
                "variant" to scheme.outlineVariant.toHex()
            )
        )
    )

    private fun darkColorGroups(scheme: ColorScheme): List<ColorGroup> = listOf(
        ColorGroup(
            key = "dark-primary",
            entries = listOf(
                "DEFAULT" to scheme.primary.toHex(),
                "light" to lighten(scheme.primary).toHex(),
                "dark" to darken(scheme.primary).toHex(),
                "container" to scheme.primaryContainer.toHex(),
                "on" to scheme.onPrimary.toHex(),
                "on-container" to scheme.onPrimaryContainer.toHex()
            )
        ),
        ColorGroup(
            key = "dark-secondary",
            entries = listOf(
                "DEFAULT" to scheme.secondary.toHex(),
                "light" to lighten(scheme.secondary).toHex(),
                "dark" to darken(scheme.secondary).toHex(),
                "container" to scheme.secondaryContainer.toHex(),
                "on" to scheme.onSecondary.toHex(),
                "on-container" to scheme.onSecondaryContainer.toHex()
            )
        ),
        ColorGroup(
            key = "dark-tertiary",
            entries = listOf(
                "DEFAULT" to scheme.tertiary.toHex(),
                "light" to lighten(scheme.tertiary).toHex(),
                "dark" to darken(scheme.tertiary).toHex(),
                "container" to scheme.tertiaryContainer.toHex(),
                "on" to scheme.onTertiary.toHex(),
                "on-container" to scheme.onTertiaryContainer.toHex()
            )
        ),
        ColorGroup(
            key = "dark-error",
            entries = listOf(
                "DEFAULT" to scheme.error.toHex(),
                "light" to lighten(scheme.error).toHex(),
                "dark" to darken(scheme.error).toHex(),
                "container" to scheme.errorContainer.toHex(),
                "on" to scheme.onError.toHex(),
                "on-container" to scheme.onErrorContainer.toHex()
            )
        ),
        ColorGroup(
            key = "dark-background",
            entries = listOf(
                "DEFAULT" to scheme.background.toHex(),
                "on" to scheme.onBackground.toHex()
            )
        ),
        ColorGroup(
            key = "dark-surface",
            entries = listOf(
                "DEFAULT" to scheme.surface.toHex(),
                "variant" to scheme.surfaceVariant.toHex(),
                "dim" to scheme.surfaceDim.toHex(),
                "bright" to scheme.surfaceBright.toHex(),
                "container-lowest" to scheme.surfaceContainerLowest.toHex(),
                "container-low" to scheme.surfaceContainerLow.toHex(),
                "container" to scheme.surfaceContainer.toHex(),
                "container-high" to scheme.surfaceContainerHigh.toHex(),
                "container-highest" to scheme.surfaceContainerHighest.toHex(),
                "on" to scheme.onSurface.toHex(),
                "on-variant" to scheme.onSurfaceVariant.toHex()
            )
        ),
        ColorGroup(
            key = "dark-outline",
            entries = listOf(
                "DEFAULT" to scheme.outline.toHex(),
                "variant" to scheme.outlineVariant.toHex()
            )
        )
    )

    private fun buildVariableAssignments(groups: List<ColorGroup>): List<Pair<String, String>> {
        return buildList {
            groups.forEach { group ->
                group.entries.forEach { (variant, value) ->
                    add(group.buildVariableName(variant) to value)
                }
            }
        }
    }

    private fun ColorGroup.buildVariableName(variant: String): String {
        val normalizedGroup = key.replace(" ", "-")
        val normalizedVariant = variant.replace(" ", "-")
        return buildString {
            append("--color-")
            append(normalizedGroup.lowercase())
            if (!normalizedVariant.equals("DEFAULT", ignoreCase = true)) {
                append('-')
                append(normalizedVariant.lowercase())
            }
        }
    }

    private data class ColorGroup(
        val key: String,
        val entries: List<Pair<String, String>>
    )

    private fun lighten(color: Color): Color = lerp(color, Color.White, LIGHTEN_FRACTION)

    private fun darken(color: Color): Color = lerp(color, Color.Black, DARKEN_FRACTION)

    private fun Color.toHex(): String {
        val argb = toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return "#" + r.toHexComponent() + g.toHexComponent() + b.toHexComponent()
    }

    private fun Int.toHexComponent(): String {
        val hex = toString(16).uppercase()
        return if (hex.length == 1) "0$hex" else hex
    }
}
