package com.example.data.security

import android.util.Base64
import java.security.SecureRandom

/**
 * Pure Kotlin Libsodium-compatible `crypto_box_seal` implementation
 * (X25519 + HSalsa20 + XSalsa20 + Poly1305 + RFC 7693 Blake2b-192 nonce).
 *
 * Produces byte-for-byte identical ciphertexts to `libsodium`'s `crypto_box_seal`
 * required by GitHub Actions Repository Secrets API:
 * `PUT /repos/{owner}/{repo}/actions/secrets/{secret_name}`.
 */
object GitHubSecretCrypto {

    private const val PK_BYTES = 32
    private const val SK_BYTES = 32
    private const val NONCE_BYTES = 24
    private const val MAC_BYTES = 16

    fun sealSecretBase64(recipientPublicKeyBase64: String, secretUtf8: String): String {
        val pk = try {
            Base64.decode(recipientPublicKeyBase64.trim(), Base64.DEFAULT)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid GitHub repository public key Base64.", e)
        }
        require(pk.size == PK_BYTES) {
            "Expected 32-byte X25519 repository public key, got ${pk.size} bytes."
        }
        val sealed = sealBox(secretUtf8.toByteArray(Charsets.UTF_8), pk)
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun sealBox(message: ByteArray, recipientPk: ByteArray, ephemeralSeed: ByteArray? = null): ByteArray {
        require(recipientPk.size == PK_BYTES) { "recipientPk must be 32 bytes" }
        val epk = ByteArray(PK_BYTES)
        val esk = ByteArray(SK_BYTES)
        cryptoBoxKeyPair(epk, esk, ephemeralSeed)

        val nonce = deriveSealNonce(epk, recipientPk)
        val boxed = cryptoBox(message, nonce, recipientPk, esk)

        val out = ByteArray(PK_BYTES + boxed.size)
        System.arraycopy(epk, 0, out, 0, PK_BYTES)
        System.arraycopy(boxed, 0, out, PK_BYTES, boxed.size)
        esk.fill(0)
        return out
    }

    fun openSealedBox(sealed: ByteArray, recipientPk: ByteArray, recipientSk: ByteArray): ByteArray {
        require(recipientPk.size == PK_BYTES && recipientSk.size == SK_BYTES)
        require(sealed.size >= PK_BYTES + MAC_BYTES) { "Sealed box too short" }
        val epk = sealed.copyOfRange(0, PK_BYTES)
        val boxed = sealed.copyOfRange(PK_BYTES, sealed.size)
        val nonce = deriveSealNonce(epk, recipientPk)
        return cryptoBoxOpen(boxed, nonce, epk, recipientSk)
    }

    fun deriveSealNonce(epk: ByteArray, pk: ByteArray): ByteArray {
        val input = ByteArray(epk.size + pk.size)
        System.arraycopy(epk, 0, input, 0, epk.size)
        System.arraycopy(pk, 0, input, epk.size, pk.size)
        return blake2b(input, NONCE_BYTES)
    }

    fun cryptoBoxKeyPair(pkOut: ByteArray, skOut: ByteArray, seed: ByteArray? = null) {
        if (seed != null) {
            require(seed.size == SK_BYTES) { "Seed must be 32 bytes" }
            System.arraycopy(seed, 0, skOut, 0, SK_BYTES)
        } else {
            SecureRandom().nextBytes(skOut)
        }
        cryptoScalarMultBase(pkOut, skOut)
    }

    fun cryptoScalarMultBase(q: ByteArray, n: ByteArray) {
        val base = ByteArray(32)
        base[0] = 9
        cryptoScalarMult(q, n, base)
    }

    fun cryptoScalarMult(q: ByteArray, n: ByteArray, p: ByteArray) {
        val z = ByteArray(32)
        val x = LongArray(80)
        var r: Int
        var i: Int
        val a = LongArray(16)
        val b = LongArray(16)
        val c = LongArray(16)
        val d = LongArray(16)
        val e = LongArray(16)
        val f = LongArray(16)

        for (idx in 0..30) z[idx] = n[idx]
        z[31] = ((n[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()

        unpack25519(x, p)
        for (idx in 0..15) {
            b[idx] = x[idx]
            d[idx] = 0
            a[idx] = 0
            c[idx] = 0
        }
        a[0] = 1
        d[0] = 1

        var bitIdx = 254
        while (bitIdx >= 0) {
            r = (z[bitIdx ushr 3].toInt() ushr (bitIdx and 7)) and 1
            sel25519(a, b, r)
            sel25519(c, d, r)
            A(e, a, c)
            Z(a, a, c)
            A(c, b, d)
            Z(b, b, d)
            S(d, e)
            S(f, a)
            M(a, c, a)
            M(c, b, e)
            A(e, a, c)
            Z(a, a, c)
            S(b, a)
            Z(c, d, f)
            M(a, c, _121665)
            A(a, a, d)
            M(c, c, a)
            M(a, d, f)
            M(d, b, x)
            S(b, e)
            sel25519(a, b, r)
            sel25519(c, d, r)
            bitIdx--
        }

        for (idx in 0..15) {
            x[idx + 16] = a[idx]
            x[idx + 32] = c[idx]
            x[idx + 48] = b[idx]
            x[idx + 64] = d[idx]
        }
        val invC = LongArray(16)
        inv25519(invC, c)
        val mul = LongArray(16)
        M(mul, a, invC)
        pack25519(q, mul)
    }

    // --- NaCl Box (XSalsa20 + Poly1305) ---

    private fun cryptoBox(message: ByteArray, nonce: ByteArray, pk: ByteArray, sk: ByteArray): ByteArray {
        val shared = ByteArray(32)
        cryptoScalarMult(shared, sk, pk)
        val key = hsalsa20(ByteArray(16), shared)

        val padded = ByteArray(32 + message.size)
        System.arraycopy(message, 0, padded, 32, message.size)

        val stream = xsalsa20Xor(padded, nonce, key)
        // Compute Poly1305 MAC over ciphertext excluding first 32 bytes (first 32 bytes of keystream = Poly1305 key)
        val polyKey = xsalsa20KeystreamBlock0(nonce, key)
        val ciphertextOnly = stream.copyOfRange(32, stream.size)
        val mac = poly1305(ciphertextOnly, polyKey)

        val out = ByteArray(MAC_BYTES + ciphertextOnly.size)
        System.arraycopy(mac, 0, out, 0, MAC_BYTES)
        System.arraycopy(ciphertextOnly, 0, out, MAC_BYTES, ciphertextOnly.size)
        return out
    }

    private fun cryptoBoxOpen(boxed: ByteArray, nonce: ByteArray, pk: ByteArray, sk: ByteArray): ByteArray {
        require(boxed.size >= MAC_BYTES) { "Ciphertext too short" }
        val shared = ByteArray(32)
        cryptoScalarMult(shared, sk, pk)
        val key = hsalsa20(ByteArray(16), shared)

        val expectedMac = boxed.copyOfRange(0, MAC_BYTES)
        val ciphertextOnly = boxed.copyOfRange(MAC_BYTES, boxed.size)
        val polyKey = xsalsa20KeystreamBlock0(nonce, key)
        val actualMac = poly1305(ciphertextOnly, polyKey)

        var diff = 0
        for (i in 0 until MAC_BYTES) {
            diff = diff or ((expectedMac[i].toInt() xor actualMac[i].toInt()) and 0xff)
        }
        if (diff != 0) throw IllegalStateException("Decryption failed: MAC mismatch")

        val padded = ByteArray(32 + ciphertextOnly.size)
        System.arraycopy(ciphertextOnly, 0, padded, 32, ciphertextOnly.size)
        val plainPadded = xsalsa20Xor(padded, nonce, key)
        return plainPadded.copyOfRange(32, plainPadded.size)
    }

    private fun xsalsa20KeystreamBlock0(nonce24: ByteArray, key32: ByteArray): ByteArray {
        val subkey = hsalsa20(nonce24.copyOfRange(0, 16), key32)
        val blockNonce = ByteArray(16)
        System.arraycopy(nonce24, 16, blockNonce, 0, 8)
        val block = ByteArray(64)
        salsa20Core(block, blockNonce, subkey, SIGMA)
        return block.copyOfRange(0, 32)
    }

    private fun xsalsa20Xor(input: ByteArray, nonce24: ByteArray, key32: ByteArray): ByteArray {
        val subkey = hsalsa20(nonce24.copyOfRange(0, 16), key32)
        val output = ByteArray(input.size)
        val blockNonce = ByteArray(16)
        System.arraycopy(nonce24, 16, blockNonce, 0, 8)
        val keystream = ByteArray(64)
        var offset = 0
        var blockCounter = 0L
        while (offset < input.size) {
            for (i in 0..7) {
                blockNonce[8 + i] = ((blockCounter ushr (8 * i)) and 0xff).toByte()
            }
            salsa20Core(keystream, blockNonce, subkey, SIGMA)
            val chunk = minOf(64, input.size - offset)
            for (i in 0 until chunk) {
                output[offset + i] = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += chunk
            blockCounter++
        }
        return output
    }

    private val SIGMA = "expand 32-byte k".toByteArray(Charsets.US_ASCII)

    private fun hsalsa20(in16: ByteArray, key32: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = ld32(SIGMA, 0)
        x[1] = ld32(key32, 0)
        x[2] = ld32(key32, 4)
        x[3] = ld32(key32, 8)
        x[4] = ld32(key32, 12)
        x[5] = ld32(SIGMA, 4)
        x[6] = ld32(in16, 0)
        x[7] = ld32(in16, 4)
        x[8] = ld32(in16, 8)
        x[9] = ld32(in16, 12)
        x[10] = ld32(SIGMA, 8)
        x[11] = ld32(key32, 16)
        x[12] = ld32(key32, 20)
        x[13] = ld32(key32, 24)
        x[14] = ld32(key32, 28)
        x[15] = ld32(SIGMA, 12)

        for (i in 0 until 10) {
            x[4] = x[4] xor rotL(x[0] + x[12], 7)
            x[8] = x[8] xor rotL(x[4] + x[0], 9)
            x[12] = x[12] xor rotL(x[8] + x[4], 13)
            x[0] = x[0] xor rotL(x[12] + x[8], 18)

            x[9] = x[9] xor rotL(x[5] + x[1], 7)
            x[13] = x[13] xor rotL(x[9] + x[5], 9)
            x[1] = x[1] xor rotL(x[13] + x[9], 13)
            x[5] = x[5] xor rotL(x[1] + x[13], 18)

            x[14] = x[14] xor rotL(x[10] + x[6], 7)
            x[2] = x[2] xor rotL(x[14] + x[10], 9)
            x[6] = x[6] xor rotL(x[2] + x[14], 13)
            x[10] = x[10] xor rotL(x[6] + x[2], 18)

            x[3] = x[3] xor rotL(x[15] + x[11], 7)
            x[7] = x[7] xor rotL(x[3] + x[15], 9)
            x[11] = x[11] xor rotL(x[7] + x[3], 13)
            x[15] = x[15] xor rotL(x[11] + x[7], 18)

            x[1] = x[1] xor rotL(x[0] + x[3], 7)
            x[2] = x[2] xor rotL(x[1] + x[0], 9)
            x[3] = x[3] xor rotL(x[2] + x[1], 13)
            x[0] = x[0] xor rotL(x[3] + x[2], 18)

            x[6] = x[6] xor rotL(x[5] + x[4], 7)
            x[7] = x[7] xor rotL(x[6] + x[5], 9)
            x[4] = x[4] xor rotL(x[7] + x[6], 13)
            x[5] = x[5] xor rotL(x[4] + x[7], 18)

            x[11] = x[11] xor rotL(x[10] + x[9], 7)
            x[8] = x[8] xor rotL(x[11] + x[10], 9)
            x[9] = x[9] xor rotL(x[8] + x[11], 13)
            x[10] = x[10] xor rotL(x[9] + x[8], 18)

            x[12] = x[12] xor rotL(x[15] + x[14], 7)
            x[13] = x[13] xor rotL(x[12] + x[15], 9)
            x[14] = x[14] xor rotL(x[13] + x[12], 13)
            x[15] = x[15] xor rotL(x[14] + x[13], 18)
        }

        val out = ByteArray(32)
        st32(out, 0, x[0])
        st32(out, 4, x[5])
        st32(out, 8, x[10])
        st32(out, 12, x[15])
        st32(out, 16, x[6])
        st32(out, 20, x[7])
        st32(out, 24, x[8])
        st32(out, 28, x[9])
        return out
    }

    private fun salsa20Core(out64: ByteArray, in16: ByteArray, key32: ByteArray, c16: ByteArray) {
        val j = IntArray(16)
        j[0] = ld32(c16, 0)
        j[1] = ld32(key32, 0)
        j[2] = ld32(key32, 4)
        j[3] = ld32(key32, 8)
        j[4] = ld32(key32, 12)
        j[5] = ld32(c16, 4)
        j[6] = ld32(in16, 0)
        j[7] = ld32(in16, 4)
        j[8] = ld32(in16, 8)
        j[9] = ld32(in16, 12)
        j[10] = ld32(c16, 8)
        j[11] = ld32(key32, 16)
        j[12] = ld32(key32, 20)
        j[13] = ld32(key32, 24)
        j[14] = ld32(key32, 28)
        j[15] = ld32(c16, 12)

        val x = j.copyOf()
        for (i in 0 until 10) {
            x[4] = x[4] xor rotL(x[0] + x[12], 7)
            x[8] = x[8] xor rotL(x[4] + x[0], 9)
            x[12] = x[12] xor rotL(x[8] + x[4], 13)
            x[0] = x[0] xor rotL(x[12] + x[8], 18)

            x[9] = x[9] xor rotL(x[5] + x[1], 7)
            x[13] = x[13] xor rotL(x[9] + x[5], 9)
            x[1] = x[1] xor rotL(x[13] + x[9], 13)
            x[5] = x[5] xor rotL(x[1] + x[13], 18)

            x[14] = x[14] xor rotL(x[10] + x[6], 7)
            x[2] = x[2] xor rotL(x[14] + x[10], 9)
            x[6] = x[6] xor rotL(x[2] + x[14], 13)
            x[10] = x[10] xor rotL(x[6] + x[2], 18)

            x[3] = x[3] xor rotL(x[15] + x[11], 7)
            x[7] = x[7] xor rotL(x[3] + x[15], 9)
            x[11] = x[11] xor rotL(x[7] + x[3], 13)
            x[15] = x[15] xor rotL(x[11] + x[7], 18)

            x[1] = x[1] xor rotL(x[0] + x[3], 7)
            x[2] = x[2] xor rotL(x[1] + x[0], 9)
            x[3] = x[3] xor rotL(x[2] + x[1], 13)
            x[0] = x[0] xor rotL(x[3] + x[2], 18)

            x[6] = x[6] xor rotL(x[5] + x[4], 7)
            x[7] = x[7] xor rotL(x[6] + x[5], 9)
            x[4] = x[4] xor rotL(x[7] + x[6], 13)
            x[5] = x[5] xor rotL(x[4] + x[7], 18)

            x[11] = x[11] xor rotL(x[10] + x[9], 7)
            x[8] = x[8] xor rotL(x[11] + x[10], 9)
            x[9] = x[9] xor rotL(x[8] + x[11], 13)
            x[10] = x[10] xor rotL(x[9] + x[8], 18)

            x[12] = x[12] xor rotL(x[15] + x[14], 7)
            x[13] = x[13] xor rotL(x[12] + x[15], 9)
            x[14] = x[14] xor rotL(x[13] + x[12], 13)
            x[15] = x[15] xor rotL(x[14] + x[13], 18)
        }

        for (i in 0 until 16) {
            st32(out64, i * 4, x[i] + j[i])
        }
    }

    private fun poly1305(msg: ByteArray, key32: ByteArray): ByteArray {
        val r = IntArray(17)
        val h = IntArray(17)
        val c = IntArray(17)
        val g = IntArray(17)
        val hr = IntArray(17)

        r[0] = key32[0].toInt() and 0xff
        r[1] = key32[1].toInt() and 0xff
        r[2] = key32[2].toInt() and 0xff
        r[3] = key32[3].toInt() and 0x0f
        r[4] = key32[4].toInt() and 0xfc
        r[5] = key32[5].toInt() and 0xff
        r[6] = key32[6].toInt() and 0xff
        r[7] = key32[7].toInt() and 0x0f
        r[8] = key32[8].toInt() and 0xfc
        r[9] = key32[9].toInt() and 0xff
        r[10] = key32[10].toInt() and 0xff
        r[11] = key32[11].toInt() and 0x0f
        r[12] = key32[12].toInt() and 0xfc
        r[13] = key32[13].toInt() and 0xff
        r[14] = key32[14].toInt() and 0xff
        r[15] = key32[15].toInt() and 0x0f
        r[16] = 0

        var offset = 0
        var remaining = msg.size
        while (remaining > 0) {
            c.fill(0)
            val blockLen = minOf(16, remaining)
            for (i in 0 until blockLen) {
                c[i] = msg[offset + i].toInt() and 0xff
            }
            c[blockLen] = 1
            offset += blockLen
            remaining -= blockLen

            var u = 0
            for (i in 0..16) {
                u += h[i] + c[i]
                h[i] = u and 0xff
                u = u ushr 8
            }

            for (i in 0..16) {
                var sum = 0
                for (j in 0..i) sum += h[j] * r[i - j]
                for (j in (i + 1)..16) sum += 320 * h[j] * r[i + 17 - j]
                hr[i] = sum
            }
            for (i in 0..16) h[i] = hr[i]
            squeeze(h)
        }

        freeze(h)
        for (i in 0..15) c[i] = key32[16 + i].toInt() and 0xff
        c[16] = 0
        var u = 0
        for (i in 0..16) {
            u += h[i] + c[i]
            h[i] = u and 0xff
            u = u ushr 8
        }

        val out = ByteArray(16)
        for (i in 0..15) out[i] = h[i].toByte()
        return out
    }

    private fun squeeze(h: IntArray) {
        var u = 0
        for (j in 0..15) {
            u += h[j]
            h[j] = u and 255
            u = u ushr 8
        }
        u += h[16]
        h[16] = u and 3
        u = 5 * (u ushr 2)
        for (j in 0..15) {
            u += h[j]
            h[j] = u and 255
            u = u ushr 8
        }
        u += h[16]
        h[16] = u
    }

    private val MINUSP = intArrayOf(5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 252)

    private fun freeze(h: IntArray) {
        val horig = h.copyOf()
        var u = 0
        for (j in 0..16) {
            u += h[j] + MINUSP[j]
            h[j] = u and 255
            u = u ushr 8
        }
        val negative = -((h[16] ushr 7) and 1)
        for (j in 0..16) {
            h[j] = h[j] xor (negative and (horig[j] xor h[j]))
        }
    }

    // --- Curve25519 Field Arithmetic (TweetNaCl) ---

    private val _121665 = longArrayOf(0xdb41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private fun unpack25519(o: LongArray, n: ByteArray) {
        for (i in 0..15) {
            o[i] = ((n[2 * i].toInt() and 0xff) + ((n[2 * i + 1].toInt() and 0xff) shl 8)).toLong()
        }
        o[15] = o[15] and 0x7fffL
    }

    private fun pack25519(o: ByteArray, n: LongArray) {
        val t = n.copyOf()
        val m = LongArray(16)
        car25519(t)
        car25519(t)
        car25519(t)
        for (j in 0..1) {
            m[0] = t[0] - 0xffedL
            for (i in 1..14) {
                m[i] = t[i] - 0xffffL - ((m[i - 1] ushr 16) and 1L)
                m[i - 1] = m[i - 1] and 0xffffL
            }
            m[15] = t[15] - 0x7fffL - ((m[14] ushr 16) and 1L)
            val b = ((m[15] ushr 16) and 1L)
            m[14] = m[14] and 0xffffL
            sel25519(t, m, (1L - b).toInt())
        }
        for (i in 0..15) {
            o[2 * i] = (t[i] and 0xffL).toByte()
            o[2 * i + 1] = ((t[i] ushr 8) and 0xffL).toByte()
        }
    }

    private fun car25519(o: LongArray) {
        for (i in 0..15) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            o[(i + 1) * (if (i < 15) 1 else 0)] += c - 1L + (if (i == 15) 37L * (c - 1L) else 0L)
            o[i] -= c shl 16
        }
    }

    private fun sel25519(p: LongArray, q: LongArray, b: Int) {
        val c = ((b - 1).inv()).toLong()
        for (i in 0..15) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun A(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0..15) o[i] = a[i] + b[i]
    }

    private fun Z(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0..15) o[i] = a[i] - b[i]
    }

    private fun M(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0..15) {
            for (j in 0..15) {
                t[i + j] += a[i] * b[j]
            }
        }
        for (i in 0..14) t[i] += 38L * t[i + 16]
        for (i in 0..15) o[i] = t[i]
        car25519(o)
        car25519(o)
    }

    private fun S(o: LongArray, a: LongArray) = M(o, a, a)

    private fun inv25519(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            S(c, c)
            if (a != 2 && a != 4) M(c, c, i)
        }
        for (a in 0..15) o[a] = c[a]
    }

    // --- RFC 7693 Blake2b ---

    private val BLAKE2B_IV = longArrayOf(
        0x6a09e667f3bcc908L, -0x4498517a7b3558c5L, 0x3c6ef372fe94f82bL, -0x5ab00ac5a0e2c90fL,
        0x510e527fade682d1L, -0x64fa9773d4c193e1L, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
    )

    private val BLAKE2B_SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3)
    )

