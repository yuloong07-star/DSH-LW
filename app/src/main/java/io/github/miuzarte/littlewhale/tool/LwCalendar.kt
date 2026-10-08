package io.github.miuzarte.littlewhale.tool

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import io.github.miuzarte.littlewhale.util.Capability
import io.github.miuzarte.littlewhale.util.PermissionGate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日程: 列日历 / 查事件 / 读一条 / 建一条 / 改一条 / 查空闲
 *
 * 这一条**不过特权通道** —— 读日历是 app uid 拿 `ContentResolver` 就能做的事, 走通道只是多绕一圈
 * binder; 与通知、剪贴板那几个同形 (它们都落在 `tool/` 这一层, 由 `PrivilegedBridge` 转发)
 *
 * 三条纪律照本仓既有那一条:
 *
 * - **缺权限要说清缺哪一条、怎么给**, 不假装成功 (见 [require])
 * - **写完读回**: `create` 与 `update` 都回读一次, 免得一个 uri 被当成"写进去了"
 * - **绝不假装写进去**: 一本可写的日历都没有时拒绝, 并列出只读的那几本 —— 这条在模拟器上尤其容易
 *   露馅 (那里本来一本日历都没有)
 *
 * 时间两种写法都收 (`2026-10-09T14:00` 或 epoch 毫秒), 回执两种都给: 本地时间给人看, 毫秒给下一次
 * 调用往回传。时区就是**设备时区**, 这一层不做"把主人说的时区换算过去"那种事 —— 说下午三点就是本地
 * 下午三点
 *
 * 删除这一版**没有**: 主人 2026-10-08 选的口径是"日程的增删改只做读建改查空闲", 删一条去日历应用里删
 */
internal object LwCalendar {

    /**
     * op 名单, **插件那边念给模型的也是这六个** (`host-plugin/index.mjs` 的 `lw_calendar`)
     *
     * `tools/check-quick-commands.mjs` 拿这一行去比插件那一边, 漂开了就是"工具说明里有一个 op 其实
     * 没实现"或者反过来
     */
    val OPERATIONS = listOf("list", "events", "read", "create", "update", "free")

    /** 一次 `events` 最多回多少条 (不给就用缺省那个) */
    private const val DEFAULT_LIMIT = 50
    private const val MAX_LIMIT = 200

    /** 建一条不给结束时间时给多久 */
    private const val DEFAULT_DURATION_MINUTES = 60

    /** 查空闲时, 多长的缝才算一段空档 */
    private const val DEFAULT_FREE_MINUTES = 30

    /** 查空闲默认的作息窗口: 早上九点到晚上十点之外不排事 */
    private val DEFAULT_DAY_START: LocalTime = LocalTime.of(9, 0)
    private val DEFAULT_DAY_END: LocalTime = LocalTime.of(22, 0)

    /** 一次查询最长跨多少天: 再长就不是"查日程"而是"导出日历"了 */
    private const val MAX_RANGE_DAYS = 62

