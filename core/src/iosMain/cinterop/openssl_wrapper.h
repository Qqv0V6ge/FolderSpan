#include <openssl/bio.h>
#include <openssl/bn.h>
#include <openssl/buffer.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <stdlib.h>
#include <string.h>

static inline void fm_openssl_init(void) {
    OPENSSL_init_ssl(0, NULL);
}

static inline EVP_PKEY *fm_generate_rsa_key(void) {
    EVP_PKEY *pkey = EVP_PKEY_new();
    RSA *rsa = RSA_new();
    BIGNUM *bn = BN_new();
    if (pkey == NULL || rsa == NULL || bn == NULL) {
        EVP_PKEY_free(pkey);
        RSA_free(rsa);
        BN_free(bn);
        return NULL;
    }
    if (BN_set_word(bn, RSA_F4) != 1 || RSA_generate_key_ex(rsa, 2048, bn, NULL) != 1) {
        EVP_PKEY_free(pkey);
        RSA_free(rsa);
        BN_free(bn);
        return NULL;
    }
    if (EVP_PKEY_assign_RSA(pkey, rsa) != 1) {
        EVP_PKEY_free(pkey);
        RSA_free(rsa);
        BN_free(bn);
        return NULL;
    }
    BN_free(bn);
    return pkey;
}

static inline X509 *fm_generate_self_signed_cert(EVP_PKEY *pkey) {
    X509 *cert = X509_new();
    if (cert == NULL) return NULL;
    ASN1_INTEGER_set(X509_get_serialNumber(cert), 1);
    X509_gmtime_adj(X509_get_notBefore(cert), -24L * 60L * 60L);
    X509_gmtime_adj(X509_get_notAfter(cert), 20L * 365L * 24L * 60L * 60L);
    X509_set_pubkey(cert, pkey);

    X509_NAME *name = X509_get_subject_name(cert);
    X509_NAME_add_entry_by_txt(
        name,
        "CN",
        MBSTRING_ASC,
        (const unsigned char *)"FolderSpan Device",
        -1,
        -1,
        0
    );
    X509_set_issuer_name(cert, name);

    if (X509_sign(cert, pkey, EVP_sha256()) == 0) {
        X509_free(cert);
        return NULL;
    }
    return cert;
}

static inline char *fm_x509_to_pem(X509 *cert) {
    BIO *bio = BIO_new(BIO_s_mem());
    if (bio == NULL) return NULL;
    if (PEM_write_bio_X509(bio, cert) != 1) {
        BIO_free(bio);
        return NULL;
    }
    BUF_MEM *mem = NULL;
    BIO_get_mem_ptr(bio, &mem);
    if (mem == NULL) {
        BIO_free(bio);
        return NULL;
    }
    char *out = (char *)malloc(mem->length + 1);
    if (out == NULL) {
        BIO_free(bio);
        return NULL;
    }
    memcpy(out, mem->data, mem->length);
    out[mem->length] = '\0';
    BIO_free(bio);
    return out;
}

static inline char *fm_private_key_to_pem(EVP_PKEY *pkey) {
    BIO *bio = BIO_new(BIO_s_mem());
    if (bio == NULL) return NULL;
    if (PEM_write_bio_PrivateKey(bio, pkey, NULL, NULL, 0, NULL, NULL) != 1) {
        BIO_free(bio);
        return NULL;
    }
    BUF_MEM *mem = NULL;
    BIO_get_mem_ptr(bio, &mem);
    if (mem == NULL) {
        BIO_free(bio);
        return NULL;
    }
    char *out = (char *)malloc(mem->length + 1);
    if (out == NULL) {
        BIO_free(bio);
        return NULL;
    }
    memcpy(out, mem->data, mem->length);
    out[mem->length] = '\0';
    BIO_free(bio);
    return out;
}

static inline EVP_PKEY *fm_read_private_key_pem(const char *pem);

