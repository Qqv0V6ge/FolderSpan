package com.folderspan.ui.state.file

import com.folderspan.utils.FileAccessPermission
import androidx.compose.runtime.mutableStateListOf
import com.folderspan.data.StatusEnum
import com.folderspan.data.file.FileFilterSort
import com.folderspan.data.file.FileFilterType
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.data.main.DiskBase
import com.folderspan.data.main.Local
import com.folderspan.data.main.device.Device
import com.folderspan.data.main.network.ChunkReadableNetworkAccess
import com.folderspan.data.main.network.NetworkAccess
import com.folderspan.data.main.share.Share
import com.folderspan.data.main.share.ShareProtocol
import com.folderspan.db.FileFilter
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.extensions.filter
import com.folderspan.extensions.indexByExtension
import com.folderspan.extensions.parentPath
import com.folderspan.extensions.withFileFilterTypes
import com.folderspan.ignore.*
import com.folderspan.ui.state.main.Task
import com.folderspan.ui.state.main.TaskType
import com.folderspan.utils.*
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock
import strings.AppStrings

private const val PATH_PREFERENCE_KEEP_LIMIT = 500L

private data class FilePathPreferenceScope(
    val protocol: FileProtocol,
    val protocolId: String,
    val path: String,
)

private data class FilePathPreferenceValues(
    val sort: FileFilterSort,
    val isHideFile: Boolean,
    val ignoreFiles: List<String>,
)

internal fun supportsIgnoreFileUi(desk: DiskBase?): Boolean {
    return when (desk) {
        is Local -> true
        is Device -> true
        is NetworkAccess -> true
        is Share -> desk.protocol == ShareProtocol.Remote
        else -> false
    }
}

internal fun buildIgnoreFileMenuItems(
    configuredIgnoreFiles: List<String>,
    exists: (String) -> Boolean,
): List<IgnoreFileMenuItem> {
    val configured = configuredIgnoreFiles
        .filter { item -> item in SupportedIgnoreFileNames }
        .toSet()
    return SupportedIgnoreFileNames.mapNotNull { fileName ->
        val fileExists = exists(fileName)
        if (fileExists || fileName in configured) {
            IgnoreFileMenuItem(
                fileName = fileName,
                enabled = fileName in configured,
                exists = fileExists,
            )
        } else {
            null
        }
    }
}

class FileFilterState(private val settings: Settings) : KoinComponent {
    private val database by inject<FolderSpanDatabase>()
    private val scope = MainScope()
    private var currentPreferenceScope: FilePathPreferenceScope? = null
    private var currentDesk: DiskBase? = null
    private var currentDirectoryFiles: List<FileSimpleInfo> = emptyList()
    private var currentIgnoreMatcher: ResolvedIgnoreMatcher? = null
    private var fileFiltersByExtension: Map<String, FileFilter> = emptyMap()
    val filterFileTypes = mutableStateListOf<FileFilter>()

    private val _updateKey: MutableStateFlow<Int> = MutableStateFlow(0)
    val updateKey: StateFlow<Int> = _updateKey
    fun updateFilerKey() {
        _updateKey.value++
    }

    init {
        scope.launch {
            syncFilterFileTypes()
        }
    }

    private val _isSearchText: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isSearchText: StateFlow<Boolean> = _isSearchText
    fun updateSearch(value: Boolean) {
        _isSearchText.value = value
    }

    private val _searchText: MutableStateFlow<String> = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText
    fun updateSearchText(value: String) {
        _searchText.value = value
        _updateKey.value++
    }

    private val _sortType: MutableStateFlow<FileFilterSort> = MutableStateFlow(defaultSortType())
    val sortType: StateFlow<FileFilterSort> = _sortType
    fun updateSortType(value: FileFilterSort, persist: Boolean = true) {
        _sortType.value = value
        if (persist) {
            persistPathPreference(value, _isHideFile.value, _currentPathIgnoreFiles.value)
        }
        _updateKey.value++
    }

    suspend fun bindPathPreferenceScope(desk: DiskBase, path: String) {
        val (protocol, protocolId) = resolveFavoriteContext(desk)
        val normalizedPath = path.trim().ifBlank { desk.pathSeparator.ifBlank { "/" } }
        val nextScope = FilePathPreferenceScope(
            protocol = protocol,
            protocolId = protocolId,
            path = normalizedPath
        )
        if (nextScope == currentPreferenceScope) return
        currentPreferenceScope = nextScope
        currentDesk = desk
        val preference = withContext(Dispatchers.Default) {
            database.filePathPreferenceQueries.queryByPath(
                protocol = nextScope.protocol,
                protocolId = nextScope.protocolId,
                path = nextScope.path
            ).executeAsOneOrNullAwait()
        }
        val resolvedPreference = FilePathPreferenceValues(
            sort = preference?.sort ?: defaultSortType(),
            isHideFile = preference?.isHideFile ?: defaultHideFile(),
            ignoreFiles = preference?.ignoreFiles.orEmpty()
        )
        _currentPathIgnoreFiles.value = sanitizeIgnoreFiles(resolvedPreference.ignoreFiles)
        if (_sortType.value != resolvedPreference.sort) {
            _sortType.value = resolvedPreference.sort
            _updateKey.value++
        }
        if (_isHideFile.value != resolvedPreference.isHideFile) {
            _isHideFile.value = resolvedPreference.isHideFile
            _updateKey.value++
        }
        refreshIgnoreState(desk, nextScope)
    }

