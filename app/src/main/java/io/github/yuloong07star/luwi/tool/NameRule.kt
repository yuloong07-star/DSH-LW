package io.github.yuloong07star.luwi.tool

/** 一个名字最多多长: 再长在设置页那一段里会折成两行, 念给模型也啰嗦 */
private const val NAME_MAX = 60

/**
 * 名字规范化: 应用里所有"一个名字一个文件"的东西共用这一处
 *
 * 拒的那几样都是"写出去会长到别处或读不回来"的写法: 路径分隔符能把文件写到目录外, `..` 同理, 控制
 * 字符会让界面上那一行看起来是空的, 点开头的名字在文件管理器里是隐藏文件
 *
 * 快捷指令与自动指令**只有这一处**判名字: 两处各写一遍的代价不是重复, 而是"一条能建出来却读不回来"
 *
 * @param what 出错那句话里的主语 (`a quick command` / `an automatic command`)
 * @param suffix 名字后面那个固定的后缀 (`".md"` / `".json"`), 给了就一并去掉 —— 主人写名字时常常把
 *   后缀也带上, 那不该算成一个不同的名字
 */
internal fun normalizeArtifactName(name: String, what: String, suffix: String? = null): String {
    var trimmed = name.trim()
    if (suffix != null) trimmed = trimmed.removeSuffix(suffix).trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("$what needs a name")
    if (trimmed.contains('/') || trimmed.contains('\\')) {
        throw IllegalArgumentException("$what's name cannot contain a path separator: $trimmed")
    }
    if (trimmed.contains("..")) {
        throw IllegalArgumentException("$what's name cannot contain \"..\": $trimmed")
    }
    if (trimmed.any { it.isISOControl() }) {
        throw IllegalArgumentException("$what's name cannot contain control characters")
    }
    if (trimmed.startsWith(".")) {
        throw IllegalArgumentException("$what's name cannot start with a dot: $trimmed")
    }
    if (trimmed.length > NAME_MAX) {
        throw IllegalArgumentException("$what's name is at most $NAME_MAX characters")
    }
    return trimmed
}
