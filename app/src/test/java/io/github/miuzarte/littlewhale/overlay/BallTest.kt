package io.github.miuzarte.littlewhale.overlay

import io.github.miuzarte.littlewhale.wake.WakeWordService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮标那几处边界的判据: 状态词该说哪个、松手贴哪边、半隐藏多少、拖出去多远、键盘压不压得住
 *
 * 这些都是**没有设备也能量**的算式, 而它们恰恰是最容易在真机上"看着差不多"却差一个球的地方:
 * 半隐藏多了一点点就点不到, 拖动少钳一下球就飞出去, 吸附判反了它永远停在对面 —— 真机上这些症状
 * 都长得像"手感有点怪", 说不到具体哪一个数上
 *
 * 参考的是开源那一份 (Petterpx/FloatingX) 的约定: 半隐是**比例** (`FxHalfHide`, demo 用 0.3),
 * 吸附取**剩余距离最近**的边 (`nearestEdge`), 拖动允许暂时越界再回弹 (`rebound`)
 */
class BallTest {

    private val ball = 144 // 48 dp @ 3x, vivo 那块屏的密度
    private val screen = 1260

    /* ── 状态词: 在念 > 在想 > 在听 ─────────────────────────────────────────── */

    @Test
    fun `什么都没在跑时球上画的是那个标`() {
        assertNull(BallStatus.wordFor(speaking = false, thinking = false, listening = false))
    }

    @Test
    fun `常驻语音在跑时说的是正在听`() {
        assertEquals(BallWord.LISTENING, BallStatus.wordFor(speaking = false, thinking = false, listening = true))
    }

    /**
     * 一轮在跑而麦克风没开: 这是"打字问的"那一情形, 它照样要显示"正在想" —— 用眼睛盯着球的人
     * 此刻等的就是回答
     */
    @Test
    fun `宿主说一轮在跑时说的是正在想`() {
        assertEquals(BallWord.THINKING, BallStatus.wordFor(speaking = false, thinking = true, listening = false))
    }

    /**
     * 念回答那几秒里半双工那道闸把麦克风整个关掉了, 所以"正在听"与"正在念"可以同时为真 ——
     * 这时说的是"正在念", 因为那才是此刻真正发生的事 (听见自己念的话会被录回去, 那不是"在听")
     */
    @Test
    fun `念回答的时候正在念盖过正在听`() {
        assertEquals(BallWord.SPEAKING, BallStatus.wordFor(speaking = true, thinking = false, listening = true))
    }

    @Test
    fun `念回答的时候正在念也盖过正在想`() {
        assertEquals(BallWord.SPEAKING, BallStatus.wordFor(speaking = true, thinking = true, listening = true))
    }

    @Test
    fun `正在想盖过正在听`() {
        assertEquals(BallWord.THINKING, BallStatus.wordFor(speaking = false, thinking = true, listening = true))
    }

    /* ── 吸附: 取剩余距离最近的那条边 ───────────────────────────────────────── */

    @Test
    fun `贴左边时靠左半屏的都吸到左边`() {
        assertEquals(0, BallGeometry.snapX(0, screen, ball))
        assertEquals(0, BallGeometry.snapX(screen / 2 - ball, screen, ball))
    }

    @Test
    fun `贴右边时靠右半屏的都吸到右边`() {
        assertEquals(screen - ball, BallGeometry.snapX(screen - ball, screen, ball))
        assertEquals(screen - ball, BallGeometry.snapX(screen / 2 + 1, screen, ball))
    }

    /**
     * 屏幕中点那条线: 两边的剩余距离一样, 取左边 —— 这是个**确定的**选择 (不是"随机哪边"),
     * 单测把它钉住, 免得以后改成另一个方向时没人发现
     */
    @Test
    fun `正中间时吸到左边`() {
        val middle = (screen - ball) / 2
        assertEquals(0, BallGeometry.snapX(middle, screen, ball))
    }

    /**
     * 拖出屏幕外之后松手: 距离是"到那条边要走的距离", 负的按 0 算不了 —— 藏出去一半的球
     * (x 是负的) 必须还认得出自己贴的是左边, 否则松手那一刻它会飞到对面去
     */
    @Test
    fun `拖出屏幕外时贴的是它自己那一边`() {
        assertEquals(0, BallGeometry.snapX(-40, screen, ball))
        assertEquals(screen - ball, BallGeometry.snapX(screen + 40, screen, ball))
    }

    @Test
    fun `吸附之后知道自己在哪一边`() {
        assertEquals(BallGeometry.EDGE_LEFT, BallGeometry.edgeFor(0, screen, ball))
        assertEquals(BallGeometry.EDGE_RIGHT, BallGeometry.edgeFor(screen - ball, screen, ball))
    }

    /* ── 半隐: 比例, 不是像素 ───────────────────────────────────────────────── */

    @Test
    fun `半隐是球的一半在屏幕外`() {
        val hidden = (ball * BallGeometry.HALF_HIDE).toInt()
        assertEquals(0.5f, BallGeometry.HALF_HIDE, 0.0001f)
        assertEquals(72, hidden) // 48 dp 的球在 3 倍密度下是 144 px, 藏一半 = 72 px
        // 贴左边就往屏幕外推 (x 是负的), 贴右边就往外推一把 —— 两边都是"那一份留在框外"
        assertEquals(-hidden, BallGeometry.peekX(0, BallGeometry.EDGE_LEFT, ball))
        assertEquals(screen - ball + hidden, BallGeometry.peekX(screen - ball, BallGeometry.EDGE_RIGHT, ball))
    }

    /** 贴右边时 x 要**超出右边界** (screen - ball), 不是停在边界上 */
    @Test
    fun `右贴边时窗口越出右边界`() {
        val restX = screen - ball
        val peeked = BallGeometry.peekX(restX, BallGeometry.EDGE_RIGHT, ball)
        assertEquals(true, peeked > restX)
        assertEquals(true, peeked > screen - ball)
    }

    /** 藏一半之后还得点得到: 剩下的那一半照旧在屏幕里 */
    @Test
    fun `半隐之后还剩一半可以点`() {
        val visible = ball - (ball * BallGeometry.HALF_HIDE).toInt()
        assertEquals(true, visible >= ball / 2)
    }

