package com.example.util

import android.content.Context
import android.net.Uri
import com.example.data.local.CartinoDatabase
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.IOException
import java.security.MessageDigest

object VaultZipExporter {

    private const val STREAM_BUFFER_BYTES = 64 * 1024

    private data class GroupExportNode(
        val group: VaultGroup,
        val zipFolderPath: String
    )

    suspend fun exportGroupToZip(
        context: Context,
        groupId: String,
        targetUri: Uri,
        zipPassword: CharArray
    ): ExportResult = withContext(Dispatchers.IO) {
        if (zipPassword.isEmpty()) {
            return@withContext ExportResult(
                success = false,
                errorMessage = "رمز فایل ZIP نمی‌تواند خالی باشد",
                totalFiles = 0
            )
        }

        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val vaultDao = CartinoDatabase.getDatabase(appContext).vaultDao()

        val allGroups = vaultDao.getAllGroupsList()
        val rootGroup = allGroups.firstOrNull { it.id == groupId }
            ?: vaultDao.getGroupById(groupId)
            ?: return@withContext ExportResult(
                success = false,
                errorMessage = "گروه مورد نظر یافت نشد",
                totalFiles = 0
            )

        val allFiles = vaultDao.getAllFilesList()
        val filesByGroupId = allFiles.groupBy { it.groupId }
        val childGroupsByParentId = allGroups.groupBy { it.parentGroupId }

        val nodes = collectGroupHierarchy(
            rootGroup = rootGroup,
            childGroupsByParentId = childGroupsByParentId
        )

        val usedFileNamesPerFolder = mutableMapOf<String, MutableSet<String>>()
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var writtenFilesCount = 0

        try {
            val rawOut = try {
                resolver.openOutputStream(targetUri, "w")
            } catch (_: Throwable) {
                resolver.openOutputStream(targetUri)
            } ?: throw IOException("امکان باز کردن مسیر خروجی برای نوشتن وجود ندارد")

            rawOut.use { outputStream ->
                ZipOutputStream(outputStream, zipPassword).use { zipOut ->
                    for (node in nodes) {
                        val groupFiles = filesByGroupId[node.group.id]
                            .orEmpty()
                            .sortedWith(compareBy<VaultFile> { it.createdAt }.thenBy { it.id })
                        val hasChildren = childGroupsByParentId[node.group.id].orEmpty().isNotEmpty()

                        if (groupFiles.isEmpty() && !hasChildren && node.zipFolderPath.isNotEmpty()) {
                            val dirParams = ZipParameters().apply {
                                compressionMethod = CompressionMethod.STORE
                                isEncryptFiles = false
                                fileNameInZip = "${node.zipFolderPath}/"
                            }
                            zipOut.putNextEntry(dirParams)
                            zipOut.closeEntry()
                            continue
                        }

                        for (file in groupFiles) {
                            val desiredName = file.displayName.ifBlank { file.originalFileName }
                            val uniqueFileName = resolveUniqueFileName(
                                folderPath = node.zipFolderPath,
                                desiredName = desiredName,
                                usedNamesPerFolder = usedFileNamesPerFolder
                            )
                            val entryPath = if (node.zipFolderPath.isEmpty()) {
                                uniqueFileName
                            } else {
                                "${node.zipFolderPath}/$uniqueFileName"
                            }

                            val params = ZipParameters().apply {
                                compressionMethod = CompressionMethod.DEFLATE
                                isEncryptFiles = true
                                encryptionMethod = EncryptionMethod.AES
                                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                                fileNameInZip = entryPath
                            }

                            zipOut.putNextEntry(params)
                            val digest = MessageDigest.getInstance("SHA-256")
                            VaultStorageManager.openDecryptedInputStream(appContext, file.storageName)
                                .use { decryptedIn ->
                                    while (true) {
                                        val read = decryptedIn.read(buffer)
                                        if (read == -1) break
                                        digest.update(buffer, 0, read)
                                        zipOut.write(buffer, 0, read)
                                    }
                                }

                            if (file.sha256.isNotBlank()) {
                                val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                                if (!computedHash.equals(file.sha256, ignoreCase = true)) {
                                    throw IOException("یکپارچگی فایل «${file.displayName}» تأیید نشد")
                                }
                            }

                            zipOut.closeEntry()
                            writtenFilesCount++
                        }
                    }
                    zipOut.flush()
                }
            }

            SyncLogger.log(
                "VAULT",
                "خروجی ZIP رمزدار گروه «${rootGroup.name}» با $writtenFilesCount فایل انجام شد"
            )
            ExportResult(
                success = true,
                errorMessage = null,
                totalFiles = writtenFilesCount
            )
        } catch (t: Throwable) {
            SyncLogger.log(
                "VAULT",
                "خطا در ساخت ZIP رمزدار گروه «${rootGroup.name}»: ${t.javaClass.simpleName}"
            )
            ExportResult(
                success = false,
                errorMessage = t.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "خطا در ساخت فایل ZIP رمزدار",
                totalFiles = 0
            )
        }
    }

    private fun collectGroupHierarchy(
        rootGroup: VaultGroup,
        childGroupsByParentId: Map<String?, List<VaultGroup>>
    ): List<GroupExportNode> {
        val result = mutableListOf<GroupExportNode>()
        val visited = mutableSetOf<String>()

        fun visit(group: VaultGroup, currentPath: String) {
            if (!visited.add(group.id)) return
            result.add(GroupExportNode(group = group, zipFolderPath = currentPath))

            val usedChildFolderNames = mutableSetOf<String>()
            val children = childGroupsByParentId[group.id]
                .orEmpty()
                .sortedWith(compareBy<VaultGroup> { it.name.lowercase() }.thenBy { it.id })

            for (child in children) {
                val baseSegment = sanitizeFolderSegment(child.name)
                var uniqueSegment = baseSegment
                var counter = 1
                while (!usedChildFolderNames.add(uniqueSegment.lowercase())) {
                    uniqueSegment = "$baseSegment ($counter)"
                    counter++
                }
                val childPath = if (currentPath.isEmpty()) {
                    uniqueSegment
                } else {
                    "$currentPath/$uniqueSegment"
                }
                visit(child, childPath)
            }
        }

        visit(rootGroup, "")
        return result
    }

    internal fun resolveUniqueFileName(
        folderPath: String,
        desiredName: String,
        usedNamesPerFolder: MutableMap<String, MutableSet<String>>
    ): String {
        val sanitized = sanitizeFileName(desiredName)
        val used = usedNamesPerFolder.getOrPut(folderPath) { mutableSetOf() }

        if (used.add(sanitized.lowercase())) {
            return sanitized
        }

        val dotIndex = sanitized.lastIndexOf('.')
        val (stem, ext) = if (dotIndex > 0) {
            sanitized.substring(0, dotIndex) to sanitized.substring(dotIndex)
        } else {
            sanitized to ""
        }

        var counter = 1
        while (true) {
            val candidate = "$stem ($counter)$ext"
            if (used.add(candidate.lowercase())) {
                return candidate
            }
            counter++
        }
    }

    internal fun sanitizeFolderSegment(rawName: String): String {
        val cleaned = rawName.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .replace("\u0000", "")
            .replace("..", "__")
            .trim()
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "folder" else cleaned
    }

    internal fun sanitizeFileName(rawName: String): String {
        val cleaned = rawName.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .replace("\u0000", "")
            .replace("..", "__")
            .trim()
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "file" else cleaned
    }
}
