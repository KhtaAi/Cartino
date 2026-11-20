package com.example

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CartinoDatabase
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import com.example.ui.CartinoViewModel
import com.example.util.VaultStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultCascadeTest {

    private lateinit var application: Application
    private lateinit var viewModel: CartinoViewModel
    private lateinit var sourceTempDir: File
    private lateinit var testSecretKey: SecretKey

    @Before
    fun setup() {
        runBlocking {
            application = ApplicationProvider.getApplicationContext()
            sourceTempDir = File(application.cacheDir, "vault_cascade_sources").apply {
                deleteRecursively()
                mkdirs()
            }
            VaultStorageManager.getVaultDirectory(application).deleteRecursively()
            VaultStorageManager.getVaultDirectory(application)

            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            testSecretKey = keyGen.generateKey()
            VaultStorageManager.setTestKeyProviderForTesting { testSecretKey }

            // Ensure clean vault tables on active DB instance
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            dao.getAllFilesList().forEach { dao.deleteFileById(it.id) }
            dao.getAllGroupsList().forEach { dao.deleteGroupById(it.id) }

            viewModel = CartinoViewModel(application)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            val dao = CartinoDatabase.getDatabase(application).vaultDao()
            dao.getAllFilesList().forEach { dao.deleteFileById(it.id) }
            dao.getAllGroupsList().forEach { dao.deleteGroupById(it.id) }
            VaultStorageManager.setTestKeyProviderForTesting(null)
            VaultStorageManager.getVaultDirectory(application).deleteRecursively()
            sourceTempDir.deleteRecursively()
        }
    }

    @Test
    fun deleteVaultGroupCascade_removesAllNestedSubgroupsFilesInDbAndFilesOnDisk() = runBlocking {
        val dao = CartinoDatabase.getDatabase(application).vaultDao()

        val parentGroup = VaultGroup(id = "vg-parent", name = "مالی", parentGroupId = null)
        val childGroup = VaultGroup(id = "vg-child", name = "بانکی", parentGroupId = parentGroup.id)
        val grandChildGroup = VaultGroup(id = "vg-grandchild", name = "رسیدها", parentGroupId = childGroup.id)
        val unrelatedGroup = VaultGroup(id = "vg-other", name = "شخصی", parentGroupId = null)

        dao.insertGroup(parentGroup)
        dao.insertGroup(childGroup)
        dao.insertGroup(grandChildGroup)
        dao.insertGroup(unrelatedGroup)

        val parentFile = importEncryptedSample("parent.bin", 1024, parentGroup.id)
        val childFile = importEncryptedSample("child.bin", 2048, childGroup.id)
        val grandChildFile = importEncryptedSample("grandchild.bin", 4096, grandChildGroup.id)
        val unrelatedFile = importEncryptedSample("other.bin", 1500, unrelatedGroup.id)
        val rootFile = importEncryptedSample("root.bin", 800, null)

        dao.insertFile(parentFile)
        dao.insertFile(childFile)
        dao.insertFile(grandChildFile)
        dao.insertFile(unrelatedFile)
        dao.insertFile(rootFile)

        // Confirm all encrypted files exist on disk before cascade delete
        assertTrue(VaultStorageManager.encryptedFileExists(application, parentFile.storageName))
        assertTrue(VaultStorageManager.encryptedFileExists(application, childFile.storageName))
        assertTrue(VaultStorageManager.encryptedFileExists(application, grandChildFile.storageName))
        assertTrue(VaultStorageManager.encryptedFileExists(application, unrelatedFile.storageName))
        assertTrue(VaultStorageManager.encryptedFileExists(application, rootFile.storageName))

        val (deletedCount, freedBytes) = viewModel.deleteVaultGroupCascadeInternal(parentGroup.id)

        assertEquals(3, deletedCount)
        assertTrue(freedBytes >= (1024L + 2048L + 4096L))

        // Target group & all descendant groups deleted from DB
        assertNull(dao.getGroupById(parentGroup.id))
        assertNull(dao.getGroupById(childGroup.id))
        assertNull(dao.getGroupById(grandChildGroup.id))

        // All files inside target hierarchy deleted from DB & disk
        assertNull(dao.getFileById(parentFile.id))
        assertNull(dao.getFileById(childFile.id))
        assertNull(dao.getFileById(grandChildFile.id))
        assertFalse(VaultStorageManager.encryptedFileExists(application, parentFile.storageName))
        assertFalse(VaultStorageManager.encryptedFileExists(application, childFile.storageName))
        assertFalse(VaultStorageManager.encryptedFileExists(application, grandChildFile.storageName))

        // Unrelated group and root file preserved intact in DB & disk
        assertNotNull(dao.getGroupById(unrelatedGroup.id))
        assertNotNull(dao.getFileById(unrelatedFile.id))
        assertNotNull(dao.getFileById(rootFile.id))
        assertTrue(VaultStorageManager.encryptedFileExists(application, unrelatedFile.storageName))
        assertTrue(VaultStorageManager.encryptedFileExists(application, rootFile.storageName))
    }

    @Test
    fun cleanupOrphanVaultFiles_removesUnreferencedAndTempFiles_keepsValidFiles() = runBlocking {
        val dao = CartinoDatabase.getDatabase(application).vaultDao()

        val validFile = importEncryptedSample("valid.bin", 1200, null)
        dao.insertFile(validFile)

        val vaultDir = VaultStorageManager.getVaultDirectory(application)
        val orphanEncrypted = File(vaultDir, "orphan_unknown.vault").apply {
            writeBytes(ByteArray(500) { 0x11 })
        }
        val leftoverTemp = File(vaultDir, "tmp_abandoned.part").apply {
            writeBytes(ByteArray(300) { 0x22 })
        }

        assertTrue(orphanEncrypted.exists())
        assertTrue(leftoverTemp.exists())

        val result = viewModel.cleanupOrphanVaultFilesInternal()

        assertEquals(2, result.deletedCount)
        assertEquals(800L, result.freedBytes)
        assertFalse(orphanEncrypted.exists())
        assertFalse(leftoverTemp.exists())
        assertTrue(VaultStorageManager.encryptedFileExists(application, validFile.storageName))
    }

    private fun importEncryptedSample(name: String, size: Int, groupId: String?): VaultFile {
        val sourceFile = File(sourceTempDir, name).apply {
            writeBytes(Random(name.hashCode()).nextBytes(size))
        }
        val imported = VaultStorageManager.importFromUri(application, Uri.fromFile(sourceFile))
        return VaultFile(
            groupId = groupId,
            displayName = name,
            originalFileName = imported.originalFileName,
            mimeType = imported.mimeType,
            sizeBytes = imported.sizeBytes,
            sha256 = imported.sha256,
            storageName = imported.storageName,
            encryptionVersion = imported.encryptionVersion
        )
    }
}
