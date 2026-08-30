package com.cyberphantom.contextkeeper

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Heuristic extractor for the accessibility tree.
 * Keeps raw visible text rather than semantic summarization.
 */
object ConversationExtractor {
    data class Segment(val role: String, val text: String)

    fun extract(root: AccessibilityNodeInfo?): List<Segment> {
        if (root == null) return emptyList()
        val nodes = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        walk(root, 0, nodes)
        val segments = mutableListOf<Segment>()
        for ((node, _) in nodes) {
            val raw = node.text?.toString()?.trim().orEmpty()
            if (raw.isBlank() || raw.length < 2) continue
            if (raw.equals("ChatGPT", true) || raw.equals("You", true)) continue
            segments += Segment(inferRole(node), raw)
        }
        return coalesce(segments)
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, out: MutableList<Pair<AccessibilityNodeInfo, Int>>) {
        out += node to depth
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1, out) }
    }

    private fun inferRole(node: AccessibilityNodeInfo): String {
        val cd = node.contentDescription?.toString()?.lowercase().orEmpty()
        return when {
            cd.contains("you") || cd.contains("user") -> "user"
            cd.contains("assistant") || cd.contains("chatgpt") -> "assistant"
            else -> "unknown"
        }
    }

    private fun coalesce(input: List<Segment>): List<Segment> {
        val out = mutableListOf<Segment>()
        for (segment in input) {
            val prev = out.lastOrNull()
            if (prev != null && prev.role == segment.role && prev.text == segment.text) continue
            out += segment
        }
        return out
    }
}
