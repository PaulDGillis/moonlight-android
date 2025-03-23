package com.limelight.nvstream.http

import com.limelight.LimeLog
import com.limelight.nvstream.http.model.ServerInfo
import org.bouncycastle.crypto.BlockCipher
import org.bouncycastle.crypto.engines.AESLightEngine
import org.bouncycastle.crypto.params.KeyParameter
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.InvalidKeyException
import java.security.Key
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.SignatureException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

class PairingManager(
    private val pairingRepo: PairingRepo
) {
    private val pk: PrivateKey = pairingRepo.ktorClient.cryptoProvider.clientPrivateKey
    private val cert: X509Certificate = pairingRepo.ktorClient.cryptoProvider.clientCertificate
    private val pemCertBytes: ByteArray = pairingRepo.ktorClient.cryptoProvider.pemEncodedClientCertificate

    enum class PairState { NOT_PAIRED, PAIRED, PIN_WRONG, FAILED, ALREADY_IN_PROGRESS }

    private fun generateRandom16ByteArray(): ByteArray {
        val rand = ByteArray(16)
        SecureRandom().nextBytes(rand)
        return rand
    }

    @Throws(IOException::class, XmlPullParserException::class)
    suspend fun pair(serverInfo: ServerInfo, pin: String, passphrase: String?): PairState {
        val hashAlgo: PairingHashAlgorithm?

        LimeLog.info("Pairing with server generation: ${serverInfo.serverMajorVersion}")
        hashAlgo = if (serverInfo.serverMajorVersion >= 7) {
            // Gen 7+ uses SHA-256 hashing
            PairingHashAlgorithm.Sha256
        } else {
            // Prior to Gen 7, SHA-1 is used
            PairingHashAlgorithm.Sha1
        }


        // Generate a salt for hashing the PIN
        val salt = generateRandom16ByteArray()

        // Combine the salt and pin, then create an AES key from them
        val saltedPin = ByteArray(salt.size + pin.length)
        System.arraycopy(salt, 0, saltedPin, 0, salt.size)
        System.arraycopy(pin.toByteArray(charset("UTF-8")), 0, saltedPin, salt.size, pin.length)

        val aesKey: ByteArray = hashAlgo.hashData(saltedPin).copyOf(16)

        val saltStr: String = salt.toHexString()
        val clientCert = pemCertBytes.toHexString()

        val passphraseHexString = if (passphrase != null) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val plainText = pin + saltStr + passphrase
                val hash = digest.digest(plainText.toByteArray())

                hash.joinToString(separator = "") {
                    String.format("%02X", it)
                }
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            }
        } else null

        // Send the salt and get the server cert. This doesn't have a read timeout
        // because the user must enter the PIN before the server responds
        val response = pairingRepo.getServerCert(saltStr, clientCert, passphraseHexString)

        if (response.pairedStatus != 1) {
            return PairState.FAILED
        } else if (response.plainCert == null) {
            // Plaincert may be null if another client is already trying to pair
            // Attempting to pair while another device is pairing will cause GFE
            // to give an empty cert in the response.
            pairingRepo.unpair()
            return PairState.ALREADY_IN_PROGRESS
        }

        val certBytes: ByteArray = response.plainCert.hexToByteArray()

        val pairedCert = try {
            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        } catch (e: CertificateException) {
            e.printStackTrace()
            throw RuntimeException(e)
        }

        // Save this cert for retrieval later
        // Require this cert for TLS to this host
        pairingRepo.ktorClient.updateClientProtocol(pairedCert)

        // Generate a random challenge and encrypt it with our AES key
        val randomChallenge = generateRandom16ByteArray()
        val encryptedChallenge: ByteArray = randomChallenge.encryptToAes(aesKey)

        // Send the encrypted challenge to the server
        val challengeResp =
            pairingRepo.sendClientChallenge(encryptedChallenge.toHexString())
        if (challengeResp.pairedStatus != 1) {
            pairingRepo.unpair()
            return PairState.FAILED
        }

        // Decode the server's response and subsequent challenge
        val encServerChallengeResponse: ByteArray =
            challengeResp.challengeResponse!!.hexToByteArray()
        val decServerChallengeResponse: ByteArray = encServerChallengeResponse.decryptFromAes(aesKey)

        val serverResponse = decServerChallengeResponse.copyOfRange(0, hashAlgo.hashLength)
        val serverChallenge =
            decServerChallengeResponse.copyOfRange(hashAlgo.hashLength, hashAlgo.hashLength + 16)

        // Using another 16 bytes secret, compute a challenge response hash using the secret, our cert sig, and the challenge
        val clientSecret = generateRandom16ByteArray()
        val challengeRespHash = hashAlgo.hashData(
            serverChallenge
                .concatBytes(cert.signature)
                .concatBytes(clientSecret)
        )
        val challengeRespEncrypted: ByteArray = challengeRespHash.encryptToAes(aesKey)

        val secretResp = pairingRepo.sendServerChallengeResponse(challengeRespEncrypted.toHexString())
        if (secretResp.pairedStatus != 1) {
            pairingRepo.unpair()
            return PairState.FAILED
        }

        // Get the server's signed secret
        val serverSecretResp: ByteArray =
            secretResp.pairingSecret!!.hexToByteArray()
        val serverSecret = serverSecretResp.copyOfRange(0, 16)
        val serverSignature = serverSecretResp.copyOfRange(16, serverSecretResp.size)

        // Ensure the authenticity of the data
        if (!serverSecret.verifySignature(serverSignature, pairedCert)) {
            // Cancel the pairing process
            pairingRepo.unpair()


            // Looks like a MITM
            return PairState.FAILED
        }


        // Ensure the server challenge matched what we expected (aka the PIN was correct)
        val serverChallengeRespHash = hashAlgo.hashData(
                randomChallenge
                    .concatBytes(pairedCert.signature)
                    .concatBytes(serverSecret)
        )
        if (!serverChallengeRespHash.contentEquals(serverResponse)) {
            // Cancel the pairing process
            pairingRepo.unpair()


            // Probably got the wrong PIN
            return PairState.PIN_WRONG
        }


        // Send the server our signed secret
        val clientPairingSecret: ByteArray =
            clientSecret.concatBytes(clientSecret.signData(pk)!!)

        val clientSecretResp = pairingRepo.sendClientPairingSecret(clientPairingSecret.toHexString())
        if (clientSecretResp.pairedStatus != 1) {
            pairingRepo.unpair()
            return PairState.FAILED
        }

        // Do the initial challenge (seems necessary for us to show as paired)
        val pairChallenge = pairingRepo.sendPairingChallenge()
        if (pairChallenge.pairedStatus != 1) {
            pairingRepo.unpair()
            return PairState.FAILED
        }

        return PairState.PAIRED
    }

    sealed class PairingHashAlgorithm(
        val hashLength: Int,
        val name: String
    ) {
        data object Sha1 : PairingHashAlgorithm(20, "SHA-1")
        data object Sha256 : PairingHashAlgorithm(32, "SHA-256")

        fun hashData(data: ByteArray): ByteArray {
            try {
                val md = MessageDigest.getInstance(this.name)
                return md.digest(data)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

    companion object {
        private val hexArray = "0123456789ABCDEF".toCharArray()
        private fun ByteArray.toHexString(): String {
            val hexChars = CharArray(this.size * 2)
            for (j in this.indices) {
                val v = this[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }

        private fun String.hexToByteArray(): ByteArray {
            val len = this.length
            require(len % 2 == 0) { "Illegal string length: $len" }

            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((((this[i].digitToIntOrNull(16) ?: (-1 shl 4)) + (this[i + 1].digitToIntOrNull(16) ?: -1)) )).toByte()
                i += 2
            }
            return data
        }

        @Throws(NoSuchAlgorithmException::class)
        private fun getSha256SignatureInstanceForKey(key: Key): Signature {
            return when (key.algorithm) {
                "RSA" -> Signature.getInstance("SHA256withRSA")
                "EC" -> Signature.getInstance("SHA256withECDSA")
                else -> throw NoSuchAlgorithmException("Unhandled key algorithm: " + key.algorithm)
            }
        }

        private fun ByteArray?.verifySignature(
            signature: ByteArray?,
            cert: X509Certificate
        ): Boolean {
            try {
                val sig: Signature = getSha256SignatureInstanceForKey(cert.publicKey)
                sig.initVerify(cert.publicKey)
                sig.update(this)
                return sig.verify(signature)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            } catch (e: SignatureException) {
                e.printStackTrace()
                throw RuntimeException(e)
            } catch (e: InvalidKeyException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        private fun ByteArray?.signData(key: PrivateKey): ByteArray? {
            try {
                val sig: Signature = getSha256SignatureInstanceForKey(key)
                sig.initSign(key)
                sig.update(this)
                return sig.sign()
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            } catch (e: SignatureException) {
                e.printStackTrace()
                throw RuntimeException(e)
            } catch (e: InvalidKeyException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }

        private fun ByteArray.performBlockCipher(blockCipher: BlockCipher): ByteArray {
            val blockSize = blockCipher.blockSize
            val blockRoundedSize = (this.size + (blockSize - 1)) and (blockSize - 1).inv()

            val blockRoundedInputData = this.copyOf(blockRoundedSize)
            val blockRoundedOutputData = ByteArray(blockRoundedSize)

            var offset = 0
            while (offset < blockRoundedSize) {
                blockCipher.processBlock(
                    blockRoundedInputData,
                    offset,
                    blockRoundedOutputData,
                    offset
                )
                offset += blockSize
            }

            return blockRoundedOutputData
        }

        private fun ByteArray.decryptFromAes(aesKey: ByteArray): ByteArray {
            val aesEngine: BlockCipher = AESLightEngine()
            aesEngine.init(false, KeyParameter(aesKey))
            return this.performBlockCipher(aesEngine)
        }

        private fun ByteArray.encryptToAes(aesKey: ByteArray): ByteArray {
            val aesEngine: BlockCipher = AESLightEngine()
            aesEngine.init(true, KeyParameter(aesKey))
            return this.performBlockCipher(aesEngine)
        }

        private fun ByteArray.concatBytes(other: ByteArray): ByteArray {
            val c = ByteArray(this.size + other.size)
            System.arraycopy(this, 0, c, 0, this.size)
            System.arraycopy(other, 0, c, this.size, other.size)
            return c
        }

        fun generatePinString(): String {
            val r = SecureRandom()
            return String.format(
                null as Locale?, "%d%d%d%d",
                r.nextInt(10), r.nextInt(10),
                r.nextInt(10), r.nextInt(10)
            )
        }
    }
}
