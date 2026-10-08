package com.joy.android.data

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ChatLlmClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val activeCall = AtomicReference<Call?>(null)

    /** 取消进行中的 HTTP（识图/生成）；供 UI「取消」调用。 */
    fun cancelActive() {
        activeCall.getAndSet(null)?.cancel()
    }

    /**
     * 用回复模型发一条极短请求，验证 Base URL / Key / Model 是否可用。
     * @return 成功摘要，例如「连通成功 · deepseek-v4-flash · 0.8s」
     */
    fun testConnection(settings: JoySettings): String {
        val base = settings.baseUrl.trim().trimEnd('/')
        require(base.isNotEmpty()) { "请先填写 Base URL" }
        require(settings.apiKey.isNotBlank()) { "请先填写 API Key" }
        val model = settings.model.trim()
        require(model.isNotBlank()) { "请先填写回复模型" }

        val url = chatCompletionsUrl(base)
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_tokens", 16)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "只回复一个字：通"),
                    ),
            )
        applySpeedFlags(body, model, base)

        val started = System.nanoTime()
        try {
            val result = postChat(url, settings.apiKey, body)
            val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)
            val elapsed = formatProbeElapsed(elapsedMs)
            val preview = result.outputText.trim().replace('\n', ' ').take(24)
            return if (preview.isEmpty()) {
                "连通成功 · $model · $elapsed（返回空正文，但接口可用）"
            } else {
                "连通成功 · $model · $elapsed · 回声「$preview」"
            }
        } catch (e: Exception) {
            if (e.message == "已取消") throw e
            // 关思考参数不被支持时去掉再试一次
            if (hasSpeedFlags(body) && looksLikeParamError(e)) {
                try {
                    stripSpeedFlags(body)
                    val started2 = System.nanoTime()
                    postChat(url, settings.apiKey, body)
                    val elapsedMs = ((System.nanoTime() - started2) / 1_000_000L).coerceAtLeast(0)
                    return "连通成功 · $model · ${formatProbeElapsed(elapsedMs)}"
                } catch (e2: Exception) {
                    if (e2.message == "已取消") throw e2
                    throw IllegalStateException(humanizeApiError(e2), e2)
                }
            }
            throw IllegalStateException(humanizeApiError(e), e)
        }
    }

    fun generateReplies(settings: JoySettings, chatText: String): List<String> {
        val base = settings.baseUrl.trim().trimEnd('/')
        require(base.isNotEmpty()) { "请先在设置里填写 Base URL" }
        require(settings.apiKey.isNotBlank()) { "请先在设置里填写 API Key" }
        require(settings.model.isNotBlank()) { "请先在设置里填写 Model" }
        require(chatText.isNotBlank()) { "请先粘贴聊天内容" }

        val url = chatCompletionsUrl(base)
        val body = JSONObject()
            .put("model", settings.model.trim())
            .put("temperature", 0.7)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", settings.systemPrompt),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                "下面是微信聊天摘录。行首「对方：」是对面说的，「我：」是我方已发。" +
                                    "请站在「我」的立场，给出1～2条可直接发送的短回复，每条独占一行：\n\n" +
                                    chatText.trim(),
                            ),
                    ),
            )
        applySpeedFlags(body, settings.model.trim(), base)

        try {
            val result = postChat(url, settings.apiKey, body)
            val text = result.outputText.trim()
            if (text.isEmpty()) {
                throw IllegalStateException(
                    "模型返回为空（finish_reason=${result.finishReason ?: "?"}）",
                )
            }
            return parseReplyCandidates(text)
        } catch (e: Exception) {
            if (e.message == "已取消") throw e
            if (hasSpeedFlags(body) && looksLikeParamError(e)) {
                stripSpeedFlags(body)
                val result = postChat(url, settings.apiKey, body)
                val text = result.outputText.trim()
                if (text.isEmpty()) {
                    throw IllegalStateException(
                        "模型返回为空（finish_reason=${result.finishReason ?: "?"}）",
                    )
                }
                return parseReplyCandidates(text)
            }
            throw e
        }
    }

    /**
     * 多模态识图：把微信截图转成「对方：/我：」文本摘录。
     * [dataUrl] 形如 `data:image/jpeg;base64,...`
     *
     * 对齐桌面：关长思考、从 reasoning 兜底、空结果重试一次。
     */
    fun extractChatFromImage(settings: JoySettings, dataUrl: String): ChatExtractResult {
        val base = settings.resolvedVisionBaseUrl()
        val apiKey = settings.resolvedVisionApiKey()
        require(base.isNotEmpty()) { "请先在设置里填写视觉 Base URL（或回复 Base URL）" }
        require(apiKey.isNotBlank()) { "请先在设置里填写视觉 API Key（或回复 API Key）" }
        val visionModel = settings.resolvedVisionModel()
        require(visionModel.isNotBlank()) { "请先在设置里填写视觉模型" }
        require(dataUrl.startsWith("data:image")) { "图片数据无效" }

        val url = chatCompletionsUrl(base)
        var lastError: Exception? = null

        // 第 1 次：detail=low 更快；第 2 次：high 兜底空结果
        for (attempt in 0..1) {
            val detail = if (attempt == 0) "low" else "high"
            val maxTokens = if (attempt == 0) 1536 else 2560
            val extraHint = if (attempt == 0) {
                ""
            } else {
                "上一次没有有效正文。必须先写「会话对象：…」行，再输出「对方：/我：」行，禁止空回复，禁止只写思考过程。"
            }
            val body = buildVisionRequestBody(
                model = visionModel,
                dataUrl = dataUrl,
                policy = settings.visionImagePolicy,
                detail = detail,
                maxTokens = maxTokens,
                baseUrl = base,
                extraHint = extraHint,
                applySpeed = true,
            )
            try {
                val result = postChat(url, apiKey, body)
                val raw = stripThinkTags(result.outputText).trim()
                if (raw.isEmpty()) {
                    lastError = IllegalStateException(
                        "视觉模型返回为空（finish_reason=${result.finishReason ?: "?"}，" +
                            "source=${result.textSource}）",
                    )
                    continue
                }
                return parseVisionExtract(raw)
            } catch (e: IllegalStateException) {
                if (e.message == "已取消") throw e
                lastError = e
            } catch (e: Exception) {
                if (e.message == "已取消") throw e
                lastError = e
                // 关思考参数不被支持时，去掉再试同一次
                if (attempt == 0 && hasSpeedFlags(body) && looksLikeParamError(e)) {
                    try {
                        val body2 = buildVisionRequestBody(
                            model = visionModel,
                            dataUrl = dataUrl,
                            policy = settings.visionImagePolicy,
                            detail = detail,
                            maxTokens = maxTokens,
                            baseUrl = base,
                            extraHint = extraHint,
                            applySpeed = false,
                        )
                        val result = postChat(url, apiKey, body2)
                        val raw = stripThinkTags(result.outputText).trim()
                        if (raw.isNotEmpty()) {
                            return parseVisionExtract(raw)
                        }
                        lastError = IllegalStateException(
                            "视觉模型返回为空（finish_reason=${result.finishReason ?: "?"}）",
                        )
                    } catch (e2: Exception) {
                        if (e2.message == "已取消") throw e2
                        lastError = e2
                    }
                }
            }
        }
        throw lastError ?: IllegalStateException("视觉模型返回为空")
    }

    private fun buildVisionRequestBody(
        model: String,
        dataUrl: String,
        policy: VisionImagePolicy,
        detail: String,
        maxTokens: Int,
        baseUrl: String,
        extraHint: String,
        applySpeed: Boolean,
    ): JSONObject {
        val prompt = buildString {
            append(buildVisionExtractPrompt(policy))
            if (extraHint.isNotBlank()) {
                append("\n")
                append(extraHint)
            }
        }
        val imageUrl = JSONObject()
            .put("url", dataUrl)
            .put("detail", detail)
        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", imageUrl),
            )
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_tokens", maxTokens)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", userContent),
                    ),
            )
        if (applySpeed) {
            applySpeedFlags(body, model, baseUrl)
        }
        return body
    }

    /** SenseNova reasoning_effort / 智谱 thinking.type=disabled */
    private fun applySpeedFlags(body: JSONObject, model: String, baseUrl: String) {
        if (shouldDisableLongReasoning(model, baseUrl)) {
            body.put("reasoning_effort", "none")
        }
        if (usesZhipuThinkingParam(model, baseUrl)) {
            body.put("thinking", JSONObject().put("type", "disabled"))
        }
    }

    private fun hasSpeedFlags(body: JSONObject): Boolean =
        body.has("reasoning_effort") || body.has("thinking")

    private fun stripSpeedFlags(body: JSONObject) {
        body.remove("reasoning_effort")
        body.remove("thinking")
    }

    private data class ChatResult(
        val outputText: String,
        val finishReason: String?,
        val textSource: String,
    )

    private fun postChat(url: String, apiKey: String, body: JSONObject): ChatResult {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = http.newCall(request)
        activeCall.set(call)
        try {
            call.execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("API ${resp.code}: ${raw.take(280)}")
                }
                val root = JSONObject(raw)
                val choice = root.optJSONArray("choices")?.optJSONObject(0)
                val message = choice?.optJSONObject("message")
                val finish = choice?.optString("finish_reason")?.ifBlank { null }
                val (text, source) = extractMessageOutput(message)
                return ChatResult(text, finish, source)
            }
        } catch (e: IOException) {
            if (call.isCanceled()) {
                throw IllegalStateException("已取消", e)
            }
            throw e
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun parseReplyCandidates(content: String): List<String> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            throw IllegalStateException("模型返回为空")
        }
        return trimmed
            .lines()
            .map { it.trim() }
            .map { line ->
                line
                    .removePrefix("1.")
                    .removePrefix("2.")
                    .removePrefix("1、")
                    .removePrefix("2、")
                    .removePrefix("-")
                    .removePrefix("•")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
            .ifEmpty { listOf(trimmed) }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val THINK_TAG_RE =
            Regex("""(?is)<think>.*?</think>|<thinking>.*?</thinking>""")

        private fun chatCompletionsUrl(base: String): String =
            if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

        fun shouldDisableLongReasoning(model: String, baseUrl: String): Boolean {
            val blob = "$model $baseUrl".lowercase()
            // 智谱用 thinking 参数，不要塞 reasoning_effort
            if (usesZhipuThinkingParam(model, baseUrl)) return false
            return "sensenova" in blob ||
                "deepseek-v4" in blob ||
                "deepseek-flash" in blob ||
                "deepseek" in blob
        }

        /** 智谱 GLM：用 thinking.type=disabled 关深度思考（识图提速） */
        fun usesZhipuThinkingParam(model: String, baseUrl: String): Boolean {
            val url = baseUrl.lowercase()
            if ("bigmodel.cn" in url) return true
            val m = model.lowercase()
            // 勿用泛匹配 glm-5：商汤列表里也有 glm-5.2
            return m.startsWith("glm-4.6v") ||
                m.startsWith("glm-4.7-flash") ||
                m.startsWith("glm-4.5-flash") ||
                m == "glm-4-flash" ||
                m.startsWith("glm-4-flash-")
        }

        fun looksLikeParamError(e: Exception): Boolean {
            val msg = e.message.orEmpty().lowercase()
            return "reasoning" in msg ||
                "unexpected" in msg ||
                "invalid" in msg ||
                "400" in msg ||
                "unsupported" in msg
        }

        fun formatProbeElapsed(ms: Long): String {
            val sec = ms.coerceAtLeast(0) / 1000.0
            return String.format("%.1fs", sec)
        }

        /** 把网络/HTTP 异常收成可读中文，避免整段 JSON 糊脸。 */
        fun humanizeApiError(e: Exception): String {
            val raw = e.message.orEmpty().trim()
            if (raw.isEmpty()) return e.javaClass.simpleName
            val lower = raw.lowercase()
            val code = Regex("""\bAPI\s+(\d{3})\b""").find(raw)?.groupValues?.getOrNull(1)
                ?: Regex("""\b([45]\d{2})\b""").find(raw)?.groupValues?.getOrNull(1)
            when (code) {
                "401", "403" -> return "Key 无效或无权限（HTTP $code）"
                "404" -> return "地址或模型不存在（HTTP 404），检查 Base URL 与模型名"
                "429" -> return "请求过于频繁（HTTP 429），稍后再试"
                "500", "502", "503" -> return "服务端异常（HTTP $code），稍后再试"
            }
            return when {
                "unable to resolve host" in lower ||
                    "unknownhost" in lower ||
                    "nodename nor servname" in lower ->
                    "网络不通：无法解析主机，检查 Base URL 与网络"
                "timeout" in lower || "timed out" in lower ->
                    "请求超时，检查网络或换个模型再试"
                "failed to connect" in lower || "connection refused" in lower ->
                    "连不上服务器，检查 Base URL"
                "cleartext" in lower ->
                    "明文 HTTP 被系统拦截，请用 https"
                raw.startsWith("API ") -> {
                    val snippet = raw.removePrefix("API ").take(160)
                    "接口报错：$snippet"
                }
                else -> raw.take(200)
            }
        }

        /**
         * SenseNova 推理模型常把正文放在 reasoning / reasoning_content，content 为空。
         */
        fun extractMessageOutput(message: JSONObject?): Pair<String, String> {
            if (message == null) return "" to "empty"
            val fromContent = contentToText(message.opt("content")).trim()
            if (fromContent.isNotEmpty()) return fromContent to "content"
            for (key in listOf("reasoning", "reasoning_content")) {
                val alt = message.optString(key).trim()
                if (alt.isNotEmpty()) return alt to key
            }
            return "" to "empty"
        }

        fun contentToText(content: Any?): String {
            return when (content) {
                null -> ""
                is String -> content
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        when (val p = content.opt(i)) {
                            is String -> append(p)
                            is JSONObject -> append(p.optString("text"))
                        }
                    }
                }
                else -> content.toString()
            }
        }

        fun stripThinkTags(raw: String): String =
            THINK_TAG_RE.replace(raw, "").trim()

        fun buildVisionExtractPrompt(policy: VisionImagePolicy): String {
            val imageRule = when (policy) {
                VisionImagePolicy.IGNORE ->
                    "聊天中的照片/表情包/嵌套截图气泡：整条跳过，不要输出，也不要OCR图内文字。"
                VisionImagePolicy.PLACEHOLDER ->
                    "聊天中的照片/表情包/嵌套截图气泡：保留该条，正文只写「[图片]」，" +
                        "禁止把图内文字当聊天气泡输出；语音写「[语音]」。"
                VisionImagePolicy.DESCRIBE ->
                    "聊天中的照片/表情包/嵌套截图气泡：保留该条，写成「[图片:不超过12字的可见内容简述]」，" +
                        "看不清就写「[图片]」；禁止编造看不见的细节；禁止把图内大段文字当普通聊天句输出；" +
                        "语音写「[语音]」。"
            }
            return buildString {
                append("这是一张微信聊天截图。\n")
                append("第一行必须写：会话对象：<顶栏聊天对象昵称>（电脑端在窗口左上，手机端在顶栏中间；")
                append("看不清就写「会话对象：」空着）。\n")
                append("然后提取聊天气泡。忽略：状态栏、时间条、输入栏、Tab、表情选择面板、群成员昵称小字。\n")
                append(imageRule)
                append('\n')
                append("角色（群聊也一样）：右侧绿泡=「我」，左侧白/灰泡=「对方」。")
                append("长绿泡会向左撑开，仍算「我」，不要当成对方。\n")
                append("气泡按从上到下输出，每行严格：\n")
                append("对方：...\n")
                append("或\n")
                append("我：...\n")
                append("不要解释、不要编号。除「会话对象：」行外不要其它前缀。")
            }
        }

        fun parseVisionExtract(raw: String): ChatExtractResult {
            val cleaned = stripThinkTags(raw)
                .replace("```", "")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            var peerName: String? = null
            val rest = mutableListOf<String>()
            for (line in cleaned) {
                when {
                    line.startsWith("会话对象：") || line.startsWith("会话对象:") -> {
                        val value = line.substringAfter("：", missingDelimiterValue = "")
                            .ifEmpty { line.substringAfter(":", missingDelimiterValue = "") }
                            .trim()
                        peerName = ChatPeerName.sanitize(value)
                    }
                    else -> rest += line
                }
            }
            val chatText = normalizeVisionChatText(rest.joinToString("\n"))
            return ChatExtractResult(chatText = chatText, peerName = peerName)
        }

        /** 去掉模型偶发的代码块/空行，保留对方/我行。 */
        fun normalizeVisionChatText(raw: String): String {
            val cleaned = stripThinkTags(raw)
                .replace("```", "")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { line ->
                    when {
                        line.startsWith("对方：") || line.startsWith("我：") -> line
                        line.startsWith("对方:") -> "对方：" + line.removePrefix("对方:").trim()
                        line.startsWith("我:") -> "我：" + line.removePrefix("我:").trim()
                        else -> line
                    }
                }
            val roleLines = cleaned.filter {
                it.startsWith("对方：") || it.startsWith("我：")
            }
            val result = if (roleLines.isNotEmpty()) roleLines else cleaned
            if (result.isEmpty()) {
                throw IllegalStateException("视觉识别未得到聊天正文")
            }
            // 若整段都在 reasoning 里且没有角色行，尽量从长文里捞
            if (roleLines.isEmpty() && result.size == 1 && result[0].length > 80) {
                val salvaged = salvageRoleLinesFromBlob(result[0])
                if (salvaged.isNotEmpty()) return salvaged.joinToString("\n")
            }
            return result.joinToString("\n")
        }

        fun salvageRoleLinesFromBlob(blob: String): List<String> {
            val lines = mutableListOf<String>()
            val re = Regex("""(?:^|\n)\s*(对方|我)\s*[:：]\s*(.+)""")
            for (m in re.findAll(blob)) {
                val side = m.groupValues[1]
                val text = m.groupValues[2].trim()
                if (text.isNotEmpty()) lines += "$side：$text"
            }
            return lines
        }
    }
}