static inline char *fm_x509_public_key_to_pem(X509 *cert) {
    EVP_PKEY *pkey = X509_get_pubkey(cert);
    if (pkey == NULL) return NULL;
    BIO *bio = BIO_new(BIO_s_mem());
    if (bio == NULL) {
        EVP_PKEY_free(pkey);
        return NULL;
    }
    if (PEM_write_bio_PUBKEY(bio, pkey) != 1) {
        BIO_free(bio);
        EVP_PKEY_free(pkey);
        return NULL;
    }
    BUF_MEM *mem = NULL;
    BIO_get_mem_ptr(bio, &mem);
    char *out = mem == NULL ? NULL : (char *)malloc(mem->length + 1);
    if (out != NULL) {
        memcpy(out, mem->data, mem->length);
        out[mem->length] = '\0';
    }
    BIO_free(bio);
    EVP_PKEY_free(pkey);
    return out;
}

static inline char *fm_sign_sha256_rsa_hex(
    const char *private_key_pem,
    const unsigned char *payload,
    size_t payload_len
) {
    EVP_PKEY *pkey = fm_read_private_key_pem(private_key_pem);
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    if (pkey == NULL || ctx == NULL) {
        EVP_PKEY_free(pkey);
        EVP_MD_CTX_free(ctx);
        return NULL;
    }
    size_t signature_len = 0;
    int ok = EVP_DigestSignInit(ctx, NULL, EVP_sha256(), NULL, pkey) == 1 &&
        EVP_DigestSignUpdate(ctx, payload, payload_len) == 1 &&
        EVP_DigestSignFinal(ctx, NULL, &signature_len) == 1;
    unsigned char *signature = ok ? (unsigned char *)malloc(signature_len) : NULL;
    if (signature == NULL || EVP_DigestSignFinal(ctx, signature, &signature_len) != 1) {
        free(signature);
        EVP_MD_CTX_free(ctx);
        EVP_PKEY_free(pkey);
        return NULL;
    }
    static const char hex[] = "0123456789abcdef";
    char *out = (char *)malloc(signature_len * 2 + 1);
    if (out != NULL) {
        for (size_t i = 0; i < signature_len; i++) {
            out[i * 2] = hex[signature[i] >> 4];
            out[i * 2 + 1] = hex[signature[i] & 0x0f];
        }
        out[signature_len * 2] = '\0';
    }
    free(signature);
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(pkey);
    return out;
}

static inline int fm_verify_sha256_rsa_hex(
    const char *public_key_pem,
    const unsigned char *payload,
    size_t payload_len,
    const char *signature_hex
) {
    size_t hex_len = strlen(signature_hex);
    if (hex_len == 0 || (hex_len % 2) != 0) return 0;
    BIO *bio = BIO_new_mem_buf(public_key_pem, -1);
    EVP_PKEY *pkey = bio == NULL ? NULL : PEM_read_bio_PUBKEY(bio, NULL, NULL, NULL);
    BIO_free(bio);
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    unsigned char *signature = (unsigned char *)malloc(hex_len / 2);
    if (pkey == NULL || ctx == NULL || signature == NULL) {
        EVP_PKEY_free(pkey);
        EVP_MD_CTX_free(ctx);
        free(signature);
        return 0;
    }
    for (size_t i = 0; i < hex_len; i += 2) {
        char pair[3] = { signature_hex[i], signature_hex[i + 1], '\0' };
        char *end = NULL;
        long value = strtol(pair, &end, 16);
        if (end == NULL || *end != '\0') {
            EVP_PKEY_free(pkey);
            EVP_MD_CTX_free(ctx);
            free(signature);
            return 0;
        }
        signature[i / 2] = (unsigned char)value;
    }
    int ok = EVP_DigestVerifyInit(ctx, NULL, EVP_sha256(), NULL, pkey) == 1 &&
        EVP_DigestVerifyUpdate(ctx, payload, payload_len) == 1 &&
        EVP_DigestVerifyFinal(ctx, signature, hex_len / 2) == 1;
    EVP_PKEY_free(pkey);
    EVP_MD_CTX_free(ctx);
    free(signature);
    return ok;
}

static inline X509 *fm_read_x509_pem(const char *pem) {
    BIO *bio = BIO_new_mem_buf(pem, -1);
    if (bio == NULL) return NULL;
    X509 *cert = PEM_read_bio_X509(bio, NULL, NULL, NULL);
    BIO_free(bio);
    return cert;
}

