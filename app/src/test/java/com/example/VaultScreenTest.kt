package com.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.VaultFile
import com.example.data.model.VaultGroup
import com.example.ui.components.VaultFileItem
import com.example.ui.components.formatVaultFileSize
import com.example.ui.screens.VaultScreenContent
import com.example.ui.screens.countVaultFilesRecursive
import com.example.ui.theme.CartinoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VaultScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleFile(
        id: String,
        groupId: String? = null,
        displayName: String = "فایل $id",
        originalFileName: String = "$id.pdf",
        mimeType: String = "application/pdf",
        sizeBytes: Long = 2048L,
        notes: String = ""
    ) = VaultFile(
        id = id,
        groupId = groupId,
        displayName = displayName,
        originalFileName = originalFileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sha256 = "0".repeat(64),
        storageName = "$id.enc",
        notes = notes
    )

    @Test
    fun rootShowsGroupsRootFilesAndOrphanFilesHidingNestedFiles() {
        val groups = listOf(
            VaultGroup(id = "g1", name = "اسناد ملکی", parentGroupId = null)
        )
        val files = listOf(
            sampleFile(id = "f-root", groupId = null, displayName = "رسید آب"),
            sampleFile(id = "f-nested", groupId = "g1", displayName = "سند خانه"),
            sampleFile(id = "f-orphan", groupId = "missing-group", displayName = "فایل سرگردان")
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    VaultScreenContent(
                        groups = groups,
                        files = files,
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onAddOrUpdateGroup = {},
                        onDeleteGroupCascade = { _, _, _ -> }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("ریشه").assertExists()
        composeTestRule.onNodeWithText("اسناد ملکی").assertExists()
        composeTestRule.onNodeWithText("رسید آب").assertExists()
        composeTestRule.onNodeWithText("فایل سرگردان").assertExists()
        composeTestRule.onNodeWithText("سند خانه").assertDoesNotExist()
        composeTestRule.onNodeWithTag("vault_fab").assertExists()
    }

    @Test
    fun clickingGroupNavigatesNestedAndBreadcrumbReturnsToRoot() {
        val groups = listOf(
            VaultGroup(id = "g1", name = "قراردادها", parentGroupId = null),
            VaultGroup(id = "g2", name = "اجاره‌نامه", parentGroupId = "g1")
        )
        val files = listOf(
            sampleFile(id = "f-g1", groupId = "g1", displayName = "قرارداد کار"),
            sampleFile(id = "f-g2", groupId = "g2", displayName = "اجاره سال ۱۴۰۴")
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    VaultScreenContent(
                        groups = groups,
                        files = files,
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onAddOrUpdateGroup = {},
                        onDeleteGroupCascade = { _, _, _ -> }
                    )
                }
            }
        }

        // Navigate into g1
        composeTestRule.onNodeWithTag("vault_group_g1").performClick()
        composeTestRule.onNodeWithTag("vault_breadcrumbs_back_button").assertExists()
        composeTestRule.onNodeWithTag("vault_group_g2").assertExists()
        composeTestRule.onNodeWithText("قرارداد کار").assertExists()
        composeTestRule.onNodeWithText("اجاره سال ۱۴۰۴").assertDoesNotExist()

        // Navigate deeper into g2
        composeTestRule.onNodeWithTag("vault_group_g2").performClick()
        composeTestRule.onNodeWithText("اجاره سال ۱۴۰۴").assertExists()
        composeTestRule.onNodeWithText("قرارداد کار").assertDoesNotExist()

        // Jump back to root via first breadcrumb item
        composeTestRule.onNodeWithTag("vault_breadcrumb_item_0").performClick()
        composeTestRule.onNodeWithTag("vault_breadcrumbs_back_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("vault_group_g1").assertExists()
        composeTestRule.onNodeWithText("اجاره سال ۱۴۰۴").assertDoesNotExist()
    }

    @Test
    fun searchHidesBreadcrumbsAndGroupsAndMatchesAcrossAllLevels() {
        val groups = listOf(
            VaultGroup(id = "g1", name = "پوشه محرمانه", parentGroupId = null)
        )
        val files = listOf(
            sampleFile(
                id = "f1",
                groupId = "g1",
                displayName = "گذرنامه",
                originalFileName = "passport_scan.pdf",
                notes = "اعتبار تا ۲۰۲۹"
            ),
            sampleFile(
                id = "f2",
                groupId = null,
                displayName = "قبض برق",
                originalFileName = "bill.png"
            )
        )
        var queryState by mutableStateOf("")

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    VaultScreenContent(
                        groups = groups,
                        files = files,
                        searchQuery = queryState,
                        onSearchQueryChange = { queryState = it },
                        onAddOrUpdateGroup = {},
                        onDeleteGroupCascade = { _, _, _ -> }
                    )
                }
            }
        }

        // Search by originalFileName from nested folder
        composeTestRule.onNodeWithTag("vault_search_input").performTextInput("passport")
        composeTestRule.onNodeWithTag("vault_breadcrumbs_bar").assertDoesNotExist()
        composeTestRule.onNodeWithTag("vault_group_g1").assertDoesNotExist()
        composeTestRule.onNodeWithText("گذرنامه").assertExists()
        composeTestRule.onNodeWithText("قبض برق").assertDoesNotExist()

        // Clear search and verify normal navigation view returns
        composeTestRule.onNodeWithTag("vault_search_clear").performClick()
        composeTestRule.onNodeWithTag("vault_breadcrumbs_bar").assertExists()
        composeTestRule.onNodeWithTag("vault_group_g1").assertExists()
    }

    @Test
    fun expandableFabCreatesGroupWithValidationAndLaunchesDocumentPickerForNewFile() {
        val existingGroups = listOf(
            VaultGroup(id = "g-root", name = "بیمه", parentGroupId = null)
        )
        var savedGroup: VaultGroup? = null

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    VaultScreenContent(
                        groups = existingGroups,
                        files = emptyList(),
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onAddOrUpdateGroup = { savedGroup = it },
                        onDeleteGroupCascade = { _, _, _ -> }
                    )
                }
            }
        }

        // Open FAB and verify New File launches SAF picker (collapses FAB, no placeholder Toast)
        composeTestRule.onNodeWithTag("vault_fab").performClick()
        composeTestRule.onNodeWithTag("fab_add_vault_group").assertExists()
        composeTestRule.onNodeWithTag("fab_add_vault_file").performClick()
        composeTestRule.onNodeWithTag("fab_add_vault_group").assertDoesNotExist()
        assertTrue(ShadowToast.getTextOfLatestToast() != "به‌زودی اضافه می‌شود")

        // Re-open FAB and open New Group dialog
        composeTestRule.onNodeWithTag("vault_fab").performClick()
        composeTestRule.onNodeWithTag("fab_add_vault_group").performClick()

        // Confirm disabled when empty
        composeTestRule.onNodeWithTag("create_vault_group_confirm").assertIsNotEnabled()

        // Duplicate sibling name triggers error and keeps button disabled
        composeTestRule.onNodeWithTag("new_vault_group_name_input").performTextInput("بیمه")
        composeTestRule.onNodeWithText("گروهی با این نام در این سطح وجود دارد").assertExists()
        composeTestRule.onNodeWithTag("create_vault_group_confirm").assertIsNotEnabled()

        // Valid unique name enables confirm and saves group
        composeTestRule.onNodeWithTag("new_vault_group_name_input").performTextClearance()
        composeTestRule.onNodeWithTag("new_vault_group_name_input").performTextInput("مالیاتی")
        composeTestRule.onNodeWithTag("create_vault_group_confirm").assertIsEnabled().performClick()

        assertNotNull(savedGroup)
        assertEquals("مالیاتی", savedGroup?.name)
        assertEquals(null, savedGroup?.parentGroupId)
    }

    @Test
    fun groupMenuRenamesPreservingMetadataAndCascadeDeletesWithRecursiveFileCount() {
        val parent = VaultGroup(
            id = "g-parent",
            name = "مالی",
            parentGroupId = null,
            createdAt = 12345L
        )
        val child = VaultGroup(
            id = "g-child",
            name = "بانکی",
            parentGroupId = "g-parent",
            createdAt = 23456L
        )
        val grandchild = VaultGroup(
            id = "g-grandchild",
            name = "تراکنش‌ها",
            parentGroupId = "g-child",
            createdAt = 34567L
        )
        val groups = listOf(parent, child, grandchild)
        val files = listOf(
            sampleFile("f1", groupId = "g-parent"),
            sampleFile("f2", groupId = "g-child"),
            sampleFile("f3", groupId = "g-grandchild")
        )

        var renamedGroup: VaultGroup? = null
        var deletedGroup: VaultGroup? = null

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    VaultScreenContent(
                        groups = groups,
                        files = files,
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onAddOrUpdateGroup = { renamedGroup = it },
                        onDeleteGroupCascade = { grp, _, _ -> deletedGroup = grp }
                    )
                }
            }
        }

        // Rename parent group via 3-dot menu
        composeTestRule.onNodeWithTag("vault_group_menu_g-parent").performClick()
        composeTestRule.onNodeWithText("تغییر نام").performClick()
        composeTestRule.onNodeWithTag("rename_vault_group_input").performTextClearance()
        composeTestRule.onNodeWithTag("rename_vault_group_input").performTextInput("اسناد مالی")
        composeTestRule.onNodeWithTag("rename_vault_group_confirm").performClick()

        assertEquals("g-parent", renamedGroup?.id)
        assertEquals("اسناد مالی", renamedGroup?.name)
        assertEquals(null, renamedGroup?.parentGroupId)
        assertEquals(12345L, renamedGroup?.createdAt)

        // Cascade delete confirmation shows recursive file count (3)
        assertEquals(3, countVaultFilesRecursive("g-parent", groups, files))
        composeTestRule.onNodeWithTag("vault_group_menu_g-parent").performClick()
        composeTestRule.onNodeWithText("حذف گروه").performClick()
        composeTestRule.onNodeWithText("حذف آبشاری گروه").assertExists()
        composeTestRule.onNodeWithText("3", substring = true).assertExists()
        composeTestRule.onNodeWithTag("confirm_cascade_delete_vault_group").performClick()

        assertEquals("g-parent", deletedGroup?.id)
    }

    @Test
    fun emptyFolderShowsEmptyStateMessage() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    VaultScreenContent(
                        groups = emptyList(),
                        files = emptyList(),
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onAddOrUpdateGroup = {},
                        onDeleteGroupCascade = { _, _, _ -> }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("این گروه خالی است").assertExists()
        composeTestRule.onNodeWithText("+", substring = true).assertExists()
    }

    @Test
    fun fileSizeFormattingUsesLatinDigitsAndItemShowsOriginalNameOnlyWhenDifferent() {
        assertEquals("\u2068512\u2069 بایت", formatVaultFileSize(512L))
        assertEquals("\u20681.5 KB\u2069", formatVaultFileSize(1536L))
        assertEquals("\u20682.00 MB\u2069", formatVaultFileSize(2L * 1024L * 1024L))
        assertTrue(formatVaultFileSize(1536L).contains("1.5"))

        val fileDifferentName = sampleFile(
            id = "f-diff",
            displayName = "بیمه‌نامه",
            originalFileName = "insurance_1404.pdf",
            sizeBytes = 2048L
        )
        val fileSameName = sampleFile(
            id = "f-same",
            displayName = "receipt.jpg",
            originalFileName = "receipt.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 500L
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                CartinoTheme {
                    androidx.compose.foundation.layout.Column {
                        VaultFileItem(file = fileDifferentName)
                        VaultFileItem(file = fileSameName)
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("بیمه‌نامه").assertExists()
        composeTestRule.onNodeWithText("insurance_1404.pdf", substring = true).assertExists()
        composeTestRule.onNodeWithText("2.0 KB", substring = true).assertExists()
        composeTestRule.onNodeWithText("receipt.jpg").assertExists()
        composeTestRule.onNodeWithText("500", substring = true).assertExists()
    }
}
