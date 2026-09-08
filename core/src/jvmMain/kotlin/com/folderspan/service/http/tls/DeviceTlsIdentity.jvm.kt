package com.folderspan.service.http.tls

import strings.AppStrings

import com.folderspan.createSettings
import com.folderspan.utils.LogKit
import com.russhwolf.settings.Settings
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

actual object DeviceTlsIdentity {
    private const val STORE_TYPE = "PKCS12"
    private const val ALIAS = "folderspan-device"

    @Volatile
    private var cached: CachedIdentity? = null

    private val keyStoreProvider: Provider by lazy(::BouncyCastleProvider)

    actual fun loadOrCreate(): DeviceTlsIdentityInfo {
        return loadOrCreateCached().info
    }

    actual fun publicKeyPem(): String? = runCatching {
        val encoded = loadOrCreateCached().keyStore.getCertificate(ALIAS).publicKey.encoded
        "-----BEGIN PUBLIC KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(encoded) +
            "\n-----END PUBLIC KEY-----"
    }.getOrNull()

    actual fun signSha256WithRsa(payload: ByteArray): String? = runCatching {
        val identity = loadOrCreateCached()
        val privateKey = identity.keyStore.getKey(ALIAS, identity.password) as PrivateKey
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(payload)
        }.sign()
        Base64.getEncoder().encodeToString(signature)
    }.getOrNull()

    actual fun verifySha256WithRsa(
        publicKeyPem: String,
        payload: ByteArray,
        signature: String,
    ): Boolean = runCatching {
        val encodedPublicKey = Base64.getDecoder().decode(
            publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .filterNot(Char::isWhitespace)
        )
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(java.security.spec.X509EncodedKeySpec(encodedPublicKey))
        Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(payload)
            verify(Base64.getDecoder().decode(signature))
        }
    }.getOrDefault(false)

    internal fun createServerSslContext(): SSLContext {
        val identity = loadOrCreateCached()
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(identity.keyStore, identity.password)
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, SecureRandom())
        }
    }

    /** Device session TLS only. LinkShare HTTPS must keep using [createServerSslContext]. */
    internal fun createSessionServerSslContext(): SSLContext = createServerSslContext()

    private fun loadOrCreateCached(): CachedIdentity {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadOrCreateKeyStore().also { cached = it }
        }
    }

    private fun loadOrCreateKeyStore(): CachedIdentity {
        return loadOrCreateKeyStore(
            readIdentityPayload = DeviceTlsIdentityFileStore::readIdentityPayload,
            writeIdentityPayload = DeviceTlsIdentityFileStore::writeIdentityPayload,
            settings = createSettings(),
        )
    }

    internal fun loadOrCreateUncached(
        fileStore: JvmTlsIdentityFileStore,
        settings: Settings,
    ): DeviceTlsIdentityInfo {
        return loadOrCreateKeyStore(
            readIdentityPayload = fileStore::readIdentityPayload,
            writeIdentityPayload = fileStore::writeIdentityPayload,
            settings = settings,
        ).info
    }

    private fun loadOrCreateKeyStore(
        readIdentityPayload: () -> ByteArray?,
        writeIdentityPayload: (ByteArray) -> Unit,
        settings: Settings,
    ): CachedIdentity {
        val stored = readIdentityPayload()
        if (stored != null) {
            try {
                return decodeStoredIdentity(stored, settings)
            } catch (error: Exception) {
                LogKit.e(AppStrings.ui_persisted_tls_identity_parse_failed_reset_rejected, error)
                throw IllegalStateException(AppStrings.ui_persisted_tls_identity_invalid, error)
            }
        }

        return generateAndPersistKeyStore(writeIdentityPayload, settings)
    }

    private fun generateAndPersistKeyStore(
        writeIdentityPayload: (ByteArray) -> Unit,
        settings: Settings,
    ): CachedIdentity {
        val password = resolvePkcs12Password(settings)
        val keyStore = generateKeyStore(password)
        val cert = keyStore.getCertificate(ALIAS) as X509Certificate
        val identity = CachedIdentity(keyStore, DeviceTlsIdentityInfo(cert.encoded.sha256Hex()), password)
        val encoded = try {
            encodeKeyStore(keyStore, password)
        } catch (error: Exception) {
            LogKit.e(AppStrings.ui_encoding_desktop_tls_identity_failed, error)
            throw IllegalStateException(AppStrings.ui_tls_identity_encoding_failed, error)
        }
        try {
            writeIdentityPayload(encoded)
        } catch (error: Exception) {
            LogKit.e(AppStrings.ui_persistent_desktop_tls_identity_failed, error)
            throw IllegalStateException(AppStrings.ui_tls_identity_persistence_failed, error)
        }
        return identity
    }

    private fun decodeStoredIdentity(
        bytes: ByteArray,
        settings: Settings,
    ): CachedIdentity {
        val storedPassword = DeviceTlsPkcs12Password.read(settings)
            ?: error(AppStrings.ui_device_tls_pkcs12_password_missing)
        return decodeKeyStore(bytes, storedPassword.toCharArray())
    }

    private fun decodeKeyStore(bytes: ByteArray, password: CharArray): CachedIdentity {
        val keyStore = KeyStore.getInstance(STORE_TYPE, keyStoreProvider)
        keyStore.load(ByteArrayInputStream(bytes), password)
        val cert = keyStore.getCertificate(ALIAS) as X509Certificate
        return CachedIdentity(keyStore, DeviceTlsIdentityInfo(cert.encoded.sha256Hex()), password)
    }

    private fun encodeKeyStore(keyStore: KeyStore, password: CharArray): ByteArray {
        val out = ByteArrayOutputStream()
        keyStore.store(out, password)
        return out.toByteArray()
    }

    private fun generateKeyStore(password: CharArray): KeyStore {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()
        val now = System.currentTimeMillis()
        val principal = X500Principal("CN=FolderSpan Device")
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(keyStoreProvider)
            .build(keyPair.private)
        val certificate = JcaX509CertificateConverter()
            .setProvider(keyStoreProvider)
            .getCertificate(
                JcaX509v3CertificateBuilder(
                    principal,
                    BigInteger(160, SecureRandom()).abs(),
                    Date(now - 24L * 60L * 60L * 1000L),
                    Date(now + 20L * 365L * 24L * 60L * 60L * 1000L),
                    principal,
                    keyPair.public
                ).build(signer)
            )

        return KeyStore.getInstance(STORE_TYPE, keyStoreProvider).apply {
            load(null, password)
            setKeyEntry(ALIAS, keyPair.private, password, arrayOf(certificate))
        }
    }

    private fun resolvePkcs12Password(settings: Settings): CharArray {
        val existing = DeviceTlsPkcs12Password.read(settings)
        if (existing != null) return existing.toCharArray()
        val generated = generatePkcs12Password()
        DeviceTlsPkcs12Password.write(settings, generated)
        return generated.toCharArray()
    }

    private fun generatePkcs12Password(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString("") { byte -> "%02X".format(byte) }
    }

    private data class CachedIdentity(
        val keyStore: KeyStore,
        val info: DeviceTlsIdentityInfo,
        val password: CharArray,
    )
}
