package com.flowmetric.shared.analytics

/**
 * Generates a unified diff-style patch string between two texts.
 */
object LinePatchBuilder {
    /**
     * Constructs a patch string in a format similar to the unified diff format.
     *
     * @param previousText The original text content.
     * @param currentText The modified text content.
     * @return A patch string if there are differences, or `null` if the texts are identical.
     *         The patch format consists of one or more "hunks", each representing a section of changes.
     */
    fun build(previousText: String, currentText: String): String? {
        val previousLines = previousText.lines()
        val currentLines = currentText.lines()
        if (previousLines == currentLines) return null

        val operations = computeOperations(previousLines, currentLines)
        if (operations.none { it is DiffOp.Insert || it is DiffOp.Delete }) return null

        val chunks = mutableListOf<String>()
        val hunkLines = mutableListOf<String>()
        var oldLine = 1
        var newLine = 1
        var hunkOldStart = 1
        var hunkNewStart = 1
        var hunkOldCount = 0
        var hunkNewCount = 0
        var inHunk = false

        fun flushHunk() {
            if (!inHunk) return
            chunks += buildString {
                append("@@ -")
                append(hunkOldStart)
                append(",")
                append(hunkOldCount)
                append(" +")
                append(hunkNewStart)
                append(",")
                append(hunkNewCount)
                append(" @@")
                if (hunkLines.isNotEmpty()) {
                    appendLine()
                    append(hunkLines.joinToString("\n"))
                }
            }
            hunkLines.clear()
            hunkOldCount = 0
            hunkNewCount = 0
            inHunk = false
        }

        operations.forEach { operation ->
            when (operation) {
                is DiffOp.Equal -> {
                    flushHunk()
                    oldLine += 1
                    newLine += 1
                }

                is DiffOp.Delete -> {
                    if (!inHunk) {
                        inHunk = true
                        hunkOldStart = oldLine
                        hunkNewStart = newLine
                    }
                    hunkLines += "-${operation.line}"
                    hunkOldCount += 1
                    oldLine += 1
                }

                is DiffOp.Insert -> {
                    if (!inHunk) {
                        inHunk = true
                        hunkOldStart = oldLine
                        hunkNewStart = newLine
                    }
                    hunkLines += "+${operation.line}"
                    hunkNewCount += 1
                    newLine += 1
                }
            }
        }

        flushHunk()
        return chunks.joinToString("\n")
    }

    /**
     * Computes the difference between two lists of strings using a dynamic programming
     * approach based on the Longest Common Subsequence (LCS) algorithm.
     *
     * @param left The original list of lines.
     * @param right The modified list of lines.
     * @return A list of [DiffOp] operations (Insert, Delete, Equal) representing
     *         the edit script to transform the `left` list into the `right` list.
     */
    private fun computeOperations(left: List<String>, right: List<String>): List<DiffOp> {
        val dp = Array(left.size + 1) { IntArray(right.size + 1) }
        for (leftIndex in left.indices.reversed()) {
            for (rightIndex in right.indices.reversed()) {
                dp[leftIndex][rightIndex] = if (left[leftIndex] == right[rightIndex]) {
                    dp[leftIndex + 1][rightIndex + 1] + 1
                } else {
                    maxOf(dp[leftIndex + 1][rightIndex], dp[leftIndex][rightIndex + 1])
                }
            }
        }

        val operations = mutableListOf<DiffOp>()
        var leftIndex = 0
        var rightIndex = 0

        while (leftIndex < left.size && rightIndex < right.size) {
            when {
                left[leftIndex] == right[rightIndex] -> {
                    operations += DiffOp.Equal(left[leftIndex])
                    leftIndex += 1
                    rightIndex += 1
                }

                dp[leftIndex + 1][rightIndex] >= dp[leftIndex][rightIndex + 1] -> {
                    operations += DiffOp.Delete(left[leftIndex])
                    leftIndex += 1
                }

                else -> {
                    operations += DiffOp.Insert(right[rightIndex])
                    rightIndex += 1
                }
            }
        }

        while (leftIndex < left.size) {
            operations += DiffOp.Delete(left[leftIndex])
            leftIndex += 1
        }

        while (rightIndex < right.size) {
            operations += DiffOp.Insert(right[rightIndex])
            rightIndex += 1
        }

        return operations
    }
}

/**
 * Represents a single operation in a diff sequence.
 */
private sealed interface DiffOp {
    /** Indicates a line that is common to both texts. */
    data class Equal(val line: String) : DiffOp
    /** Indicates a line that was deleted from the original text. */
    data class Delete(val line: String) : DiffOp
    /** Indicates a line that was added to the new text. */
    data class Insert(val line: String) : DiffOp
}
