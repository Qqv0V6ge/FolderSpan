package com.folderspan.utils

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 用于表示窗口尺寸分类的枚举类。
 * 该分类通常用于根据设备屏幕尺寸调整界面布局。
 */
enum class WindowSizeClass {
    /**
     * 表示窗口大小分类中的“紧凑”类型，用于描述较小尺寸的窗口状态。
     *
     * 该枚举值通常用于根据窗口的物理尺寸调整用户界面布局，以提供更好的用户体验。
     */
    Compact,

    /**
     * 表示窗口的中等尺寸分类。
     *
     * 使用此枚举值可以支持根据窗口大小动态适配 UI 和逻辑。
     * 典型的用例是使用 Medium 表示适合该屏幕尺寸的布局样式。
     *
     * 此类用于窗口大小适配逻辑中的一个枚举值，用于标记适中的窗口宽度或高度范围。
     */
    Medium,

    /**
     * 表示窗口尺寸类别中的 "扩展" 类别，用于适配更大的设备屏幕。
     *
     * 该枚举值通常用于响应式界面设计中，根据设备的窗口尺寸选择适合的布局。
     * 常见使用场景包括大屏幕设备如平板电脑或桌面设备。
     */
    Expanded,
    /**
     * 表示窗口尺寸类别中的 "大屏" 类型。
     */
    Large,

    /**
     * 表示窗口尺寸类别中的 "超大屏" 类型。
     */
    ExtraLarge
}

/**
 * 表示响应式窗口布局可同时展示的窗格数量。
 */
enum class WindowPaneMode {
    /** 单窗格布局。 */
    SinglePane,

    /** 双窗格布局。 */
    TwoPane,

    /** 三窗格布局。 */
    ThreePane,
}

fun calculateWindowSizeClass(maxWidth: Dp, maxHeight: Dp): WindowSizeClass = when {
    maxWidth < 600.dp -> WindowSizeClass.Compact
    maxWidth < 840.dp -> WindowSizeClass.Medium
    maxWidth < 1200.dp -> WindowSizeClass.Expanded
    maxWidth < 1600.dp -> WindowSizeClass.Large
    else -> WindowSizeClass.ExtraLarge
}

fun calculateGridColumnCount(maxWidth: Dp, maxHeight: Dp): Int = when (
    calculateWindowSizeClass(maxWidth, maxHeight)
) {
    WindowSizeClass.Compact -> 1
    WindowSizeClass.Medium -> 2
    WindowSizeClass.Expanded -> 3
    WindowSizeClass.Large -> 4
    WindowSizeClass.ExtraLarge -> 5
}