    /**
     * 拖动放大那一下不许被窗口裁掉
     *
     * 窗口开的是 view 的测量尺寸 (48 dp), 球体画在里面; 放大 1.06 倍之后必须仍在窗口里 —— 这正是
     * 主人 2026-10-05 报的"球形四边会超出边框, 四边被削掉一部分"
     */
    @Test
    fun `放大之后球还在窗口里`() {
        assertEquals(true, BallView.BALL_DRAW_DP < BallView.BALL_SIZE_DP)
        assertEquals(true, BallView.BALL_DRAW_DP * 1.06f <= BallView.BALL_SIZE_DP)
    }

    /* ── 拖动: 允许暂时越界 (rebound) ───────────────────────────────────────── */

    @Test
    fun `拖动可以把它推出屏幕到半隐的位置`() {
        val hidden = (ball * BallGeometry.HALF_HIDE).toInt()
        assertEquals(-hidden, BallGeometry.dragX(-9999, screen, ball))
        assertEquals(screen - ball + hidden, BallGeometry.dragX(9999, screen, ball))
    }

    @Test
    fun `拖动中屏幕里面照旧跟手`() {
        assertEquals(300, BallGeometry.dragX(300, screen, ball))
    }

    /* ── 纵向与键盘 ─────────────────────────────────────────────────────────── */

    @Test
    fun `纵向不许出屏`() {
        assertEquals(0, BallGeometry.clampY(-20, 2800, ball))
        assertEquals(2800 - ball, BallGeometry.clampY(9999, 2800, ball))
        assertEquals(700, BallGeometry.clampY(700, 2800, ball))
    }

    /** 拿不到 inset (0 = 没有键盘, 或者这一屏不上报) 时什么都不动: 猜一个高度会让球跳到半空中 */
    @Test
    fun `没有键盘时不动它`() {
        assertEquals(2000, BallGeometry.clampAboveIme(2000, 0, 2800, ball))
    }

    @Test
    fun `键盘起来时把它抬到键盘上沿之上`() {
        val ime = 900
        val keyboardTop = 2800 - ime
        assertEquals(keyboardTop - ball, BallGeometry.clampAboveIme(2600, ime, 2800, ball))
    }

    @Test
    fun `已经在键盘上方的球不动`() {
        assertEquals(300, BallGeometry.clampAboveIme(300, 900, 2800, ball))
    }

    /** 键盘比屏幕还高 (横屏 + 手写键盘那种) 时算出来的位置要落在屏幕里, 不能是负数 */
    @Test
    fun `键盘高过屏幕时球停在顶上`() {
        assertEquals(0, BallGeometry.clampAboveIme(500, 2800, 2800, ball))
    }

    /* ── 换屏之后按存盘的边重算 ─────────────────────────────────────────────── */

    @Test
    fun `存的是边与 y, 换宽度之后重算 x`() {
        assertEquals(0, BallGeometry.xForEdge(BallGeometry.EDGE_LEFT, 2800, ball))
        assertEquals(2800 - ball, BallGeometry.xForEdge(BallGeometry.EDGE_RIGHT, 2800, ball))
    }

    /** 认不出来的边当成右边 (存盘里那个值坏了也不该让球跑到左上角去) */
    @Test
    fun `认不出的边落在右边`() {
        assertEquals(screen - ball, BallGeometry.xForEdge("up", screen, ball))
    }

    /* ── 点击序列: 第一次只召出, 第二次才说话 (批次 5 那三段) ─────────────── */

    /**
     * 需求原话的三段: "第一次点击只召出浮标, 第二次点击才打开语音输入, 召出后再次点击不弹提示、
     * 直接进入语音输入"
     *
     * 第一段与第二段在 `act` 里是**两个不同的输入** (`RESTED` 与 `SUMMONED`), 而"不弹提示"那一条
     * 从 2026-10-05 起是全局的 (主人: 关于球的所有操作都不要有提示) —— 这里钉住的是"哪一档该开
     * 语音、哪一档只召出", 那是三段里最容易点错的地方
     */
    @Test
    fun `半隐收着时点一下只召出`() {
        assertEquals(BallAct.SUMMON, BallTouch.act(BallPhase.RESTED, speaking = false, channelOpen = false))
    }

    @Test
    fun `召出之后再点一下就进语音输入`() {
        assertEquals(BallAct.LISTEN, BallTouch.act(BallPhase.SUMMONED, speaking = false, channelOpen = false))
    }

    @Test
    fun `正听着的时候点一下是收回来`() {
        assertEquals(BallAct.HUSH, BallTouch.act(BallPhase.VOICE, speaking = false, channelOpen = false))
    }

    /**
     * 念回答的时候那一下是"别念了" (主人 2026-10-05 追加: "念回复时再点一次球可停止播报")
     *
     * 它压在"正听着"与"收着"两档**前面**: 半双工那道闸在念的时候把麦克风整个关掉了, 那时人真想按
     * 的就是"停", 而球上写着"正在念"
     */
    @Test
    fun `正在念的时候点一下是掐断播报`() {
        assertEquals(BallAct.STOP, BallTouch.act(BallPhase.VOICE, speaking = true, channelOpen = false))
        assertEquals(BallAct.STOP, BallTouch.act(BallPhase.SUMMONED, speaking = true, channelOpen = false))
        assertEquals(BallAct.STOP, BallTouch.act(BallPhase.RESTED, speaking = true, channelOpen = false))
    }

    /**
     * **输入通道开着时球那一下照旧算话** (2026-10-06 改的那一条)
     *
     * 原来这里是"什么都不做" (`channelOpen -> NOTHING`), 而主人报的正是它: "键盘输入有概率不能使用
     * 点击浮标的方式打开语音" —— 有概率 = 那块框还开着的时候。框有自己的位置与焦点, 球不该替它挡
     * 住语音这一个入口; 框该不该收仍由"框外那一下双击"那本账管 (见 `BallTaps.blankDouble`)
     */
    @Test
    fun `通道开着时点球也认那几段`() {
        assertEquals(BallAct.SUMMON, BallTouch.act(BallPhase.RESTED, speaking = false, channelOpen = true))
        assertEquals(BallAct.LISTEN, BallTouch.act(BallPhase.SUMMONED, speaking = false, channelOpen = true))
        assertEquals(BallAct.HUSH, BallTouch.act(BallPhase.VOICE, speaking = false, channelOpen = true))
        assertEquals(BallAct.STOP, BallTouch.act(BallPhase.VOICE, speaking = true, channelOpen = true))
    }

