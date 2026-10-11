package com.folderspan.data.file

import strings.AppStrings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.folderspan.db.FileFilter

enum class FileFilterType(type: String) {
    Folder("Folder"),
    File("File"),
    Hidden("Hidden"),
    Text("Text"),
    Audio("Audio"),
    Video("Video"),
    Image("Image"),
    ImageRaw("ImageRaw"),
    ImageVector("ImageVector"),
    Image3D("Image3D"),
    PageLayout("PageLayout"),
    Database("Database"),
    Executable("Executable"),
    Game("Game"),
    CAD("CAD"),
    GIS("GIS"),
    Web("Web"),
    Plugin("Plugin"),
    Font("Font"),
    System("System"),
    Settings("Settings"),
    Encoded("Encoded"),
    Compressed("Compressed"),
    Disk("Disk"),
    Developer("Developer"),
    Backup("Backup"),
    Misc("Misc"),
    Custom("Custom")
}

fun FileFilterType.displayName(): String = when (this) {
    FileFilterType.Folder -> AppStrings.ui_folder
    FileFilterType.File -> AppStrings.ui_file
    FileFilterType.Hidden -> AppStrings.ui_file_filter_hidden
    FileFilterType.Text -> AppStrings.ui_file_filter_text
    FileFilterType.Audio -> AppStrings.ui_file_filter_audio
    FileFilterType.Video -> AppStrings.ui_file_filter_video
    FileFilterType.Image -> AppStrings.ui_file_filter_image
    FileFilterType.ImageRaw -> AppStrings.ui_file_filter_image_raw
    FileFilterType.ImageVector -> AppStrings.ui_file_filter_image_vector
    FileFilterType.Image3D -> AppStrings.ui_file_filter_image_3d
    FileFilterType.PageLayout -> AppStrings.ui_file_filter_page_layout
    FileFilterType.Database -> AppStrings.ui_file_filter_database
    FileFilterType.Executable -> AppStrings.ui_file_filter_executable
    FileFilterType.Game -> AppStrings.ui_file_filter_game
    FileFilterType.CAD -> AppStrings.ui_file_filter_cad
    FileFilterType.GIS -> AppStrings.ui_file_filter_gis
    FileFilterType.Web -> AppStrings.ui_file_filter_web
    FileFilterType.Plugin -> AppStrings.ui_file_filter_plugin
    FileFilterType.Font -> AppStrings.ui_file_filter_font
    FileFilterType.System -> AppStrings.ui_file_filter_system
    FileFilterType.Settings -> AppStrings.ui_file_filter_settings
    FileFilterType.Encoded -> AppStrings.ui_file_filter_encoded
    FileFilterType.Compressed -> AppStrings.ui_file_filter_compressed
    FileFilterType.Disk -> AppStrings.ui_file_filter_disk
    FileFilterType.Developer -> AppStrings.ui_file_filter_developer
    FileFilterType.Backup -> AppStrings.ui_file_filter_backup
    FileFilterType.Misc -> AppStrings.ui_file_filter_misc
    FileFilterType.Custom -> AppStrings.ui_file_filter_custom
}

fun FileFilter.displayName(): String =
    if (type == FileFilterType.Custom) name else type.displayName()

@Composable
fun GetFileFilterType(type: FileFilterType, modifier: Modifier = Modifier) {
    when (type) {
        FileFilterType.Folder -> Icon(Icons.Default.Folder, null, modifier = modifier)
        FileFilterType.File -> Icon(Icons.AutoMirrored.Default.Note, null, modifier = modifier)
        FileFilterType.Hidden -> Icon(Icons.Default.VisibilityOff, null, modifier = modifier)
        FileFilterType.Text -> Icon(Icons.Default.Description, null, modifier = modifier)
        FileFilterType.Audio -> Icon(Icons.Default.Headphones, null, modifier = modifier)
        FileFilterType.Video -> Icon(Icons.Default.Videocam, null, modifier = modifier)
        FileFilterType.Image -> Icon(Icons.Default.Image, null, modifier = modifier)
        FileFilterType.ImageRaw -> Icon(Icons.Default.RawOn, null, modifier = modifier)
        FileFilterType.ImageVector -> Icon(Icons.Default.Landscape, null, modifier = modifier)
        FileFilterType.Image3D -> Icon(Icons.Default.ViewInAr, null, modifier = modifier)
        FileFilterType.PageLayout -> Icon(Icons.AutoMirrored.Default.Article, null, modifier = modifier)
        FileFilterType.Database -> Icon(Icons.Default.Dataset, null, modifier = modifier)
        FileFilterType.Executable -> Icon(Icons.Default.Emergency, null, modifier = modifier)
        FileFilterType.Game -> Icon(Icons.Default.Games, null, modifier = modifier)
        FileFilterType.CAD -> Icon(Icons.Default.Architecture, null, modifier = modifier)
        FileFilterType.GIS -> Icon(Icons.Default.Map, null, modifier = modifier)
        FileFilterType.Web -> Icon(Icons.Default.Web, null, modifier = modifier)
        FileFilterType.Plugin -> Icon(Icons.Default.Extension, null, modifier = modifier)
        FileFilterType.Font -> Icon(Icons.Default.FontDownload, null, modifier = modifier)
        FileFilterType.System -> Icon(Icons.Default.Build, null, modifier = modifier)
        FileFilterType.Settings -> Icon(Icons.Default.SettingsEthernet, null, modifier = modifier)
        FileFilterType.Encoded -> Icon(Icons.Default.Lock, null, modifier = modifier)
        FileFilterType.Compressed -> Icon(Icons.Default.FolderZip, null, modifier = modifier)
        FileFilterType.Disk -> Icon(Icons.Default.Adjust, null, modifier = modifier)
        FileFilterType.Developer -> Icon(Icons.Default.DataObject, null, modifier = modifier)
        FileFilterType.Backup -> Icon(Icons.Default.Backup, null, modifier = modifier)
        FileFilterType.Misc -> Icon(Icons.Default.HideSource, null, modifier = modifier)
        FileFilterType.Custom -> Icon(Icons.Default.MoreHoriz, null, modifier = modifier)
    }
}

enum class FileFilterSort(type: Int) {
    NameAsc(0),
    NameDesc(1),
    SizeAsc(2),
    SizeDesc(3),
    TypeAsc(4),
    TypeDesc(5),
    CreatedDateAsc(6),
    CreatedDateDesc(7),
    UpdatedDateAsc(8),
    UpdatedDateDesc(9),
}
