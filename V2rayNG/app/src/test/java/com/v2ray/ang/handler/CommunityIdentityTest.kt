package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import javax.crypto.KeyGenerator

class CommunityIdentityTest {
    private val knownSecret = ByteArray(32).also { it[31] = 3 }

    @Test
    fun officialBip340VectorsVerifyAndSignThroughNativeBinding() {
        val vectors = vectors().filter { it.message.size == 32 }
        assertEquals(15, vectors.size)
        vectors.forEach { vector ->
            assertEquals(vector.valid, CommunityCrypto.verifySignature(vector.message, vector.publicKey, vector.signature), vector.id)
            if (vector.secret.isNotEmpty()) {
                assertArrayEquals(vector.publicKey, CommunityCrypto.publicKey(vector.secret), vector.id)
                assertArrayEquals(vector.signature, CommunityCrypto.signWithAuxiliary(vector.message, vector.secret, vector.auxiliary), vector.id)
            }
        }
    }

    @Test
    fun nostrHashBoundaryRejectsOfficialNon32ByteMessageVectors() {
        val variable = vectors().filter { it.message.size != 32 }
        assertEquals(4, variable.size)
        variable.forEach {
            assertFalse(CommunityCrypto.verifySignature(it.message, it.publicKey, it.signature))
            assertThrows(IllegalArgumentException::class.java) {
                CommunityCrypto.signWithAuxiliary(it.message, it.secret, it.auxiliary)
            }
        }
    }