    /**
     * **回复框在屏上且有回复时, 点球一下就直接开口** (2026-10-06 那条例外)
     *
     * 平常那一档是"先召出、再点一下才说话" ([BallMinutes.summonIsFresh]), 而框在屏上时人已经在那块
     * 框上说话了 —— 再要他点两下是多余的。三条判据把两档都钉住: 框在就开、刚召出也开、其余不开
     */
    @Test
    fun `回复框在屏上时点球一下就直接开口`() {
        assertEquals(true, BallTouch.opensVoiceNow(summonFresh = false, replyBox = true))
        assertEquals(true, BallTouch.opensVoiceNow(summonFresh = true, replyBox = false))
        assertEquals(false, BallTouch.opensVoiceNow(summonFresh = false, replyBox = false))
    }

    /* ── 收边那一档: 六条闸与"为什么没收边" ───────────────────────────────── */

    /** 那几个读数全"不忙"、而且闲够 5 s 了: 这一拍才真的收边 */
    @Test
    fun `闲够 5 秒而且没人用它才收边`() {
        val wait = BallRest.wait(
            phase = BallPhase.SUMMONED,
            peeked = false,
            dragging = false,
            menuOpen = false,
            listening = false,
            keyboard = false,
            channelOpen = false,
            idleDue = true,
        )
        assertEquals(true, wait.peek)
        assertEquals("peeking", wait.reason)
    }

    @Test
    fun `还没闲够就不收`() {
        val wait = BallRest.wait(BallPhase.SUMMONED, peeked = false, dragging = false, menuOpen = false,
            listening = false, keyboard = false, channelOpen = false, idleDue = false)
        assertEquals(false, wait.peek)
        assertEquals("waiting", wait.reason)
    }

    /**
     * 主人 2026-10-06 报的第一条: "键盘输入时, 浮标会空闲半隐藏, 键盘输入时不属于空闲"
     *
     * 这一条钉的是**判据本身**: 键盘那一档为真时不管闲了多久都不收, 而且报出来的原因就是 `keyboard`
     * (排查时靠这一个字认账)
     */
    @Test
    fun `键盘在用就不算空闲`() {
        val wait = BallRest.wait(BallPhase.SUMMONED, peeked = false, dragging = false, menuOpen = false,
            listening = false, keyboard = true, channelOpen = false, idleDue = true)
        assertEquals(false, wait.peek)
        assertEquals("keyboard", wait.reason)
        assertEquals(true, BallRest.isHeld(wait))
    }

    /**
     * 主人 2026-10-06 定的第四条: **框发出问题后立刻关, 回答到了自动打开 (不抢焦点)**
     *
     * 自动弹出的那一档**不弹键盘**, 所以"键盘在用"那条闸不成立 —— 少了 `channel` 这条, 球会在回答
     * 出现的同时半隐溜走, 而框是跟着球摆的 (那一下看起来就是"回答一闪就跑到屏幕边上了")
     */
    @Test
    fun `回答弹出来的框开着时也不收边`() {
        val wait = BallRest.wait(BallPhase.SUMMONED, peeked = false, dragging = false, menuOpen = false,
            listening = false, keyboard = false, channelOpen = true, idleDue = true)
        assertEquals(false, wait.peek)
        assertEquals("channel", wait.reason)
        assertEquals(true, BallRest.isHeld(wait))
        // 框一关 (框外双击) 就又回到普通那一档
        assertEquals("peeking", BallRest.wait(BallPhase.SUMMONED, peeked = false, dragging = false,
            menuOpen = false, listening = false, keyboard = false, channelOpen = false, idleDue = true).reason)
    }

    /** 语音链在跑的那一档: 它在场才有意义 (球上写着"正在听") */
    @Test
    fun `正在听的时候不收边`() {
        val wait = BallRest.wait(BallPhase.VOICE, peeked = false, dragging = false, menuOpen = false,
            listening = true, keyboard = false, channelOpen = false, idleDue = true)
        assertEquals(false, wait.peek)
        assertEquals("listening", wait.reason)
    }

    @Test
    fun `手指按着与菜单开着都算在用`() {
        assertEquals("held", BallRest.wait(BallPhase.SUMMONED, peeked = false, dragging = true,
            menuOpen = false, listening = false, keyboard = false, channelOpen = false, idleDue = true).reason)
        assertEquals("menu", BallRest.wait(BallPhase.SUMMONED, peeked = false, dragging = false,
            menuOpen = true, listening = false, keyboard = false, channelOpen = false, idleDue = true).reason)
    }

    /**
     * 已经在半隐那一档了就不要再收一次 (第一条判据就是它): 少了它, 每一拍都会重新触发一次收边动画,
     * 而"收边发生过几次"那个读数就再也不是证据了
     */
    @Test
    fun `已经收着的不再收第二次`() {
        val wait = BallRest.wait(BallPhase.RESTED, peeked = true, dragging = false, menuOpen = false,
            listening = false, keyboard = false, channelOpen = false, idleDue = true)
        assertEquals(false, wait.peek)
        assertEquals("asleep", wait.reason)
    }

    /**
     * 主人 2026-10-06 报的第三条 ("说话"点两次之后球不再空闲半隐) 的**序列**算术
     *
     * 那两下走完之后的档必须是"全露着但没在听" ([BallPhase.SUMMONED]), 语音那一档要随着 `capturing`
     * 落下去; 于是 5 s 之后 [BallRest.wait] 会给出 `peeking`。这条测试把门外那几档串起来量一遍 ——
     * 单看每一条闸都对而串起来卡住, 正是报上来的那个现象
     */
    @Test
    fun `说话开一次再收一次之后仍然能收边`() {
        val t0 = 1_000_000L
        // 走到"召出"那一档 (两段点击的第一段)
        var phase = BallPhase.SUMMONED
        var listening = false
        var keyboard = false
        // 第二段: 进语音。语音链在跑 → 那一档不收边, 而且它是"正在听"
        listening = true
        phase = BallPhase.VOICE
        assertEquals(false, BallRest.wait(phase, false, false, false, listening, keyboard,
            channelOpen = false, idleDue = true).peek)
        // 再点一次: 窗口收回去, 语音链自己那一档落下来 → 回到"全露着但没在听"
        listening = false
        phase = BallPhase.SUMMONED
        // 那一下本身算一次活动: 紧接着的一拍还没到点
        assertEquals(false, BallRest.wait(phase, false, false, false, listening, keyboard,
            channelOpen = false, idleDue = false).peek)
        // 5 s 之后: 收
        val wait = BallRest.wait(phase, false, false, false, listening, keyboard,
            channelOpen = false, idleDue = true)
        assertEquals(true, wait.peek)
        assertEquals("peeking", wait.reason)
    }

