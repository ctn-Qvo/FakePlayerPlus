package com.coderxi.plugin.fakeplayer.config

import com.coderxi.plugin.fakeplayer.api.entity.FakePlayerSettings.DeathAction
import com.coderxi.plugin.fakeplayer.provider.invsee.AdvancedInvseeProvider
import com.coderxi.plugin.fakeplayer.provider.invsee.InvseeProvider
import com.coderxi.plugin.fakeplayer.provider.invsee.OpenInvInvseeProvider
import com.coderxi.plugin.fakeplayer.provider.invsee.VanillaInvseeProvider
import eu.okaeri.configs.OkaeriConfig
import eu.okaeri.configs.annotation.*

class FakePlayerPlusPluginConfig : OkaeriConfig() {

    @Comment("插件限制设置")
    var limit = LimitConfig()
    class LimitConfig : OkaeriConfig() {

        @Comment("全服创建数量上限")
        @CustomKey("server-spawn")
        var serverSpawn: Int = 999

        @Comment("玩家创建数量上限 (需要权限: fakeplayer.spawn)")
        @CustomKey("player-spawn")
        var playerSpawn: Int = 3

        @Comment("自定义创建数量权限 (需要手动设置玩家/权限组权限: fakeplayer.spawn.limit.<权限名>)")
        @CustomKey("custom-spawn")
        var customSpawn: Map<String, Int> = hashMapOf("vip" to 10)

        @Comment("玩家IP创建数量上限")
        @CustomKey("ip-spawn")
        var ipSpawn: Int = 3

        @Comment("根据服务器TPS动态调整玩家创建数量上限")
        @CustomKey("tps-adaptive")
        var tpsAdaptive = TpsAdaptiveLimitConfig()
        class TpsAdaptiveLimitConfig : OkaeriConfig() {

            @Comment("是否启用此功能")
            var enabled = true

            @Comment("检测间隔 (单位:秒)")
            var interval = 120

            @Comment("检测阈值 (检测TPS低于此值时，将逐步降低假人上限，高于此值则恢复)")
            var threshold = 17.0

            @Comment("最低假人上限")
            @CustomKey("min-count")
            var minCount = 1

        }
    }

    @Comment("假人名称功能")
    var name = NameConfig()
    class NameConfig : OkaeriConfig() {

        @Comment("假人名称允许的字符(正则表达式)")
        @CustomKey("spawn-pattern")
        var pattern = Regex("^[a-zA-Z0-9_]+$")

        @Comment("按序号生成假人名称时的前缀")
        @CustomKey("sequence-name-prefix")
        var sequenceNamePrefix = ""

    }

    @Comment("假人皮肤功能")
    var skin = SkinConfig()
    class SkinConfig : OkaeriConfig() {

        @Comment("如果假人未被 /fp skin 设置过皮肤则使用此皮肤")
        @Comment("NONE: 不设置皮肤")
        @Comment("SPAWNER: 跟随生成者皮肤")
        @Comment("player1: 固定为一个皮肤")
        @Comment("player1,player2: 从数组中随机设置")
        @CustomKey("default")
        var default = "SPAWNER"

    }

    @Comment("假人默认设置")
    @CustomKey("default-settings")
    var defaultSettings = FakePlayerSettingsConfig()

    @Comment("强制覆盖假人的设置，如果你希望服务器假人统一应用某个选项并不可修改，可以在这里设置")
    @CustomKey("override-settings")
    var overrideSettings = mapOf(
        "deathAction" to DeathAction.QUIT.name
    )

    @Comment(
        "假人生命周期指令绑定",
        "(无前缀)假人自身执行 变量 {uuid} {name} {spawner_uuid} {spawner_name}",
        "[CONSOLE]控制台执行 变量同上",
        "[SPAWNER]创建者执行 变量同上",
        "[OWNERS]全部所有者都会执行 额外变量 {owner_uuid} {owner_name}"
    )
    @CustomKey("lifecycle-commands")
    var lifecycleCommands = LifecycleCommandsConfig()
    class LifecycleCommandsConfig : OkaeriConfig() {
        @Comment("假人刚被初始化 (尚未建立网络连接) 此时无法通过假人自身执行(必须带前缀)")
        var preparing: List<String> = arrayListOf(
            "[CONSOLE] /lp user {uuid} parent set bot"
        )

        @Comment("假人已建立网络连接并注册到了假人列表 (尚未进入世界)")
        @Comment("此阶段可以添加 /register 和 /login 方法进行认证")
        @Comment("例如: /register sjkJFln1il sjkJFln1il , /login sjkJFln1il")
        var connected: List<String> = arrayListOf(
            ""
        )

        @Comment("假人已进入世界")
        var spawned: List<String> = arrayListOf(
            "/tell {spawner_name} 你好，我来了！"
        )

        @Comment("假人触发退出事件 (仍在世界中)")
        var quit: List<String> = arrayListOf(
            "/tell {spawner_name} 再见，我先走了！"
        )

        @CustomKey("post-quit")
        @Comment("假人完全退出 此时无法通过假人自身执行(必须带前缀)")
        var quited: List<String> = arrayListOf(
            "[CONSOLE] /tell {spawner_name} 你创建的假人 {name} 已被移除"
        )
    }

    @Comment("其他杂项设置")
    var msic = MiscConfig()
    class MiscConfig : OkaeriConfig() {

        @Comment("假人背包查看器")
        @Comment("ADVANCED: 高级(装备栏+副手+快捷栏切换)")
        @Comment("VANILLA: 原版(不支持查看装备栏)")
        @Comment("OPENINV: 需单独安装(装备栏+副手+合成) https://github.com/Jikoo/OpenInv/releases")
        @CustomKey("invsee-type")
        var invseeType =  InvseeProviderType.ADVANCED
        enum class InvseeProviderType(val providerClass: Class<out InvseeProvider>) {
            ADVANCED(AdvancedInvseeProvider::class.java),
            VANILLA(VanillaInvseeProvider::class.java),
            OPENINV(OpenInvInvseeProvider::class.java)
        }

        @Comment("防止假人被其他插件踢掉，这个选项用来兼容一些插件因为某些问题而踢掉假人")
        @Comment("NEVER: 不进行任何处理")
        @Comment("SPAWNING: 创建时防止被踢出")
        @CustomKey("prevent-kicking")
        var preventKicking = PreventKickingType.SPAWNING
        enum class PreventKickingType { NEVER, SPAWNING }

        @Comment("假人ping初始值")
        @Comment("可以设置固定值 或者用 20,50 表示在 20-50 范围内的随机值")
        @CustomKey("ping-init")
        var pingInit = "20,50"

        @Comment("模拟真实ping抖动")
        @CustomKey("ping-jitter")
        var pingJitter = true

        @Comment("ping值抖动间隔 (单位:秒)")
        @CustomKey("ping-jitter-interval")
        var pingJitterInterval = 3

        @Comment("自动注册与登录(使用随机密码)")
        @Comment("目前只支持 AuthMe 系列插件，其他系列登录插件请使用 lifecycle-commands.connected 添加 /login 和 /register 的方式进行验证")
        @CustomKey("auto-auth")
        var autoAuth = true

    }

}
