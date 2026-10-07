package io.github.miuzarte.littlewhale.overlay

/**
 * 浮标这一摊的时长, **一份来源** (主人 2026-10-05 的要求: 时间参数集中定义, 不用散落的魔法数字)
 *
 * 改哪一个数会让什么跟着变, 每个常量上面都写着 —— 状态提示与收边**不是同一个数**: 混成一个的话
 * "提示消失"与"球收边"就会同时发生, 而那看起来像球自己闪了一下
 *
 * **2026-10-05 起这里只剩两个数了**: 主人说"关于球的所有操作都不要有提示", 于是那颗球上不再有
 * 任何自己冒出来的话 (召出提示、手势提示都拿掉了), 与"说一句什么"有关的时长跟着一起没了 ——
 * 留下的 [NOTE_MS] 管的是球上那三个字 (正在听 / 正在想 / 正在念) 显示多久
 *
 * 语音链那一半的闲置超时不在这个文件里: 它属于 `WakeWordService` (那边的
 * `WakeWordService.VOICE_IDLE_MS`), 这里只引一个名字, 不复制那个值
 */
internal object BallMinutes {

    /**
     * 球闲着多久就收边 (半隐)
     *
     * **主人 2026-10-05 原话: "空闲 3 秒后自动隐藏" 之后改成 "4s 改为 5s, 其它不变"** —— 所以这
     * 个数是 5 s, 而"3 秒"落在 [NOTE_MS] 上 (状态提示显示多久), 两件事各管各的
     *
     * 判据是"最后一次活动之后过了这么久", 活动包括: 有人碰它 (按下 / 拖完松手)、它的状态词换过、
     * 菜单开了关、输入通道开了关。有状态词 (正在听 / 正在想 / 正在念) 时不收
     */
    const val PEEK_IDLE_MS = 5_000L

    /**
     * 状态提示 (球上那三个字) 显示多久
     *
     * 主人 2026-10-05 点过名: **3 秒**。它管的是"多久之后不再拿状态词当主角", 与 [PEEK_IDLE_MS]
     * 是两笔账: 提示消失不等于球收边, 收边也不把语音链的状态清掉 (需求里那条"提示的显隐与功能的
     * 开合分开处理")
     */
    const val NOTE_MS = 3_000L

    /** 收边 / 滑回来的那条动画 (与那套内置动画的缺省时长同一个数) */
    const val EDGE_SNAP_MS = 200L

    /**
     * 收边那一刻的**实际**判据 (测试与排查用): 只读 [PEEK_IDLE_MS] 那一个数, **不另写一个字面量**
     *
     * 主人点过名的两个数 (提示 3 s 与空闲 5 s) 都只有一条来源, 而模拟器上要量"3 s 时还没收、5 s 后
     * 收了"这条判据得有个一致的读数 —— 这一条就是给它用的 (见 tools/lw-ball-check.ps1 的 -IdleMs)
     */
    fun idleDue(now: Long, lastActiveAt: Long): Boolean = now - lastActiveAt > PEEK_IDLE_MS

    /** 收起输入条之后等一拍再摆球: 键盘那一次收尾的 inset 回调就在这之后 */
    const val IME_SETTLE_MS = 400L

    /**
     * 两次点球之间至少要隔多久才算"两次"
     *
     * 主人 2026-10-06 报的: "点一次可能直接打开语音输入" —— 手指一抖出两下, 第一下召出、第二下就
     * 顺路进了语音输入。**平台的 `doubleTapTimeout` 是 300 ms** (系统自己拿它区分单击与双击), 所以
     * 这里取同一个量级但更宽一档: 350 ms 之内连着的第二下当作同一次点击, 只记一次账
     */
    const val TAP_GUARD_MS = 350L