    /**
     * 打字那一档要能"开始"也要能"结束": 键盘在用时不收, 一收就回到普通那一档, 再闲够 5 s 才收
     *
     * 报出来的原因也要跟着换 —— 排查时先看那一个字: `keyboard` 是"人正在打字", 而它不见了之后的
     * `waiting` 才是"在等那 5 秒"
     */
    @Test
    fun `键盘收掉之后重新起算`() {
        val t0 = 1_000_000L
        val typing = BallRest.wait(BallPhase.SUMMONED, peeked = false, dragging = false, menuOpen = false,
            listening = false, keyboard = true, channelOpen = false, idleDue = true)
        assertEquals("keyboard", typing.reason)
        // 键盘刚收掉那一刻: 还在那一档里等着, 计时是从这一刻起算的
        assertEquals(false, BallMinutes.idleDue(t0 + 1_000, t0))
        assertEquals("waiting", BallRest.wait(BallPhase.SUMMONED, peeked = false, dragging = false,
            menuOpen = false, listening = false, keyboard = false, channelOpen = false,
            idleDue = false).reason)
        // 5 s 之后: 这一档才真的收
        val later = BallRest.wait(BallPhase.SUMMONED, peeked = false, dragging = false, menuOpen = false,
            listening = false, keyboard = false, channelOpen = false,
            idleDue = BallMinutes.idleDue(t0 + BallMinutes.PEEK_IDLE_MS + 1, t0))
        assertEquals(true, later.peek)
        assertEquals("peeking", later.reason)
    }

    /* ── 时长: 3 秒提示与 5 秒空闲是两笔账 ───────────────────────────────── */

    /**
     * 主人 2026-10-05 的话是两句: "浮标状态提示显示时长改为 3 秒" 与 "空闲 3 秒后自动隐藏", 之后
     * 又把隐藏那一档改成 5 s ("4s 改为 5s, 其它不变") —— 所以两个数**不一样**是有意的
     */
    @Test
    fun `提示 3 秒而空闲收边 5 秒`() {
        assertEquals(3_000L, BallMinutes.NOTE_MS)
        assertEquals(5_000L, BallMinutes.PEEK_IDLE_MS)
    }

    /** 收边动画与它自己的收尾回调都在 200 ms 那一档: 动画比提示短得多, 否则看着像"卡住了" */
    @Test
    fun `收边动画比提示短`() {
        assertEquals(true, BallMinutes.EDGE_SNAP_MS < BallMinutes.NOTE_MS)
    }

    /**
     * 那一条判据本身也要钉住: **5 s 之内不收, 过了才收**
     *
     * 模拟器上量的就是这一条 (3 s 时球还在停靠位、5 s 后已经在半隐位置), 所以它不能是"看着像"
     */
    @Test
    fun `5 秒之内不收边, 过了才收`() {
        val t0 = 1_000_000L
        assertEquals(false, BallMinutes.idleDue(t0 + 3_000, t0))
        assertEquals(false, BallMinutes.idleDue(t0 + 5_000, t0))
        assertEquals(true, BallMinutes.idleDue(t0 + 5_001, t0))
    }

    /**
     * 语音链那一档的闲置超时是**另一个文件里的一个数** (需求: 两条关闭路径都要真实生效)
     *
     * 这条断言的意义是"它没有被悄悄改成别的值": 10 s 是主人 2026-10-05 点过名的那个数
     */
    @Test
    fun `语音输入的闲置超时还是 10 秒`() {
        assertEquals(10_000L, WakeWordService.VOICE_IDLE_MS)
    }

    /**
     * 两次点球之间的"防连击"那一个数: 主人 2026-10-06 报的"点一次可能直接打开语音输入"
     *
     * 手指一抖出两下时, 第一下把球召出来、紧接着的第二下就顺路进了语音输入。这个数是两次之间的
     * 最小间隔, 取的是平台 `doubleTapTimeout` (300 ms) 的同一个量级 —— 它必须**大于**系统自己
     * 认双击的那个窗口, 否则"真连击"仍会被当成两下
     */
    @Test
    fun `防连击的窗口比系统双击窗口宽一档`() {
        assertEquals(350L, BallMinutes.TAP_GUARD_MS)
        assertTrue("要比平台的 doubleTapTimeout(300ms) 宽", BallMinutes.TAP_GUARD_MS > 300L)
        assertTrue("又不能宽到把两次真点击并成一次", BallMinutes.TAP_GUARD_MS < 600L)
    }

    /**
     * "点两次才说话"的唯一判据: 这一击是不是**紧跟着那一次召出**
     *
     * 2026-10-06 主人报的"点一次可能直接打开语音输入"就出在这里 —— 改之前用的是一个只立不落的
     * 布尔, 球第一次用完之后此后任何一击都直接开语音。这条钉住的是: 召出之后紧接着的那一击才算
     * "第二次点击", 隔久了 (或者压根没召出过) 只能当第一次
     */
    @Test
    fun `只有紧跟着召出的那一击才算第二次点击`() {
        val t = 1_000_000L
        assertTrue("召出之后马上点: 算第二次", BallMinutes.summonIsFresh(t + 800, t))
        assertTrue("卡在窗口上沿也算", BallMinutes.summonIsFresh(t + BallMinutes.SUMMON_FRESH_MS, t))
        assertTrue("隔久了就不算了 (那一下只该召出)", !BallMinutes.summonIsFresh(t + BallMinutes.SUMMON_FRESH_MS + 1, t))
        assertTrue("从没召出过就不算", !BallMinutes.summonIsFresh(t, 0L))
    }

    /* ── 手势: 双击打断、三击开键盘、两档防连击 (2026-10-06 / 10-07 主人点名的几个数) ── */

    /**
     * "正在想"里 300 ms 之内两下 = 双击打断 (主人点名的 0.3 s)
     *
     * 差一点点 (301 ms) 的那一下**不算双击**, 而它会落到防连击那一档 (350 ms 之内) 变成"什么都不做"
     * —— 这是故意的: 那种情形下既不该打断, 也不该把"收/开语音窗口"那一下做掉, 于是行为是确定的
     */
    @Test
    fun `正在想里的双击窗口是 0_3 秒`() {
        assertEquals(300L, BallMinutes.DOUBLE_TAP_MS)
        val t = 1_000_000L
        assertEquals(BallTap.SINGLE, BallTaps.kind(BallWord.THINKING, t, 0L))
        assertEquals(BallTap.DOUBLE, BallTaps.kind(BallWord.THINKING, t + 150, t, 1))
        assertEquals(BallTap.DOUBLE, BallTaps.kind(BallWord.THINKING, t + BallMinutes.DOUBLE_TAP_MS, t, 1))
        assertEquals(BallTap.TOO_SOON, BallTaps.kind(BallWord.THINKING, t + BallMinutes.DOUBLE_TAP_MS + 1, t))
        assertEquals(BallTap.SINGLE, BallTaps.kind(BallWord.THINKING, t + 400, t))
        assertEquals(BallTap.SINGLE, BallTaps.kind(BallWord.THINKING, t + 5_000, t))
    }