    // 过滤的文件类型
    val filterFileExtensions = mutableStateListOf<FileFilterType>()

    // 是否显示隐藏文件
    private val _isHideFile: MutableStateFlow<Boolean> =
        MutableStateFlow(settings.getBoolean(SettingsUtils.KEY_FILE_FILTER_SHOW_HIDDEN, false))
    val isHideFile: StateFlow<Boolean> = _isHideFile
    fun updateHideFile(value: Boolean, persist: Boolean = true) {
        _isHideFile.value = value
        if (persist) {
            persistPathPreference(_sortType.value, value, _currentPathIgnoreFiles.value)
        }
        _updateKey.value++
    }

    // updateKey 是 Compose 失效令牌：调用方读取它，以便筛选状态变化后重新执行过滤。
    fun filter(
        fileInfos: List<FileSimpleInfo>,
        @Suppress("UNUSED_PARAMETER") updateKey: Int,
    ): List<FileSimpleInfo> {
        return prepareFileSimpleInfos(markIgnoredFiles(fileInfos)).filter(
            isHidden = _isHideFile.value,
            filterFileExtensions = filterFileExtensions,
            searchText = searchText.value,
            sortType = _sortType.value,
            filterFileTypes = filterFileTypes
        )
    }

    fun prepareFileSimpleInfos(fileInfos: List<FileSimpleInfo>): List<FileSimpleInfo> {
        return fileInfos.withFileFilterTypes(fileFiltersByExtension)
    }

    private val _currentPathIgnoreFiles: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

    private val _activeIgnoreFiles: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

    private val _ignoreFileMenuItems: MutableStateFlow<List<IgnoreFileMenuItem>> = MutableStateFlow(emptyList())
    val ignoreFileMenuItems: StateFlow<List<IgnoreFileMenuItem>> = _ignoreFileMenuItems

    fun updateCurrentDirectoryFiles(files: List<FileSimpleInfo>) {
        currentDirectoryFiles = files
        updateIgnoreFileMenuItems()
        val deskSnapshot = currentDesk ?: return
        val scopeSnapshot = currentPreferenceScope ?: return
        if (!supportsIgnoreUi(deskSnapshot)) return
        scope.launch(Dispatchers.Default) {
            refreshIgnoreState(deskSnapshot, scopeSnapshot)
        }
    }

    fun toggleIgnoreFile(fileName: String) {
        val deskSnapshot = currentDesk ?: return
        if (!supportsIgnoreUi(deskSnapshot)) return
        if (fileName !in SupportedIgnoreFileNames) return
        val scopeSnapshot = currentPreferenceScope ?: return
        val current = _currentPathIgnoreFiles.value
        val next = if (fileName in current) {
            current - fileName
        } else {
            (current + fileName).filter { item -> item in SupportedIgnoreFileNames }.distinct()
        }
        _currentPathIgnoreFiles.value = next
        updateIgnoreFileMenuItems()
        scope.launch(Dispatchers.Default) {
            persistPathPreferenceNow(
                scopeSnapshot = scopeSnapshot,
                sort = _sortType.value,
                isHideFile = _isHideFile.value,
                ignoreFiles = next,
            )
            refreshIgnoreState(deskSnapshot, scopeSnapshot)
        }
    }

    private val _isCreateDialog: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isCreateDialog: StateFlow<Boolean> = _isCreateDialog
    fun updateCreateDialog(value: Boolean) {
        _isCreateDialog.value = value
    }

    suspend fun getFileFilter(filterId: Long) =
        database.fileFilterQueries.queryById(filterId).executeAsOneAwait()

    suspend fun updateFileFilter(extensions: List<String>, id: Long) {
        database.fileFilterQueries.updateExtensionsById(extensions, id).awaitDatabaseReady()
    }

    suspend fun deleteFilter(fileFilter: FileFilter) {
        database.fileFilterQueries.deleteById(fileFilter.id).awaitDatabaseReady()
        syncFilterFileTypes()
    }

