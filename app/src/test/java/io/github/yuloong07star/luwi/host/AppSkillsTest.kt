package io.github.yuloong07star.luwi.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能删除那三条护栏 ([AppSkills.remove]) 里唯一没有设备也能量的那一条: 名字能不能当目录名用
 *
 * 为什么值得单测: 删技能是这条链上**唯一不可逆**的动作, 而"删哪儿"由一个名字决定 —— 带斜杠的名字
 * 会指到 `$DSH_HOME/skills/` 外面去 (主人 2026-10-10: "设置里的 skill 板块加上删除 skill 按钮")
 */
class AppSkillsTest {

    /**
     * 远端那份 `index.json` (`yuloong07-star/app-skills`) → 目录条目: 字段对应与容错
     *
     * 那一份的形状与本机随包的 `catalog.json` **不是同一个** (它是 `apps: [{id,name,package,path,…}]`),
     * 所以这一个映射写错了就是"设置页那段什么都列不出来" —— 没有设备也能量
     */
    @Test
    fun `远端 index 映射成目录条目`() {
        val json = """
            {"repo":"app-skills","updated":"2026-10-11","apps":[
              {"id":"android-vivo-notes","name":"原子笔记","package":"com.android.notes","path":"skills/android-vivo-notes/SKILL.md"},
              {"id":"android-vivo-camera","package":"com.android.camera"},
              {"id":"no-package-here","name":"坏条目"},
              {"name":"没有 id"}
            ]}
        """.trimIndent()
        val entries = AppSkills.remoteEntries(json)
        // 缺 package 的那条与缺 id 的那条都不要, 剩下两条 (其中一条缺 name, 用 id 顶上)
        assertTrue("缺 package / 缺 id 的都不要", entries.size == 2)
        assertTrue(entries[0].skill == "android-vivo-notes")
        assertTrue(entries[0].title == "原子笔记")
        assertTrue(entries[0].apps == listOf("com.android.notes"))
        assertTrue("缺 name 时用 id 当显示名", entries[1].title == "android-vivo-camera")
        assertTrue("缺 package 的那条不要", entries.none { it.skill == "no-package-here" })
    }

    @Test
    fun `远端 index 不是 JSON 或没有 apps 时回空表`() {
        assertTrue(AppSkills.remoteEntries("not json").isEmpty())
        assertTrue(AppSkills.remoteEntries("""{"repo":"app-skills"}""").isEmpty())
    }

    @Test
    fun `技能名只许是目录名`() {
        with(AppSkills) {
            assertTrue("android-vivo-camera".isSafeSkillName())
            assertTrue("camp-schedule-sync".isSafeSkillName())
            assertTrue("中文名也行".isSafeSkillName())
            for (bad in listOf("", ".", "..", "a/b", "/etc/passwd", "a\\b", "../dsh-home")) {
                assertFalse("$bad 不该过", bad.isSafeSkillName())
            }
        }
    }
}