    /**
     * **三击 = 打开键盘输入** (主人 2026-10-07: "加上三击 ball 打开键盘输入, ball 菜单的键盘输入可以
     * 删除")
     *
     * 判据是"每一下都贴着上一记": 第二下 (chain=1) 算双击, 第三下 (chain=2) 起算三击, 而**四连击
     * 也算三击** —— 那只是主人多戳了一下, 不该变成另一个手势
     */
    @Test
    fun `三下贴着上一记就是三击`() {
        val t = 1_000_000L
        assertEquals(BallTap.DOUBLE, BallTaps.kind(null, t + 150, t, 1))
        assertEquals(BallTap.TRIPLE, BallTaps.kind(null, t + 300, t + 150, 2))
        assertEquals(BallTap.TRIPLE, BallTaps.kind(null, t + 450, t + 300, 3))
    }

    /**
     * **三击要看整串的总时长** (2026-10-08 防误触): 头一下之后 450 ms 内数到第三下才算
     *
     * 只看相邻两下的话, "一下一下慢慢戳三下"也算三击 —— 而三击要开的是一块盖住半屏的输入框, 那是这
     * 套手势里最值得防的一处误触。超时的那一串**落回双击**: 它在多数状态里没有动作, 也就是什么都不做
     */
    @Test
    fun `三击那一串要在 450 毫秒之内走完`() {
        val t = 1_000_000L
        assertEquals(450L, BallMinutes.TRIPLE_SPAN_MS)
        // 头一下 (t) 之后 150 / 300 ms: 整串 300 ms, 算三击
        assertEquals(BallTap.TRIPLE, BallTaps.kind(null, t + 300, t + 150, 2, chainFrom = t))
        // 贴着窗口上沿 (整串正好 450 ms) 也算
        assertEquals(BallTap.TRIPLE, BallTaps.kind(null, t + 450, t + 300, 2, chainFrom = t))
        // 整串 451 ms: 停在双击那一档, 不打开输入框
        assertEquals(BallTap.DOUBLE, BallTaps.kind(null, t + 451, t + 300, 2, chainFrom = t))
        // 三下慢慢戳 (每一下都贴着上一记, 但整串 900 ms): 一样停在双击
        assertEquals(BallTap.DOUBLE, BallTaps.kind(null, t + 900, t + 600, 2, chainFrom = t))
        // 不知道头一下什么时候 (chainFrom = 0): 按老口径放行 (只看相邻两下)
        assertEquals(BallTap.TRIPLE, BallTaps.kind(null, t + 300, t + 150, 2, chainFrom = 0L))
    }

    /* ── 防误触: 长按门槛、拖动门槛、刚拖完那一下 (2026-10-08 主人: "增加 ball 各操作的防误触") ── */

    /**
     * 长按出菜单要按住 650 ms, **不是**平台那个 400~500 ms
     *
     * 球一直在屏上, 慢一点的单击 (按下、觉得不对、松开) 会被平台的判据吃掉并弹出菜单 —— 而
     * `BallView` 就是照这个常量去 `postDelayed` 的 (见 Ball.kt 的 ACTION_DOWN 那一支)
     */
    @Test
    fun `长按门槛比平台默认长一档`() {
        assertEquals(650L, BallMinutes.BALL_PRESS_MS)
        assertTrue("要比平台那 400~500 ms 长", BallMinutes.BALL_PRESS_MS > 500L)
        assertTrue("又不能让球像卡住", BallMinutes.BALL_PRESS_MS <= 800L)
    }

    /** 拖动门槛至少 12 dp: 平台那个 8 dp 上下太灵敏, 手一抖就把单击吃掉 */
    @Test
    fun `拖动门槛至少十二 dp`() {
        assertEquals(12, BallMinutes.BALL_SLOP_DP)
    }

    /**
     * 刚拖完的那一下不算单击 (250 ms)
     *
     * 拖动松手会吸附到边上, 而人常常在松手之后补一下 —— 那一下要是算单击, 球就在刚放好的位置上说话
     */
    @Test
    fun `刚拖完的那一下不算单击`() {
        assertEquals(250L, BallMinutes.BALL_DROP_GUARD_MS)
        val t = 1_000_000L
        assertTrue("还没拖过: 不算", !BallMinutes.tapAfterDropIsFresh(t, 0L))
        assertTrue("松手之后马上补一下: 不算", BallMinutes.tapAfterDropIsFresh(t + 80, t))
        assertTrue("卡在窗口上沿: 也不算", BallMinutes.tapAfterDropIsFresh(t + 250, t))
        assertTrue("过 1 ms 就恢复", !BallMinutes.tapAfterDropIsFresh(t + 251, t))
    }

    /**
     * 两下贴着 (双击) 在"正在想"以外的档里**只是把手势认成双击**: 净效果由调用方定, 而
     * `OverlayService.onTap` 在那些档里什么都不做 (于是还是原来那一次单击)
     *
     * "正在念"时点一下是掐断播报、"正在听"时点一下是收回来 —— 那两下本身就是主人要的, 把已经清楚
     * 的手势改成两下的只会更难用
     */
    @Test
    fun `别的状态里双击也认得出来只是没有动作`() {
        val t = 1_000_000L
        assertEquals(BallTap.DOUBLE, BallTaps.kind(BallWord.LISTENING, t + 150, t, 1))
        assertEquals(BallTap.DOUBLE, BallTaps.kind(BallWord.SPEAKING, t + 150, t, 1))
        assertEquals(BallTap.DOUBLE, BallTaps.kind(null, t + 150, t, 1))
    }