    fun blake2b(input: ByteArray, outLen: Int = 64): ByteArray {
        require(outLen in 1..64)
        val h = BLAKE2B_IV.copyOf()
        h[0] = h[0] xor (0x01010000L or outLen.toLong())

        var pos = 0
        var remaining = input.size
        var bytesCompressed = 0L

        if (remaining > 128) {
            while (remaining > 128) {
                bytesCompressed += 128L
                val block = input.copyOfRange(pos, pos + 128)
                blake2bCompress(h, block, bytesCompressed, isLast = false)
                pos += 128
                remaining -= 128
            }
        }

        val finalBlock = ByteArray(128)
        System.arraycopy(input, pos, finalBlock, 0, remaining)
        bytesCompressed += remaining.toLong()
        blake2bCompress(h, finalBlock, bytesCompressed, isLast = true)

        val out = ByteArray(outLen)
        for (i in 0 until outLen) {
            out[i] = ((h[i ushr 3] ushr (8 * (i and 7))) and 0xffL).toByte()
        }
        return out
    }

    private fun blake2bCompress(h: LongArray, block: ByteArray, t: Long, isLast: Boolean) {
        val m = LongArray(16)
        for (i in 0..15) {
            m[i] = ld64(block, i * 8)
        }
        val v = LongArray(16)
        for (i in 0..7) {
            v[i] = h[i]
            v[i + 8] = BLAKE2B_IV[i]
        }
        v[12] = v[12] xor t
        if (isLast) v[14] = v[14].inv()

        fun g(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
            v[a] = v[a] + v[b] + x
            v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
            v[c] = v[c] + v[d]
            v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
            v[a] = v[a] + v[b] + y
            v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
            v[c] = v[c] + v[d]
            v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
        }

        for (r in 0 until 12) {
            val s = BLAKE2B_SIGMA[r]
            g(0, 4, 8, 12, m[s[0]], m[s[1]])
            g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]])
            g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]])
            g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]])
            g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }

        for (i in 0..7) {
            h[i] = h[i] xor v[i] xor v[i + 8]
        }
    }

    private fun ld32(x: ByteArray, off: Int): Int {
        return (x[off].toInt() and 0xff) or
            ((x[off + 1].toInt() and 0xff) shl 8) or
            ((x[off + 2].toInt() and 0xff) shl 16) or
            ((x[off + 3].toInt() and 0xff) shl 24)
    }

    private fun st32(x: ByteArray, off: Int, u: Int) {
        x[off] = (u and 0xff).toByte()
        x[off + 1] = ((u ushr 8) and 0xff).toByte()
        x[off + 2] = ((u ushr 16) and 0xff).toByte()
        x[off + 3] = ((u ushr 24) and 0xff).toByte()
    }

    private fun ld64(x: ByteArray, off: Int): Long {
        var u = 0L
        for (i in 7 downTo 0) {
            u = (u shl 8) or (x[off + i].toLong() and 0xffL)
        }
        return u
    }

    private fun rotL(x: Int, c: Int): Int = (x shl c) or (x ushr (32 - c))
}
