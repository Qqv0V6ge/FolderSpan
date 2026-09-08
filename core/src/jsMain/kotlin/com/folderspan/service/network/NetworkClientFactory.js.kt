package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.data.main.network.*

actual object NetworkClientFactory {
    actual fun create(network: Network): NetworkClient {
        return when (network.protocol) {
            NetworkProtocol.WebDav.name -> WebDavNetworkClient(network)
            NetworkProtocol.S3.name -> KtorS3NetworkClient(network)
            else -> UnsupportedNetworkClient(AppStrings.ui_web_platform_does_not_currently_support_arg0.format(arg0 = network.protocol))
        }
    }
}
