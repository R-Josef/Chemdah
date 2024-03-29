package ink.ptms.chemdah.core.conversation.theme

import ink.ptms.chemdah.api.ChemdahAPI
import ink.ptms.chemdah.api.event.collect.ConversationEvents
import ink.ptms.chemdah.core.conversation.ConversationManager
import ink.ptms.chemdah.core.conversation.PlayerReply
import ink.ptms.chemdah.core.conversation.Session
import ink.ptms.chemdah.util.asList
import ink.ptms.chemdah.util.colored
import ink.ptms.chemdah.util.setIcon
import io.izzel.taboolib.Version
import io.izzel.taboolib.module.inject.TListener
import io.izzel.taboolib.util.Coerce
import io.izzel.taboolib.util.item.inventory.MenuBuilder
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture
import javax.script.SimpleBindings

/**
 * Chemdah
 * ink.ptms.chemdah.core.conversation.ThemeChest
 *
 * @author sky
 * @since 2021/2/12 2:08 上午
 */
@TListener
class ThemeChest : Theme<ThemeChestSetting>(), Listener {

    init {
        register("chest")
    }

    override fun createConfig(): ThemeChestSetting {
        return ThemeChestSetting(ConversationManager.conf.getConfigurationSection("theme-chest")!!)
    }

    override fun allowFarewell(): Boolean {
        return false
    }

    override fun onDisplay(session: Session, message: List<String>, canReply: Boolean): CompletableFuture<Void> {
        var end = false
        return session.createDisplay { replies ->
            MenuBuilder.builder()
                .lockHand()
                .title(settings.title.toTitle(session))
                .rows(rows(replies.size))
                .buildAsync {
                    replies.forEachIndexed { index, playerReply ->
                        if (index < settings.playerSlot.size) {
                            it.setItem(settings.playerSlot[index], settings.playerItem.buildItem(session, playerReply, index + 1))
                        }
                    }
                    it.setItem(settings.npcSlot, settings.npcItem.buildItem(session, message))
                    // 唤起事件
                    ConversationEvents.ChestThemeBuild(session, message, canReply, it)
                }.click { e ->
                    replies.getOrNull(settings.playerSlot.indexOf(e.rawSlot))?.run {
                        check(session).thenAccept { check ->
                            if (check) {
                                end = true
                                select(session).thenAccept {
                                    // 若未进行页面切换则关闭页面
                                    if (session.player.openInventory.topInventory == e.inventory) {
                                        session.player.closeInventory()
                                    }
                                }
                            }
                        }
                    }
                }.close {
                    if (!end) {
                        session.close(refuse = true)
                    }
                }.open(session.player)
        }
    }


    private fun ItemStack.buildItem(session: Session, reply: PlayerReply, index: Int): ItemStack {
        val icon = reply.root["icon"]?.toString()
        if (icon != null) {
            setIcon(icon)
        }
        itemMeta = itemMeta.also { meta ->
            meta.setDisplayName(meta.displayName.replace("{index}", index.toString()).replace("{playerSide}", reply.build(session)))
        }
        return this
    }

    private fun ItemStack.buildItem(session: Session, message: List<String>): ItemStack {
        val icon = session.conversation.root.getString("npc icon")
        if (icon != null) {
            setIcon(icon)
        }
        itemMeta = itemMeta.also { meta ->
            meta.setDisplayName(meta.displayName.toTitle(session))
            meta.lore = meta.lore?.flatMap { line ->
                if (line.contains("{npcSide}")) {
                    message.map { line.replace("{npcSide}", it) }
                } else {
                    line.asList()
                }
            }
        }
        return this
    }

    private fun String.toTitle(session: Session): String {
        return replace("{title}", session.conversation.option.title.replace("{name}", session.npcName)).colored()
    }

    private fun rows(size: Int): Int {
        return try {
            Coerce.toInteger(settings.rows?.eval(SimpleBindings(mapOf("\$size" to size))) ?: 1)
        } catch (ex: Exception) {
            1
        }
    }
}