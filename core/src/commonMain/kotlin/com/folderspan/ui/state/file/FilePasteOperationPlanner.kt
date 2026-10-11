package com.folderspan.ui.state.file

import com.folderspan.data.file.FileProtocol
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.replaceLast
import com.folderspan.ui.state.file.FileOperationType.Jump
import com.folderspan.ui.state.file.FileOperationType.Replace
import com.folderspan.ui.state.file.FileOperationType.Reserve

internal class FilePasteOperationPlanner(
    private val pathSeparatorFor: (FileProtocol, String) -> String,
) {
    fun updateConflictOperations(
        srcFileInfos: List<FileSimpleInfo>,
        destFileInfo: FileSimpleInfo,
        fileAndFolders: List<FileSimpleInfo>,
        fileOperationState: FileOperationState,
    ): Boolean {
        val fileOperations = buildConflictOperations(srcFileInfos, destFileInfo, fileAndFolders)
        fileOperationState.files.apply {
            clear()
            addAll(fileOperations)
        }
        return fileOperations.any { item -> item.isConflict }
    }

    fun buildPendingOperations(
        selectedOperations: List<FileOperation>,
        destFileInfo: FileSimpleInfo,
        fileAndFolders: List<FileSimpleInfo>,
    ): List<PendingFileOperation> {
        val fileOperations = mutableListOf<PendingFileOperation>()

        for ((isConflict, src, dest, _, type) in selectedOperations) {
            if (isConflict) {
                when (type) {
                    Replace -> fileOperations.add(
                        PendingFileOperation(
                            src = src,
                            dest = src.toPasteDestination(
                                target = dest,
                                targetPath = dest.path,
                                targetName = src.name,
                            ),
                            replaceTarget = dest.takeIf { target ->
                                target.isDirectory != src.isDirectory
                            },
                        )
                    )

                    Jump -> {}

                    Reserve -> {
                        val reservedName = buildReservedName(src, fileAndFolders)
                        val reservedPath = joinDestinationPath(
                            parentPath = destFileInfo.path,
                            childName = reservedName,
                            protocol = destFileInfo.protocol,
                            protocolId = destFileInfo.protocolId,
                        )

                        fileOperations.add(
                            PendingFileOperation(
                                src = src,
                                dest = src.toPasteDestination(
                                    target = destFileInfo,
                                    targetPath = reservedPath,
                                    targetName = reservedName,
                                )
                            )
                        )
                    }
                }
                continue
            }

            fileOperations.add(
                PendingFileOperation(
                    src = src,
                    dest = src.toPasteDestination(
                        target = dest,
                        targetPath = buildDefaultPasteTargetPath(src, dest),
                        targetName = src.name,
                    )
                )
            )
        }

        return fileOperations
    }

    private fun buildConflictOperations(
        srcFileInfos: List<FileSimpleInfo>,
        destFileInfo: FileSimpleInfo,
        fileAndFolders: List<FileSimpleInfo>,
    ): List<FileOperation> {
        return srcFileInfos.map { srcFileInfo ->
            val isConflictFolder = srcFileInfo.isDirectory && destFileInfo.isDirectory && (
                    srcFileInfo.name == destFileInfo.name ||
                            fileAndFolders.any { item -> item.name == srcFileInfo.name }
                    )
            val isConflictFileFolder = !srcFileInfo.isDirectory && destFileInfo.isDirectory && (
                    srcFileInfo.name == destFileInfo.name ||
                            fileAndFolders.any { item -> item.name == srcFileInfo.name }
                    )
            val isConflictFile =
                !srcFileInfo.isDirectory && !destFileInfo.isDirectory && srcFileInfo.name == destFileInfo.name
            val isConflictDirectoryFile =
                srcFileInfo.isDirectory && !destFileInfo.isDirectory && srcFileInfo.name == destFileInfo.name
            val isConflict = isConflictFolder || isConflictFileFolder || isConflictFile || isConflictDirectoryFile

            FileOperation(
                isConflict = isConflict,
                src = srcFileInfo,
                dest = if (isConflict) {
                    fileAndFolders.firstOrNull { item -> item.name == srcFileInfo.name } ?: destFileInfo
                } else {
                    destFileInfo
                },
            )
        }
    }

    private fun FileSimpleInfo.toPasteDestination(
        target: FileSimpleInfo,
        targetPath: String,
        targetName: String,
    ): FileSimpleInfo {
        return copy(
            name = targetName,
            path = targetPath,
            protocol = target.protocol,
            protocolId = target.protocolId,
        )
    }

    private fun buildDefaultPasteTargetPath(
        srcFileInfo: FileSimpleInfo,
        destFileInfo: FileSimpleInfo,
    ): String {
        return if (!destFileInfo.isDirectory) {
            destFileInfo.path.replaceLast(destFileInfo.name, srcFileInfo.name)
        } else {
            joinDestinationPath(
                parentPath = destFileInfo.path,
                childName = srcFileInfo.name,
                protocol = destFileInfo.protocol,
                protocolId = destFileInfo.protocolId,
            )
        }
    }

    private fun joinDestinationPath(
        parentPath: String,
        childName: String,
        protocol: FileProtocol,
        protocolId: String,
    ): String {
        val separator = pathSeparatorFor(protocol, protocolId)
        val normalizedParent = parentPath.trimEnd(separator.firstOrNull() ?: '/')
        return if (normalizedParent.isBlank()) {
            separator + childName
        } else {
            normalizedParent + separator + childName
        }
    }

    private fun buildReservedName(
        srcFileInfo: FileSimpleInfo,
        existingFiles: List<FileSimpleInfo>,
    ): String {
        val (baseName, extension) = splitNameAndExtension(srcFileInfo.name, srcFileInfo.isDirectory)
        val nameRegex = "^${Regex.escape(baseName)}(?:\\((\\d+)\\))?${Regex.escape(extension)}$".toRegex()
        val maxIndex = existingFiles
            .asSequence()
            .mapNotNull { item -> nameRegex.matchEntire(item.name) }
            .maxOfOrNull { match -> match.groups[1]?.value?.toIntOrNull() ?: 1 } ?: 1
        return "$baseName(${maxIndex + 1})$extension"
    }

    private fun splitNameAndExtension(name: String, isDirectory: Boolean): Pair<String, String> {
        if (isDirectory) return name to ""
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == name.lastIndex) return name to ""
        return name.substring(0, dotIndex) to name.substring(dotIndex)
    }
}
