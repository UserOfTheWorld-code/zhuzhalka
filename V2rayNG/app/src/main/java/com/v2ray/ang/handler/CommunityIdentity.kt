package com.v2ray.ang.handler

import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import fr.acinq.secp256k1.Secp256k1
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface CommunitySigner {
    fun publicKeyHex(): String
    fun signHash32(hash: ByteArray): ByteArray
}

/** BIP340 operations use the pinned Bitcoin Core bindings, never a project-owned curve implementation. */
internal object CommunityCrypto {
    private val random = SecureRandom()

    fun verifySignature(hash: ByteArray, publicKey: ByteArray, signature: ByteArray): Boolean {
        if (hash.size != 32 || publicKey.size != 32 || signature.size != 64) return false
        return try {
            Secp256k1.verifySchnorr(signature, hash, publicKey)
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            throw CommunityIdentityException(CommunityIdentityFailure.CRYPTO_UNAVAILABLE)
        }
    }

    /** In-memory signer for caller-owned fixtures; the Android identity never exports its secret. */
    fun createSigner(secret: ByteArray): CommunitySigner {
        requireValidSecret(secret)
        val owned = secret.copyOf()
        return object : CommunitySigner {
            override fun publicKeyHex() = publicKey(owned).toCommunityHex()
            override fun signHash32(hash: ByteArray) = sign(hash, owned)
        }
    }

    internal fun requireValidSecret(secret: ByteArray) {
        if (secret.size != 32) throw CommunityIdentityException(CommunityIdentityFailure.INVALID_KEY)
        val valid = try { Secp256k1.secKeyVerify(secret) } catch (_: LinkageError) {
            throw CommunityIdentityException(CommunityIdentityFailure.CRYPTO_UNAVAILABLE)
        }
        if (!valid) throw CommunityIdentityException(CommunityIdentityFailure.INVALID_KEY)
    }

    internal fun publicKey(secret: ByteArray): ByteArray {
        requireValidSecret(secret)
        val uncompressed = Secp256k1.pubkeyCreate(secret)
        check(uncompressed.size == 65 && uncompressed[0] == 4.toByte()) { "Unexpected public key encoding" }
        return uncompressed.copyOfRange(1, 33)
    }

    internal fun sign(hash: ByteArray, secret: ByteArray): ByteArray {
        val auxiliary = ByteArray(32).also(random::nextBytes)
        return try { signWithAuxiliary(hash, secret, auxiliary) } finally { auxiliary.fill(0) }
    }

    /** Explicit auxiliary input allows the official deterministic BIP340 vectors to exercise our binding. */
    internal fun signWithAuxiliary(hash: ByteArray, secret: ByteArray, auxiliary: ByteArray): ByteArray {
        require(hash.size == 32 && auxiliary.size == 32) { "Expected 32-byte hash and auxiliary randomness" }
        requireValidSecret(secret)
        return Secp256k1.signSchnorr(hash, secret, auxiliary).also {
            check(it.size == 64) { "Unexpected Schnorr signature encoding" }
        }
    }
}

internal enum class CommunityIdentityFailure { INVALID_KEY, INVALID_RECORD, KEY_UNAVAILABLE, STORAGE_FAILED, CRYPTO_UNAVAILABLE }

/** Fixed error codes deliberately exclude exception messages, keys, ciphertext and received payloads. */
internal class CommunityIdentityException(val reason: CommunityIdentityFailure) :
    IllegalStateException("Community identity: ${reason.name}")

internal interface CommunityIdentityStorage {
    fun read(): String?
    fun write(record: String)
}

internal interface CommunityKeyProtector {
    fun seal(secret: ByteArray): String
    fun open(record: String): ByteArray
}

/** Owns the opt-in identity lifetime; opening a signer is the only operation that may create an identity. */
internal class StoredCommunityIdentity(
    private val storage: CommunityIdentityStorage,
    private val protector: CommunityKeyProtector,
    private val newSecret: () -> ByteArray = ::newCommunitySecret,
) {
    private val lock = Any()

    fun getOrCreateSigner(): CommunitySigner = synchronized(lock) {
        if (readRecord() == null) {
            val secret = newSecret()
            try {
                CommunityCrypto.requireValidSecret(secret)
                val record = protector.seal(secret)
                try {
                    storage.write(record)
                    if (readRecord() != record) throw CommunityIdentityException(CommunityIdentityFailure.STORAGE_FAILED)
                } catch (_: Exception) {
                    throw CommunityIdentityException(CommunityIdentityFailure.STORAGE_FAILED)
                }
            } finally { secret.fill(0) }
        }
        // Detect a lost Keystore key or damaged record before accepting the opt-in operation.
        withSecret { CommunityCrypto.publicKey(it) }
        object : CommunitySigner {
            override fun publicKeyHex() = synchronized(lock) { withSecret { CommunityCrypto.publicKey(it).toCommunityHex() } }
            override fun signHash32(hash: ByteArray) = synchronized(lock) { withSecret { CommunityCrypto.sign(hash, it) } }
        }
    }

    private fun <T> withSecret(operation: (ByteArray) -> T): T {
        val record = readRecord() ?: throw CommunityIdentityException(CommunityIdentityFailure.INVALID_RECORD)
        val secret = protector.open(record)
        return try {
            CommunityCrypto.requireValidSecret(secret)
            operation(secret)
        } finally { secret.fill(0) }
    }

    private fun readRecord(): String? = try { storage.read() } catch (_: Exception) {
        throw CommunityIdentityException(CommunityIdentityFailure.STORAGE_FAILED)
    }
}

