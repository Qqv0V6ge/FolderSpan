package com.folderspan.ui.state.file

import androidx.compose.runtime.mutableStateListOf
import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.db.FileFavorite
import com.folderspan.db.FolderSpanDatabase
import com.folderspan.utils.awaitDatabaseReady
import com.folderspan.utils.executeAsListAwait
import com.folderspan.utils.executeAsOneOrNullAwait
import com.folderspan.utils.SyncSnapshotChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class FileFavoriteState : KoinComponent {
    private val database by inject<FolderSpanDatabase>()

    val favorites = mutableStateListOf<FileFavorite>()

    var startLimit = 0L
    var endLimit = 100L

    // 当前显示文件列表的收藏状态缓存
    private val _currentFavorites = MutableStateFlow<Set<String>>(emptySet())
    val currentFavorites: StateFlow<Set<String>> = _currentFavorites

    /**
     * 批量查询文件列表的收藏状态
     */
    suspend fun loadFavoritesForCurrentFiles(
        protocol: FileProtocol,
        protocolId: String,
        paths: List<String>
    ) {
        if (paths.isEmpty()) {
            _currentFavorites.value = emptySet()
            return
        }

        withContext(Dispatchers.Default) {
            val favoritePaths = database.fileFavoriteQueries
                .queryPathsInList(protocol, protocolId, paths)
                .executeAsListAwait()
            _currentFavorites.value = favoritePaths.toSet()
        }
    }

    /**
     * 添加收藏
     */
    suspend fun addFavorite(file: FileSimpleInfo) {
        withContext(Dispatchers.Default) {
            database.fileFavoriteQueries.insert(
                name = file.name,
                isDirectory = file.isDirectory,
                isFixed = false,
                path = file.path,
                mineType = file.mineType,
                size = file.size,
                createdDate = file.createdDate,
                updatedDate = file.updatedDate,
                protocol = file.protocol,
                protocolId = file.protocolId,
            ).awaitDatabaseReady()
        }
        // 更新缓存
        _currentFavorites.value += file.path
        SyncSnapshotChangeNotifier.onFavoritesChanged()
    }

    /**
     * 移除收藏
     */
    suspend fun removeFavorite(file: FileSimpleInfo) {
        withContext(Dispatchers.Default) {
            val favorite = database.fileFavoriteQueries
                .queryByPathProtocol(file.path, file.protocol, file.protocolId)
                .executeAsOneOrNullAwait()

            favorite?.let { item ->
                database.fileFavoriteQueries.deleteById(item).awaitDatabaseReady()
            }
        }
        // 更新缓存
        _currentFavorites.value -= file.path
        SyncSnapshotChangeNotifier.onFavoritesChanged()
    }

    /**
     * 切换收藏状态
     */
    suspend fun toggleFavorite(file: FileSimpleInfo) {
        if (_currentFavorites.value.contains(file.path)) {
            removeFavorite(file)
        } else {
            addFavorite(file)
        }
    }

    suspend fun sync() {
        startLimit = 0L
        endLimit = 100L
        favorites.clear()
        favorites.addAll(
            database.fileFavoriteQueries.queryAllByLimit(startLimit, endLimit).executeAsListAwait()
        )
    }

    suspend fun updateFixed(favorite: FileFavorite) {
        val index = favorites.indexOf(favorite)
        if (index < 0) return
        database.fileFavoriteQueries.updateIsFixedById(!favorite.isFixed, favorite.id)
            .awaitDatabaseReady()
        favorites[index] = favorite.copy(isFixed = !favorite.isFixed)
        favorites.sortByDescending { item ->  item.isFixed }
        SyncSnapshotChangeNotifier.onFavoritesChanged()
    }

    suspend fun delete(favorite: FileFavorite) {
        database.fileFavoriteQueries.deleteById(favorite.id).awaitDatabaseReady()
        favorites.remove(favorite)
        SyncSnapshotChangeNotifier.onFavoritesChanged()
    }
}