    @Test
    fun signAndVerifyRequireExactLengthsAndValidCurvePoints() {
        val signer = CommunityCrypto.createSigner(knownSecret)
        val hash = ByteArray(32) { 7 }
        val publicKey = signer.publicKeyHex().communityHexBytes()
        val signature = signer.signHash32(hash)
        assertTrue(CommunityCrypto.verifySignature(hash, publicKey, signature))
        assertFalse(CommunityCrypto.verifySignature(hash.copyOf(31), publicKey, signature))
        assertFalse(CommunityCrypto.verifySignature(hash, publicKey.copyOf(33), signature))
        assertFalse(CommunityCrypto.verifySignature(hash, publicKey, signature.copyOf(65)))
        assertFalse(CommunityCrypto.verifySignature(hash, ByteArray(32) { -1 }, signature))
        assertFalse(CommunityCrypto.verifySignature(hash, publicKey, signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        assertThrows(CommunityIdentityException::class.java) { CommunityCrypto.createSigner(ByteArray(32)) }
        assertThrows(CommunityIdentityException::class.java) { CommunityCrypto.createSigner(ByteArray(32) { -1 }) }
    }

    @Test
    fun callerMutationCannotReplaceAnExistingFixtureSigner() {
        val supplied = knownSecret.copyOf()
        val signer = CommunityCrypto.createSigner(supplied)
        val expected = signer.publicKeyHex()
        supplied.fill(0)
        assertEquals(expected, signer.publicKeyHex())
        assertTrue(CommunityCrypto.verifySignature(ByteArray(32), expected.communityHexBytes(), signer.signHash32(ByteArray(32))))
    }

    @Test
    fun constructingIdentityDoesNotReadStorageGenerateKeysOrUseKeystore() {
        var calls = 0
        StoredCommunityIdentity(
            object : CommunityIdentityStorage {
                override fun read(): String? { calls++; return null }
                override fun write(record: String) { calls++ }
            },
            object : CommunityKeyProtector {
                override fun seal(secret: ByteArray): String { calls++; return "" }
                override fun open(record: String): ByteArray { calls++; return ByteArray(0) }
            },
            newSecret = { calls++; ByteArray(32) },
        )
        assertEquals(0, calls)
    }

    @Test
    fun wrappedIdentitySurvivesOwnerRecreationWithoutRegeneration() {
        val storage = MemoryStorage()
        val protector = aesProtector()
        val generated = knownSecret.copyOf()
        var generations = 0
        val original = StoredCommunityIdentity(storage, protector) { generations++; generated }.getOrCreateSigner()
        assertTrue(generated.all { it == 0.toByte() })
        assertFalse(storage.record!!.contains(knownSecret.toCommunityHex()))
        val reopened = StoredCommunityIdentity(storage, protector) { error("Must not regenerate existing identity") }.getOrCreateSigner()
        assertEquals(original.publicKeyHex(), reopened.publicKeyHex())
        assertTrue(CommunityCrypto.verifySignature(ByteArray(32), original.publicKeyHex().communityHexBytes(), reopened.signHash32(ByteArray(32))))
        assertEquals(1, generations)
        assertEquals(1, storage.writes)
    }

    @Test
    fun corruptExistingRecordFailsClosedWithoutReplacingIdentity() {
        val storage = MemoryStorage().apply { record = "corrupted-private-payload" }
        val failure = assertThrows(CommunityIdentityException::class.java) {
            StoredCommunityIdentity(storage, aesProtector()) { error("Must not regenerate") }.getOrCreateSigner()
        }
        assertEquals(CommunityIdentityFailure.INVALID_RECORD, failure.reason)
        assertEquals("corrupted-private-payload", storage.record)
        assertEquals(0, storage.writes)
        assertFalse(failure.toString().contains(storage.record!!))
        assertNull(failure.cause)
    }

    @Test
    fun lostWrappingKeyDoesNotCreateAReplacementOrResetStoredIdentity() {
        val storage = MemoryStorage().apply { record = aesProtector().seal(knownSecret) }
        val before = storage.record
        var requestedCreate: Boolean? = null
        val protector = AndroidCommunityKeyProtector { create ->
            requestedCreate = create
            throw IllegalStateException("Secret provider detail must not escape")
        }
        val failure = assertThrows(CommunityIdentityException::class.java) {
            StoredCommunityIdentity(storage, protector) { error("Must not regenerate") }.getOrCreateSigner()
        }
        assertEquals(false, requestedCreate)
        assertEquals(CommunityIdentityFailure.KEY_UNAVAILABLE, failure.reason)
        assertEquals(before, storage.record)
        assertEquals(0, storage.writes)
        assertNull(failure.cause)
    }

    @Test
    fun failedStorageWriteWipesGeneratedSecretAndReturnsNoSigner() {
        val generated = knownSecret.copyOf()
        val storage = MemoryStorage().apply { failWrites = true }
        val failure = assertThrows(CommunityIdentityException::class.java) {
            StoredCommunityIdentity(storage, aesProtector()) { generated }.getOrCreateSigner()
        }
        assertEquals(CommunityIdentityFailure.STORAGE_FAILED, failure.reason)
        assertTrue(generated.all { it == 0.toByte() })
        assertNull(storage.record)
    }

    @Test
    fun invalidGeneratedSecretIsWipedWithoutWriting() {
        val generated = ByteArray(32) { -1 }
        val storage = MemoryStorage()
        assertThrows(CommunityIdentityException::class.java) {
            StoredCommunityIdentity(storage, aesProtector()) { generated }.getOrCreateSigner()
        }
        assertTrue(generated.all { it == 0.toByte() })
        assertEquals(0, storage.writes)
    }

    @Test
    fun decryptedSecretsAreWipedAfterSuccessAndInvalidHashFailure() {
        val opened = mutableListOf<ByteArray>()
        val storage = MemoryStorage().apply { record = "opaque" }
        val protector = object : CommunityKeyProtector {
            override fun seal(secret: ByteArray) = error("No creation expected")
            override fun open(record: String) = knownSecret.copyOf().also { opened.add(it) }
        }
        val signer = StoredCommunityIdentity(storage, protector).getOrCreateSigner()
        signer.publicKeyHex()
        signer.signHash32(ByteArray(32))
        assertThrows(IllegalArgumentException::class.java) { signer.signHash32(ByteArray(31)) }
        assertEquals(4, opened.size)
        assertTrue(opened.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun decryptedInvalidScalarIsWipedAndNeverSilentlyRegenerated() {
        val decrypted = ByteArray(32) { -1 }
        val storage = MemoryStorage().apply { record = "opaque" }
        assertThrows(CommunityIdentityException::class.java) {
            StoredCommunityIdentity(storage, object : CommunityKeyProtector {
                override fun seal(secret: ByteArray) = error("No creation expected")
                override fun open(record: String) = decrypted
            }).getOrCreateSigner()
        }
        assertTrue(decrypted.all { it == 0.toByte() })
        assertEquals(0, storage.writes)
    }

    @Test
    fun aesGcmUsesFreshIvAndAuthenticatesCiphertextAndIv() {
        val protector = aesProtector()
        val first = protector.seal(knownSecret)
        val second = protector.seal(knownSecret)
        assertNotEquals(first, second)
        assertArrayEquals(knownSecret, protector.open(first))
        assertArrayEquals(knownSecret, protector.open(second))
        val (iv, ciphertext) = CommunityWrappedKey.decode(first)
        val badCipher = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val badIv = iv.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(CommunityIdentityException::class.java) { protector.open(CommunityWrappedKey.encode(iv, badCipher)) }
        assertThrows(CommunityIdentityException::class.java) { protector.open(CommunityWrappedKey.encode(badIv, ciphertext)) }
        assertThrows(CommunityIdentityException::class.java) { aesProtector().open(first) }
    }

    @Test
    fun wrappedRecordRejectsOtherVersionsLengthsWhitespaceAndNonCanonicalHex() {
        val record = CommunityWrappedKey.encode(ByteArray(12), ByteArray(48))
        for (bad in listOf("", "2" + record.drop(1), "$record ", record.dropLast(1), record + "00", "1:" + "a".repeat(24) + ":" + "F".repeat(96))) {
            assertThrows(CommunityIdentityException::class.java) { CommunityWrappedKey.decode(bad) }
        }
        assertThrows(CommunityIdentityException::class.java) { CommunityWrappedKey.encode(ByteArray(11), ByteArray(48)) }
        assertThrows(CommunityIdentityException::class.java) { CommunityWrappedKey.encode(ByteArray(12), ByteArray(47)) }
    }

    private fun aesProtector(): AndroidCommunityKeyProtector {
        val key = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }.generateKey()
        return AndroidCommunityKeyProtector { key }
    }

    private class MemoryStorage : CommunityIdentityStorage {
        var record: String? = null
        var writes = 0
        var failWrites = false
        override fun read() = record
        override fun write(record: String) {
            if (failWrites) throw IllegalStateException("private storage detail")
            writes++
            this.record = record
        }
    }

    private data class Vector(val id: String, val secret: ByteArray, val publicKey: ByteArray, val auxiliary: ByteArray, val message: ByteArray, val signature: ByteArray, val valid: Boolean)

    private fun vectors(): List<Vector> = checkNotNull(javaClass.getResourceAsStream("/community/bip340-test-vectors.csv"))
        .bufferedReader().useLines { lines ->
            lines.drop(1).filter { it.isNotBlank() }.map { line ->
                val values = line.split(',', limit = 8)
                Vector(values[0], values[1].communityHexBytes(), values[2].communityHexBytes(), values[3].communityHexBytes(), values[4].communityHexBytes(), values[5].communityHexBytes(), values[6] == "TRUE")
            }.toList()
        }
}
