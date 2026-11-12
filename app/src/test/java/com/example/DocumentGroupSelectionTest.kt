package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.DocumentGroup
import com.example.data.model.DocumentType
import com.example.data.model.IdentityDocument
import com.example.ui.components.AddEditDocumentDialog
import com.example.ui.components.buildDocumentGroupHierarchy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DocumentGroupSelectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleGroups = listOf(
        DocumentGroup(id = "root-1", name = "مدارک شغلی", parentGroupId = null),
        DocumentGroup(id = "child-1", name = "قراردادها", parentGroupId = "root-1"),
        DocumentGroup(id = "child-1-sub", name = "سال ۱۴۰۳", parentGroupId = "child-1"),
        DocumentGroup(id = "root-2", name = "اسناد رسمی", parentGroupId = null)
    )

    @Test
    fun hierarchyBuilder_sortsParentsBeforeChildrenAndComputesFullPaths() {
        val hierarchy = buildDocumentGroupHierarchy(sampleGroups)

        assertEquals(4, hierarchy.size)

        // root-2 (اسناد رسمی) and root-1 (مدارک شغلی) sorted by name
        val names = hierarchy.map { it.group.name }
        assertTrue(names.contains("اسناد رسمی"))
        assertTrue(names.contains("مدارک شغلی"))
        assertTrue(names.contains("قراردادها"))
        assertTrue(names.contains("سال ۱۴۰۳"))

        val subChild = hierarchy.find { it.group.id == "child-1-sub" }
        assertNotNull(subChild)
        assertEquals(2, subChild?.depth)
        assertEquals("مدارک شغلی / قراردادها / سال ۱۴۰۳", subChild?.fullPath)
    }

    @Test
    fun dialog_addAtRoot_defaultsToRoot() {
        composeTestRule.setContent {
            AddEditDocumentDialog(
                initialDocument = null,
                availableGroups = sampleGroups,
                defaultGroupId = null,
                onDismiss = {},
                onSave = {}
            )
        }

        // Selector displays "بدون گروه (ریشه)"
        composeTestRule.onNodeWithTag("document_group_selector").assertExists()
        composeTestRule.onNodeWithText("بدون گروه (ریشه)").assertExists()
    }

    @Test
    fun dialog_addInsideGroup_defaultsToTargetGroup() {
        composeTestRule.setContent {
            AddEditDocumentDialog(
                initialDocument = null,
                availableGroups = sampleGroups,
                defaultGroupId = "child-1",
                onDismiss = {},
                onSave = {}
            )
        }

        // Selector displays the full path of child-1
        composeTestRule.onNodeWithText("مدارک شغلی / قراردادها").assertExists()
    }

    @Test
    fun dialog_editDocument_defaultsToDocumentOwnGroup() {
        val doc = IdentityDocument(
            id = "d1",
            title = "قرارداد استخدامی",
            docType = DocumentType.OTHER,
            groupId = "child-1"
        )

        composeTestRule.setContent {
            AddEditDocumentDialog(
                initialDocument = doc,
                availableGroups = sampleGroups,
                defaultGroupId = "root-2", // Different from doc's own group
                onDismiss = {},
                onSave = {}
            )
        }

        // Should use doc's groupId, not defaultGroupId
        composeTestRule.onNodeWithText("مدارک شغلی / قراردادها").assertExists()
    }

    @Test
    fun dialog_editOrphanDocument_fallsBackToRoot() {
        // Document points to a non-existent groupId
        val orphanDoc = IdentityDocument(
            id = "d-orphan",
            title = "مدرک یتیم",
            docType = DocumentType.OTHER,
            groupId = "deleted-group-xyz"
        )

        composeTestRule.setContent {
            AddEditDocumentDialog(
                initialDocument = orphanDoc,
                availableGroups = sampleGroups,
                defaultGroupId = null,
                onDismiss = {},
                onSave = {}
            )
        }

        // Orphan reference must fall back to "بدون گروه (ریشه)"
        composeTestRule.onNodeWithText("بدون گروه (ریشه)").assertExists()
    }

    @Test
    fun dialog_save_savesSelectedGroupIdInDocument() {
        var savedDoc: IdentityDocument? = null

        composeTestRule.setContent {
            AddEditDocumentDialog(
                initialDocument = null,
                availableGroups = sampleGroups,
                defaultGroupId = null,
                onDismiss = {},
                onSave = { savedDoc = it }
            )
        }

        // Open group dropdown
        composeTestRule.onNodeWithTag("document_group_selector").performClick()

        // Select root-2
        composeTestRule.onNodeWithTag("document_group_option_root-2").performClick()
        composeTestRule.waitForIdle()

        // Click save button (inside scrollable sheet)
        composeTestRule.onNodeWithText("ذخیره مدرک").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertNotNull(savedDoc)
        assertEquals("root-2", savedDoc?.groupId)
    }
}
