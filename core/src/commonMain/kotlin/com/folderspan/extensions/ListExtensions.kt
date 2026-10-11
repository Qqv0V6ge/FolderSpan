package com.folderspan.extensions

import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.db.FileFilter
import com.folderspan.utils.NaturalOrderComparator

/**
 * 根据文件扩展名获取对应的文件过滤器
 * @param type 文件扩展名
 * @return 匹配的文件过滤器，未找到返回null
 */
fun List<FileFilter>.getFilterByExtension(type: String): FileFilter? {
    return firstOrNull { item ->  item.extensions.contains(type) }
}

/**
 * 为文件扩展名建立常量时间查询索引。
 *
 * 当多个过滤器声明同一个扩展名时，保持 [getFilterByExtension] 的既有语义：
 * 列表中最先出现的过滤器优先。
 */
fun List<FileFilter>.indexByExtension(): Map<String, FileFilter> = buildMap {
    this@indexByExtension.forEach { filter ->
        filter.extensions.forEach { extension ->
            if (extension !in this) {
                this[extension] = filter
            }
        }
    }
}

/**
 * 在数据准备阶段一次性为文件附加渲染所需的过滤类型。
 *
 * Compose 文件组件只读取 [FileSimpleInfo.fileFilterType]，不会在每个条目组合时查找扩展名。
 */
fun List<FileSimpleInfo>.withFileFilterTypes(
    filtersByExtension: Map<String, FileFilter>,
): List<FileSimpleInfo> = map { file ->
    val resolvedType = when {
        file.isDirectory -> FileFilterType.Folder
        else -> filtersByExtension[file.mineType]?.type ?: FileFilterType.File
    }
    if (file.fileFilterType == resolvedType) {
        file
    } else {
        file.withCopy().also { copy -> copy.fileFilterType = resolvedType }
    }
}

/**
 * 获取指定文件类型的所有扩展名
 * @param type 文件类型
 * @return 该类型对应的所有扩展名列表
 */
fun List<FileFilter>.getExtensions(type: FileFilterType): List<String> {
    return lastOrNull { item ->  item.type == type }?.extensions ?: emptyList()
}

/**
 * 对文件列表进行过滤和排序的扩展函数
 *
 * @param isHidden 是否显示隐藏文件
 * @param filterFileExtensions 文件类型过滤条件列表；包含 Hidden 时仅保留隐藏项目，并可与其他类型过滤叠加
 * @param searchText 搜索文本，为空时不进行搜索过滤
 * @param sortType 排序类型
 * @param filterFileTypes 文件类型过滤器配置
 * @param isFilterMatchAll true为AND逻辑（文件需匹配所有类型），false为OR逻辑（文件匹配任意一个类型即保留）
 * @return 过滤和排序后的文件列表
 */

fun List<FileSimpleInfo>.filter(
    isHidden: Boolean = false,
    filterFileExtensions: List<FileFilterType> = emptyList(),
    searchText: String = "",
    sortType: FileFilterSort = FileFilterSort.NameAsc,
    filterFileTypes: List<FileFilter> = emptyList(),
    isFilterMatchAll: Boolean = false,
): List<FileSimpleInfo> {
    var files = this

    // 过滤隐藏文件
    if (!isHidden) {
        files = files.filter { item ->  !item.isHidden }
    }

    val selectedFileFilters = filterFileExtensions.filterNot { item -> item == FileFilterType.Hidden }
    if (isHidden && FileFilterType.Hidden in filterFileExtensions) {
        files = files.filter { item -> item.isHidden }
    }

    // 根据文件类型进行过滤
    if (selectedFileFilters.isNotEmpty()) {
        val matchType: List<FileFilterType>.(predicate: (FileFilterType) -> Boolean) -> Boolean =
            if (isFilterMatchAll) List<FileFilterType>::all else List<FileFilterType>::any

        files = files.filter { item ->
            selectedFileFilters.matchType { type ->
                when (type) {
                    FileFilterType.Folder -> item.isDirectory
                    FileFilterType.File -> !item.isDirectory
                    FileFilterType.Hidden -> item.isHidden
                    else -> filterFileTypes.getExtensions(type).contains(item.mineType)
                }
            }
        }
    }

    // 根据搜索文本过滤文件名
    if (searchText.isNotEmpty()) {
        files = files.filter { item ->  item.name.contains(searchText, ignoreCase = true) }
    }

    // 根据排序类型进行排序
    return when (sortType) {
        // 文件名升序：文件夹优先，然后按自然顺序排序
        FileFilterSort.NameAsc -> files.sortedWith(
            compareByDescending<FileSimpleInfo> { item ->  item.isDirectory }
                .then(NaturalOrderComparator())
        )

        // 文件名降序：文件夹优先，然后按自然顺序逆序
        FileFilterSort.NameDesc -> files.sortedWith(
            compareByDescending<FileSimpleInfo> { item ->  item.isDirectory }
                .thenDescending(NaturalOrderComparator())
        )

        // 文件大小升序：文件夹优先，然后按大小升序
        FileFilterSort.SizeAsc -> files.sortedWith(
            compareByDescending<FileSimpleInfo> { item ->  item.isDirectory }
                .thenBy { item ->  item.size }
        )

        // 文件大小降序：文件夹优先，然后按大小降序
        FileFilterSort.SizeDesc -> files.sortedWith(
            compareByDescending<FileSimpleInfo> { item ->  item.isDirectory }
                .thenByDescending { item ->  item.size }
        )

        // 文件类型升序：文件夹优先，然后按自然顺序排序
        FileFilterSort.TypeAsc -> files.sortedWith(
            compareBy<FileSimpleInfo> { item ->  item.isDirectory }
                .then(NaturalOrderComparator())
        )

        // 文件类型降序：文件夹优先，然后按自然顺序排序
        FileFilterSort.TypeDesc -> files.sortedWith(
            compareByDescending<FileSimpleInfo> { item ->  item.isDirectory }
                .then(NaturalOrderComparator())
        )

        FileFilterSort.CreatedDateAsc -> files.sortedBy { item -> item.createdDate }
        FileFilterSort.CreatedDateDesc -> files.sortedByDescending { item -> item.createdDate }
        FileFilterSort.UpdatedDateAsc -> files.sortedBy { item -> item.updatedDate }
        FileFilterSort.UpdatedDateDesc -> files.sortedByDescending { item -> item.updatedDate }
    }
}