    /**
     * "正在听"那一档的防连击窗口是主人点名的 1 s, 其余状态还是 350 ms
     *
     * 那一档的点击是"把语音输入收回来", 而开语音那一下也是点球 —— 手抖出第二下就会"刚开就关"
     */
    @Test
    fun `正在听那一档的防连击窗口是 1 秒`() {
        assertEquals(1_000L, BallMinutes.LISTEN_GUARD_MS)
        assertEquals(BallMinutes.LISTEN_GUARD_MS, BallTaps.guard(BallWord.LISTENING))
        assertEquals(BallMinutes.TAP_GUARD_MS, BallTaps.guard(BallWord.THINKING))
        assertEquals(BallMinutes.TAP_GUARD_MS, BallTaps.guard(null))
        val t = 1_000_000L
        assertEquals(BallTap.TOO_SOON, BallTaps.kind(BallWord.LISTENING, t + 999, t))
        assertEquals(BallTap.SINGLE, BallTaps.kind(BallWord.LISTENING, t + BallMinutes.LISTEN_GUARD_MS, t))
        // 连击窗口之外、防连击窗口之内的那一下既不计数也不动作 —— 三击那条链在这里断掉 (下面那一下
        // 是新的一次单击)
        assertEquals(BallTap.TOO_SOON, BallTaps.kind(null, t + 320, t, 1))
        assertEquals(BallTap.SINGLE, BallTaps.kind(null, t + 400, t, 1))
    }

    /* ── 空闲那一笔账: 状态结束只重置一次 (2026-10-06 那条病根) ───────────── */

    /**
     * **这一条就是"半隐藏又失效"的钉子**
     *
     * 报上来的那条病根是"每一拍都把空闲计时推到当下", 于是 `idleDue` 永远为假。判据是这三笔账:
     * 状态出现时记一次、字没换什么都不记、字落下那一拍照旧只记一次
     */
    @Test
    fun `状态词落下之后空闲计时只重置一次`() {
        assertEquals(BallIdle.STATE, BallIdle.reset(BallWord.THINKING, false, null))
        assertNull(BallIdle.reset(BallWord.THINKING, false, BallWord.THINKING))
        assertEquals(BallIdle.STATE, BallIdle.reset(BallWord.LISTENING, false, BallWord.THINKING))
        assertEquals(BallIdle.VOICE_OFF, BallIdle.reset(null, false, BallWord.THINKING))
        assertNull(BallIdle.reset(null, false, null))
    }

    /**
     * 真机上量到的那个形状: 一个字在球上挂了很久之后落下 —— 之后的每一拍都不许再记那一笔账
     *
     * 20 拍就是 20 个 400 ms 的 tick (8 秒), 而那 8 s 里计时必须一直在长, 球才能在 5 s 后收边
     */
    @Test
    fun `字落下之后的二十拍只记一次账`() {
        var previous: BallWord? = BallWord.THINKING
        var resets = 0
        repeat(20) { tick ->
            val reason = BallIdle.reset(null, false, previous)
            if (reason != null) {
                resets += 1
                assertEquals(BallIdle.VOICE_OFF, reason)
                assertEquals("只有第一拍该记", 0, tick)
            }
            previous = null
        }
        assertEquals(1, resets)
    }

    /** 有字那一档里的"换了字"才记 (同一拍里字没换过就不记, 否则 400 ms 一格永远推到当下) */
    @Test
    fun `同一个字挂着的那几拍不记第二笔`() {
        var previous: BallWord? = null
        var resets = 0
        repeat(20) {
            if (BallIdle.reset(BallWord.THINKING, false, previous) != null) resets += 1
            previous = BallWord.THINKING
        }
        assertEquals(1, resets)
    }

    /** 语音链在跑而字还没上来的那一拍 (理论上不该同时发生, 判据要能容忍): 与"有字"同一支 */
    @Test
    fun `只有语音在跑时也不重复记账`() {
        // 一个字都还没有 (上一拍也是空): 这一拍不记
        assertNull(BallIdle.reset(null, true, null))
        // 字落下而麦克风还开着: 那一下算"状态换了", 只记这一次
        assertEquals(BallIdle.STATE, BallIdle.reset(null, true, BallWord.LISTENING))
        assertNull(BallIdle.reset(null, true, null))
    }

    /* ── 回复框自己收: 20 s (主人 2026-10-06 选的处置) ───────────────────── */

    /**
     * 回复到了框会自己张出来, 而框开着时球不收边 —— 于是那一笔账要有人收尾
     *
     * 主人选的处置是"框也跟着空闲自己收": 它比收边的 5 s 长得多 (框是给人读回复的), 而框收掉之后
     * 球那一笔账从那一刻重新起算
     */
    @Test
    fun `回复框闲置 20 秒自己收`() {
        assertEquals(20_000L, BallMinutes.BOX_IDLE_MS)
        assertTrue("要比收边那 5 s 长得多", BallMinutes.BOX_IDLE_MS > BallMinutes.PEEK_IDLE_MS)
        assertTrue("又要短于一分钟 (它只是'别一直挡着')", BallMinutes.BOX_IDLE_MS < 60_000L)
    }

    /* ── 框外那两下: 真双击 (主人 2026-10-06 收窄的口径) ─────────────────── */

    /**
     * 主人 2026-10-06 的口径: "收窄成真双击, 超时的要重新计算, 而不是隔多久都累计"
     *
     * 收窄之前那本账是个计数器 (`blankTaps`): 点一下、过一会儿再点一下也会把框关掉。现在只有
     * **窗口之内的第二下**才算双击, 而超时的那一下是**新的第一下** (它自己也不关, 但它之后的 300 ms
     * 之内再点一下就关)
     */
    @Test
    fun `框外那两下只有窗口之内才算双击`() {
        assertEquals(300L, BallMinutes.BOX_DOUBLE_TAP_MS)
        val t = 1_000_000L
        assertTrue("还没有点过: 这不是第二下", !BallTaps.blankDouble(t, 0L))
        assertTrue("150 ms 之后: 是", BallTaps.blankDouble(t + 150, t))
        assertTrue("卡在窗口上沿也算", BallTaps.blankDouble(t + BallMinutes.BOX_DOUBLE_TAP_MS, t))
        assertTrue("过了 1 ms 就不是了", !BallTaps.blankDouble(t + BallMinutes.BOX_DOUBLE_TAP_MS + 1, t))
        assertTrue("隔了一秒当然更不算", !BallTaps.blankDouble(t + 1_000, t))
    }