    /**
     * 双击的窗口: 主人 2026-10-06 点名的**0.3 s**
     *
     * 它只在"正在想"那一档有用 (见 [BallTap] / [BallTaps]): 两下之间的间隔不超过它才算一次双击,
     * 而双击那一下要打断正在跑的那一轮。这个数**小于** [TAP_GUARD_MS] 是有意的 —— 双击窗口是"怎么算
     * 一下", 而防连击窗口是"第二下要不要当没发生", 两件事的时长本来就不是一个
     */
    const val DOUBLE_TAP_MS = 300L

    /**
     * "正在听"那一档的防连击窗口: 主人 2026-10-06 点名的**1 s**
     *
     * 那一档的点击是"把语音输入收回来"([BallAct.HUSH]), 而开语音的那一下也是点球 —— 手抖出第二下
     * 就会"刚开就关"。1 s 之内的第二下**只记一次账** (什么都不做), 1 s 之后照旧能关; 其余状态的窗口
     * 还是 [TAP_GUARD_MS]
     */
    const val LISTEN_GUARD_MS = 1_000L

    /**
     * 输入通道那块框没人碰多久就自己收掉: **20 s**
     *
     * 主人 2026-10-06 选的处置 (回复框也跟着空闲自己收): 回答到了框会自己张出来 ([OverlayService.deliverReply]),
     * 而框开着的那段时间球不收边 ([BallRest.wait] 的 `channel` 那条闸, 因为框是跟着球摆的)。于是
     * "回复到了"与"球半隐"这两件事会互相顶住 —— 框没人碰就自己收, 收掉之后球那一笔账从"框关掉"重新
     * 起算 ([OverlayService.closeChannel] 的 [BallRest] 记账), 5 s 后就收边
     *
     * 20 s 是"够读完那几条回复"的量级 (回复那一块最多 [BallBox.REPLY_LINES] 行, 2026-10-06 由 3 行
     * 加到 5 行), 比收边的 5 s 长得多是故意的: 框是给人读的, 而球只是"别挡着"。**有人用它就不算
     * 没人碰**: 打进一个字 (`BoxView.Listener.onTyping`)、滚动内容 (`onScroll`)、摸到框 (`onTouch`)
     * 与开语音 (服务那一侧 `parkChannel`) 都续期, 所以停手 20 s 才收 —— 框里的半截文字留着, 再张
     * 回来还在
     */
    const val BOX_IDLE_MS = 20_000L

    /**
     * 框外那两下的窗口: 与球上那个 [DOUBLE_TAP_MS] 同一个数 (**300 ms**)
     *
     * 主人 2026-10-06 点名的口径: "收窄成真双击, 超时的要重新计算, 而不是隔多久都累计" ——
     * 原来那本账是个纯粹的计数器 (点一下、过一会儿再点一下也会把关掉), 现在第一下只在窗口内有意义:
     * 超时的第二下**重新起算** (它是新的一下, 不是第一下的第二下)
     *
     * 与球上那个数是**同一个量级也是同一个值**: "双击"在这套东西里就只有一个时长, 两处不一样的话
     * 只会让人猜"到底多快才算" (判据见 [BallTaps.blankDouble])
     */
    const val BOX_DOUBLE_TAP_MS = 300L

    /**
     * "这一次召出"算多久之内还有效 —— 也就是"再点一下就说话"给主人留的那段时间
     *
     * 这一段是**点击序列那四段的核心** (2026-10-06 主人报的两条毛病合成的一条病根):
     *
     * - 球被召出之后 (半隐里点一下 / 菜单关掉之后), 紧随其后的下一击才是"第二次点击" → 开语音
     * - 超过这段时间再点, **只当又召出一次** (不会开口) —— 于是"点一次就开了语音"不可能发生
     *
     * 要长到"看完球滑回来再点"仍然算得上 (滑回来那条动画 200 ms, 加上人的反应), 又要短到"隔了很久
     * 随手一点"不会被当成那半句话的开头。3 s 与状态提示同一个量级
     */
    const val SUMMON_FRESH_MS = 3_000L

