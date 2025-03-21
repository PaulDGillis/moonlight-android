package com.limelight.nvstream.http

import com.limelight.LimeLog
import org.bouncycastle.crypto.BlockCipher
import org.bouncycastle.crypto.engines.AESLightEngine
import org.bouncycastle.crypto.params.KeyParameter
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.security.InvalidKeyException
import java.security.Key
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.SignatureException
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

class PairingManagerKtx(private val http: NvHTTPKtx, cryptoProvider: LimelightCryptoProvider) {
    private val pk: PrivateKey = cryptoProvider.getClientPrivateKey()
    private val cert: X509Certificate = cryptoProvider.getClientCertificate()
    private val pemCertBytes: ByteArray = cryptoProvider.getPemEncodedClientCertificate()

    var pairedCert: X509Certificate? = null
        private set

    enum class PairState {
        NOT_PAIRED,
        PAIRED,
        PIN_WRONG,
        FAILED,
        ALREADY_IN_PROGRESS
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun extractPlainCert(text: String?): X509Certificate? {
        // Plaincert may be null if another client is already trying to pair
        val certText = NvHTTPKtx.getXmlString(text, "plaincert", false)
        if (certText != null) {
            val certBytes: ByteArray = hexToBytes(certText)

            try {
                val cf = CertificateFactory.getInstance("X.509")
                return cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate?
            } catch (e: CertificateException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        } else {
            return null
        }
    }

    private fun generateRandomBytes(length: Int): ByteArray {
        val rand = ByteArray(length)
        SecureRandom().nextBytes(rand)
        return rand
    }

    @Throws(IOException::class, XmlPullParserException::class)
    fun pair(serverInfo: String?, pin: String, passphrase: String?): PairState {
        val hashAlgo: PairingHashAlgorithm?

        val serverMajorVersion = http.getServerMajorVersion(serverInfo)
        LimeLog.info("Pairing with server generation: $serverMajorVersion")
        hashAlgo = if (serverMajorVersion >= 7) {
            // Gen 7+ uses SHA-256 hashing
            Sha256PairingHash()
        } else {
            // Prior to Gen 7, SHA-1 is used
            Sha1PairingHash()
        }


        // Generate a salt for hashing the PIN
        val salt = generateRandomBytes(16)

        // Combine the salt and pin, then create an AES key from them
        val aesKey: ByteArray = generateAesKey(hashAlgo, saltPin(salt, pin))

        val saltStr: String = bytesToHex(salt)

        var pairingArguments = "phrase=getservercert&salt=" +
                saltStr + "&clientcert=" + bytesToHex(pemCertBytes)

        if (passphrase != null) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val plainText = pin + saltStr + passphrase
                val hash = digest.digest(plainText.toByteArray())

                val hexString = StringBuilder()
                for (b in hash) {
                    hexString.append(String.format("%02X", b))
                }

                pairingArguments += "&otpauth=$hexString"
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException(e)
            }
        }


        // Send the salt and get the server cert. This doesn't have a read timeout
        // because the user must enter the PIN before the server responds
        val getCert = http.executePairingCommand(pairingArguments, false)
        if (NvHTTPKtx.getXmlString(getCert, "paired", true) != "1") {
            return PairState.FAILED
        }

        // Save this cert for retrieval later
        this.pairedCert = extractPlainCert(getCert)
        if (this.pairedCert == null) {
            // Attempting to pair while another device is pairing will cause GFE
            // to give an empty cert in the response.
            http.unpair()
            return PairState.ALREADY_IN_PROGRESS
        }

        // Require this cert for TLS to this host
        http.setServerCert(this.pairedCert)


        // Generate a random challenge and encrypt it with our AES key
        val randomChallenge = generateRandomBytes(16)
        val encryptedChallenge: ByteArray = encryptAes(randomChallenge, aesKey)


