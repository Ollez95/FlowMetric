package com.flowmetric.shared.analytics

import com.flowmetric.shared.model.FileLineDelta

object LineDiffEstimator {
    fun estimate(previousText: String, currentText: String): FileLineDelta {
        val previousLines = previousText.lines()
        val currentLines = currentText.lines()
        if (previousLines == currentLines) {
            return FileLineDelta()
        }

        val diff = computeLineDiff(previousLines, currentLines)
        return FileLineDelta(
            inserted = diff.inserted,
            deleted = diff.deleted,
            largestInsertedBlock = diff.largestInsertedBlock,
            largestDeletedBlock = diff.largestDeletedBlock,
        )
    }

    private fun computeLineDiff(left: List<String>, right: List<String>): LineDiffSummary {
        if (left.isEmpty() && right.isEmpty()) {
            return LineDiffSummary()
        }

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

        var leftIndex = 0
        var rightIndex = 0
        var inserted = 0
        var deleted = 0
        var insertedRun = 0
        var deletedRun = 0
        var largestInsertedBlock = 0
        var largestDeletedBlock = 0

        fun flushRuns() {
            largestInsertedBlock = maxOf(largestInsertedBlock, insertedRun)
            largestDeletedBlock = maxOf(largestDeletedBlock, deletedRun)
            insertedRun = 0
            deletedRun = 0
        }

        while (leftIndex < left.size && rightIndex < right.size) {
            when {
                left[leftIndex] == right[rightIndex] -> {
                    flushRuns()
                    leftIndex++
                    rightIndex++
                }

                dp[leftIndex + 1][rightIndex] >= dp[leftIndex][rightIndex + 1] -> {
                    deleted++
                    deletedRun++
                    largestInsertedBlock = maxOf(largestInsertedBlock, insertedRun)
                    insertedRun = 0
                    leftIndex++
                }

                else -> {
                    inserted++
                    insertedRun++
                    largestDeletedBlock = maxOf(largestDeletedBlock, deletedRun)
                    deletedRun = 0
                    rightIndex++
                }
            }
        }

        while (leftIndex < left.size) {
            deleted++
            deletedRun++
            leftIndex++
        }
        while (rightIndex < right.size) {
            inserted++
            insertedRun++
            rightIndex++
        }
        flushRuns()

        return LineDiffSummary(
            inserted = inserted,
            deleted = deleted,
            largestInsertedBlock = largestInsertedBlock,
            largestDeletedBlock = largestDeletedBlock,
        )
    }
}

private data class LineDiffSummary(
    val inserted: Int = 0,
    val deleted: Int = 0,
    val largestInsertedBlock: Int = 0,
    val largestDeletedBlock: Int = 0,
)
