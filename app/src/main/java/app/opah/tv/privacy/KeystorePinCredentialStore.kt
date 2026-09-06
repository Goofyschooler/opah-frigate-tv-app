package app.opah.tv.privacy

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface PinCredentialStoreRead {
    data object Missing : PinCredentialStoreRead
    data class Available(val snapshot: PinCredentialSnapshot) : PinCredentialStoreRead
    data object Corrupt : PinCredentialStoreRead
}

internal sealed interface PinCredentialStoreWrite {
    data object Written : PinCredentialStoreWrite
    data object Failed : PinCredentialStoreWrite
}

internal interface PinCredentialStore {
    fun read(): PinCredentialStoreRead
    fun write(snapshot: PinCredentialSnapshot): PinCredentialStoreWrite
    fun clear(): Boolean
}

/** Dedicated AES-GCM store. Corrupt records are retained and reported locked, never as absent. */
internal class KeystorePinCredentialStore(
    context: Context,
    private val codec: PinCredentialRecordCodec = PinCredentialRecordCodec(),
) : PinCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    override fun read(): PinCredentialStoreRead {
        val hasIv = runCatching { preferences.contains(IV_KEY) }.getOrDefault(true)
        val hasPayload = runCatching { preferences.contains(PAYLOAD_KEY) }.getOrDefault(true)
        if (!hasIv && !hasPayload) return PinCredentialStoreRead.Missing
        if (!hasIv || !hasPayload) return PinCredentialStoreRead.Corrupt
        val encodedIv = runCatching { preferences.getString(IV_KEY, null) }.getOrNull()
            ?: return PinCredentialStoreRead.Corrupt
        val encodedPayload = runCatching { preferences.getString(PAYLOAD_KEY, null) }.getOrNull()
            ?: return PinCredentialStoreRead.Corrupt
        if (
            encodedIv.length !in MIN_ENCODED_IV_CHARS..MAX_ENCODED_IV_CHARS ||
            encodedPayload.length !in MIN_ENCODED_PAYLOAD_CHARS..MAX_ENCODED_PAYLOAD_CHARS
        ) {
            return PinCredentialStoreRead.Corrupt
        }
        val iv = runCatching { Base64.decode(encodedIv, Base64.NO_WRAP) }.getOrNull()
            ?: return PinCredentialStoreRead.Corrupt
        val payload = runCatching { Base64.decode(encodedPayload, Base64.NO_WRAP) }.getOrNull()
            ?: return PinCredentialStoreRead.Corrupt
        return try {
            if (iv.size !in MIN_IV_BYTES..MAX_IV_BYTES || payload.size < GCM_TAG_BYTES) {
                return PinCredentialStoreRead.Corrupt
            }
            val cleartext = runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
                cipher.updateAAD(AAD)
                cipher.doFinal(payload)
            }.getOrNull() ?: return PinCredentialStoreRead.Corrupt
            try {
                when (val decoded = codec.decode(cleartext)) {
                    is PinCredentialDecodeResult.Decoded -> PinCredentialStoreRead.Available(decoded.snapshot)
                    PinCredentialDecodeResult.Corrupt -> PinCredentialStoreRead.Corrupt
                }
            } finally {
                cleartext.fill(0)
            }
        } finally {
            iv.fill(0)
            payload.fill(0)
        }
    }

    @Synchronized
    @SuppressLint("UseKtx") // The KTX wrapper discards commit(), but failure must stay observable.
    override fun write(snapshot: PinCredentialSnapshot): PinCredentialStoreWrite {
        val cleartext = runCatching { codec.encode(snapshot) }.getOrNull()
            ?: return PinCredentialStoreWrite.Failed
        return try {
            val cipher = runCatching {
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                    updateAAD(AAD)
                }
            }.getOrNull() ?: return PinCredentialStoreWrite.Failed
            val payload = runCatching { cipher.doFinal(cleartext) }.getOrNull()
                ?: return PinCredentialStoreWrite.Failed
            try {
                val committed = runCatching {
                    preferences.edit()
                        .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                        .putString(PAYLOAD_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                        .commit()
                }.getOrDefault(false)
                if (committed) PinCredentialStoreWrite.Written else PinCredentialStoreWrite.Failed
            } finally {
                payload.fill(0)
            }
        } finally {
            cleartext.fill(0)
        }
    }

    @Synchronized
    @SuppressLint("UseKtx") // Forgotten-PIN recovery must know whether secure deletion committed.
    override fun clear(): Boolean = runCatching {
        preferences.edit()
            .remove(IV_KEY)
            .remove(PAYLOAD_KEY)
            .commit()
    }.getOrDefault(false)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(KEY_SIZE_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opah_pin_credentials_aes_v1"
        const val PREFERENCES_NAME = "opah_pin_credentials"
        const val IV_KEY = "pin_record_iv_v1"
        const val PAYLOAD_KEY = "pin_record_payload_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
        const val GCM_TAG_BYTES = TAG_LENGTH_BITS / 8
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 16
        const val MIN_ENCODED_IV_CHARS = 16
        const val MAX_ENCODED_IV_CHARS = 32
        const val MIN_ENCODED_PAYLOAD_CHARS = 24
        const val MAX_ENCODED_PAYLOAD_CHARS = 8_192
        val AAD = "pin_credential_record_v1".encodeToByteArray()
    }
}