    /**
     * 这一击算不算"刚被召出来那一下之后的第一击"
     *
     * 纯函数, 没有设备也能量 (见 BallTest): 召出那一刻之后的 [SUMMON_FRESH_MS] 之内为真
     */
    fun summonIsFresh(now: Long, summonedAt: Long): Boolean =
        summonedAt != 0L && now - summonedAt in 0..SUMMON_FRESH_MS
}

/**
 * 点击序列与期望结果的对照表 —— 这一段是**纯函数**, 没有设备也能量 (见 BallTest)
 *
 * 需求原话里那几段最容易写错: "第一次点击只召出浮标"、"第二次点击才打开语音输入"、"召出后再次
 * 点击不弹提示、直接进入语音输入", 加上 2026-10-05 追加的"念回复时再点一次球可停止播报"。四种
 * 情形落在同一个 `onTap` 上, 靠的就是这里这一个 `when`:
 *
 * | 点之前的样子 | 点一下之后 |
 * | :-- | :-- |
 * | 半隐收着 ([BallPhase.RESTED]) | 滑回来, **不碰语音链** ([BallAct.SUMMON]) |
 * | 已经召出 ([BallPhase.SUMMONED]) | 进语音输入 ([BallAct.LISTEN]) |
 * | 正听着 ([BallPhase.VOICE]) | 收回语音输入 ([BallAct.HUSH]) |
 * | 正在念回答 (`speaking`) | **掐断播报** ([BallAct.STOP]) |
 *
 * **输入通道开着不再是"什么都不做"** (2026-10-06 改): 原来第一档是 `channelOpen -> NOTHING`, 于是
 * "键盘开着时点球"什么都不发生 —— 主人报的正是这一条 ("键盘输入有概率不能使用点击浮标的方式打开
 * 语音", 有概率 = 那块框还在的时候)。框有自己的位置、自己的焦点, 球不该替它挡住语音这一个入口;
 * 点球那一下照旧会把"框外双击"那本账清掉 (见 `OverlayService.ballListener` 的 onTap), 所以
 * 框该不该收还是由那本账说了算
 *
 * **"正在念"排在"正听着"前面**: 半双工那道闸在念的时候把麦克风整个关掉了, 所以那时人真正想按的是
 * "别念了" —— 而"正在念"这几个字就写在球上, 按下去掐断它是最直的那个理解
 *
 * **按住 (长按) 不走这里**: 它一直在球自己的触摸处理里 ([BallView.Listener.onLongPress]), 菜单
 * 与这几段点击互不干扰
 */
internal enum class BallPhase {
    /** 半隐收着: 只有一条边留在屏幕里 */
    RESTED,

    /** 全露着, 但没在听 (`idle`) */
    SUMMONED,

    /** 常驻语音那半条在跑 */
    VOICE,
}

/** 球上点一下该做什么 */
internal enum class BallAct {
    /** 半隐的球滑回来 */
    SUMMON,

    /** 开语音输入 (与常驻语音同一个入口) */
    LISTEN,

    /** 把语音输入收回来 */
    HUSH,

    /** 掐断正在念的回答 (播报) */
    STOP,

    /** 什么都不做 */
    NOTHING,
}

internal object BallTouch {
    fun act(phase: BallPhase, speaking: Boolean, channelOpen: Boolean): BallAct = when {
        // 念回答的时候那一下是"别念了" (主人 2026-10-05 追加的一条)
        speaking -> BallAct.STOP
        phase == BallPhase.VOICE -> BallAct.HUSH
        phase == BallPhase.RESTED -> BallAct.SUMMON
        else -> BallAct.LISTEN
    }

    /**
     * 这一下 [BallAct.LISTEN] 该不该**当场**开语音
     *
     * 两条: 刚被召出来那一下之后的第一击 (主人点名的"点两次才说话"), 或者**回复框在屏上且有回复**
     * —— 那时人已经在那块框上说话了, 再要他点两下是多余的 (2026-10-06 那条例外: "有回复窗时也可以
     * 点击小球进行语音输入")。判据是纯函数 (见 BallTest), 调用方只按它的答案走
     */
    fun opensVoiceNow(summonFresh: Boolean, replyBox: Boolean): Boolean = replyBox || summonFresh
}

