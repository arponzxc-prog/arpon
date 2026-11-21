package com.example.builder

import java.security.MessageDigest

/**
 * Pure Kotlin incremental Git hash diff planner.
 * Computes exact Git Blob SHA-1 (`SHA-1("blob <size>\0" + bytes)`) locally
 * so unchanged files skip `createBlob` API calls cleanly.
 */
object IncrementalGitSync {

    data class SyncCandidate(
        val relativePath: String,
        val bytes: ByteArray,
        val mode: String
    ) {
        val gitBlobSha1: String by lazy { computeGitBlobSha1(bytes) }
    }

    data class SyncPlan(
        val toUpload: List<SyncCandidate>,
        val skippedUnchangedPaths: List<String>
    )

    fun computeGitBlobSha1(bytes: ByteArray): String {
        val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(header)
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeDiffPlan(
        candidates: List<SyncCandidate>,
        remoteBlobShas: Map<String, String>
    ): SyncPlan {
        val toUpload = mutableListOf<SyncCandidate>()
        val skipped = mutableListOf<String>()
        for (candidate in candidates) {
            val remoteSha = remoteBlobShas[candidate.relativePath]
            if (remoteSha != null && remoteSha.equals(candidate.gitBlobSha1, ignoreCase = true)) {
                skipped.add(candidate.relativePath)
            } else {
                toUpload.add(candidate)
            }
        }
        return SyncPlan(toUpload = toUpload, skippedUnchangedPaths = skipped)
    }
}