    suspend fun createFilter(name: String) {
        database.fileFilterQueries.insert(
            name = name,
            type = FileFilterType.Custom,
            extensions = listOf(),
            icon = null,
            sort = 0
        ).awaitDatabaseReady()
        syncFilterFileTypes()
    }

    suspend fun syncFilterFileTypes() {
        val (filters, filtersByExtension) = withContext(Dispatchers.Default) {
            val loadedFilters = database.fileFilterQueries.queryAllByLimit(0, 100).executeAsListAwait()
            loadedFilters to loadedFilters.indexByExtension()
        }
        withContext(Dispatchers.Main) {
            fileFiltersByExtension = filtersByExtension
            filterFileTypes.clear()
            filterFileTypes.addAll(filters)
            updateFilerKey()
        }
    }

    private fun defaultSortType(): FileFilterSort {
        return FileFilterSort.NameAsc
    }

    private fun defaultHideFile(): Boolean {
        return settings.getBoolean(SettingsUtils.KEY_FILE_FILTER_SHOW_HIDDEN, false)
    }

    private fun persistPathPreference(
        sort: FileFilterSort,
        isHideFile: Boolean,
        ignoreFiles: List<String>,
    ) {
        val scopeSnapshot = currentPreferenceScope ?: return
        scope.launch(Dispatchers.Default) {
            persistPathPreferenceNow(scopeSnapshot, sort, isHideFile, ignoreFiles)
        }
    }

    private suspend fun persistPathPreferenceNow(
        scopeSnapshot: FilePathPreferenceScope,
        sort: FileFilterSort,
        isHideFile: Boolean,
        ignoreFiles: List<String>,
    ) {
        withContext(Dispatchers.Default) {
            val timestamp = Clock.System.now().toEpochMilliseconds()
            val existing = database.filePathPreferenceQueries.queryByPath(
                protocol = scopeSnapshot.protocol,
                protocolId = scopeSnapshot.protocolId,
                path = scopeSnapshot.path
            ).executeAsOneOrNullAwait()
            val createdAt = existing?.createdAt ?: timestamp

            database.filePathPreferenceQueries.upsert(
                protocol = scopeSnapshot.protocol,
                protocolId = scopeSnapshot.protocolId,
                path = scopeSnapshot.path,
                sort = sort,
                isHideFile = isHideFile,
                ignoreFiles = sanitizeIgnoreFiles(ignoreFiles),
                createdAt = createdAt,
                updatedAt = timestamp,
                lastAccessed = timestamp
            ).awaitDatabaseReady()

            database.filePathPreferenceQueries.deleteExtras(PATH_PREFERENCE_KEEP_LIMIT)
                .awaitDatabaseReady()
        }
    }

    private suspend fun refreshIgnoreState(
        desk: DiskBase,
        scopeSnapshot: FilePathPreferenceScope,
    ) {
        if (!supportsIgnoreUi(desk)) {
            currentIgnoreMatcher = null
            _activeIgnoreFiles.value = emptyList()
            _ignoreFileMenuItems.value = emptyList()
            _updateKey.value++
            return
        }
        val preference = findNearestIgnorePreference(
            database = database,
            protocol = scopeSnapshot.protocol,
            protocolId = scopeSnapshot.protocolId,
            path = scopeSnapshot.path,
            separator = desk.pathSeparator.ifBlank { PathUtils.getPathSeparator().ifBlank { "/" } },
        )
        _activeIgnoreFiles.value = preference?.ignoreFiles.orEmpty()
        currentIgnoreMatcher = if (preference == null) {
            null
        } else {
            loadResolvedIgnoreMatcher(preference) { filePath ->
                readIgnoreFileLines(desk, filePath)
            }
        }
        updateIgnoreFileMenuItems()
        _updateKey.value++
    }

    private suspend fun readIgnoreFileLines(desk: DiskBase, filePath: String): Result<List<String>> {
        return when (desk) {
            is Local -> withContext(Dispatchers.Default) {
                runCatching { FileUtils.readFileLines(FileAccessPermission.Allowed, filePath) }
            }

            is Device -> desk.files.readLines(filePath)
            is Share -> readShareIgnoreFileLines(filePath)
            is NetworkAccess -> readNetworkIgnoreFileLines(desk, filePath)
            else -> Result.failure(IllegalStateException(AppStrings.ui_ignore_files_on_the_current_disk))
        }
    }

    private suspend fun readShareIgnoreFileLines(filePath: String): Result<List<String>> {
        val desk = currentDesk as? Share
            ?: return Result.failure(IllegalStateException(AppStrings.ui_the_disk_is_unavailable))
        val file = resolveShareIgnoreFileInfo(desk, filePath)
            ?: return Result.failure(IllegalStateException(AppStrings.operation_ignore_file_not_found))
        if (file.isDirectory || file.size < 0 || file.size > 256 * 1024) {
            return Result.failure(IllegalArgumentException(AppStrings.operation_ignore_file_unreadable))
        }
        return desk.readBytes(file.path, 0, file.size)
            .map { bytes -> bytes.decodeToString().lines() }
    }