        // Send the encrypted challenge to the server
        val challengeResp =
            http.executePairingCommand("clientchallenge=" + bytesToHex(encryptedChallenge), true)
        if (NvHTTPKtx.getXmlString(challengeResp, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }


        // Decode the server's response and subsequent challenge
        val encServerChallengeResponse: ByteArray =
            hexToBytes(NvHTTPKtx.getXmlString(challengeResp, "challengeresponse", true)!!)
        val decServerChallengeResponse: ByteArray = decryptAes(encServerChallengeResponse, aesKey)

        val serverResponse = decServerChallengeResponse.copyOfRange(0, hashAlgo.hashLength)
        val serverChallenge =
            decServerChallengeResponse.copyOfRange(hashAlgo.hashLength, hashAlgo.hashLength + 16)


        // Using another 16 bytes secret, compute a challenge response hash using the secret, our cert sig, and the challenge
        val clientSecret = generateRandomBytes(16)
        val challengeRespHash = hashAlgo.hashData(
            concatBytes(
                concatBytes(serverChallenge, cert.signature),
                clientSecret
            )
        )
        val challengeRespEncrypted: ByteArray = encryptAes(challengeRespHash, aesKey)
        val secretResp = http.executePairingCommand(
            "serverchallengeresp=" + bytesToHex(challengeRespEncrypted),
            true
        )
        if (NvHTTPKtx.getXmlString(secretResp, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }


        // Get the server's signed secret
        val serverSecretResp: ByteArray =
            hexToBytes(NvHTTPKtx.getXmlString(secretResp, "pairingsecret", true)!!)
        val serverSecret = serverSecretResp.copyOfRange(0, 16)
        val serverSignature = serverSecretResp.copyOfRange(16, serverSecretResp.size)

        // Ensure the authenticity of the data
        if (!Companion.verifySignature(
                serverSecret, serverSignature,
                this.pairedCert!!
            )
        ) {
            // Cancel the pairing process
            http.unpair()


            // Looks like a MITM
            return PairState.FAILED
        }


        // Ensure the server challenge matched what we expected (aka the PIN was correct)
        val serverChallengeRespHash = hashAlgo.hashData(
            concatBytes(
                concatBytes(randomChallenge, pairedCert!!.signature),
                serverSecret
            )
        )
        if (!serverChallengeRespHash.contentEquals(serverResponse)) {
            // Cancel the pairing process
            http.unpair()


            // Probably got the wrong PIN
            return PairState.PIN_WRONG
        }


        // Send the server our signed secret
        val clientPairingSecret: ByteArray =
            Companion.concatBytes(clientSecret, signData(clientSecret, pk)!!)
        val clientSecretResp = http.executePairingCommand(
            "clientpairingsecret=" + bytesToHex(clientPairingSecret),
            true
        )
        if (NvHTTPKtx.getXmlString(clientSecretResp, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }


        // Do the initial challenge (seems necessary for us to show as paired)
        val pairChallenge = http.executePairingChallenge()
        if (NvHTTPKtx.getXmlString(pairChallenge, "paired", true) != "1") {
            http.unpair()
            return PairState.FAILED
        }

        return PairState.PAIRED
    }

    private interface PairingHashAlgorithm {
        val hashLength: Int
        fun hashData(data: ByteArray): ByteArray
    }

    private class Sha1PairingHash() : PairingHashAlgorithm {
        override val hashLength: Int
            get() = 20

        override fun hashData(data: ByteArray): ByteArray {
            try {
                val md = MessageDigest.getInstance("SHA-1")
                return md.digest(data)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

    private class Sha256PairingHash : PairingHashAlgorithm {
        override val hashLength: Int
            get() = 32

        override fun hashData(data: ByteArray): ByteArray {
            try {
                val md = MessageDigest.getInstance("SHA-256")
                return md.digest(data)
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

    companion object {
        private val hexArray = "0123456789ABCDEF".toCharArray()
        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }

        private fun hexToBytes(s: String): ByteArray {
            val len = s.length
            require(len % 2 == 0) { "Illegal string length: $len" }

            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((((s[i].digitToIntOrNull(16) ?: (-1 shl 4)) + (s[i + 1].digitToIntOrNull(16) ?: -1)) )).toByte()
                i += 2
            }
            return data
        }

        @Throws(UnsupportedEncodingException::class)
        private fun saltPin(salt: ByteArray, pin: String): ByteArray {
            val saltedPin = ByteArray(salt.size + pin.length)
            System.arraycopy(salt, 0, saltedPin, 0, salt.size)
            System.arraycopy(pin.toByteArray(charset("UTF-8")), 0, saltedPin, salt.size, pin.length)
            return saltedPin
        }

        @Throws(NoSuchAlgorithmException::class)
        private fun getSha256SignatureInstanceForKey(key: Key): Signature {
            when (key.algorithm) {
                "RSA" -> return Signature.getInstance("SHA256withRSA")
                "EC" -> return Signature.getInstance("SHA256withECDSA")
                else -> throw NoSuchAlgorithmException("Unhandled key algorithm: " + key.algorithm)
            }
        }

        private fun verifySignature(
            data: ByteArray?,
            signature: ByteArray?,
            cert: Certificate
        ): Boolean {
            try {
                val sig: Signature = getSha256SignatureInstanceForKey(cert.publicKey)
                sig.initVerify(cert.publicKey)
                sig.update(data)
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

        private fun signData(data: ByteArray?, key: PrivateKey): ByteArray? {
            try {
                val sig: Signature = getSha256SignatureInstanceForKey(key)
                sig.initSign(key)
                sig.update(data)
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

        private fun performBlockCipher(blockCipher: BlockCipher, input: ByteArray): ByteArray {
            val blockSize = blockCipher.blockSize
            val blockRoundedSize = (input.size + (blockSize - 1)) and (blockSize - 1).inv()

            val blockRoundedInputData = input.copyOf(blockRoundedSize)
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

        private fun decryptAes(encryptedData: ByteArray, aesKey: ByteArray): ByteArray {
            val aesEngine: BlockCipher = AESLightEngine()
            aesEngine.init(false, KeyParameter(aesKey))
            return performBlockCipher(aesEngine, encryptedData)
        }

        private fun encryptAes(plaintextData: ByteArray, aesKey: ByteArray): ByteArray {
            val aesEngine: BlockCipher = AESLightEngine()
            aesEngine.init(true, KeyParameter(aesKey))
            return performBlockCipher(aesEngine, plaintextData)
        }

        private fun generateAesKey(hashAlgo: PairingHashAlgorithm, keyData: ByteArray): ByteArray {
            return hashAlgo.hashData(keyData).copyOf(16)
        }

        private fun concatBytes(a: ByteArray, b: ByteArray): ByteArray {
            val c = ByteArray(a.size + b.size)
            System.arraycopy(a, 0, c, 0, a.size)
            System.arraycopy(b, 0, c, a.size, b.size)
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
