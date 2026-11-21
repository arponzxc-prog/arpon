package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.builder.BuildErrorAnalyzer
import com.example.builder.GradleBuildConfigInjector
import com.example.builder.IncrementalGitSync
import com.example.data.security.GitHubSecretCrypto
import com.example.data.security.ReleaseKeystoreManager
import com.example.data.security.SecureTokenManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Step2BuildSigningTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SecureTokenManager.clearReleaseKeystore(context)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val clean = replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun `blake2b matches RFC 7693 vectors and curve25519 matches RFC 7748 vector`() {
        // RFC 7693 Appendix A: BLAKE2b("") 64-byte digest
        val emptyDigest = GitHubSecretCrypto.blake2b(ByteArray(0), 64).toHex()
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            emptyDigest
        )

        // BLAKE2b("abc") 64-byte digest
        val abcDigest = GitHubSecretCrypto.blake2b("abc".toByteArray(Charsets.UTF_8), 64).toHex()
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            abcDigest
        )

        // RFC 7748 Section 6.1: Alice X25519 private key -> public key
        val aliceSk = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToBytes()
        val alicePkExpected = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        val alicePkActual = ByteArray(32)
        GitHubSecretCrypto.cryptoScalarMultBase(alicePkActual, aliceSk)
        assertEquals(alicePkExpected, alicePkActual.toHex())
    }

    @Test
    fun `libsodium sealed box roundtrip encrypts and decrypts cleanly and rejects tampering`() {
        val recipientPk = ByteArray(32)
        val recipientSk = ByteArray(32)
        GitHubSecretCrypto.cryptoBoxKeyPair(recipientPk, recipientSk)

        val secretPayload = "super_secret_store_pass_2026!"
        val pkBase64 = Base64.encodeToString(recipientPk, Base64.NO_WRAP)
        val sealedBase64 = GitHubSecretCrypto.sealSecretBase64(pkBase64, secretPayload)

        val sealedBytes = Base64.decode(sealedBase64, Base64.NO_WRAP)
        assertEquals(32 + 16 + secretPayload.toByteArray().size, sealedBytes.size)

        val decrypted = GitHubSecretCrypto.openSealedBox(sealedBytes, recipientPk, recipientSk)
        assertEquals(secretPayload, String(decrypted, Charsets.UTF_8))

        // Flip a ciphertext bit -> must fail Poly1305 MAC check
        val tampered = sealedBytes.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()
        var threw = false
        try {
            GitHubSecretCrypto.openSealedBox(tampered, recipientPk, recipientSk)
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("Expected MAC verification failure on tampered sealed box", threw)
    }

    @Test
    fun `release keystore generator creates reloadable PKCS12 and importer handles errors and JKS header`() {
        val genRes = ReleaseKeystoreManager.generateReleaseKeystore(
            alias = "upload",
            storePassword = "storePass123",
            keyPassword = "keyPass123",
            commonName = "Arpon Dev",
            organization = "Nexa Studio"
        )
        assertTrue(genRes.isSuccess)
        val generated = genRes.getOrThrow()
        assertEquals("upload", generated.metadata.alias)
        assertEquals("GENERATED", generated.metadata.sourceType)
        assertTrue(generated.metadata.sha256Fingerprint.contains(":"))

        val ksBytes = Base64.decode(generated.keystoreBase64, Base64.NO_WRAP)
        val ks = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(ksBytes).use { ks.load(it, "storePass123".toCharArray()) }
        assertTrue(ks.containsAlias("upload"))
        val priv = ks.getKey("upload", "keyPass123".toCharArray()) as? PrivateKey
        assertNotNull(priv)

        // Importer rejects wrong password
        val wrongPassRes = ReleaseKeystoreManager.importKeystoreBytes(
            rawBytes = ksBytes,
            alias = "upload",
            storePassword = "wrongPassword",
            keyPassword = "keyPass123"
        )
        assertTrue(wrongPassRes.isFailure)

        // Importer succeeds with matching credentials
        val validImp = ReleaseKeystoreManager.importKeystoreBytes(
            rawBytes = ksBytes,
            alias = "upload",
            storePassword = "storePass123",
            keyPassword = "keyPass123"
        )
        assertTrue(validImp.isSuccess)
        assertEquals(generated.metadata.sha256Fingerprint, validImp.getOrThrow().metadata.sha256Fingerprint)

        // JKS magic header fallback (0xFEEDFEED) works cleanly when alias supplied
        val jksMagicBytes = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte(), 0x00, 0x00, 0x00, 0x02)
        val jksRes = ReleaseKeystoreManager.importKeystoreBytes(
            rawBytes = jksMagicBytes,
            alias = "legacyAlias",
            storePassword = "storePass123",
            keyPassword = "keyPass123"
        )
        assertTrue(jksRes.isSuccess)
        assertEquals("legacyAlias", jksRes.getOrThrow().metadata.alias)
    }

    @Test
    fun `secure token manager saves encrypted keystore and redacts tokens and passwords`() {
        val gen = ReleaseKeystoreManager.generateReleaseKeystore(
            alias = "prodKey",
            storePassword = "myStorePassword99",
            keyPassword = "myKeyPassword99",
            commonName = "Prod",
            organization = "Org"
        ).getOrThrow()

        SecureTokenManager.saveReleaseKeystore(
            context = context,
            metadata = gen.metadata,
            keystoreBase64 = gen.keystoreBase64,
            storePassword = "myStorePassword99",
            keyPassword = "myKeyPassword99"
        )

        val meta = SecureTokenManager.getReleaseSigningMetadata(context)
        assertNotNull(meta)
        assertEquals("prodKey", meta?.alias)
        assertNull(meta?.uploadedRepo)

        val secrets = SecureTokenManager.getReleaseSigningSecrets(context)
        assertNotNull(secrets)
        assertEquals(gen.keystoreBase64, secrets?.first)
        assertEquals("myStorePassword99", secrets?.second)
        assertEquals("myKeyPassword99", secrets?.third)

        SecureTokenManager.markKeystoreUploadedToRepo(context, "arpon/nexa-app")
        assertEquals("arpon/nexa-app", SecureTokenManager.getReleaseSigningMetadata(context)?.uploadedRepo)

        val dirtyLog = "token=ghp_1234567890abcdef1234 STORE_PASSWORD=myStorePassword99 key=AIzaSyA1234567890123456789012"
        val cleanLog = SecureTokenManager.redactSecrets(dirtyLog, listOf("myStorePassword99", "myKeyPassword99"))
        assertFalse(cleanLog.contains("ghp_1234567890abcdef1234"))
        assertFalse(cleanLog.contains("myStorePassword99"))
        assertFalse(cleanLog.contains("AIzaSyA1234567890"))

        SecureTokenManager.clearReleaseKeystore(context)
        assertNull(SecureTokenManager.getReleaseSigningMetadata(context))
        assertNull(SecureTokenManager.getReleaseSigningSecrets(context))
    }

    @Test
    fun `gradle build config injector updates version, minify, and signingConfigs idempotently`() {
        val initialGradle = """
            android {
                namespace = "com.test.app"
                compileSdk = 35
                defaultConfig {
                    applicationId = "com.test.app"
                    minSdk = 24
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0"
                }
                buildTypes {
                    release {
                        isMinifyEnabled = false
                    }
                }
            }
        """.trimIndent()

        val firstPass = GradleBuildConfigInjector.injectIntoAppBuildGradle(
            gradleText = initialGradle,
            versionCode = 12,
            versionName = "2.5.0",
            packageName = "com.updated.pkg",
            minifyEnabled = true
        )
        assertTrue(firstPass.contains("versionCode = 12"))
        assertTrue(firstPass.contains("versionName = \"2.5.0\""))
        assertTrue(firstPass.contains("applicationId = \"com.updated.pkg\""))
        assertTrue(firstPass.contains("isMinifyEnabled = (project.findProperty(\"nexaMinify\")"))
        assertTrue(firstPass.contains("isShrinkResources ="))
        assertTrue(firstPass.contains("signingConfigs {"))
        assertTrue(firstPass.contains("signingConfig = signingConfigs.getByName(\"release\")"))

        // Second pass must remain completely idempotent (no duplicate blocks)
        val secondPass = GradleBuildConfigInjector.injectIntoAppBuildGradle(
            gradleText = firstPass,
            versionCode = 12,
            versionName = "2.5.0",
            packageName = "com.updated.pkg",
            minifyEnabled = true
        )
        assertEquals(firstPass, secondPass)
    }

    @Test
    fun `incremental git sync computes exact git blob sha1 and skips unchanged files`() {
        // Git empty blob SHA-1 standard: e69de29bb2d1d6434b8b29ae775ad8c2e48c5391
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", IncrementalGitSync.computeGitBlobSha1(ByteArray(0)))
        // "hello\n" git hash-object: ce013625030ba8dba906f756967f9e9ca394464a
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", IncrementalGitSync.computeGitBlobSha1("hello\n".toByteArray()))

        val sameFile = IncrementalGitSync.SyncCandidate("app/MainActivity.kt", "hello\n".toByteArray(), "100644")
        val changedFile = IncrementalGitSync.SyncCandidate("app/build.gradle.kts", "versionCode = 2".toByteArray(), "100644")
        val newFile = IncrementalGitSync.SyncCandidate("app/NewFile.kt", "class NewFile".toByteArray(), "100644")

        val remoteShas = mapOf(
            "app/MainActivity.kt" to "ce013625030ba8dba906f756967f9e9ca394464a",
            "app/build.gradle.kts" to "0000000000000000000000000000000000000000"
        )

        val plan = IncrementalGitSync.computeDiffPlan(listOf(sameFile, changedFile, newFile), remoteShas)
        assertEquals(listOf("app/MainActivity.kt"), plan.skippedUnchangedPaths)
        assertEquals(listOf("app/build.gradle.kts", "app/NewFile.kt"), plan.toUpload.map { it.relativePath })
    }

    @Test
    fun `build error analyzer maps common gradle and signing failures to Bengali summaries`() {
        val unresolved = BuildErrorAnalyzer.analyzeError(
            rawError = "e: file:///app/src/main/java/com/example/MainActivity.kt:42 Unresolved reference: MyMissingClass"
        )
        assertTrue(unresolved.titleBn.contains("সিম্বল") || unresolved.causeBn.contains("MyMissingClass"))
        assertEquals("MainActivity.kt", unresolved.offendingFileHint)
        assertEquals(42, unresolved.lineNumber)

        val keystoreErr = BuildErrorAnalyzer.analyzeError(
            rawError = "Execution failed for task ':app:packageRelease'. Keystore was tampered with, or password was incorrect"
        )
        assertTrue(keystoreErr.titleBn.contains("Keystore"))
        assertEquals("app/build.gradle.kts", keystoreErr.offendingFileHint)

        val preValErr = BuildErrorAnalyzer.analyzeError(
            rawError = "Pre-Build Validation Failed (AndroidManifest.xml): Malformed XML tag"
        )
        assertTrue(preValErr.titleBn.contains("ভ্যালিডেশন"))
        assertEquals("AndroidManifest.xml", preValErr.offendingFileHint)

        val scopeErr = BuildErrorAnalyzer.analyzeError(
            rawError = "HTTP 403 Forbidden: Token is missing 'workflow' scope."
        )
        assertTrue(scopeErr.titleBn.contains("টোকেন"))
    }
}
