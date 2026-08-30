package com.cyberphantom.contextkeeper

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Extracts visible text while preserving meaningful formatting such as
 * newlines, tabs, Markdown and code indentation.
 */
object ConversationExtractor {
    data class Segment(val role: String, val text: String)

    fun extract(root: AccessibilityNodeInfo?): List<Segment> {
        if (root == null) return emptyList()
        val candidates = mutableListOf<NodeText>()
        collect(root, null, candidates, intArrayOf(0))

        val filtered = candidates
            .asSequence()
            .map { it.copy(text = normalize(it.text)) }
            .filter { it.text.length >= 2 }
            .filterNot { isChromeText(it.text) }
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
        val depth: Int
    )

    private fun collect(
        node: AccessibilityNodeInfo,
        inheritedRole: String?,
        out: MutableList<NodeText>,
        counter: IntArray,
        depth: Int = 0
    ) {
        val role = inferRole(node, inheritedRole)
        val ownText = node.text?.toString().orEmpty()
        if (ownText.isNotBlank()) {
            out += NodeText(ownText.trimEnd(), role, counter[0]++, depth)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collect(child, role, out, counter, depth + 1)
            }
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
        val lower = value.trim().lowercase()
        return lower == "chatgpt" || lower == "you" || lower == "copy" ||
            lower == "edit" || lower == "regenerate" || lower == "good response" ||
            lower == "bad response"
    }

    private fun canMerge(previous: String, next: String): Boolean {
        if (previous.length > 250_000) return false
        if (previous.endsWith("```") || next.startsWith("```") ||
            previous.startsWith("```") || next.endsWith("```")) return true
        return previous.length < 4_000 || next.length < 4_000
    }

    private fun normalize(value: String): String = value
        .replace("\u00A0", " ")
        .trim()
}
