package com.folderspan.ui.components.file

import androidx.compose.runtime.Immutable
import com.folderspan.data.file.FileSimpleInfo
import com.folderspan.extensions.formatFileSize
import strings.AppStrings

enum class FileSelectorEntryKind {
    File,
    Directory,
}

@Immutable
class FileSelectorConstraints(
    allowedKinds: Set<FileSelectorEntryKind> = emptySet(),
    allowedExtensions: Set<String> = emptySet(),
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
) {
    val allowedKinds: Set<FileSelectorEntryKind> = allowedKinds.toSet()
    val allowedExtensions: Set<String> = allowedExtensions
        .map(String::normalizeFileSelectorExtension)
        .filter(String::isNotEmpty)
        .toSet()

    init {
        require(minSizeBytes == null || minSizeBytes >= 0) {
            "minSizeBytes must be non-negative"
        }
        require(maxSizeBytes == null || maxSizeBytes >= 0) {
            "maxSizeBytes must be non-negative"
        }
        require(minSizeBytes == null || maxSizeBytes == null || minSizeBytes <= maxSizeBytes) {
            "minSizeBytes must not exceed maxSizeBytes"
        }
    }

    fun evaluate(file: FileSimpleInfo): FileSelectorConstraintResult {
        val actualKind = if (file.isDirectory) {
            FileSelectorEntryKind.Directory
        } else {
            FileSelectorEntryKind.File
        }
        if (allowedKinds.isNotEmpty() && actualKind !in allowedKinds) {
            return FileSelectorConstraintResult.Rejected(
                FileSelectorConstraintViolation.KindNotAllowed(
                    actualKind = actualKind,
                    allowedKinds = allowedKinds,
                ),
            )
        }

        if (file.isDirectory) {
            return FileSelectorConstraintResult.Allowed
        }

        val actualExtension = file.name.fileSelectorExtension()
        if (allowedExtensions.isNotEmpty() && actualExtension !in allowedExtensions) {
            return FileSelectorConstraintResult.Rejected(
                FileSelectorConstraintViolation.ExtensionNotAllowed(
                    actualExtension = actualExtension,
                    allowedExtensions = allowedExtensions,
                ),
            )
        }

        val hasSizeRule = minSizeBytes != null || maxSizeBytes != null
        if (hasSizeRule && file.size < 0) {
            return FileSelectorConstraintResult.Rejected(
                FileSelectorConstraintViolation.SizeUnavailable(
                    minSizeBytes = minSizeBytes,
                    maxSizeBytes = maxSizeBytes,
                ),
            )
        }
        if (minSizeBytes != null && file.size < minSizeBytes) {
            return FileSelectorConstraintResult.Rejected(
                FileSelectorConstraintViolation.FileTooSmall(
                    actualSizeBytes = file.size,
                    minSizeBytes = minSizeBytes,
                ),
            )
        }
        if (maxSizeBytes != null && file.size > maxSizeBytes) {
            return FileSelectorConstraintResult.Rejected(
                FileSelectorConstraintViolation.FileTooLarge(
                    actualSizeBytes = file.size,
                    maxSizeBytes = maxSizeBytes,
                ),
            )
        }
        return FileSelectorConstraintResult.Allowed
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is FileSelectorConstraints &&
            allowedKinds == other.allowedKinds &&
            allowedExtensions == other.allowedExtensions &&
            minSizeBytes == other.minSizeBytes &&
            maxSizeBytes == other.maxSizeBytes

    override fun hashCode(): Int {
        var result = allowedKinds.hashCode()
        result = 31 * result + allowedExtensions.hashCode()
        result = 31 * result + minSizeBytes.hashCode()
        result = 31 * result + maxSizeBytes.hashCode()
        return result
    }

    override fun toString(): String =
        "FileSelectorConstraints(" +
            "allowedKinds=$allowedKinds, " +
            "allowedExtensions=$allowedExtensions, " +
            "minSizeBytes=$minSizeBytes, " +
            "maxSizeBytes=$maxSizeBytes)"

    companion object {
        val Unrestricted = FileSelectorConstraints()
    }
}

sealed interface FileSelectorConstraintResult {
    data object Allowed : FileSelectorConstraintResult

    @Immutable
    data class Rejected(
        val violation: FileSelectorConstraintViolation,
    ) : FileSelectorConstraintResult
}

sealed interface FileSelectorConstraintViolation {
    @Immutable
    data class KindNotAllowed(
        val actualKind: FileSelectorEntryKind,
        val allowedKinds: Set<FileSelectorEntryKind>,
    ) : FileSelectorConstraintViolation

    @Immutable
    data class ExtensionNotAllowed(
        val actualExtension: String?,
        val allowedExtensions: Set<String>,
    ) : FileSelectorConstraintViolation

    @Immutable
    data class SizeUnavailable(
        val minSizeBytes: Long?,
        val maxSizeBytes: Long?,
    ) : FileSelectorConstraintViolation

    @Immutable
    data class FileTooSmall(
        val actualSizeBytes: Long,
        val minSizeBytes: Long,
    ) : FileSelectorConstraintViolation

    @Immutable
    data class FileTooLarge(
        val actualSizeBytes: Long,
        val maxSizeBytes: Long,
    ) : FileSelectorConstraintViolation
}

fun FileSelectorConstraintViolation.localizedMessage(): String = when (this) {
    is FileSelectorConstraintViolation.KindNotAllowed ->
        AppStrings.ui_file_selector_kind_not_allowed_arg0_arg1.format(
            arg0 = actualKind.localizedLabel(),
            arg1 = allowedKinds
                .sortedBy(FileSelectorEntryKind::ordinal)
                .joinToString { kind -> kind.localizedLabel() },
        )

    is FileSelectorConstraintViolation.ExtensionNotAllowed ->
        AppStrings.ui_file_selector_extension_not_allowed_arg0_arg1.format(
            arg0 = actualExtension?.let { extension -> ".$extension" }
                ?: AppStrings.ui_file_selector_no_extension,
            arg1 = allowedExtensions.sorted().joinToString { extension -> ".$extension" },
        )

    is FileSelectorConstraintViolation.SizeUnavailable ->
        AppStrings.ui_file_selector_size_unavailable_arg0.format(
            arg0 = localizedSizeRequirement(minSizeBytes, maxSizeBytes),
        )

    is FileSelectorConstraintViolation.FileTooSmall ->
        AppStrings.ui_file_selector_too_small_arg0_arg1.format(
            arg0 = actualSizeBytes.formatFileSize(),
            arg1 = minSizeBytes.formatFileSize(),
        )

    is FileSelectorConstraintViolation.FileTooLarge ->
        AppStrings.ui_file_selector_too_large_arg0_arg1.format(
            arg0 = actualSizeBytes.formatFileSize(),
            arg1 = maxSizeBytes.formatFileSize(),
        )
}

private fun FileSelectorEntryKind.localizedLabel(): String = when (this) {
    FileSelectorEntryKind.File -> AppStrings.ui_file
    FileSelectorEntryKind.Directory -> AppStrings.ui_folder
}

private fun localizedSizeRequirement(minSizeBytes: Long?, maxSizeBytes: Long?): String = when {
    minSizeBytes != null && maxSizeBytes != null ->
        AppStrings.ui_file_selector_size_between_arg0_arg1.format(
            arg0 = minSizeBytes.formatFileSize(),
            arg1 = maxSizeBytes.formatFileSize(),
        )

    minSizeBytes != null -> AppStrings.ui_file_selector_size_at_least_arg0.format(
        arg0 = minSizeBytes.formatFileSize(),
    )

    maxSizeBytes != null -> AppStrings.ui_file_selector_size_at_most_arg0.format(
        arg0 = maxSizeBytes.formatFileSize(),
    )

    else -> AppStrings.ui_unknown
}

private fun String.normalizeFileSelectorExtension(): String =
    trim().removePrefix(".").trim().lowercase()

private fun String.fileSelectorExtension(): String? {
    val extensionSeparator = lastIndexOf('.')
    if (extensionSeparator <= 0 || extensionSeparator == lastIndex) {
        return null
    }
    return substring(extensionSeparator + 1).lowercase()
}
