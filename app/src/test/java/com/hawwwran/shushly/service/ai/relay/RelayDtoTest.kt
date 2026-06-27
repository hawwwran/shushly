package com.hawwwran.shushly.service.ai.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The relay contract (relay/src/schema.ts) is the source of truth: field names must match. */
class RelayDtoTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Test
    fun request_serializesToExactRelayFieldNames() {
        val dto = ClassifyRequestDto(
            eventId = "evt-123",
            app = AppDto(packageName = "com.example.app", label = "Example"),
            notification = NotificationDto(
                title = "T",
                body = "B",
                category = "msg",
                postedAt = "2026-06-27T12:00:00Z",
            ),
            policy = PolicyDto(userInstruction = "treat pager as critical"),
        )
        val encoded = json.encodeToString(ClassifyRequestDto.serializer(), dto)
        val obj = json.parseToJsonElement(encoded).jsonObject

        assertEquals(1, obj["schema_version"]!!.jsonPrimitive.int)
        assertEquals("evt-123", obj["event_id"]!!.jsonPrimitive.content)
        val app = obj["app"]!!.jsonObject
        assertEquals("com.example.app", app["package_name"]!!.jsonPrimitive.content)
        assertEquals("Example", app["label"]!!.jsonPrimitive.content)
        val notif = obj["notification"]!!.jsonObject
        assertTrue(notif.containsKey("posted_at"))
        assertEquals("msg", notif["category"]!!.jsonPrimitive.content)
        val policy = obj["policy"]!!.jsonObject
        assertEquals("en", policy["locale"]!!.jsonPrimitive.content)
        assertEquals("silent", policy["default_on_ambiguity"]!!.jsonPrimitive.content)
        assertEquals("treat pager as critical", policy["user_instruction"]!!.jsonPrimitive.content)

        // No camelCase leaked into the wire form.
        assertFalse(encoded.contains("packageName"))
        assertFalse(encoded.contains("eventId"))
        assertFalse(encoded.contains("userInstruction"))
        assertFalse(encoded.contains("postedAt"))
    }

    @Test
    fun request_nullUserInstruction_isOmitted() {
        val dto = ClassifyRequestDto(
            eventId = "e",
            app = AppDto("com.x", "X"),
            notification = NotificationDto(null, null, null, null),
            policy = PolicyDto(userInstruction = null),
        )
        val policy = json.parseToJsonElement(json.encodeToString(ClassifyRequestDto.serializer(), dto))
            .jsonObject["policy"]!!.jsonObject
        assertFalse(policy.containsKey("user_instruction"))
    }

    @Test
    fun response_deserializesAllFields() {
        val body = """
            {"schema_version":1,"event_id":"evt-9","decision":"alert","confidence":0.91,
             "reason_code":"ALERT_WORK_INCIDENT","user_visible_reason":"Outage reported.",
             "model":"gpt-4.1-mini","latency_ms":420}
        """.trimIndent()
        val dto = json.decodeFromString(ClassifyResponseDto.serializer(), body)
        assertEquals("evt-9", dto.eventId)
        assertEquals("alert", dto.decision)
        assertEquals(0.91, dto.confidence, 0.0001)
        assertEquals("ALERT_WORK_INCIDENT", dto.reasonCode)
        assertEquals("Outage reported.", dto.userVisibleReason)
        assertEquals("gpt-4.1-mini", dto.model)
        assertEquals(420L, dto.latencyMs)
    }

    @Test
    fun response_ignoresUnknownKeys_andDefaultsOptionalNulls() {
        val body = """{"decision":"silent","confidence":0.2,"reason_code":"SILENT_MARKETING","surprise":"x"}"""
        val dto = json.decodeFromString(ClassifyResponseDto.serializer(), body)
        assertEquals("silent", dto.decision)
        assertNull(dto.userVisibleReason)
        assertNull(dto.model)
        assertNull(dto.latencyMs)
        assertNull(dto.eventId)
    }
}