    /**
     * 超时那一下之后**要重新起算**: 它自己不算第二下, 但它是新的第一下
     *
     * 这条就是"收窄"与"累计"的分水岭: 累计的写法在第三次点击时才关 (而它从不重新起算), 现在
     * 第三下落在第二下之后的窗口里就关
     */
    @Test
    fun `超时的那一下重新起算`() {
        val t = 1_000_000L
        val first = t
        val late = t + 1_000
        assertTrue("第一下不关", !BallTaps.blankDouble(first, 0L))
        assertTrue("隔了一秒的那一下不是第二下 (也不关)", !BallTaps.blankDouble(late, first))
        assertTrue("紧跟它半秒内的一下才是", BallTaps.blankDouble(late + 200, late))
        assertTrue("再往后又是新的一下", !BallTaps.blankDouble(late + 1_500, late + 200))
    }

    /* ── 输入通道那几条尺寸与摆位 ─────────────────────────────────────────── */

    /**
     * **主人 2026-10-08: "增加文框长度, 2 个词长度"** —— 650 → 734 (+84 = 两个字宽)
     *
     * 两个数都钉住是故意的: 宽度本身, 以及"两个字宽"这个换算是怎么来的 ([BallBox.CHAR_PX])
     */
    @Test
    fun `输入框加宽两个字`() {
        assertEquals(734, BallBox.WIDTH_PX)
        assertEquals(2 * BallBox.CHAR_PX, BallBox.WIDTH_PX - 650)
    }

    /**
     * 窄屏 (横屏 / 分屏那一档) 上框要按屏宽的 62% 收窄
     *
     * 不收的话 734 的框在短边 600 出头的屏上会横跨大半屏并把球压在底下 —— 而球是唯一那个常驻入口
     */
    @Test
    fun `窄屏上把框收窄到屏宽的六成`() {
        assertEquals(BallBox.WIDTH_PX, BallBox.widthFor(2400))
        assertEquals(BallBox.WIDTH_PX, BallBox.widthFor(1200))
        assertEquals(372, BallBox.widthFor(600)) // 600 * 62% = 372
        assertEquals(true, BallBox.widthFor(600) < 600)
    }

    /** "行数上限为 5 行, 高度随行数增长而不是固定的" —— **2026-10-06 主人追加两行, 所以是 7** */
    @Test
    fun `输入框随行数长高但封顶 7 行`() {
        assertEquals(1, BallBox.limitedLines(0))
        assertEquals(3, BallBox.limitedLines(3))
        assertEquals(7, BallBox.limitedLines(7))
        assertEquals(7, BallBox.limitedLines(40))
        assertEquals(7, BallBox.MAX_LINES)
    }

    /**
     * 回复那一块**2026-10-06 也追加两行: 3 → 5**
     *
     * 与输入框那两个数各管各的: 一个是"人写的" (`MAX_LINES = 7`), 一个是"agent 回复的"
     * (`REPLY_LINES = 5`)
     */
    @Test
    fun `回复区最多五行`() {
        assertEquals(5, BallBox.REPLY_LINES)
    }

    /**
     * 回复进来时**停在最新那条的首行** (主人 2026-10-06: "消息回复框应保持首行, 以免每次都要向上找")
     *
     * 只有一条回复时那个位置就是 0 (框自己的首行); 两条时它指向第二条的开头 —— 也就是说长回答不会
     * 再一进来就露出尾巴
     */
    @Test
    fun `新回复的头一行是滚动的位置`() {
        assertEquals(0, BallBox.newestStart("只有一条回答", "只有一条回答"))
        assertEquals(4, BallBox.newestStart("旧话\n\n新话", "新话"))
        assertEquals(0, BallBox.newestStart("", ""))
    }

    /** 球贴左边时框摆在右边, 而且不会越出屏幕 */
    @Test
    fun `框摆在球里侧那一边`() {
        val box = BallBox.WIDTH_PX
        val x = BoxSpot.x(ballX = 0, ballSize = ball, boxWidth = box, screenWidth = screen)
        assertEquals(ball + BoxSpot.GAP_PX, x)
        assertEquals(true, x + box <= screen)
    }

    /** 球贴右边: 里侧放不下就翻到左边那一边 (翻过去也放不下才钳在右边界上) */
    @Test
    fun `球贴右边时框翻到左边`() {
        val box = BallBox.WIDTH_PX
        val ballX = screen - ball
        val x = BoxSpot.x(ballX = ballX, ballSize = ball, boxWidth = box, screenWidth = screen)
        assertEquals(true, x + box <= ballX)
        assertEquals(ballX - box - BoxSpot.GAP_PX, x)
    }

    /**
     * 窄屏 (横屏那一档): 两边都放不下时也**整块留在屏幕里**, 宁可压在球上
     *
     * **2026-10-08 起框宽先过 [BallBox.widthFor]**: 屏宽 700 时框收到 434 (62%), 于是"放不下"这件事
     * 只在球贴着边的时候才发生, 而钳完之后整块仍在屏内。直接用 734 那一版会钳在 0 上却还是越界 ——
     * 收窄与钳制**必须用同一个宽度**, 这一条就是钉那个的
     */
    @Test
    fun `窄屏上把框收窄再钳进屏里`() {
        val narrow = 700
        val box = BallBox.widthFor(narrow)
        assertEquals(434, box)
        val x = BoxSpot.x(ballX = 0, ballSize = ball, boxWidth = box, screenWidth = narrow)
        // 收窄之后球贴着左边时框放得下里侧那一边 (0 + 144 + 10 = 154), 不必再钳
        assertEquals(ball + BoxSpot.GAP_PX, x)
        assertEquals(true, x + box <= narrow)
        // 球贴右边时翻到左边也放得下
        val right = BoxSpot.x(ballX = narrow - ball, ballSize = ball, boxWidth = box, screenWidth = narrow)
        assertEquals(true, right + box <= narrow - ball)
    }

    /** 纵向: 框跟着球的中心走, 并且不越过屏幕上边, 也不压到键盘上 */
    @Test
    fun `框跟着球走并让开键盘`() {
        val boxHeight = 300
        // 球心在 1000 + 72 = 1072, 框高 300 时它该从 922 起 —— 也就是球心对准框的中线
        assertEquals(922, BoxSpot.y(ballY = 1000, ballSize = ball, boxHeight = boxHeight, screenHeight = 2800, imeBottom = 0))
        // 键盘起来时最多摆在键盘上沿之上
        val lifted = BoxSpot.y(ballY = 2600, ballSize = ball, boxHeight = boxHeight, screenHeight = 2800, imeBottom = 900)
        assertEquals(2800 - 900 - boxHeight, lifted)
        // 球在顶上时框不许是负的
        assertEquals(0, BoxSpot.y(ballY = 0, ballSize = ball, boxHeight = boxHeight, screenHeight = 2800, imeBottom = 0))
    }

