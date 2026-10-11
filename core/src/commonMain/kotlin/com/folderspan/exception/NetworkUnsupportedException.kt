package com.folderspan.exception

import strings.AppStrings

class NetworkUnsupportedException(
    message: String = AppStrings.network_protocol_not_supported,
) : Exception(message)
