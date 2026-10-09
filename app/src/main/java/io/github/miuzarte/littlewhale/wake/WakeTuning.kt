package io.github.miuzarte.littlewhale.wake

/**
 * 唤醒词这一摊**唯一的一份参数表** (纪律: 参数先落表再写码)
 *
 * 原来那四个数散在两处 ([io.github.miuzarte.littlewhale.tool.LwWakeWord] 与 [WakeWordService]
 * 各一份 `DEFAULT_THRESHOLD` / `DEFAULT_SCORE`), 两边只要有一边忘了改, 就会出现"设置页念的是一个数、
 * 守的是另一个数"。现在集中在这里, 通道那条 `op=start` 仍可以逐个覆盖 (真机回调要用), 缺省取的就是
 * 下面这四个
 *
 * **2026-10-09 调参的来由** (主人报"漏唤醒太多, 有时不是模型没分, 而是后处理太硬"):
 *
 * | 数 | 原值 | 现在 | 为什么 |
 * | :-- | :-- | :-- | :-- |
 * | [KEYWORDS_THRESHOLD] | 0.25 | **0.01** | 声学阈值, 越低越容易报 —— 它是"后处理太硬"那一条的解 |
 * | [KEYWORDS_SCORE] | 1.5 | **3.0** | 词表里每个 token 的加分, 让关键词那条路更压得住别的路径 |
 * | [NUM_TRAILING_BLANKS] | 2 | **1** | 命中之后要挂几帧静音才收 —— **这一条是"喊完接着就说"能不能唤得醒的关键**, 见下面 |
 * | [MAX_ACTIVE_PATHS] | 4 | **16** | modified beam search 的束宽, 越宽越不容易把关键词那条路剪掉 |
 *
 * **分数 EMA 不做, 也做不了**: 我们钉的这份 sherpa-onnx AAR (1.13.8, upstream 最新也是) 里
 * `KeywordSpotterResult` 只有 keyword / tokens / timestamps, **不吐任何分数** (`// TODO: Add more
 * fields`), 所以"对分数做 EMA"拿不到输入值。能做的那半件事就是用 [NUM_TRAILING_BLANKS] 当模型内建的
 * 帧确认, 再加上 [HIT_COOLDOWN_MS] 与 [COMMAND_TAIL_MAX_MS] 这一层判定去抖, 见 [WakeDecision]
 *
 * **为什么 [NUM_TRAILING_BLANKS] 是 1 而不是 3** (2026-10-09 第二轮, 主人说"还是难唤醒"): 那个数说的
 * 不是"确认几帧", 而是 **sherpa 的解码器要求关键词 tokens 之后还要出现这么多帧空白才肯收** (判据在
 * upstream `transducer-keyword-decoder.cc`: `best_hyp.num_trailing_blanks > num_trailing_blanks_`,
 * 一帧 subsampling 之后是 40 ms)。所以它**越大越难唤醒**, 而主人说话的常态正是"肥鱼肥鱼"后面
 * 紧接一句指令、中间没有停顿 —— 3 就等于要求那 160 ms 静音, 那一句永远等不到。1 (要 80 ms) 是
 * "不要求停顿、又不至于在半截词上收"的那一档, 多出来的抖动交给 [HIT_COOLDOWN_MS] 那一层压
 */
internal object WakeTuning {

    /** 声学阈值 (概率), 越低越容易触发、误报也越多, 缺省 0.01 (旧值 0.25) */
    const val KEYWORDS_THRESHOLD = 0.01f

    /** 词表每个 token 的加分, 缺省 3.0 (旧值 1.5; 2026-10-09 第二轮从 2.0 抬上来) */
    const val KEYWORDS_SCORE = 3.0f

    /** 关键词之后要挂几帧静音才收, 缺省 1 (旧值 2; 越大越难唤醒, 见上面那段) */
    const val NUM_TRAILING_BLANKS = 1

    /** modified beam search 的束宽, 缺省 16 (旧值 4; 2026-10-09 第二轮从 8 抬上来) */
    const val MAX_ACTIVE_PATHS = 16

    /**
     * 命中之后的**冷却下限**: 这么长时间里再报一次也不算数
     *
     * 主人 2026-10-09 点名 1-2 s; 取 1.5 s, 它是"同一个词被连着报两遍"那类抖动的兜底
     */
    const val HIT_COOLDOWN_MS = 1_500L

    /**
     * **一句话投出去之后**还给窗口留多久 (2026-10-09 主人: "说完后发送问题, 正在听状态没有结束,
     * 应该结束掉才对" / "发送问题后就可以收掉了")
     *
     * 这一档是"一次唤醒 = 一句话"的落点: 那句话说完了、出字也投出去了, 窗口就不该再挂着
     * [WakeWordService.VOICE_IDLE_MS] 那 10 s 了。规则是三条一起看:
     *
     * - **还在说就继续听**: 出字之后 VAD 每报一次人声都给这一笔账续期 (主人 2026-10-09: "要看'还在不在
     *   说'") —— 一句话被切成两段、或者他接着说第二句, 都不会被从中间收掉
     * - **一停下来就收**: 最后一次人声之后只等这一小段 (看门狗节拍 500 ms, 所以实际是"停下后
     *   0.3-0.8 s 收"), 不再等那 10 s
     * - **不再挂起**: 这一档里"助手正在想 / 正在念"不算数 (那是 [WakeWordService.VOICE_IDLE_MS] 那一档
     *   的规则) —— 否则问一句就等回答的状态下窗口永远收不掉, 正是主人报的那条
     *
     * 代价说清楚: 一句话被 VAD 从中间切成两段 (停顿超过 0.8 s) 时, **第一段投出去之后窗口就收了**,
     * 后半句不会被听见 —— 那要重新喊一次唤醒词。主人 2026-10-09 选了这一档 ("发送问题后就可以收掉了")
     */
    const val SENT_TAIL_MS = 300L

    /**
     * 命中之后"命令尾"最多静默多久
     *
     * 主人 2026-10-09: "如果唤醒词后面紧接指令, 不要把指令前半段误当成下一轮唤醒"。所以命中之后
     * KWS 接着吃音频但**结果一律不算数**, 直到这一句话的第一段出字投递 (正常几百毫秒到两三秒)
     *
     * **上限 2026-10-09 第二轮从 5 s 收到 2 s** (主人说"还是难唤醒"): 那 5 s 里喊第二遍是不算数的,
     * 而"喊一次没反应就再喊一遍"正是最常见的动作 —— 于是那个上限本身变成了难唤醒。真正要盖住的只是
     * 唤醒词自己那点尾音 (几百毫秒), 2 s 足够
     */
    const val COMMAND_TAIL_MAX_MS = 2_000L
}
