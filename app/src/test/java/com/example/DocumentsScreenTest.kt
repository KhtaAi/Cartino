package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.DocumentGroup
import com.example.data.model.DocumentType
import com.example.data.model.IdentityDocument
import com.example.ui.screens.DocumentsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DocumentsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun documentsScreen_showsGroupAndDocumentsAtRoot() {
        val groups = listOf(
            DocumentGroup(id = "grp-1", name = "مدارک هویتی", parentGroupId = null)
        )
        val docs = listOf(
            IdentityDocument(id = "doc-1", title = "کارت ملی", docType = DocumentType.NATIONAL_CARD, groupId = null),
            IdentityDocument(id = "doc-2", title = "پاسپورت", docType = DocumentType.PASSPORT, groupId = "grp-1")
        )

        composeTestRule.setContent {
            DocumentsScreen(
                documents = docs,
                groups = groups,
                searchQuery = "",
                onSearchQueryChange = {},
                onDeleteDocument = {},
                onSaveDocument = {},
                onAddOrUpdateGroup = {},
                onDeleteGroupCascade = { _, _, _ -> }
            )
        }

        // Verify root breadcrumb is visible
        composeTestRule.onNodeWithText("ریشه").assertExists()

        // Verify root group is visible
        composeTestRule.onNodeWithText("مدارک هویتی").assertExists()

        // Verify root document is visible
        composeTestRule.onNodeWithText("کارت ملی").assertExists()

        // doc-2 is inside grp-1, so it shouldn't be visible at root
        composeTestRule.onNodeWithText("پاسپورت").assertDoesNotExist()

        // FAB is present
        composeTestRule.onNodeWithTag("documents_fab").assertExists()
    }

    @Test
    fun documentsScreen_orphanDocumentsAppearAtRoot() {
        val groups = listOf(
            DocumentGroup(id = "grp-active", name = "فعال", parentGroupId = null)
        )
        // doc-orphan has a groupId that doesn't exist in groups
        val docs = listOf(
            IdentityDocument(id = "doc-orphan", title = "مدرک سرگردان", docType = DocumentType.OTHER, groupId = "deleted-group-id")
        )

        composeTestRule.setContent {
            DocumentsScreen(
                documents = docs,
                groups = groups,
                searchQuery = "",
                onSearchQueryChange = {},
                onDeleteDocument = {},
                onSaveDocument = {},
                onAddOrUpdateGroup = {},
                onDeleteGroupCascade = { _, _, _ -> }
            )
        }

        // Verify orphan document is rendered at root and never lost
        composeTestRule.onNodeWithText("مدرک سرگردان").assertExists()
    }

    @Test
    fun documentsScreen_navigatesIntoGroupOnClick() {
        val groups = listOf(
            DocumentGroup(id = "grp-1", name = "اسناد رسمی", parentGroupId = null)
        )
        val docs = listOf(
            IdentityDocument(id = "doc-inside", title = "سند ملکی", docType = DocumentType.OTHER, groupId = "grp-1")
        )

        composeTestRule.setContent {
            DocumentsScreen(
                documents = docs,
                groups = groups,
                searchQuery = "",
                onSearchQueryChange = {},
                onDeleteDocument = {},
                onSaveDocument = {},
                onAddOrUpdateGroup = {},
                onDeleteGroupCascade = { _, _, _ -> }
            )
        }

        // Click on group to navigate inside
        composeTestRule.onNodeWithText("اسناد رسمی").performClick()

        // Breadcrumbs now include the group name
        composeTestRule.onNodeWithText("اسناد رسمی").assertExists()

        // The document inside this group is now visible
        composeTestRule.onNodeWithText("سند ملکی").assertExists()

        // Back button is visible in breadcrumbs
        composeTestRule.onNodeWithTag("breadcrumbs_back_button").assertExists()
    }
}
