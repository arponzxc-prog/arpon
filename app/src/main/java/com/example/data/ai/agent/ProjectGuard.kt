package com.example.data.ai.agent

import com.example.data.ai.NexaAiAgentEngine
import java.io.File

/** Decides which project paths the AI may touch. Everything is confined to the project root. */
object ProjectGuard {
    private val PROTECTED_DIRS = setOf(".git", ".gradle", ".idea")
    private val PROTECTED_NAMES = setOf(
        "local.properties", ".env", "id_rsa", "id_ed25519", "debug.keystore", "my-upload-key.jks", ".netrc"
    )
    private val PROTECTED_EXT = setOf("jks", "keystore", "pem", "p12", "pfx", "key")
    val HIDDEN_DIRS = setOf("build", ".git", ".gradle", ".idea", "node_modules", ".cxx", ".nexa")

    sealed class Resolved {
        data class Ok(val file: File, val relative: String) : Resolved()
        data class Denied(val reason: String) : Resolved()
    }

    fun isSecret(file: File): Boolean {
        val n = file.name.lowercase()
        return n in PROTECTED_NAMES || n.startsWith(".env.") || file.extension.lowercase() in PROTECTED_EXT ||
            n.contains("secret") || n.contains("credential")
    }

    /**
     * @param forWrite writes additionally refuse build outputs. Reads of secrets are always refused so
     * keys can never reach a prompt.
     */
    fun resolve(root: File, rawPath: String, packageName: String, forWrite: Boolean): Resolved {
        val file = NexaAiAgentEngine.resolveSafeProjectFile(root, rawPath, packageName)
            ?: return Resolved.Denied("Invalid or unsafe path '$rawPath' (must be a relative path inside the project, no '..').")
        val rel = file.relativeTo(root.canonicalFile).path.replace('\\', '/')
        val segments = rel.split("/")
        if (segments.any { it in PROTECTED_DIRS }) return Resolved.Denied("Path '$rel' is protected (.git/.gradle/.idea).")
        if (isSecret(file)) return Resolved.Denied("Path '$rel' looks like a secret/keystore file and is off-limits.")
        if (forWrite && segments.first() == "build") return Resolved.Denied("'build/' contains generated output; do not edit it.")
        return Resolved.Ok(file, rel)
    }
}
