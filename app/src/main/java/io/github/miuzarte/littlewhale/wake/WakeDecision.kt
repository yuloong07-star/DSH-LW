package io.github.miuzarte.littlewhale.wake

/**
 * 命中之后的去抖: 这一帧报出来的词算不算数
 *
 * 抽成纯函数是为了**没有设备也能量** (与 [HalfDuplex] / [VoiceIdle] 同一个写法, 判据在
 * `WakeDecisionTest`) —— 这台模拟器上 KWS 那一路跑不起来 (`libsherpa-onnx-jni.so` 在 linker 就崩),
 * 所以这段判定是这一批里能在开发机上真的验掉的那一半
 *
 * 三条判据, 展开就是三个数:
 *
 * 1. **冷却窗口**: 命中之后 [WakeTuning.HIT_COOLDOWN_MS] 里再来一次一律不算 —— 同一个词被连着
 *    报两遍是分数抖动最典型的样子
 * 2. **命令尾**: 主人常常"肥鱼肥鱼 + 紧接一句指令", 而命中那一刻 `reset` 之后, 指令的前半段还在
 *    解码器的手里 —— 所以要静默到**这一句话的第一段出字投递**, 上限 [WakeTuning.COMMAND_TAIL_MAX_MS]
 * 3. **没收干净**: 静默窗结束那一下由服务 [WakeWordService] 去 `spotter.reset(stream)`, 从干净的一段
 *    接着听
 *
 * **修的是"漏唤醒"的反面**: 漏唤醒多的根子是后处理太硬 (阈值高), 所以这次把声学那一头放开 (阈值
 * 降到 0.01), 再用这一层把放开之后多出来的重复报压回去 —— 两半缺一不可
 */
internal object WakeDecision {

    /** 现在这一帧报出来的词该不该算一次命中 */
    fun accepts(now: Long, mutedUntil: Long): Boolean = now >= mutedUntil

    /** 一次命中之后, 静默窗的**上限**落在哪 (等第一段出字来把它收回 [WakeTuning.HIT_COOLDOWN_MS]) */
    fun afterHit(now: Long): Long = now + WakeTuning.COMMAND_TAIL_MAX_MS

    /**
     * 命中之后的第一段出字投递了, 把静默窗收到哪一刻
     *
     * **不能比冷却下限更早**: 出字可能几百毫秒就到 (那一下正是命令尾最容易被误报的时候), 而
     * [WakeTuning.HIT_COOLDOWN_MS] 是主人点名的那个 1-2 s; 两者取晚的那个, 但不许晚过原来的上限
     *
     * 没有静默窗时 (`mutedUntil <= 0`) 回 0, 于是调用方那一笔账不用分支
     */
    fun afterDelivery(now: Long, hitAt: Long, mutedUntil: Long): Long {
        if (mutedUntil <= 0L) return 0L
        val floor = hitAt + WakeTuning.HIT_COOLDOWN_MS
        return if (now > floor) minOf(now, mutedUntil) else minOf(floor, mutedUntil)
    }
}
