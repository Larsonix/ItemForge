package me.itemforge.config

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Atomic file writer with 3-tier fallback for cross-platform reliability.
 *
 * Writes content to a temporary file first, then moves it into place. This
 * ensures the target file is never in a partially-written state — if the
 * server crashes mid-write, only the .tmp file is corrupt, not the config.
 *
 * ## 3-Tier Strategy
 *
 * ```
 * Tier 1: ATOMIC_MOVE        — instant atomic swap (Linux ext4, most POSIX)
 *                               Fails on some filesystems (NFS, old Windows)
 *
 * Tier 2: REPLACE_EXISTING   — non-atomic move (brief window of no file)
 *                               Fails on Windows with cloud sync (OneDrive,
 *                               Dropbox) due to AccessDeniedException when
 *                               the sync agent locks the file
 *
 * Tier 3: Copy + delete      — copies temp over target, then removes temp
 *                               Always works but not atomic (brief window
 *                               where both files exist with same content)
 * ```
 *
 * ## Evidence
 *
 * - BetterMap `ModConfig.java:506-530` — 2-tier (ATOMIC → REPLACE_EXISTING)
 * - Aetherhaven `TownWorldFile.java:42-82` — 3-tier with Windows AccessDeniedException detection
 * - Hytale's own backup system renames old then writes new — NOT atomic
 * - No other mod in the ecosystem has true 3-tier atomic writes
 *
 * ## Usage
 *
 * ```kotlin
 * val writer = AtomicFileWriter()
 * writer.write(configDir.resolve("items.json"), jsonContent)
 * ```
 *
 * Thread-safe: no mutable state. Multiple threads can call [write] concurrently
 * for different paths. Same-path concurrent writes should be serialized by the
 * caller (DebouncedWriter handles this).
 */
class AtomicFileWriter {

    /**
     * Writes [content] to [path] atomically (best-effort).
     *
     * Creates parent directories if they don't exist. Uses UTF-8 encoding.
     *
     * @param path The target file path
     * @param content The string content to write
     * @throws IOException if all three tiers fail
     */
    fun write(path: Path, content: String) {
        // Ensure parent directory exists (first write after install, migration, etc.)
        val parent = path.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }

        val tmpPath = tempPath(path)

        // Write content to temp file
        try {
            Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8).use { writer ->
                writer.write(content)
            }
        } catch (e: IOException) {
            // Temp write failed — clean up and propagate
            deleteSilently(tmpPath)
            throw IOException("Failed to write temp file: ${tmpPath.fileName}", e)
        }

        // Move temp into place (3-tier fallback)
        try {
            moveTierOne(tmpPath, path)
        } catch (e: IOException) {
            // All tiers failed — clean up temp and propagate
            deleteSilently(tmpPath)
            throw IOException("Failed to finalize file after all fallback tiers: ${path.fileName}", e)
        }
    }

    /**
     * Writes raw bytes to [path] atomically (best-effort).
     *
     * Same 3-tier strategy as [write], but for binary content.
     * Used for backup snapshots where encoding is irrelevant.
     *
     * @param path The target file path
     * @param bytes The raw byte content to write
     * @throws IOException if all three tiers fail
     */
    fun writeBytes(path: Path, bytes: ByteArray) {
        val parent = path.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }

        val tmpPath = tempPath(path)

        try {
            Files.write(tmpPath, bytes)
        } catch (e: IOException) {
            deleteSilently(tmpPath)
            throw IOException("Failed to write temp file: ${tmpPath.fileName}", e)
        }

        try {
            moveTierOne(tmpPath, path)
        } catch (e: IOException) {
            deleteSilently(tmpPath)
            throw IOException("Failed to finalize file after all fallback tiers: ${path.fileName}", e)
        }
    }

    // ── 3-Tier Move Strategy ────────────────────────────────────────────

    /**
     * Tier 1: Atomic move. Instant swap — the target file transitions from
     * old content to new content in a single filesystem operation.
     *
     * Fails with [AtomicMoveNotSupportedException] on filesystems that
     * don't support it (NFS, some Windows configs).
     * Fails with [AccessDeniedException] on Windows when cloud sync locks the file.
     */
    private fun moveTierOne(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Expected on some filesystems — fall through to tier 2
            moveTierTwo(source, target)
        } catch (e: IOException) {
            if (isWindowsLockException(e)) {
                // Cloud sync (OneDrive/Dropbox) locking — fall through to tier 2
                moveTierTwo(source, target)
            } else {
                throw e
            }
        }
    }

    /**
     * Tier 2: Non-atomic move with replace. There's a brief moment where the
     * target doesn't exist (between delete-old and create-new internally).
     * Safe for config files — a crash in this window means "no config" which
     * ItemForge handles by loading defaults.
     *
     * Fails with [AccessDeniedException] on Windows when cloud sync locks
     * the target for both move AND delete.
     */
    private fun moveTierTwo(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            if (isWindowsLockException(e)) {
                // Even non-atomic move blocked — last resort: copy over
                moveTierThree(source, target)
            } else {
                throw e
            }
        }
    }

    /**
     * Tier 3: Copy + delete. Copies temp content over the target (overwriting
     * in place), then deletes the temp. Both files exist briefly.
     *
     * This always works because COPY doesn't need to delete the target first —
     * it writes into the existing file handle, which cloud sync allows.
     *
     * Cleanup is best-effort: if temp delete fails, a stale .tmp remains
     * (harmless — overwritten on next write).
     */
    private fun moveTierThree(source: Path, target: Path) {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        deleteSilently(source)
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /**
     * Generates the temp file path for atomic write.
     *
     * Uses `.tmp` suffix in the same directory as the target. This ensures
     * the temp file is on the same filesystem partition — required for
     * [StandardCopyOption.ATOMIC_MOVE] which cannot cross partitions.
     */
    private fun tempPath(target: Path): Path {
        return target.resolveSibling("${target.fileName}.tmp")
    }

    /**
     * Detects Windows file-locking exceptions caused by cloud sync agents
     * (OneDrive, Dropbox, Google Drive) or antivirus scanners.
     *
     * These tools hold file handles open for indexing/sync, blocking
     * rename and delete operations. The copy fallback bypasses this because
     * it writes into the existing file handle rather than replacing it.
     */
    private fun isWindowsLockException(e: IOException): Boolean {
        return e is AccessDeniedException ||
            e.cause is AccessDeniedException
    }

    /**
     * Best-effort file deletion. Swallows all exceptions.
     * Used for temp file cleanup — a stale .tmp is harmless.
     */
    private fun deleteSilently(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // Intentionally swallowed — stale temp is harmless
        }
    }
}