/** Creating this object performs no storage, Keystore or JNI work. Call only after explicit opt-in, on IO. */
internal object CommunityIdentity {
    private val identity by lazy {
        StoredCommunityIdentity(
            storage = object : CommunityIdentityStorage {
                override fun read() = MmkvManager.decodeCommunityIdentityRecord()
                override fun write(record: String) = MmkvManager.encodeCommunityIdentityRecord(record)
            },
            protector = AndroidCommunityKeyProtector(),
        )
    }

    fun getOrCreateSigner(): CommunitySigner {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Community identity must be initialized off the main thread" }
        val signer = identity.getOrCreateSigner()
        return object : CommunitySigner {
            override fun publicKeyHex(): String {
                checkWorkerThread()
                return signer.publicKeyHex()
            }
            override fun signHash32(hash: ByteArray): ByteArray {
                checkWorkerThread()
                return signer.signHash32(hash)
            }
        }
    }

    private fun checkWorkerThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Community identity must be used off the main thread" }
    }
}

/** AndroidKeyStore cannot hold a secp256k1 signing key: its AES key wraps the random BIP340 scalar. */
internal class AndroidCommunityKeyProtector(
    private val keyProvider: (create: Boolean) -> SecretKey = AndroidCommunityWrappingKey::get,
) : CommunityKeyProtector {
    override fun seal(secret: ByteArray): String = guarded {
        require(secret.size == 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
        cipher.updateAAD(AAD)
        CommunityWrappedKey.encode(cipher.iv, cipher.doFinal(secret))
    }

    override fun open(record: String): ByteArray = guarded {
        val (iv, ciphertext) = CommunityWrappedKey.decode(record)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        cipher.doFinal(ciphertext)
    }

    private fun <T> guarded(operation: () -> T): T = try {
        operation()
    } catch (error: CommunityIdentityException) {
        throw error
    } catch (_: Exception) {
        throw CommunityIdentityException(CommunityIdentityFailure.KEY_UNAVAILABLE)
    }

    private companion object {
        val AAD = "Zhuzhalka/community-identity/v1".toByteArray(Charsets.US_ASCII)
    }
}

private object AndroidCommunityWrappingKey {
    private const val ALIAS = "zhuzhalka.community.identity.wrap.v1"

    fun get(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create || store.containsAlias(ALIAS)) throw CommunityIdentityException(CommunityIdentityFailure.KEY_UNAVAILABLE)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

}

private fun newCommunitySecret(): ByteArray {
    val random = SecureRandom()
    while (true) {
        val candidate = ByteArray(32).also(random::nextBytes)
        try {
            CommunityCrypto.requireValidSecret(candidate)
            return candidate
        } catch (error: CommunityIdentityException) {
            candidate.fill(0)
            if (error.reason != CommunityIdentityFailure.INVALID_KEY) throw error
        }
    }
}

/** Fixed-width versioned record: a 96-bit IV and a 32-byte scalar plus a 128-bit GCM tag. */
internal object CommunityWrappedKey {
    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        if (iv.size != 12 || ciphertext.size != 48) throw CommunityIdentityException(CommunityIdentityFailure.INVALID_RECORD)
        return "1:${iv.toCommunityHex()}:${ciphertext.toCommunityHex()}"
    }

    fun decode(record: String): Pair<ByteArray, ByteArray> {
        if (record.length != 123 || !record.matches(Regex("1:[0-9a-f]{24}:[0-9a-f]{96}"))) {
            throw CommunityIdentityException(CommunityIdentityFailure.INVALID_RECORD)
        }
        return record.substring(2, 26).communityHexBytes() to record.substring(27).communityHexBytes()
    }
}

internal fun ByteArray.toCommunityHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.communityHexBytes(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid hex encoding" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
