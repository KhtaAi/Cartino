package com.example.util

import com.example.data.model.PasswordGroup

data class PasswordGroupNode(
    val group: PasswordGroup,
    val depth: Int
)

data class PasswordGroupSelectorPolicy(
    val isChromeContext: Boolean,
    val allowRoot: Boolean,
    val allowedGroups: List<PasswordGroup>,
    val treeNodes: List<PasswordGroupNode>
)

object PasswordGroupTree {

    private const val CHROME_PASSWORD_GROUP_NAME = "Chrome Password"

    private val siblingComparator = compareByDescending<PasswordGroup> { it.isFavorite }
        .thenBy { it.name.trim().lowercase() }
        .thenBy { it.name }
        .thenBy { it.id }

    fun findChromeRootGroup(groups: List<PasswordGroup>): PasswordGroup? {
        return groups.firstOrNull {
            it.name.trim().equals(CHROME_PASSWORD_GROUP_NAME, ignoreCase = true) && it.parentGroupId == null
        } ?: groups.firstOrNull {
            it.name.trim().equals(CHROME_PASSWORD_GROUP_NAME, ignoreCase = true)
        }
    }

    fun collectChromeFamilyIds(groups: List<PasswordGroup>): Set<String> {
        val chromeRoot = findChromeRootGroup(groups) ?: return emptySet()
        val childrenByParent = groups.groupBy { it.parentGroupId }
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque<String>()

        visited.add(chromeRoot.id)
        queue.addLast(chromeRoot.id)

        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            val children = childrenByParent[currentId].orEmpty()
            for (child in children) {
                if (visited.add(child.id)) {
                    queue.addLast(child.id)
                }
            }
        }
        return visited
    }

    fun resolveSelectorPolicy(
        allGroups: List<PasswordGroup>,
        originGroupId: String?
    ): PasswordGroupSelectorPolicy {
        val chromeFamilyIds = collectChromeFamilyIds(allGroups)
        val isChromeContext = originGroupId != null && originGroupId in chromeFamilyIds
        val allowedGroups = if (isChromeContext) {
            allGroups.filter { it.id in chromeFamilyIds }
        } else {
            allGroups.filter { it.id !in chromeFamilyIds }
        }
        val allowRoot = !isChromeContext
        val treeNodes = buildTree(allowedGroups)

        return PasswordGroupSelectorPolicy(
            isChromeContext = isChromeContext,
            allowRoot = allowRoot,
            allowedGroups = allowedGroups,
            treeNodes = treeNodes
        )
    }

    fun buildTree(groups: List<PasswordGroup>): List<PasswordGroupNode> {
        if (groups.isEmpty()) return emptyList()

        val uniqueGroups = groups.distinctBy { it.id }
        val groupMap = uniqueGroups.associateBy { it.id }
        val childrenByParent = uniqueGroups.groupBy { it.parentGroupId }

        val topLevelRoots = uniqueGroups
            .filter { it.parentGroupId == null || it.parentGroupId !in groupMap }
            .sortedWith(siblingComparator)

        val result = ArrayList<PasswordGroupNode>(uniqueGroups.size)
        val visited = mutableSetOf<String>()

        fun dfs(group: PasswordGroup, depth: Int) {
            if (!visited.add(group.id)) return
            result.add(PasswordGroupNode(group = group, depth = depth))
            val children = childrenByParent[group.id]
                .orEmpty()
                .sortedWith(siblingComparator)
            for (child in children) {
                dfs(child, depth + 1)
            }
        }

        for (root in topLevelRoots) {
            dfs(root, 0)
        }

        if (visited.size < uniqueGroups.size) {
            val remaining = uniqueGroups.sortedWith(siblingComparator)
            for (group in remaining) {
                if (group.id !in visited) {
                    dfs(group, 0)
                }
            }
        }

        return result
    }

    fun filterTree(
        treeNodes: List<PasswordGroupNode>,
        query: String
    ): List<PasswordGroupNode> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return treeNodes

        val matchedIds = treeNodes
            .filter { it.group.name.trim().contains(trimmedQuery, ignoreCase = true) }
            .map { it.group.id }
            .toSet()

        if (matchedIds.isEmpty()) return emptyList()

        val groupMap = treeNodes.associate { it.group.id to it.group }
        val includedIds = mutableSetOf<String>()

        for (matchId in matchedIds) {
            var currentId: String? = matchId
            val cycleGuard = mutableSetOf<String>()
            while (currentId != null && currentId in groupMap && cycleGuard.add(currentId)) {
                includedIds.add(currentId)
                currentId = groupMap[currentId]?.parentGroupId
            }
        }

        return treeNodes.filter { it.group.id in includedIds }
    }
}
