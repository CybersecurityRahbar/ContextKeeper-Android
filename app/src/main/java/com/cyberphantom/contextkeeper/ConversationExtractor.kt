package com.cyberphantom.contextkeeper

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristic extractor designed to reduce duplicate parent/child text nodes.
 * It prefers text-bearing leaves, while retaining container text when it is
 * clearly a complete message. Adjacent fragments with the same inferred role
 * are merged to preserve multi-line messages and code blocks.
 */
object ConversationExtractor {
    data class Segment(val role: String, val text: String)

    fun extract(root: AccessibilityNodeInfo?): List<Segment> {
        if (root == null) return emptyList()
        val candidates = mutableListOf<NodeText>()
        collect(root, null, candidates)

        val filtered = candidates
            .asSequence()
            .map { it.copy(text = normalize(it.text)) }
            .filter { it.text.length >= 2 }
            .filterNot { isChromeText(it.text) }
            .sortedBy { it.order }
            .toList()

        val deduped = mutableListOf<Segment>()
        val seen = HashSet<String>()
        for (candidate in filtered) {
            val key = candidate.role + "\u0000" + candidate.text
            if (!seen.add(key)) continue
            val previous = deduped.lastOrNull()
            if (previous != null && previous.role == candidate.role &&
                canMerge(previous.text, candidate.text)) {
                deduped[deduped.lastIndex] = Segment(
                    previous.role,
                    previous.text + "\n" + candidate.text
                )
            } else {
                deduped += Segment(candidate.role, candidate.text)
            }
        }
        return deduped
    }

    private data class NodeText(
        val text: String,
        val role: String,
        val order: Int,
        val depth: Int,
        val leaf: Boolean
    )

    private var orderCounter = 0

    private fun collect(
        node: AccessibilityNodeInfo,
        inheritedRole: String?,
        out: MutableList<NodeText>,
        depth: Int = 0
    ) {
        val role = inferRole(node, inheritedRole)
        val ownText = node.text?.toString()?.trim().orEmpty()
        val meaningfulChildText = (0 until node.childCount).any { index ->
            node.getChild(index)?.let { child ->
                child.text?.toString()?.trim()?.isNotBlank() == true
            } == true
        }
        if (ownText.isNotBlank()) {
            out += NodeText(
                text = ownText,
                role = role,
                order = orderCounter++,
                depth = depth,
                leaf = !meaningfulChildText
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> collect(child, role, out, depth + 1) }
        }
    }

    private fun inferRole(node: AccessibilityNodeInfo, inheritedRole: String?): String {
        val description = node.contentDescription?.toString()?.lowercase().orEmpty()
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        return when {
            description.contains("assistant") || description.contains("chatgpt") ||
                viewId.contains("assistant") -> "assistant"
            description.contains("you") || description.contains("user") ||
                viewId.contains("user") -> "user"
            else -> inheritedRole ?: "unknown"
        }
    }

    private fun isChromeText(value: String): Boolean {
        val lower = value.lowercase()
        return lower == "chatgpt" || lower == "you" || lower == "copy" ||
            lower == "edit" || lower == "regenerate" || lower == "good response" ||
            lower == "bad response"
    }

    private fun canMerge(previous: String, next: String): Boolean {
        if (previous.length > 200_000) return false
        if (next.startsWith("```") || previous.endsWith("```")) return true
        return previous.length < 3_000 || next.length < 3_000
    }

    private fun normalize(value: String): String = value
        .replace("\u00A0", " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\\n\\n")
        .trim()
}
