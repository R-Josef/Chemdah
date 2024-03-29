package ink.ptms.chemdah.core.conversation.theme

import ink.ptms.chemdah.api.ChemdahAPI.conversationSession
import ink.ptms.chemdah.api.ChemdahAPI.isChemdahProfileLoaded
import ink.ptms.chemdah.core.conversation.ConversationManager
import ink.ptms.chemdah.core.conversation.Session
import ink.ptms.chemdah.util.colored
import io.izzel.taboolib.cronus.CronusUtils
import io.izzel.taboolib.kotlin.Reflex.Companion.reflexInvoke
import io.izzel.taboolib.kotlin.Tasks
import io.izzel.taboolib.kotlin.toPrinted
import io.izzel.taboolib.module.inject.TListener
import io.izzel.taboolib.module.inject.TSchedule
import io.izzel.taboolib.module.locale.TLocale
import io.izzel.taboolib.module.packet.Packet
import io.izzel.taboolib.module.packet.TPacket
import io.izzel.taboolib.module.tellraw.TellrawJson
import io.izzel.taboolib.util.Baffle
import io.izzel.taboolib.util.Coerce
import io.izzel.taboolib.util.lite.Effects
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Chemdah
 * ink.ptms.chemdah.core.conversation.ThemeChat
 *
 * @author sky
 * @since 2021/2/12 2:08 上午
 */
@TListener
class ThemeChat : Theme<ThemeChatSettings>(), Listener {

    init {
        register("chat")
    }

    val baffle = Baffle.of(100, TimeUnit.MILLISECONDS)

    /**
     * 屏蔽其他插件在对话过程中发送的动作栏信息
     * 例如 SkillAPI
     */
    @TPacket(type = TPacket.Type.SEND)
    fun e(player: Player, packet: Packet): Boolean {
        if (packet.equals("PacketPlayOutChat") && packet.read("b")?.toString() == "GAME_INFO") {
            if (player.conversationSession != null && packet.read("a")?.reflexInvoke<String>("getText") != TLocale.asString(player, "theme-chat-help")) {
                return false
            }
        }
        return true
    }

    /**
     * 取消这个行为会出现客户端显示不同步的错误
     * 以及无法 mc 无法重复切换相同物品栏
     */
    @EventHandler
    fun e(e: PlayerItemHeldEvent) {
        val session = e.player.conversationSession ?: return
        if (session.conversation.option.theme == "chat" && !session.npcTalking) {
            val replies = session.playerReplyForDisplay
            if (replies.isNotEmpty()) {
                val index = replies.indexOf(session.playerSide)
                val select = e.newSlot.coerceAtMost(replies.size - 1)
                if (select != index) {
                    session.playerSide = replies[select]
                    CompletableFuture<Void>().npcTalk(session, session.npcSide, "", session.npcSide.size, end = true, canReply = true)
                }
            }
        }
    }

