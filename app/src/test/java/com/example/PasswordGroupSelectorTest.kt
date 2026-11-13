package com.example

import com.example.data.model.PasswordGroup
import com.example.util.PasswordGroupTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGroupSelectorTest {

    private val chromeRoot = PasswordGroup(id = "chrome-root", name = "Chrome Password", parentGroupId = null)
    private val chromeGoogle = PasswordGroup(id = "chrome-google", name = "google.com", parentGroupId = "chrome-root")
    private val chromeGoogleWork = PasswordGroup(id = "chrome-google-work", name = "Work Accounts", parentGroupId = "chrome-google")
    private val chromeGithub = PasswordGroup(id = "chrome-github", name = "github.com", parentGroupId = "chrome-root")

    private val bitwardenRoot = PasswordGroup(id = "bw-root", name = "Bitwarden Password", parentGroupId = null)
    private val bitwardenSub = PasswordGroup(id = "bw-sub", name = "Social", parentGroupId = "bw-root")
    private val koofrGroup = PasswordGroup(id = "koofr", name = "Koofr", parentGroupId = null)
    private val bankingGroup = PasswordGroup(id = "banking", name = "Banking", parentGroupId = null, isFavorite = true)
    private val bankingIran = PasswordGroup(id = "banking-iran", name = "Iran Banks", parentGroupId = "banking")
    private val bankingMelli = PasswordGroup(id = "banking-melli", name = "Melli", parentGroupId = "banking-iran")

    private val fullGroupList = listOf(
        chromeRoot,
        chromeGoogle,
        chromeGoogleWork,
        chromeGithub,
        bitwardenRoot,
        bitwardenSub,
        koofrGroup,
        bankingGroup,
        bankingIran,
        bankingMelli
    )

    @Test
    fun nonChromeOrigin_excludesEntireChromeFamilyAndAllowsRootAndBitwarden() {
        val policy = PasswordGroupTree.resolveSelectorPolicy(
            allGroups = fullGroupList,
            originGroupId = koofrGroup.id
        )

        assertFalse(policy.isChromeContext)
        assertTrue(policy.allowRoot)

        val allowedIds = policy.treeNodes.map { it.group.id }.toSet()
        assertFalse(chromeRoot.id in allowedIds)
        assertFalse(chromeGoogle.id in allowedIds)
        assertFalse(chromeGoogleWork.id in allowedIds)
        assertFalse(chromeGithub.id in allowedIds)

        assertTrue(koofrGroup.id in allowedIds)
        assertTrue(bitwardenRoot.id in allowedIds)
        assertTrue(bitwardenSub.id in allowedIds)
        assertTrue(bankingGroup.id in allowedIds)
        assertTrue(bankingMelli.id in allowedIds)
    }

    @Test
    fun rootOrigin_excludesChromeFamilyAndAllowsRoot() {
        val policy = PasswordGroupTree.resolveSelectorPolicy(
            allGroups = fullGroupList,
            originGroupId = null
        )

        assertFalse(policy.isChromeContext)
        assertTrue(policy.allowRoot)
        val allowedIds = policy.treeNodes.map { it.group.id }.toSet()
        assertTrue(allowedIds.none { it.startsWith("chrome-") })
        assertEquals(6, allowedIds.size)
    }

    @Test
    fun chromeRootOrigin_allowsOnlyChromeFamilyAndForbidsRoot() {
        val policy = PasswordGroupTree.resolveSelectorPolicy(
            allGroups = fullGroupList,
            originGroupId = chromeRoot.id
        )

        assertTrue(policy.isChromeContext)
        assertFalse(policy.allowRoot)

        val allowedIds = policy.treeNodes.map { it.group.id }.toSet()
        assertEquals(
            setOf(chromeRoot.id, chromeGoogle.id, chromeGoogleWork.id, chromeGithub.id),
            allowedIds
        )
    }

    @Test
    fun chromeDeepDescendantOrigin_allowsOnlyChromeFamilyAndForbidsRoot() {
        val policy = PasswordGroupTree.resolveSelectorPolicy(
            allGroups = fullGroupList,
            originGroupId = chromeGoogleWork.id
        )

        assertTrue(policy.isChromeContext)
        assertFalse(policy.allowRoot)

        val allowedIds = policy.treeNodes.map { it.group.id }.toSet()
        assertEquals(
            setOf(chromeRoot.id, chromeGoogle.id, chromeGoogleWork.id, chromeGithub.id),
            allowedIds
        )
    }

    @Test
    fun newItemWithChromeCurrentGroup_allowsOnlyChromeFamily() {
        val chromeCaseVariant = PasswordGroup(id = "c-root", name = "  chrome password  ", parentGroupId = null)
        val chromeSub = PasswordGroup(id = "c-sub", name = "Sub", parentGroupId = "c-root")
        val other = PasswordGroup(id = "other", name = "Personal", parentGroupId = null)

        val policy = PasswordGroupTree.resolveSelectorPolicy(
            allGroups = listOf(chromeCaseVariant, chromeSub, other),
            originGroupId = chromeSub.id
        )

        assertTrue(policy.isChromeContext)
        assertFalse(policy.allowRoot)
        assertEquals(listOf("c-root", "c-sub"), policy.treeNodes.map { it.group.id })
    }

    @Test
    fun noChromeGroupInList_allowsAllGroupsAndRoot() {
        val groups = listOf(koofrGroup, bankingGroup, bankingIran, bitwardenRoot)
        val policy = PasswordGroupTree.resolveSelectorPolicy(
            allGroups = groups,
            originGroupId = bankingIran.id
        )

        assertFalse(policy.isChromeContext)
        assertTrue(policy.allowRoot)
        assertEquals(groups.size, policy.treeNodes.size)
    }

    @Test
    fun buildTree_ordersParentBeforeChildWithMultiLevelDepthAndSortsPinnedThenAlphabetical() {
        val rootZ = PasswordGroup(id = "z", name = "zeta", parentGroupId = null)
        val rootA = PasswordGroup(id = "a", name = "Alpha", parentGroupId = null)
        val rootPinned = PasswordGroup(id = "p", name = "Zebra Pinned", parentGroupId = null, isFavorite = true)
        val childB = PasswordGroup(id = "a-b", name = "beta", parentGroupId = "a")
        val childPinned = PasswordGroup(id = "a-p", name = "Omega Pinned", parentGroupId = "a", isFavorite = true)
        val grandChild = PasswordGroup(id = "a-b-1", name = "Level 2", parentGroupId = "a-b")
        val greatGrandChild = PasswordGroup(id = "a-b-1-1", name = "Level 3", parentGroupId = "a-b-1")

        val tree = PasswordGroupTree.buildTree(
            listOf(greatGrandChild, rootZ, childB, rootA, grandChild, rootPinned, childPinned)
        )

        assertEquals(
            listOf(
                "p" to 0,
                "a" to 0,
                "a-p" to 1,
                "a-b" to 1,
                "a-b-1" to 2,
                "a-b-1-1" to 3,
                "z" to 0
            ),
            tree.map { it.group.id to it.depth }
        )
    }

    @Test
    fun buildTree_orphanNodesAppearAtTopLevelWithoutDroppingAnyGroup() {
        val orphan = PasswordGroup(id = "orphan", name = "Orphan Group", parentGroupId = "missing-parent")
        val orphanChild = PasswordGroup(id = "orphan-child", name = "Orphan Child", parentGroupId = "orphan")
        val normal = PasswordGroup(id = "normal", name = "Alpha", parentGroupId = null)

        val tree = PasswordGroupTree.buildTree(listOf(orphanChild, orphan, normal))

        assertEquals(
            listOf(
                "normal" to 0,
                "orphan" to 0,
                "orphan-child" to 1
            ),
            tree.map { it.group.id to it.depth }
        )
    }

    @Test
    fun cycleParentChild_doesNotHangOrDuplicateNodesInFamilyOrTree() {
        val cycleA = PasswordGroup(id = "cyc-a", name = "Alpha", parentGroupId = "cyc-b")
        val cycleB = PasswordGroup(id = "cyc-b", name = "Beta", parentGroupId = "cyc-a")
        val selfLoop = PasswordGroup(id = "self", name = "Gamma", parentGroupId = "self")

        val tree = PasswordGroupTree.buildTree(listOf(cycleB, selfLoop, cycleA))
        assertEquals(3, tree.size)
        assertEquals(setOf("cyc-a", "cyc-b", "self"), tree.map { it.group.id }.toSet())

        // Also ensure Chrome family traversal handles cycles safely
        val chromeCyclic = PasswordGroup(id = "ch-root", name = "Chrome Password", parentGroupId = "ch-child")
        val chromeChild = PasswordGroup(id = "ch-child", name = "sub.domain", parentGroupId = "ch-root")
        val familyIds = PasswordGroupTree.collectChromeFamilyIds(listOf(chromeCyclic, chromeChild))
        assertEquals(setOf("ch-root", "ch-child"), familyIds)
    }

    @Test
    fun filterTree_searchMatchesCaseInsensitivePreservesAncestorsAndHandlesEmptyOrNoMatch() {
        val tree = PasswordGroupTree.buildTree(
            listOf(koofrGroup, bankingGroup, bankingIran, bankingMelli, bitwardenRoot, bitwardenSub)
        )

        // Empty or blank query returns full tree
        assertEquals(tree, PasswordGroupTree.filterTree(tree, ""))
        assertEquals(tree, PasswordGroupTree.filterTree(tree, "   "))

        // Substring case-insensitive match on deep child preserves entire ancestor chain & depths
        val melliFiltered = PasswordGroupTree.filterTree(tree, "  MELL ")
        assertEquals(
            listOf(
                "banking" to 0,
                "banking-iran" to 1,
                "banking-melli" to 2
            ),
            melliFiltered.map { it.group.id to it.depth }
        )

        // No match returns empty list
        assertTrue(PasswordGroupTree.filterTree(tree, "nonexistent_xyz").isEmpty())
    }
}