    /* ── 转屏那三块窗的几何 (2026-10-08 主人报的第一条) ───────────────────── */

    /**
     * 输入条的尺寸与停靠位**每一屏各算一次**, 不是创建时算一次就留着
     *
     * 这条钉的就是主人报的那个病: 竖屏算出来的那条 (1080 高) 横过来之后还是 1080 高, 而横屏只有 1080
     * 高 —— 于是"停靠位"算出来是负的, 整块条子盖满屏幕 (也就是他说的"极大偏移")。重算那一遍之后
     * 宽高与 y 都落在新屏里
     */
    @Test
    fun `转屏之后输入条按新屏重算`() {
        val margin = 12
        val lift = 140
        val (portraitW, portraitH) = 1080 to 2400
        val (landscapeW, landscapeH) = 2400 to 1080
        // 竖屏那一份 (以前被留到横屏上的正是这两个数)
        assertEquals(1056, StripSpot.width(portraitW, margin))
        assertEquals(1080, StripSpot.height(portraitH, margin, 45))
        // 横屏那一份: 又宽又矮, 而高度正好是屏高的四成半
        assertEquals(2376, StripSpot.width(landscapeW, margin))
        assertEquals(486, StripSpot.height(landscapeH, margin, 45))
        // 旧那一份在横屏上就是"整屏高": 这就是偏移的来源
        assertEquals(landscapeH, StripSpot.height(portraitH, margin, 45))
        // 新那一份落在屏里: 上边留了边距, 下边不越界
        val y = StripSpot.restY(landscapeH, StripSpot.height(landscapeH, margin, 45), margin, lift)
        assertTrue(y >= margin)
        assertTrue(y + StripSpot.height(landscapeH, margin, 45) <= landscapeH - margin)
    }

    /** 反过来 (横屏摆好之后转回竖屏) 也一样: 两个方向都要落在屏里, 而不是只修一边 */
    @Test
    fun `横屏转回竖屏也不越界`() {
        val margin = 12
        val height = StripSpot.height(2400, margin, 45)
        val y = StripSpot.restY(2400, height, margin, 140)
        val x = StripSpot.x(margin)
        assertEquals(margin, x)
        assertTrue(x + StripSpot.width(1080, margin) <= 1080)
        assertTrue(y >= margin)
        assertTrue(y + height <= 2400 - margin)
    }

    /**
     * 极矮的屏 (分屏那一档) 上高度要钳住: 45% 的屏高压进了"上下各留一条边距"里, 不许它把屏幕吃掉
     * 或者算出负数
     */
    @Test
    fun `很矮的屏上高度被钳住`() {
        val margin = 12
        // 45% 的 200 = 90, 离"上下各留一条边距"那个上限 (176) 还远
        assertEquals(90, StripSpot.height(200, margin, 45))
        // 90% 的 200 = 180 超过上限, 于是被钳到 176
        assertEquals(176, StripSpot.height(200, margin, 90))
        assertTrue(StripSpot.height(200, margin, 90) <= 200 - margin * 2)
        // 屏比两条边距还窄: 结果至少是 1, 不许是 0 或者负数
        assertTrue(StripSpot.height(10, margin, 45) >= 1)
    }

    /* ── 手势灵敏度那一档 (2026-10-08 主人: "浮标灵敏度开关") ─────────────────── */

    /**
     * 两档各自是哪些数: **防误触 (缺省) 三个门槛都收紧, 标准档全交给平台那一档**
     *
     * `0` 在这张表里的意思就是"这一档不额外加这条闸" (见 [BallFeel]), 所以它既不是"只要 0 毫秒"
     * 也不是"永远不触发" —— 标准档的净效果正是收紧之前那一版的手感
     */
    @Test
    fun `灵敏度两档各自是哪些数`() {
        assertEquals(650L, BallFeel.GUARD.pressMs)
        assertEquals(12, BallFeel.GUARD.slopDp)
        assertEquals(450L, BallFeel.GUARD.tripleSpanMs)
        assertEquals(250L, BallFeel.GUARD.dropGuardMs)

        assertEquals(0L, BallFeel.STANDARD.pressMs)
        assertEquals(0, BallFeel.STANDARD.slopDp)
        assertEquals(0L, BallFeel.STANDARD.tripleSpanMs)
        assertEquals(0L, BallFeel.STANDARD.dropGuardMs)
    }

    /**
     * 三击在标准档里**不看整串的总时长**: 一下一下慢慢戳三下也算
     *
     * 防误触那一档里同一串是"停在双击" (那条判据自己有一份测试), 这里量的是"档位真的换掉了判据"
     */
    @Test
    fun `标准档的三击不看总时长`() {
        val t = 1_000_000L
        assertEquals(BallTap.DOUBLE, BallTaps.kind(null, t + 900, t + 600, 2, chainFrom = t))
        assertEquals(
            BallTap.TRIPLE,
            BallTaps.kind(null, t + 900, t + 600, 2, chainFrom = t, feel = BallFeel.STANDARD),
        )
    }

    /** 刚拖完那一下在标准档里不挡 (防误触那一档挡 250 ms) */
    @Test
    fun `标准档不挡刚拖完那一下`() {
        val t = 1_000_000L
        assertTrue("防误触档挡它", BallMinutes.tapAfterDropIsFresh(t + 80, t))
        assertTrue("标准档不挡", !BallMinutes.tapAfterDropIsFresh(t + 80, t, BallFeel.STANDARD))
        assertTrue("没拖过在标准档里更不算", !BallMinutes.tapAfterDropIsFresh(t, 0L, BallFeel.STANDARD))
    }

    /**
     * 存盘那一份名字认不出来时**落回防误触**
     *
     * 存坏了的净效果该是"更不容易误触", 不是"球变得一碰就开" —— 后面那一半会让主人以为应用坏了
     */
    @Test
    fun `认不出来的档位落回防误触`() {
        assertEquals(BallFeel.GUARD, BallFeel.of(null))
        assertEquals(BallFeel.GUARD, BallFeel.of(""))
        assertEquals(BallFeel.GUARD, BallFeel.of("standard"))
        assertEquals(BallFeel.GUARD, BallFeel.of("STANDARD "))
        assertEquals(BallFeel.GUARD, BallFeel.of("GUARD"))
        assertEquals(BallFeel.STANDARD, BallFeel.of("STANDARD"))
    }
}