static inline EVP_PKEY *fm_read_private_key_pem(const char *pem) {
    BIO *bio = BIO_new_mem_buf(pem, -1);
    if (bio == NULL) return NULL;
    EVP_PKEY *pkey = PEM_read_bio_PrivateKey(bio, NULL, NULL, NULL);
    BIO_free(bio);
    return pkey;
}

static inline int fm_x509_sha256_fingerprint(X509 *cert, unsigned char *out, unsigned int *out_len) {
    return X509_digest(cert, EVP_sha256(), out, out_len);
}

static inline SSL_CTX *fm_create_server_ctx(const char *cert_pem, const char *key_pem) {
    fm_openssl_init();
    SSL_CTX *ctx = SSL_CTX_new(TLS_server_method());
    if (ctx == NULL) return NULL;

    X509 *cert = fm_read_x509_pem(cert_pem);
    EVP_PKEY *pkey = fm_read_private_key_pem(key_pem);
    if (cert == NULL || pkey == NULL) {
        X509_free(cert);
        EVP_PKEY_free(pkey);
        SSL_CTX_free(ctx);
        return NULL;
    }

    SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
    int ok = SSL_CTX_use_certificate(ctx, cert) == 1 &&
        SSL_CTX_use_PrivateKey(ctx, pkey) == 1 &&
        SSL_CTX_check_private_key(ctx) == 1;

    X509_free(cert);
    EVP_PKEY_free(pkey);
    if (!ok) {
        SSL_CTX_free(ctx);
        return NULL;
    }
    return ctx;
}

static const unsigned char FM_DEVICE_SESSION_ALPN[] = {
    12, 'f', 'o', 'l', 'd', 'e', 'r', 's', 'p', 'a', 'n', '/', '1'
};

static inline int fm_alpn_select_cb(
    SSL *ssl,
    const unsigned char **out,
    unsigned char *outlen,
    const unsigned char *in,
    unsigned int inlen,
    void *arg
) {
    (void)ssl;
    (void)arg;
    unsigned char *selected = NULL;
    unsigned char selected_len = 0;
    if (SSL_select_next_proto(
            &selected,
            &selected_len,
            FM_DEVICE_SESSION_ALPN,
            (unsigned int)sizeof(FM_DEVICE_SESSION_ALPN),
            in,
            inlen
        ) != OPENSSL_NPN_NEGOTIATED) {
        return SSL_TLSEXT_ERR_ALERT_FATAL;
    }
    *out = selected;
    *outlen = selected_len;
    return SSL_TLSEXT_ERR_OK;
}

static inline int fm_ssl_ctx_require_alpn(SSL_CTX *ctx) {
    if (ctx == NULL) return 0;
    SSL_CTX_set_alpn_select_cb(ctx, fm_alpn_select_cb, NULL);
    return 1;
}

static inline int fm_ssl_ctx_offer_alpn(SSL_CTX *ctx) {
    if (ctx == NULL) return 0;
    return SSL_CTX_set_alpn_protos(
        ctx,
        FM_DEVICE_SESSION_ALPN,
        (unsigned int)sizeof(FM_DEVICE_SESSION_ALPN)
    ) == 0;
}

static inline int fm_ssl_alpn_is_folderspan(SSL *ssl) {
    const unsigned char *data = NULL;
    unsigned int len = 0;
    if (ssl == NULL) return 0;
    SSL_get0_alpn_selected(ssl, &data, &len);
    return data != NULL && len == 12 && memcmp(data, "folderspan/1", 12) == 0;
}

static inline SSL_CTX *fm_create_client_ctx(void) {
    fm_openssl_init();
    SSL_CTX *ctx = SSL_CTX_new(TLS_client_method());
    if (ctx == NULL) return NULL;
    SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
    SSL_CTX_set_verify(ctx, SSL_VERIFY_NONE, NULL);
    if (!fm_ssl_ctx_offer_alpn(ctx)) {
        SSL_CTX_free(ctx);
        return NULL;
    }
    return ctx;
}

static inline int fm_ssl_peer_fingerprint_sha256(SSL *ssl, unsigned char *out, unsigned int *out_len) {
    X509 *cert = SSL_get_peer_certificate(ssl);
    if (cert == NULL) return 0;
    int ok = X509_digest(cert, EVP_sha256(), out, out_len);
    X509_free(cert);
    return ok;
}
