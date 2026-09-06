package app.opah.tv.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.opah.tv.playback.compatibility.CompatibilityHmacSha256Port
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Dedicated non-exportable HMAC key for compatibility identities; unrelated to session keys. */
class AndroidKeystoreCompatibilityHmac : CompatibilityHmacSha256Port {
    private val lock = Any()

    override fun digest(canonicalInput: ByteArray): ByteArray = synchronized(lock) {
        Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
            init(getOrCreateKey())
            doFinal(canonicalInput)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEY_STORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "opah.compatibility.identity.hmac.v1"
    }
}
