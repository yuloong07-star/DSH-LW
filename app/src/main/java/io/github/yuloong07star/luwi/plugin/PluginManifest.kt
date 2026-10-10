package io.github.yuloong07star.luwi.plugin

import io.github.yuloong07star.luwi.plugin.api.LwPluginApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `plugin.json` 的解析与校验 (协议第 4 节)
 *
 * 这一份是**装之前那道闸**: 每一条不过都回一句点名的话 (哪一条、什么值、应该是什么), 因为
 * "装不上去而不知道为什么"对写插件的人是唯一真正过不去的坎
 *
 * 两条写在这里的硬规则:
 *
 * - **只有 `companion` 这一版收**: `dex` / `script` / `theme` 三种点名拒绝, 它们分别等 P2 / P3
 * - **数字只许是整数**: 签名要拿"规范化之后的 JSON"当载荷, 而两种语言对 `25.0` 的印法不一样
 *   (见 [PluginSignature]), 所以浮点数在协议这一层就不许出现
 */
internal data class PluginManifest(
    val raw: JsonObject,
    val id: String,
    val name: String,
    val version: String,
    val minLw: String,
    val api: String,
    val kind: String,
    val toolPrefix: String,
    val entryPackage: String,
    val entryService: String,
    val publisherName: String,
    val publisherKey: String,
    val publisherSignature: String,
    val requested: List<String>,
    val dangerous: List<String>,
    val tools: List<Tool>,
    val files: Map<String, String>,
) {
    data class Tool(val name: String, val summary: String, val params: JsonObject)

    val signed: Boolean get() = publisherKey.isNotBlank() && publisherSignature.isNotBlank()

    /** 声明了哪些能力 (两个档合起来, 顺序照表里的顺序) */
    val declared: List<String> get() = requested + dangerous

    companion object {
        /** 这一版收的 kind; 其余三种的拒绝话术在 [parse] 里 */
        val SUPPORTED_KINDS = setOf("companion")

        /** 保留前缀 (协议第 6 节): 内置工具永远是 `lw_*` */
        val RESERVED_PREFIXES = setOf("lw", "dsh", "agent")

        private val ID_RULE = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")
        private val PREFIX_RULE = Regex("^[a-z][a-z0-9_]{1,15}$")
        private val TOOL_RULE = Regex("^[a-z][a-z0-9_]{1,31}$")
        private val SHA256_RULE = Regex("^sha256:[0-9a-f]{64}$")
        private val PARAM_TYPES = setOf("string", "integer", "number", "boolean", "array", "object")

        /** 解析并校验; 不合法就抛一句点名的话 */
        fun parse(text: String, lwVersion: String): PluginManifest {
            val root = try {
                kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            } catch (error: Throwable) {
                throw IllegalArgumentException("plugin.json 不是一份 JSON 对象: ${error.message}")
            }
            rejectFloats(root)

            val protocol = root.string("protocol")
            if (protocol != LwPluginApi.PROTOCOL) {
                throw IllegalArgumentException("protocol 只认 ${LwPluginApi.PROTOCOL}, 这一份写的是 \"$protocol\"")
            }

            val id = root.string("id")
            if (!ID_RULE.matches(id) || id.length > 100) {
                throw IllegalArgumentException("id 要是一个反向域名 (例如 io.example.timer), 这一份写的是 \"$id\"")
            }

            val name = root.string("name")
            if (name.length > 40) throw IllegalArgumentException("name 最多 40 个字, 这一份是 ${name.length} 个")

            val version = root.string("version")
            requireVersion("version", version)
            val minLw = root.string("minLw")
            requireVersion("minLw", minLw)
            if (PluginVersions.compare(minLw, lwVersion) > 0) {
                throw IllegalArgumentException("这个插件要 $minLw 及以上的 LW, 而这台是 $lwVersion")
            }

            val api = root.string("api")
            if (api != LwPluginApi.API_VERSION.toString()) {
                throw IllegalArgumentException("api 只认 ${LwPluginApi.API_VERSION}, 这一份写的是 \"$api\"")
            }

            val kind = root.string("kind")
            if (kind !in PluginManifest.SUPPORTED_KINDS) {
                val later = when (kind) {
                    "dex", "script", "theme" -> "那三种要等 P2 / P3, 这一版只收 companion"
                    else -> "kind 只能是 companion"
                }
                throw IllegalArgumentException("这一版只收 companion 插件, 这一份写的是 \"$kind\" —— $later")
            }

            val prefix = root.string("toolPrefix")
            if (!PREFIX_RULE.matches(prefix)) {
                throw IllegalArgumentException("toolPrefix 要匹配 [a-z][a-z0-9_]{1,15}, 这一份写的是 \"$prefix\"")
            }
            if (prefix in RESERVED_PREFIXES) {
                throw IllegalArgumentException("toolPrefix 不能是保留字 (${RESERVED_PREFIXES.joinToString(" / ")}), 换一个")
            }

            val entry = root["entry"]?.jsonObject
                ?: throw IllegalArgumentException("companion 插件必须有 entry")
            val entryPackage = entry.string("package")
            val entryService = entry.string("service")

            val publisher = root["publisher"]?.jsonObject
            val publisherName = publisher?.stringOrEmpty("name").orEmpty()
            val publisherKey = publisher?.stringOrEmpty("key").orEmpty()
            val publisherSignature = publisher?.stringOrEmpty("signature").orEmpty()
            if (publisherKey.isNotBlank() && !publisherKey.startsWith("ecdsa-p256:")) {
                throw IllegalArgumentException("publisher.key 只认 ecdsa-p256:<base64>, 这一份写的是别的算法")
            }

            val capabilities = root["capabilities"]?.jsonObject
                ?: throw IllegalArgumentException("capabilities 是必填的 (要什么能力就写什么, 一条都不要也行)")
            val requested = capabilities.strings("requested")
            val dangerous = capabilities.strings("dangerous")
            (requested + dangerous).forEach { declared ->
                val known = PluginCapabilities.find(declared)
                    ?: throw IllegalArgumentException("能力表里没有 \"$declared\" —— 未知能力一律拒绝安装")
                val claimedDangerous = PluginCapabilities.isDangerous(declared)
                if (claimedDangerous && declared !in dangerous) {
                    throw IllegalArgumentException("\"$declared\" 是敏感档, 要写在 capabilities.dangerous 里")
                }
                if (!claimedDangerous && known.level == PluginCapabilities.Level.NORMAL && declared in dangerous) {
                    throw IllegalArgumentException("\"$declared\" 是普通档, 写在 capabilities.requested 就行")
                }
            }

            val tools = (root["tools"] as? JsonArray).orEmpty().map { element ->
                val tool = element.jsonObject
                val toolName = tool.string("name")
                if (!TOOL_RULE.matches(toolName)) {
                    throw IllegalArgumentException("工具名 \"$toolName\" 要匹配 [a-z][a-z0-9_]{1,31}")
                }
                if (!toolName.startsWith("${prefix}_")) {
                    throw IllegalArgumentException("工具名 \"$toolName\" 要用前缀 \"${prefix}_\" 开头")
                }
                val params = tool["params"]?.jsonObject ?: JsonObject(emptyMap())
                params.forEach { (key, value) -> checkParam(toolName, key, value.jsonObject) }
                Tool(toolName, tool.stringOrEmpty("summary").orEmpty(), params)
            }
            if (tools.map { it.name }.toSet().size != tools.size) {
                throw IllegalArgumentException("工具名在同一个插件里重了")
            }

            val ui = root["ui"]
            if (ui != null && ui != JsonNull) {
                val keys = (ui as? JsonObject)?.keys.orEmpty()
                if (keys.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "这一版还没有声明式设置与面板 (P4), ui 这一段要么不写要么写成空对象, 现在写着 ${keys.joinToString(", ")}",
                    )
                }
            }

            val files = buildMap {
                (root["files"] as? JsonObject)?.forEach { (path, value) ->
                    val digest = (value as? JsonPrimitive)?.content.orEmpty()
                    if (!SHA256_RULE.matches(digest)) {
                        throw IllegalArgumentException("files[\"$path\"] 要写成 sha256:<64 位小写十六进制>")
                    }
                    if (path.startsWith("/") || path.contains("..")) {
                        throw IllegalArgumentException("files 里只能有包内的相对路径, 这一条是 \"$path\"")
                    }
                    put(path, digest)
                }
                if (containsKey("plugin.json")) {
                    throw IllegalArgumentException("files 里不要列 plugin.json —— 它自己的字节就是签名载荷的一部分")
                }
            }

            return PluginManifest(
                raw = root,
                id = id,
                name = name,
                version = version,
                minLw = minLw,
                api = api,
                kind = kind,
                toolPrefix = prefix,
                entryPackage = entryPackage,
                entryService = entryService,
                publisherName = publisherName,
                publisherKey = publisherKey,
                publisherSignature = publisherSignature,
                requested = requested,
                dangerous = dangerous,
                tools = tools,
                files = files,
            )
        }

        private fun requireVersion(field: String, value: String) {
            if (!Regex("^\\d+\\.\\d+\\.\\d+$").matches(value)) {
                throw IllegalArgumentException("$field 要写成三段数字 (例如 1.0.4), 这一份写的是 \"$value\"")
            }
        }

        private fun checkParam(tool: String, key: String, value: JsonObject) {
            val type = (value["type"] as? JsonPrimitive)?.content.orEmpty()
            if (type !in PARAM_TYPES) {
                throw IllegalArgumentException("工具 $tool 的参数 $key 的 type 只能是 ${PARAM_TYPES.joinToString(" / ")}, 写的是 \"$type\"")
            }
            val unknown = value.keys - setOf("type", "description", "required", "enum", "items")
            if (unknown.isNotEmpty()) {
                throw IllegalArgumentException("工具 $tool 的参数 $key 有协议不认的键: ${unknown.joinToString(", ")}")
            }
            val items = value["items"] as? JsonObject
            if (type == "array") {
                if (items == null) throw IllegalArgumentException("工具 $tool 的参数 $key 是 array, 得给 items")
                checkParam(tool, "$key.items", items)
            }
            (value["enum"] as? JsonArray)?.forEach { entry ->
                if (entry !is JsonPrimitive) throw IllegalArgumentException("工具 $tool 的参数 $key 的 enum 只能是一串简单值")
            }
            (value["required"] as? JsonPrimitive)?.let {
                it.booleanOrNull ?: throw IllegalArgumentException("工具 $tool 的参数 $key 的 required 只能是 true / false")
            }
        }

        /**
         * 浮点数一律拒: 规范化 JSON 的两种实现 (Kotlin 与 Node) 只对整数印得一模一样
         *
         * 判据是"既不是布尔也不是整数" —— 不能只看它印出来有没有 `.` 或 `e`, 因为 `true` 与 `false`
         * 里各有一个 `e` (2026-10-08 在设备上就是这么撞的: 一个 `"required": false` 被念成"浮点")
         */
        private fun rejectFloats(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (value is JsonPrimitive && !value.isString && value !is JsonNull) {
                        if (value.booleanOrNull == null && value.longOrNull == null) {
                            throw IllegalArgumentException(
                                "\"$key\" 只能是整数或 true / false, 这一份写的是 ${value.content}" +
                                    " (协议里的数字不许有小数点)",
                            )
                        }
                    }
                    rejectFloats(value)
                }
                is JsonArray -> element.forEach { rejectFloats(it) }
                else -> Unit
            }
        }
    }
}

/** 三段版本号的比较 (协议里只有"能不能装"这一处用得到) */
internal object PluginVersions {
    /** 负数表示 [left] 旧, 0 表示同一版 */
    fun compare(left: String, right: String): Int {
        val a = left.split('.')
        val b = right.split('.')
        for (index in 0 until maxOf(a.size, b.size)) {
            val one = a.getOrNull(index)?.toIntOrNull() ?: 0
            val other = b.getOrNull(index)?.toIntOrNull() ?: 0
            if (one != other) return one - other
        }
        return 0
    }
}

/** 必填字符串: 没有就抛出带字段名的错 */
private fun JsonObject.string(key: String): String =
    stringOrEmpty(key).ifBlank {
        throw IllegalArgumentException("plugin.json 缺 $key 这个字段")
    }

/** 可选字符串 */
private fun JsonObject.stringOrEmpty(key: String): String =
    (this[key] as? JsonPrimitive)?.content.orEmpty()

/** 一串字符串 (缺省是空) */
private fun JsonObject.strings(key: String): List<String> {
    val array = this[key] ?: return emptyList()
    if (array !is JsonArray) throw IllegalArgumentException("$key 要是一个数组")
    return array.map { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("$key 里只能是一串字符串")
    }
}