    private suspend fun resolveShareIgnoreFileInfo(desk: Share, filePath: String): FileSimpleInfo? {
        currentDirectoryFiles.firstOrNull { item -> item.path == filePath || item.name == filePath.substringAfterLast('/') }
            ?.let { file -> return file }
        val separator = currentDesk?.pathSeparator?.ifBlank { "/" } ?: "/"
        val parent = filePath.parentPath(separator)
        val name = filePath.substringAfterLast(separator)
        return desk.getFileList(parent).getOrNull()
            ?.firstOrNull { item -> item.name == name || item.path == filePath }
    }

    private suspend fun readNetworkIgnoreFileLines(desk: NetworkAccess, filePath: String): Result<List<String>> {
        val reader = desk as? ChunkReadableNetworkAccess
            ?: return Result.failure(IllegalStateException(AppStrings.operation_ignore_network_read_not_supported))
        val file = resolveIgnoreFileInfo(desk, filePath)
            ?: return Result.failure(IllegalStateException(AppStrings.operation_ignore_file_not_found))
        if (file.isDirectory || file.size < 0 || file.size > 256 * 1024) {
            return Result.failure(IllegalArgumentException(AppStrings.operation_ignore_file_unreadable))
        }

        val chunks = mutableListOf<ByteArray>()
        var totalBytes = 0
        val task = Task(
            taskType = TaskType.Copy,
            status = StatusEnum.LOADING,
            protocol = file.protocol,
            protocolId = file.protocolId,
        )
        val result = reader.downloadFileByChunks(task, file) { chunk, _ ->
            totalBytes += chunk.size
            if (totalBytes > 256 * 1024) {
                Result.failure(IllegalArgumentException(AppStrings.operation_ignore_file_unreadable))
            } else {
                chunks.add(chunk)
                Result.success(Unit)
            }
        }
        return result.map {
            val bytes = ByteArray(totalBytes)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(bytes, destinationOffset = offset)
                offset += chunk.size
            }
            bytes.decodeToString().lines()
        }
    }

    private suspend fun resolveIgnoreFileInfo(desk: NetworkAccess, filePath: String): FileSimpleInfo? {
        currentDirectoryFiles.firstOrNull { item -> item.path == filePath || item.name == filePath.substringAfterLast('/') }
            ?.let { file -> return file }
        val separator = currentDesk?.pathSeparator?.ifBlank { "/" } ?: "/"
        val parent = filePath.substringBeforeLast(separator, missingDelimiterValue = "")
            .ifBlank { separator }
        val name = filePath.substringAfterLast(separator)
        return desk.getList(parent).getOrNull()?.firstOrNull { item -> item.name == name || item.path == filePath }
    }

    private fun markIgnoredFiles(fileInfos: List<FileSimpleInfo>): List<FileSimpleInfo> {
        if (!supportsIgnoreUi(currentDesk)) {
            return fileInfos.map { file -> if (file.isIgnored) file.withCopy(isIgnored = false) else file }
        }
        val matcher = currentIgnoreMatcher
            ?: return fileInfos.map { file -> if (file.isIgnored) file.withCopy(isIgnored = false) else file }
        return fileInfos.map { file -> matcher.mark(file) }
    }

    private fun updateIgnoreFileMenuItems() {
        if (!supportsIgnoreUi(currentDesk)) {
            _ignoreFileMenuItems.value = emptyList()
            _activeIgnoreFiles.value = emptyList()
            return
        }
        val existingNames = currentDirectoryFiles.map { item -> item.name }.toSet()
        val localBasePath = currentPreferenceScope?.path
        val separator = currentDesk?.pathSeparator?.ifBlank { PathUtils.getPathSeparator().ifBlank { "/" } }
            ?: PathUtils.getPathSeparator().ifBlank { "/" }
        _ignoreFileMenuItems.value = buildIgnoreFileMenuItems(_currentPathIgnoreFiles.value) { fileName ->
            when (currentDesk) {
                is Local -> localBasePath?.let { basePath ->
                    PathUtils.exists(FileAccessPermission.Allowed, joinIgnorePath(basePath, fileName, separator))
                } ?: false

                else -> fileName in existingNames
            }
        }
    }

    private fun supportsIgnoreUi(desk: DiskBase?): Boolean {
        return supportsIgnoreFileUi(desk)
    }

    private fun sanitizeIgnoreFiles(ignoreFiles: List<String>): List<String> {
        return ignoreFiles
            .filter { item -> item in SupportedIgnoreFileNames }
            .distinct()
    }
}