    @EventHandler
    fun e(e: PlayerSwapHandItemsEvent) {
        val session = e.player.conversationSession ?: return
        if (session.conversation.option.theme == "chat") {
            e.isCancelled = true
            if (session.npcTalking) {
                // 是否不允许玩家跳过对话演出效果
                if (session.conversation.hasFlag("NO_SKIP") || session.conversation.hasFlag("FORCE_DISPLAY")) {
                    return
                }
                session.npcTalking = false
            } else {
                session.playerSide?.run {
                    check(session).thenApply {
                        if (it) {
                            select(session)
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    fun e(e: AsyncPlayerChatEvent) {
        val session = e.player.conversationSession ?: return
        if (session.conversation.option.theme == "chat" && !session.npcTalking && CronusUtils.isInt(e.message)) {
            e.isCancelled = true
            session.playerReplyForDisplay.getOrNull(Coerce.toInteger(e.message) - 1)?.run {
                check(session).thenApply {
                    if (it) {
                        select(session)
                    }
                }
            }
        }
    }

    override fun createConfig(): ThemeChatSettings {
        return ThemeChatSettings(ConversationManager.conf.getConfigurationSection("theme-chat")!!)
    }

    override fun onReset(session: Session): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        session.conversation.playerSide.checked(session).thenApply {
            session.playerSide = it.getOrNull(session.player.inventory.heldItemSlot.coerceAtMost(it.size - 1))
            future.complete(null)
        }
        return future
    }

    override fun onBegin(session: Session): CompletableFuture<Void> {
        Effects.create(Particle.CLOUD, session.origin.clone().add(0.0, 0.5, 0.0)).count(5).player(session.player).play()
        return super.onBegin(session)
    }

    override fun onDisplay(session: Session, message: List<String>, canReply: Boolean): CompletableFuture<Void> {
        val future = CompletableFuture<Void>()
        var d = 0L
        var cancel = false
        session.npcTalking = true
        message.colored().map { if (settings.animation) it.toPrinted("_") else listOf(it) }.forEachIndexed { index, messageText ->
            messageText.forEachIndexed { printLine, printText ->
                Tasks.delay(d++) {
                    if (session.isValid) {
                        if (session.npcTalking) {
                            future.npcTalk(session, message, printText, index, printLine + 1 == messageText.size, canReply)
                        } else if (!cancel) {
                            cancel = true
                            future.npcTalk(session, session.npcSide, "", session.npcSide.size, end = true, canReply = true)
                            future.complete(null)
                        }
                    } else if (!cancel) {
                        cancel = true
                        future.complete(null)
                    }
                }
            }
        }
        future.thenAccept {
            session.npcTalking = false
        }
        if (d == 0L) {
            future.complete(null)
        }
        return future
    }

    fun CompletableFuture<Void>.npcTalk(session: Session, messages: List<String>, message: String, index: Int, end: Boolean, canReply: Boolean) {
        session.conversation.playerSide.checked(session).thenApply { replies ->
            newJson().also { json ->
                try {
                    settings.format.forEach {
                        when {
                            it.contains("{title}") -> {
                                json.append(it.replace("{title}", session.conversation.option.title.replace("{name}", session.npcName))).newLine()
                            }
                            it.contains("{npcSide}") -> {
                                messages.colored().forEachIndexed { i, fully ->
                                    when {
                                        index > i -> json.append(it.replace("{npcSide}", fully)).newLine()
                                        index == i -> json.append(it.replace("{npcSide}", message)).newLine()
                                        else -> json.newLine()
                                    }
                                }
                            }
                            it.contains("{playerSide}") -> {
                                session.playerReplyForDisplay.clear()
                                session.playerReplyForDisplay.addAll(replies)
                                if (canReply) {
                                    replies.forEachIndexed { n, reply ->
                                        if (index + 1 >= messages.size && end) {
                                            val text = reply.build(session)
                                            if (session.playerSide == reply) {
                                                json.append(it.replace("{select}", settings.selectChar).replace("{playerSide}", "${settings.selectColor}$text"))
                                                    .hoverText(text)
                                                    .clickCommand("/session reply ${reply.uuid}")
                                                    .newLine()
                                            } else {
                                                json.append(it.replace("{select}", settings.selectOther).replace("{playerSide}", text))
                                                    .hoverText(text)
                                                    .clickCommand("/session reply ${reply.uuid}")
                                                    .newLine()
                                            }
                                        } else {
                                            if (n == 0) {
                                                json.append(settings.talking).newLine()
                                            } else {
                                                json.newLine()
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                json.append(it).newLine()
                            }
                        }
                    }
                } catch (ex: Throwable) {
                    ex.printStackTrace()
                }
            }.send(session.player)
            // 打印完成则结束演示
            if (index + 1 == messages.size && end) {
                complete(null)
            }
        }
        TLocale.Display.sendActionBar(session.player, TLocale.asString(session.player, "theme-chat-help"))
    }

    private fun newJson() = TellrawJson.create().also { json -> repeat(100) { json.newLine() } }
}