    /** 一天有多少毫秒 (全是整天活动那一条路在算) */
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MM-dd HH:mm", Locale.CHINA)

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MM-dd", Locale.CHINA)

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "list" -> list(context)
        "events" -> events(context, request)
        "read" -> read(context, request)
        "create" -> create(context, request)
        "update" -> update(context, request)
        "free" -> free(context, request)
        else -> throw IllegalArgumentException(
            "op has to be ${OPERATIONS.joinToString(", ")}, not \"$op\"",
        )
    }

    /* ── 权限 ───────────────────────────────────────────────────────────── */

    /** 读那半条: 列日历 / 查事件 / 读一条 / 查空闲都要它 */
    private fun requireRead(context: Context) =
        require(context, Manifest.permission.READ_CALENDAR, "reading the calendar")

    /** 写那半条: 建与改要它 */
    private fun requireWrite(context: Context) =
        require(context, Manifest.permission.WRITE_CALENDAR, "changing the calendar")

    /**
     * 一道闸一句话
     *
     * 能力名字用设置页与 `lw_permissions` 里那一个 ("日历"), 所以模型把这句话转给主人时, 主人照着
     * 去设置页找得到同一段
     */
    private fun require(context: Context, permission: String, what: String) {
        val capability = Capability(name = "日历", why = "lw_calendar", permissions = listOf(permission))
        val refusal = PermissionGate.refusal(context, capability)
            ?: return
        throw IllegalStateException("$what: $refusal")
    }

    /* ── 时间 ───────────────────────────────────────────────────────────── */

    /** 设备时区: 这一层唯一的那个时区 */
    private fun zone(): ZoneId = ZoneId.systemDefault()

    /**
     * 把调用给的时刻解成 epoch 毫秒
     *
     * 三种写法: 纯数字 (epoch 毫秒, 可带负号), `2026-10-09T14:00` (或空格分隔 / 带秒), `2026-10-09`
     * (那天零点)。别的写法一律拒绝并说清能接受什么 —— 猜一个时间比拒绝更坏
     */
    private fun instant(raw: String, zone: ZoneId): Long {
        val value = raw.trim()
        value.toLongOrNull()?.let { return it }
        val asDateTime = value.replace(' ', 'T')
        runCatching { LocalDateTime.parse(asDateTime) }
            .onSuccess { return it.atZone(zone).toInstant().toEpochMilli() }
        runCatching { LocalDate.parse(value) }
            .onSuccess { return it.atStartOfDay(zone).toInstant().toEpochMilli() }
        throw IllegalArgumentException(
            "\"$raw\" is not a time this tool reads: give either epoch milliseconds or a local time" +
                " like 2026-10-09T14:00, or a date like 2026-10-09",
        )
    }

    /** 一个事件什么时候: 整天那种给日期, 其余的给"周几 月-日 时:分" */
    private fun render(start: Long, allDay: Boolean, zone: ZoneId): String = when {
        allDay -> Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate().format(DAY)
        else -> Instant.ofEpochMilli(start).atZone(zone).format(STAMP)
    }

    /** 整天活动的起点落在 UTC 零点 (日历库就是这么存的), 所以从本地日期倒回去算 */
    private fun utcMidnight(ms: Long, zone: ZoneId): Long = Instant.ofEpochMilli(ms)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

    /** 一段时间至少要跨得动: 起点在终点之后就是调用方写错了 */
    private fun span(from: Long, to: Long): Pair<Long, Long> {
        if (to <= from) {
            throw IllegalArgumentException(
                "the end of the range has to be after its start ($from -> $to)",
            )
        }
        val days = (to - from) / DAY_MS
        if (days > MAX_RANGE_DAYS) {
            throw IllegalArgumentException(
                "that range is $days days long; this tool reads at most $MAX_RANGE_DAYS days at a time",
            )
        }
        return from to to
    }

    private fun range(request: JsonObject): Pair<Long, Long> {
        val zone = zone()
        val from = instant(request.string("from"), zone)
        val to = instant(request.string("to"), zone)
        return span(from, to)
    }

    /* ── 日历 ───────────────────────────────────────────────────────────── */

    /** 一本日历现在是什么样 */
    private data class Book(
        val id: Long,
        val name: String,
        val account: String,
        val access: Int,
        val color: Int?,
        val timezone: String?,
    ) {
        /** 够不够写: 500 (`CONTRIBUTOR`) 及以上才是能往里建活动的 */
        val writable: Boolean get() = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
    }

    private fun books(context: Context): List<Book> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,
        )
        val found = mutableListOf<Book>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                found += Book(
                    id = cursor.getLong(0),
                    name = cursor.getString(1) ?: "(no name)",
                    account = cursor.getString(2) ?: "(no account)",
                    access = cursor.getInt(3),
                    color = if (cursor.isNull(4)) null else cursor.getInt(4),
                    timezone = cursor.getString(5),
                )
            }
        }
        return found
    }

    /** 颜色写成 `#RRGGBB`, 读不出来就空着 */
    private fun color(value: Int?): String? = value?.let { "#%06X".format(it and 0xFFFFFF) }

    private fun access(value: Int): String = when {
        value >= CalendarContract.Calendars.CAL_ACCESS_OWNER -> "owner"
        value >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR -> "writable"
        value >= CalendarContract.Calendars.CAL_ACCESS_READ -> "read-only"
        else -> "no access"
    }

    private fun list(context: Context): JsonObject {
        requireRead(context)
        val all = books(context)
        if (all.isEmpty()) {
            return text(
                "this device has no calendar yet: the calendar provider is there but there is" +
                    " nothing to read (no account and no local calendar) - a quick command that" +
                    " writes an event would have nowhere to put it",
            )
        }
        val rows = all.joinToString("\n") { book ->
            "  id ${book.id}: ${book.name} [${access(book.access)}]" +
                " - ${book.account}, ${color(book.color) ?: "no colour"}, ${book.timezone ?: "no timezone"}"
        }
        val writable = all.count { it.writable }
        return text(
            "${all.size} calendar(s), $writable of them writable. Events are written to the first" +
                " writable one unless the call names calendarId:\n$rows",
        )
    }

    /**
     * 写进哪一本
     *
     * 给了 `calendarId` 就认那一本 (不是可写的就拒绝); 没给就用**第一本可写的**。一本可写的都没有时
     * 把只读的那几本列出来 —— 这时候拒绝才是诚实的答案
     */
    private fun writableBook(context: Context, asked: String?): Book {
        val all = books(context)
        if (all.isEmpty()) {
            throw IllegalStateException(
                "this device has no calendar at all, so there is nowhere to write an event" +
                    " (Settings -> the phone's calendar app usually offers a local calendar)",
            )
        }
        if (asked != null) {
            val id = asked.toLongOrNull()
                ?: throw IllegalArgumentException("calendarId has to be a number, not \"$asked\"")
            val book = all.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException(
                    "there is no calendar with id $id. What is there: " +
                        all.joinToString(", ") { "${it.id} (${it.name})" },
                )
            if (!book.writable) {
                throw IllegalStateException(
                    "calendar ${book.id} (${book.name}) is ${access(book.access)}, so nothing can be" +
                        " written into it; writable ones: " +
                        (all.filter { it.writable }.joinToString(", ") { "${it.id} (${it.name})" }
                            .ifEmpty { "none" }),
                )
            }
            return book
        }
        return all.firstOrNull { it.writable }
            ?: throw IllegalStateException(
                "no calendar on this device is writable, so an event cannot be created:" +
                    " ${all.joinToString(", ") { "${it.id} (${it.name}) is ${access(it.access)}" }}" +
                    " - the user has to add a writable calendar (a local one is enough) first",
            )
    }

    /* ── 事件 ───────────────────────────────────────────────────────────── */

    /** 一条事件, 只带这一层要用的字段 */
    private data class Event(
        val id: Long,
        val calendarId: Long,
        val title: String,
        val start: Long,
        val end: Long?,
        val allDay: Boolean,
        val location: String?,
    )

    private val EVENT_FIELDS = arrayOf(
        CalendarContract.Events._ID,
        CalendarContract.Events.CALENDAR_ID,
        CalendarContract.Events.TITLE,
        CalendarContract.Events.DTSTART,
        CalendarContract.Events.DTEND,
        CalendarContract.Events.ALL_DAY,
        CalendarContract.Events.EVENT_LOCATION,
    )

    private fun readCursor(cursor: Cursor): Event = Event(
        id = cursor.getLong(0),
        calendarId = cursor.getLong(1),
        title = cursor.getString(2) ?: "(no title)",
        start = cursor.getLong(3),
        end = if (cursor.isNull(4)) null else cursor.getLong(4),
        allDay = cursor.getInt(5) != 0,
        location = cursor.getString(6),
    )

    /**
     * 一段窗口里的事件
     *
     * `overlap` 那一档是给"查空闲"用的: 交集判据 (起在窗口之前、结束在窗口之后也算), 而不是"起点
     * 落在窗口里" —— 后者会把一条从 08:00 开到 23:00 的活动漏掉, 于是空档里凭空多出一段
     */
    private fun queryEvents(
        context: Context,
        from: Long,
        to: Long,
        calendarId: Long? = null,
        search: String? = null,
        overlap: Boolean = false,
    ): List<Event> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (overlap) {
            parts += "${CalendarContract.Events.DTSTART} < ?"
            args += to.toString()
            parts += "${CalendarContract.Events.DTEND} > ?"
            args += from.toString()
        } else {
            parts += "${CalendarContract.Events.DTSTART} >= ?"
            args += from.toString()
            parts += "${CalendarContract.Events.DTSTART} < ?"
            args += to.toString()
        }
        calendarId?.let {
            parts += "${CalendarContract.Events.CALENDAR_ID} = ?"
            args += it.toString()
        }
        search?.let {
            parts += "(${CalendarContract.Events.TITLE} LIKE ? OR ${CalendarContract.Events.DESCRIPTION} LIKE ?)"
            args += "%$it%"
            args += "%$it%"
        }
        val events = mutableListOf<Event>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            EVENT_FIELDS,
            parts.joinToString(" AND "),
            args.toTypedArray(),
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { cursor -> while (cursor.moveToNext()) events += readCursor(cursor) }
        return events
    }

    private fun events(context: Context, request: JsonObject): JsonObject {
        requireRead(context)
        val (from, to) = range(request)
        val zone = zone()
        val all = books(context)
        val names = all.associate { it.id to it.name }
        // 点名的日历不存在时要直说: 空结果会读成"那天没有事", 而那与"这本日历根本不在"是两件事
        val calendarId = request.stringOrNull("calendarId")?.let { asked ->
            val id = asked.toLongOrNull()
                ?: throw IllegalArgumentException("calendarId has to be a number, not \"$asked\"")
            if (all.none { it.id == id }) {
                throw IllegalArgumentException(
                    "there is no calendar with id $id. What is there: " +
                        all.joinToString(", ") { "${it.id} (${it.name})" },
                )
            }
            id
        }
        val limit = (request.intOrNull("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val found = queryEvents(context, from, to, calendarId, request.stringOrNull("query"))
        if (found.isEmpty()) {
            return text(
                "no event starts between ${render(from, false, zone)} and ${render(to, false, zone)}" +
                    (if (calendarId != null) " in calendar $calendarId" else "") +
                    (request.stringOrNull("query")?.let { " matching \"$it\"" } ?: ""),
            )
        }
        val rows = found.take(limit).joinToString("\n") { event ->
            val when_ = if (event.allDay) {
                "${render(event.start, true, zone)} (all day)"
            } else {
                val until = event.end?.let { " -> " + render(it, false, zone).substringAfter(' ') } ?: ""
                render(event.start, false, zone) + until
            }
            "  id ${event.id}: $when_  ${event.title}" +
                (event.location?.let { " @ $it" } ?: "") +
                "  [${names[event.calendarId] ?: "calendar ${event.calendarId}"}]"
        }
        val tail = if (found.size > limit) {
            "\n(and ${found.size - limit} more; this call lists at most $limit, narrow the range or pass" +
                " a bigger limit)"
        } else {
            ""
        }
        return text(
            "${found.size} event(s) between ${render(from, false, zone)} and ${render(to, false, zone)}," +
                " every time in ${zone.id}:\n$rows$tail",
        )
    }

    /** 一条事件的全文: 连备注与来自哪一本 */
    private fun read(context: Context, request: JsonObject): JsonObject {
        requireRead(context)
        val id = request.stringOrNull("eventId")?.toLongOrNull()
            ?: throw IllegalArgumentException("this call has to name eventId as a number")
        val rows = queryById(context, id)
        val event = rows.firstOrNull()
            ?: throw IllegalArgumentException(
                "there is no event with id $id (it may have been deleted since the id was read)",
            )
        val zone = zone()
        val book = books(context).firstOrNull { it.id == event.calendarId }
        val values = linkedMapOf(
            "id" to event.id.toString(),
            "title" to event.title,
            "when" to when_(context, event, zone),
            "calendar" to (book?.let { "${it.name} (id ${it.id}, ${access(it.access)})" }
                ?: "id ${event.calendarId}"),
            "start (epoch ms)" to event.start.toString(),
            "end (epoch ms)" to (event.end?.toString() ?: "not set"),
            "all day" to event.allDay.toString(),
            "location" to (event.location ?: "not set"),
            "description" to (description(context, event.id) ?: "not set"),
        )
        return text(table(values.toList()))
    }

    private fun queryById(context: Context, id: Long): List<Event> {
        val events = mutableListOf<Event>()
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            EVENT_FIELDS,
            null,
            null,
            null,
        )?.use { cursor -> while (cursor.moveToNext()) events += readCursor(cursor) }
        return events
    }

    /** 备注单独查一次: 它又长又少用, 不必进每一次 `events` 的投影 */
    private fun description(context: Context, id: Long): String? {
        context.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events.DESCRIPTION),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0) }
        return null
    }

    private fun when_(context: Context, event: Event, zone: ZoneId): String {
        val book = books(context).firstOrNull { it.id == event.calendarId }
        val span = if (event.allDay) {
            "${render(event.start, true, zone)} (all day)"
        } else {
            render(event.start, false, zone) + (event.end?.let { " -> " + render(it, false, zone) } ?: "")
        }
        return span + " [" + (book?.timezone ?: zone.id) + "]"
    }

    /* ── 建 / 改 ────────────────────────────────────────────────────────── */

    private fun create(context: Context, request: JsonObject): JsonObject {
        requireWrite(context)
        val zone = zone()
        val allDay = request.bool("allDay", false)
        val start = instant(request.string("start"), zone)
        val end = endOf(request, start, allDay, zone)
        // 结束早于开始就是调用方写错了: 与其让日历库收到一条零长度的事件, 不如在这里说清
        span(start, end)
        val book = writableBook(context, request.stringOrNull("calendarId"))
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, book.id)
            put(CalendarContract.Events.TITLE, request.string("title"))
            put(CalendarContract.Events.DTSTART, if (allDay) utcMidnight(start, zone) else start)
            put(CalendarContract.Events.DTEND, if (allDay) utcMidnight(end, zone) else end)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
            request.stringOrNull("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            request.stringOrNull("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw IllegalStateException(
                "the calendar provider refused the new event and said nothing; nothing was written",
            )
        val id = ContentUris.parseId(uri)
        val written = queryById(context, id).firstOrNull()
            ?: throw IllegalStateException(
                "the event was written as id $id but reading it back returned nothing",
            )
        return text(
            "created event id ${written.id} in \"${book.name}\" (calendar ${book.id}):" +
                " ${when_(context, written, zone)}\n" +
                "  ${written.title}" +
                (written.location?.let { " @ $it" } ?: "") + "\n" +
                "  start ${written.start} (epoch ms), timezone ${zone.id}",
        )
    }

    private fun update(context: Context, request: JsonObject): JsonObject {
        requireWrite(context)
        val zone = zone()
        val id = request.stringOrNull("eventId")?.toLongOrNull()
            ?: throw IllegalArgumentException("update has to name eventId as a number")
        val current = queryById(context, id).firstOrNull()
            ?: throw IllegalArgumentException("there is no event with id $id, so there is nothing to change")
        val allDay = request.bool("allDay", current.allDay)
        val values = ContentValues()
        request.stringOrNull("title")?.let { values.put(CalendarContract.Events.TITLE, it) }
        request.stringOrNull("location")?.let { values.put(CalendarContract.Events.EVENT_LOCATION, it) }
        request.stringOrNull("description")?.let { values.put(CalendarContract.Events.DESCRIPTION, it) }
        request.stringOrNull("calendarId")?.let {
            val book = writableBook(context, it)
            values.put(CalendarContract.Events.CALENDAR_ID, book.id)
        }
        val askedStart = request.stringOrNull("start")?.let { instant(it, zone) }
        val askedEnd = request.stringOrNull("end")?.let { instant(it, zone) }
        if (askedStart != null || askedEnd != null || request.stringOrNull("allDay") != null) {
            val start = askedStart ?: current.start
            val end = askedEnd ?: endOf(request, start, allDay, zone)
            span(start, end)
            values.put(CalendarContract.Events.DTSTART, if (allDay) utcMidnight(start, zone) else start)
            values.put(CalendarContract.Events.DTEND, if (allDay) utcMidnight(end, zone) else end)
            values.put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
        }
        if (values.size() == 0) {
            throw IllegalArgumentException(
                "this call gave nothing to change: name at least one of title / start / end /" +
                    " durationMinutes / allDay / location / description / calendarId",
            )
        }
        val changed = context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            values,
            null,
            null,
        )
        val written = queryById(context, id).firstOrNull()
            ?: throw IllegalStateException(
                "the update reported $changed row(s) but reading event $id back returned nothing",
            )
        return text(
            "updated event ${written.id} ($changed row(s) reported): ${when_(context, written, zone)}\n" +
                "  ${written.title}" + (written.location?.let { " @ $it" } ?: ""),
        )
    }

    /**
     * 结束时间: 给了就用, 没给就按 `durationMinutes` (再没有就 [DEFAULT_DURATION_MINUTES], 整天那种
     * 是第二天零点)
     */
    private fun endOf(request: JsonObject, start: Long, allDay: Boolean, zone: ZoneId): Long {
        request.stringOrNull("end")?.let { return instant(it, zone) }
        val minutes = request.intOrNull("durationMinutes")
        if (minutes != null) {
            if (minutes <= 0) throw IllegalArgumentException("durationMinutes has to be at least 1")
            return start + minutes * 60_000L
        }
        return if (allDay) start + DAY_MS else start + DEFAULT_DURATION_MINUTES * 60_000L
    }

    /* ── 空档 ───────────────────────────────────────────────────────────── */

    private fun free(context: Context, request: JsonObject): JsonObject {
        requireRead(context)
        val (from, to) = range(request)
        val zone = zone()
        val minMinutes = request.intOrNull("minMinutes") ?: DEFAULT_FREE_MINUTES
        if (minMinutes <= 0) throw IllegalArgumentException("minMinutes has to be at least 1")
        val dayStart = request.stringOrNull("dayStart")?.let { LocalTime.parse(it) } ?: DEFAULT_DAY_START
        val dayEnd = request.stringOrNull("dayEnd")?.let { LocalTime.parse(it) } ?: DEFAULT_DAY_END
        if (!dayEnd.isAfter(dayStart)) {
            throw IllegalArgumentException("dayEnd ($dayEnd) has to be after dayStart ($dayStart)")
        }
        // 占用的那些段: 整天活动按"那一天整天"算, 其余按它自己的起止
        val busy = queryEvents(context, from, to, overlap = true).map { event ->
            if (event.allDay) {
                val date = Instant.ofEpochMilli(event.start).atZone(ZoneOffset.UTC).toLocalDate()
                date.atStartOfDay(zone).toInstant().toEpochMilli() to
                    date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            } else {
                event.start to (event.end ?: (event.start + DEFAULT_DURATION_MINUTES * 60_000L))
            }
        }.sortedBy { it.first }
        val slots = mutableListOf<Pair<Long, Long>>()
        var day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val lastDay = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        while (!day.isAfter(lastDay)) {
            val windowStart = maxOf(from, day.atTime(dayStart).atZone(zone).toInstant().toEpochMilli())
            val windowEnd = minOf(to, day.atTime(dayEnd).atZone(zone).toInstant().toEpochMilli())
            if (windowEnd - windowStart >= minMinutes * 60_000L) {
                var cursor = windowStart
                busy.filter { it.second > windowStart && it.first < windowEnd }.forEach { (start, end) ->
                    if (start - cursor >= minMinutes * 60_000L) slots += cursor to start
                    cursor = maxOf(cursor, end)
                }
                if (windowEnd - cursor >= minMinutes * 60_000L) slots += cursor to windowEnd
            }
            day = day.plusDays(1)
        }
        if (slots.isEmpty()) {
            return text(
                "no window of $minMinutes minutes or more is free between" +
                    " ${render(from, false, zone)} and ${render(to, false, zone)}," +
                    " inside ${dayStart}-${dayEnd} each day",
            )
        }
        val rows = slots.joinToString("\n") { (start, end) ->
            val minutes = (end - start) / 60_000L
            "  ${render(start, false, zone)} -> ${render(end, false, zone).substringAfter(' ')}" +
                "  ($minutes min)"
        }
        return text(
            "${slots.size} free window(s) between ${render(from, false, zone)} and" +
                " ${render(to, false, zone)}, each ${dayStart}-${dayEnd}, at least $minMinutes minutes:\n" +
                "$rows",
        )
    }

    /* ── 小工具 ─────────────────────────────────────────────────────────── */

    /** 一个可能没有的整数参数 (`ToolSupport` 那个带默认值的在这里不够用: "没给"与"给了 0"是两件事) */
    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.longOrNull?.toInt()
}
