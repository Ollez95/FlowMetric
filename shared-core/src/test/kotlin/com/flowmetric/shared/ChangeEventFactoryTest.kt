package com.flowmetric.shared

import com.flowmetric.shared.model.EventSource
import com.flowmetric.shared.tracking.ChangeEventFactory
import com.flowmetric.shared.tracking.ChangeEventRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChangeEventFactoryTest {
    private val factory = ChangeEventFactory()

    @Test
    fun `codex metadata includes model when provided`() {
        val prepared = factory.build(
            ChangeEventRequest(
                projectPath = "/tmp/project",
                filePath = "/tmp/project/src/Main.kt",
                fileExtension = "kt",
                sourceLabel = "Codex",
                agentModel = "gpt-5-codex",
                previousText = "fun main() = println(\"old\")\n",
                currentText = "fun main() = println(\"new\")\n",
                source = EventSource.CODEX_PATCH,
                existingEvents = emptyList(),
                timestampEpochMillis = 1_000L,
            ),
        )

        requireNotNull(prepared)
        assertEquals("gpt-5-codex", prepared.event.metadata.agentModel)
    }

    @Test
    fun `codex metadata includes generated line patch`() {
        val prepared = factory.build(
            ChangeEventRequest(
                projectPath = "/tmp/project",
                filePath = "/tmp/project/src/Main.kt",
                fileExtension = "kt",
                sourceLabel = "Codex",
                previousText = "fun main() = println(\"old\")\n",
                currentText = "fun main() = println(\"new\")\n",
                source = EventSource.CODEX_PATCH,
                existingEvents = emptyList(),
                timestampEpochMillis = 1_000L,
            ),
        )

        requireNotNull(prepared)
        assertNotNull(prepared.event.metadata.linePatch)
    }
}
