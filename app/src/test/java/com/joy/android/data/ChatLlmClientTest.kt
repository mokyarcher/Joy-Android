package com.joy.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLlmClientTest {
    @Test
    fun normalizeVisionChatTextKeepsRoleLines() {
        val raw = """
            ```
            对方：好像不行
            我：Steam 就是这样的
            对方：他不帮我做
            ```
        """.trimIndent()
        val out = ChatLlmClient.normalizeVisionChatText(raw)
        assertEquals(
            "对方：好像不行\n我：Steam 就是这样的\n对方：他不帮我做",
            out,
        )
    }

    @Test
    fun normalizeVisionChatTextFixesHalfwidthColon() {
        val out = ChatLlmClient.normalizeVisionChatText("对方:在吗\n我:有空")
        assertTrue(out.contains("对方：在吗"))
        assertTrue(out.contains("我：有空"))
    }

    @Test
    fun visionPromptChangesWithImagePolicy() {
        val ignore = ChatLlmClient.buildVisionExtractPrompt(VisionImagePolicy.IGNORE)
        assertTrue(ignore.contains("整条跳过"))
        assertTrue(ignore.contains("会话对象："))

        val placeholder = ChatLlmClient.buildVisionExtractPrompt(VisionImagePolicy.PLACEHOLDER)
        assertTrue(placeholder.contains("[图片]"))
        assertFalse(placeholder.contains("不超过12字"))

        val describe = ChatLlmClient.buildVisionExtractPrompt(VisionImagePolicy.DESCRIBE)
        assertTrue(describe.contains("[图片:"))
        assertTrue(describe.contains("12字"))
        assertTrue(describe.contains("会话对象："))
    }

    @Test
    fun extractMessageOutputFallsBackToReasoning() {
        // 不依赖 Android JSONObject 桩；直接测从长文捞角色行
        val blob = "一堆推理过程……\n对方：在吗\n我：有空\n完"
        val lines = ChatLlmClient.salvageRoleLinesFromBlob(blob)
        assertEquals(listOf("对方：在吗", "我：有空"), lines)
    }

    @Test
    fun stripThinkTagsRemovesBlocks() {
        val raw = "<think>一堆思考</think>\n对方：你好\n我：嗯"
        val out = ChatLlmClient.normalizeVisionChatText(raw)
        assertEquals("对方：你好\n我：嗯", out)
        assertFalse(out.contains("思考"))
    }

    @Test
    fun shouldDisableLongReasoningForSenseNova() {
        assertTrue(
            ChatLlmClient.shouldDisableLongReasoning(
                "sensenova-6.8-flash-lite",
                "https://token.sensenova.cn/v1",
            ),
        )
    }

    @Test
    fun humanizeApiErrorMapsCommonFailures() {
        assertTrue(
            ChatLlmClient.humanizeApiError(IllegalStateException("API 401: invalid_api_key"))
                .contains("Key"),
        )
        assertTrue(
            ChatLlmClient.humanizeApiError(IllegalStateException("API 429: rate limit"))
                .contains("频繁"),
        )
        assertTrue(
            ChatLlmClient.humanizeApiError(
                java.net.UnknownHostException("Unable to resolve host token.example"),
            ).contains("网络不通"),
        )
    }

    @Test
    fun zhipuThinkingFlagDoesNotHitSensenovaGlm() {
        assertTrue(
            ChatLlmClient.usesZhipuThinkingParam(
                "glm-4.6v-flash",
                "https://open.bigmodel.cn/api/paas/v4",
            ),
        )
        assertFalse(
            ChatLlmClient.shouldDisableLongReasoning(
                "glm-4.6v-flash",
                "https://open.bigmodel.cn/api/paas/v4",
            ),
        )
        // 商汤列表里的 glm-5.2 不能误加智谱 thinking
        assertFalse(
            ChatLlmClient.usesZhipuThinkingParam(
                "glm-5.2",
                "https://token.sensenova.cn/v1",
            ),
        )
    }

    @Test
    fun zhipuPresetExposesFreeVisionModel() {
        val visions = ProviderPresets.visionModelsForPreset("zhipu")
        assertTrue(visions.contains("glm-4.6v-flash"))
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            ProviderPresets.byId("zhipu").baseUrl,
        )
        assertTrue(ProviderPresets.visionCapablePresets().any { it.id == "zhipu" })
        assertTrue(ProviderPresets.visionCapablePresets().any { it.id == "deepseek" })
        assertEquals(
            "deepseek-v4-flash-vision-exp",
            ProviderPresets.defaultVisionModelForPreset("deepseek"),
        )
    }

    @Test
    fun visionCredentialsResolveIndependently() {
        val s = JoySettings(
            baseUrl = "https://api.deepseek.com",
            apiKey = "reply-key",
            model = "deepseek-chat",
            visionBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
            visionApiKey = "vision-key",
            visionModel = "glm-4.6v-flash",
        )
        assertEquals("https://open.bigmodel.cn/api/paas/v4", s.resolvedVisionBaseUrl())
        assertEquals("vision-key", s.resolvedVisionApiKey())
        assertEquals("glm-4.6v-flash", s.resolvedVisionModel())

        val shared = JoySettings(
            baseUrl = "https://api.deepseek.com",
            apiKey = "reply-key",
            visionBaseUrl = "",
            visionApiKey = "",
            visionModel = "sensenova-6.7-flash-lite",
        )
        assertEquals("https://api.deepseek.com", shared.resolvedVisionBaseUrl())
        assertEquals("reply-key", shared.resolvedVisionApiKey())
    }

    @Test
    fun authorizationHeaderUsesBasicForUserPassword() {
        val h = ChatLlmClient.authorizationHeader("ollama:secret")
        assertTrue(h.startsWith("Basic "))
        assertEquals(
            "Basic " + java.util.Base64.getEncoder()
                .encodeToString("ollama:secret".toByteArray(Charsets.UTF_8)),
            h,
        )
        assertEquals("Bearer sk-abc", ChatLlmClient.authorizationHeader("sk-abc"))
    }
}
