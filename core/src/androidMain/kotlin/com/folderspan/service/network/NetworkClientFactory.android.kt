package com.folderspan.service.network

import com.folderspan.service.network.nfs.NfsNetworkClient

import strings.AppStrings

import com.folderspan.data.main.network.*

actual object NetworkClientFactory {
    actual fun create(network: NetworkConnectionConfig): StorageProvider {
        return when (network.protocol) {
            NetworkProtocol.Seafile.name -> SeafileNetworkClient(network)
            NetworkProtocol.Nextcloud.name -> NextcloudNetworkClient(network)
            NetworkProtocol.Dropbox.name -> DropboxNetworkClient(network)
            NetworkProtocol.GoogleDrive.name -> GoogleDriveNetworkClient(network)
            NetworkProtocol.NFS.name -> NfsNetworkClient(network)
            NetworkProtocol.HttpDirectory.name -> HttpDirectoryNetworkClient(network)
            NetworkProtocol.FTP.name -> FtpNetworkClient(network)
            NetworkProtocol.SFTP.name -> SftpNetworkClient(network)
            NetworkProtocol.SMB.name -> SmbNetworkClient(network)
            NetworkProtocol.WebDav.name -> WebDavNetworkClient(network)
            NetworkProtocol.S3.name -> KtorS3NetworkClient(network)
            else -> UnsupportedNetworkClient(AppStrings.ui_arg0_not_supported_yet.format(arg0 = network.protocol))
        }
    }
}