/**
 * 这一下点击算哪一种
 *
 * 三个词就是全部答案, 而它比 [BallTouch.act] 先跑一步: "双击"与"单点"是**两个不同的手势**, 得先
 * 分开, 再拿单点那一下去问 [BallTouch.act] 该做什么
 */
internal enum class BallTap {
    /** 一次算数的点击: 走原来的四段 ([BallTouch.act]) */
    SINGLE,

    /** 双击的第二下 (只在"正在想"那一档): 打断 [BallAct] 那一套之外的那一个动作 */
    DOUBLE,

    /**
     * **三击: 打开键盘输入那块框** (主人 2026-10-07: "加上三击 ball 打开键盘输入, ball 菜单的
     * 键盘输入可以删除")
     */
    TRIPLE,

    /** 离上一次太近, 当同一次点击 (什么都不做, 只记一行) */
    TOO_SOON,
}

/**
 * 点击序列外面那一层: 双击、三击与防连击
 *
 * 判据是四个输入 (**球现在说的是哪三个字** + 这一次 / 上一次的时刻 + 这一下算进第几次连击), 所以
 * 没有设备也能量 (见 BallTest) —— 而它恰恰是最容易在真机上"看着差不多"的地方: 双击窗口比防连击窗口
 * 宽一点, 单点的那一下就会被双击吃掉; 窄一点, 手一抖就算两次手势
 *
 * 三条规则:
 *
 * - **两下贴着 (300 ms 之内, [BallMinutes.DOUBLE_TAP_MS]) = 双击** ([BallTap.DOUBLE]): 只有"正在想"
 *   那一档有动作 (打断正在跑的那一轮), 别的档里第二下什么都不做 —— 于是"两下贴着"对主人的净效果
 *   还是原来那一次单击
 * - **三下连着 (每一下都贴着上一记) = 三击** ([BallTap.TRIPLE]): 打开键盘输入那块框。这是菜单里
 *   那行「键盘输入」的替身 (那行删掉了)
 * - 离上一记超过连击窗口但**还在防连击窗口里**的那一下 = [BallTap.TOO_SOON]: 什么都不做, 只记一行
 *
 * **净效果那一层要想清楚**: 单击那一下是**当场**做掉的 (只有"正在想"那一档押后 300 ms 等双击),
 * 所以连击计数只是"把手势认出来", 不取消第一下已经做的事 —— 三击因此是"召出/开语音那一下 + 关掉语音
 * 窗口 + 打开键盘输入" ([OverlayService.onTap] 里那一支), 而不是"从头到尾什么都没发生"
 */
internal object BallTaps {

    /** 这一档的防连击窗口: 正在听 1 s, 其余 350 ms */
    fun guard(word: BallWord?): Long =
        if (word == BallWord.LISTENING) BallMinutes.LISTEN_GUARD_MS else BallMinutes.TAP_GUARD_MS

    /**
     * 这一下算哪一种
     *
     * @param lastTapAt 上一次**算数的**点击时刻 (0 = 还没有过)
     * @param chain 这一下之前那串连击已经数到几次 (0 = 上一记之后已经隔开了)
     *
     * 判据的先后就是它的意思: **先看"是不是接着上一记"** (300 ms 之内), 是就往下数 (第 2 下是双击,
     * 第 3 下起是三击 —— 四连击也算三击, 那只是主人多戳了一下); 不是再看"是不是离得太近" (防连击那
     * 个窗口), 太近的**既不计数也不动作**; 都不是才是新的一次单击
     */
    fun kind(word: BallWord?, now: Long, lastTapAt: Long, chain: Int = 0): BallTap {
        if (lastTapAt == 0L) return BallTap.SINGLE
        val gap = now - lastTapAt
        return when {
            gap <= BallMinutes.DOUBLE_TAP_MS -> if (chain + 1 >= 3) BallTap.TRIPLE else BallTap.DOUBLE
            gap < guard(word) -> BallTap.TOO_SOON
            else -> BallTap.SINGLE
        }
    }

