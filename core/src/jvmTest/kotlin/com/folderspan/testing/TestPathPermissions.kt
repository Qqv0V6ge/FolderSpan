package com.folderspan.testing

import strings.AppStrings

import com.folderspan.db.FolderSpanDatabase

internal fun FolderSpanDatabase.grantTestAdministratorAccess() {
    val permissionId = 10_003L
    devicePermissionQueries.insertWithId(
        permissionId,
        System.getProperty("java.io.tmpdir"),
        true,
        true,
        true,
        true,
        true,
        0L,
        AppStrings.ui_test_test_path_permissions_test_root_permission,
    )
    deviceRoleDevicePermissionQueries.insert(1L, permissionId)
}
