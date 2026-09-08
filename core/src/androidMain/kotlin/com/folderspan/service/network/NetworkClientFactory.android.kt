package com.folderspan.service.network

import strings.AppStrings

import com.folderspan.data.main.network.*

actual object NetworkClientFactory {
    actual fun create(network: Network): NetworkClient {
        return when (network.protocol) {
            NetworkProtocol.FTP.name -> FtpNetworkClient(network)
            NetworkProtocol.SFTP.name -> SftpNetworkClient(network)
            NetworkProtocol.SMB.name -> SmbNetworkClient(network)
            NetworkProtocol.WebDav.name -> WebDavNetworkClient(network)
            NetworkProtocol.S3.name -> KtorS3NetworkClient(network)
            else -> UnsupportedNetworkClient(AppStrings.ui_arg0_not_supported_yet.format(arg0 = network.protocol))
        }
    }
}