    /**
     * 框外那两下算不算一次双击: 真 = 这一下就是窗口内的第二下 (**关掉那块框**)
     *
     * 主人 2026-10-06 的口径是"收窄成真双击, 超时的要重新计算": 假的那一支**不是"差一点就关掉"**,
     * 而是"它是新的一下" —— 调用方在那一支上把窗口重新起算 ([OverlayService.noteBlankTap]), 所以
     * 点一下、过一会儿再点一下**永远不会**把框关掉, 而超时那一下之后紧跟的半秒内再点一下就关
     *
     * 与球上那个双击判据 ([kind]) 分开写, 是因为它们答的不是同一个问题: 那一个是"球上这一下算哪种
     * 手势", 这一条是"框外这一下是不是第二下"
     */
    fun blankDouble(now: Long, lastAt: Long): Boolean =
        lastAt != 0L && now - lastAt <= BallMinutes.BOX_DOUBLE_TAP_MS
}

/**
 * 闲置那一笔账该不该重算, 以及为什么 —— **纯函数, 没有设备也能量** (见 BallTest)
 *
 * 这是 [OverlayService.refresh] 那 400 ms 一拍里唯一的一处记账判据, 而它成立的理由是 2026-10-06 主人
 * 报的那一条 ("ball 半隐藏功能又失效了"): 原来那一处写的是
 *
 * ```kotlin
 * if (word != null || capturing) { if (prevWord != word) reset("state change") }
 * else { if (prevWord != null) reset("voice off") }
 * ```
 *
 * 而 `prevWord` 只在"字换了"那一下被赋值 —— 于是一旦出现过任何一个状态词 (正在听 / 正在想 / 正在念),
 * `prevWord` 就**再也不落**, 每一拍都进"voice off"那一支把 `lastActiveAt` 推到当下, `idleDue` 永远
 * 为假。真机上量到的指纹是 13.7 h 里 `peeked=true` 只出现过 14 次, 而 `voice off` 与 `waiting` 两行
 * 互相接替了 8000 多次 (见 docs/floating-input.md)
 *
 * 现在两处都判**上一拍**那个字 ([previous]): "字刚落下"与"字刚换"各只记一次账, 记完就过去
 */
internal object BallIdle {

    /** 状态词换了: 有状态那一档开始 / 换了一个字 */
    const val STATE = "state change"

    /** 状态词落下 (回到空闲那一档): 那一下本身算一次活动, 5 s 从这里起算 */
    const val VOICE_OFF = "voice off"

    /**
     * 这一拍要记哪一笔账 (null = 不记, 计时照走)
     *
     * [previous] 必须是**上一拍**那个字, 不是"上一次变化时的字" —— 那一个字的区别就是这条判据的全部
     */
    fun reset(next: BallWord?, capturing: Boolean, previous: BallWord?): String? = when {
        next != null || capturing -> if (next != previous) STATE else null
        previous != null -> VOICE_OFF
        else -> null
    }
}

/**
 * 球现在该收边还是该待着, 以及**为什么**
 *
 * 这是那个 400 ms 一拍里唯一的一处判断 (见 `OverlayService.refresh` 与它调用的 `waitNow`): 原来
 * "要不要收边"散在两处 —— `peek()` 里三条闸、`refresh()` 里再多两条 —— 于是每加一条就多一个只在一处
 * 生效的闸, 而 2026-10-06 主人报的两条 (键盘输入时它居然算空闲, 以及"说话"点两次之后它不再收边)
 * 都是这么来的
 *
 * 六条闸各自的理由 (顺序就是报出来的顺序, 排查时先看那一个):
 *
 * | 闸 | 什么时候算 | 为什么不算空闲 |
 * | :-- | :-- | :-- |
 * | `held` | 手指正按着 / 拖着 | 人在用它 |
 * | `menu` | 菜单开着 | 那一眼正在选, 收起去就是"点了个空的" |
 * | `listening` | 语音链正在跑 (`VoiceState.capturing`) | 主人正在说话, 那正是它最该在场的时候 |
 * | `keyboard` | 输入框有焦点 / 键盘在屏上 | **键盘输入不属于空闲** (主人 2026-10-06 原话) |
 * | `channel` | 输入通道那块框在窗口上 | 回答会自己把框张出来 (那时它**不抢焦点**, 键盘没起) —— 少了这一条, 球会在回答出现的同时半隐溜走, 而框跟着球走 |
 *
 * 这几条都**不记活动那一笔账**: 计时由 `OverlayService` 按"这一档开始 / 结束"去重写 (见
 * `OverlayService.resetIdleWait`), 因为它知道"上一拍是怎么判的"。这里只回答"现在这一档对吗"
 */
internal object BallRest {

    /**
     * 两拍之间过多久才真的收边
     *
     * 收边的判据是"闲置超过 [BallMinutes.PEEK_IDLE_MS]"(严格大于), 而这一拍是 [OverlayService.TICK_MS]
     * 一次 —— 两者相加才是"点完之后最早什么时候看见它收起来"。那个数不进任何提示, 只用来写注释与
     * 解释"为什么 5.0 s 时还看得见"
     */
    const val TICK_PAD_MS = 400L

    /** 球现在在干嘛 (`waiting` / `asleep` / `peeking` 三选一) */
    fun wait(
        phase: BallPhase,
        peeked: Boolean,
        dragging: Boolean,
        menuOpen: Boolean,
        listening: Boolean,
        keyboard: Boolean,
        channelOpen: Boolean,
        idleDue: Boolean,
    ): BallWait = when {
        peeked -> BallWait(peek = false, reason = "asleep")
        dragging -> BallWait(peek = false, reason = "held")
        menuOpen -> BallWait(peek = false, reason = "menu")
        listening -> BallWait(peek = false, reason = "listening")
        keyboard -> BallWait(peek = false, reason = "keyboard")
        channelOpen -> BallWait(peek = false, reason = "channel")
        idleDue -> BallWait(peek = true, reason = "peeking")
        // 剩下的只有"全露着但没在听"那一档 ([BallPhase.SUMMONED]) 与还没到点的 [BallPhase.RESTED]:
        // 后者已经在收着那一支里被 `peeked` 接住了, 所以走到这里一律是"还等着"
        else -> BallWait(peek = false, reason = "waiting")
    }

    /** 这一档是不是"人正在用它": 是的话调用方不去动那笔空闲账 —— 账该从它**结束**那一刻起算 */
    fun isHeld(wait: BallWait): Boolean =
        wait.reason == "held" || wait.reason == "menu" || wait.reason == "listening" ||
            wait.reason == "keyboard" || wait.reason == "channel"
}

/** [BallRest.wait] 的答案: 收不收边, 以及这一拍为什么这么判 */
internal data class BallWait(val peek: Boolean, val reason: String)

/**
 * 设置页那个开关该画成什么 —— **纯函数, 没有设备也能量** (见 BallSwitchTest)
 *
 * 那一个"浮标一直在"的开关有两个来源, 而它们会分家:
 *
 * - [remembered] 是**存盘的记号** (`ball-on`): 它管的是"下一次应用起来要不要把球放出来"
 * - [showing] 是**球此刻真的在不在屏幕上** (`OverlayState.showing`): 菜单里、通知栏上、或者服务
 *   自己那边都能把球收掉, 而那几条路都不会回来改设置页里那一份记忆值
 *
 * 只认 [remembered] 就是 2026-10-06 报上来的那个样子: 从菜单里把球关掉, 屏幕上球没了, 而这个开关
 * 照旧画着"开" —— 人看到的是"两个入口各说各话"。所以判据是"**球真的在, 或者它记着要在**": 多认
 * 前者的那一半, 少认的那一半会在下一次 `ensure()` 里补上 (它照着 [remembered] 放球)
 */
internal object BallSwitch {
    fun onFor(showing: Boolean, remembered: Boolean): Boolean = showing || remembered
}
