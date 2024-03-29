package ink.ptms.chemdah.core.quest

import ink.ptms.chemdah.api.ChemdahAPI
import ink.ptms.chemdah.api.event.collect.QuestEvents
import ink.ptms.chemdah.core.PlayerProfile
import ink.ptms.chemdah.core.quest.AgentType.Companion.toAgentType
import ink.ptms.chemdah.core.quest.addon.Addon
import ink.ptms.chemdah.core.quest.meta.Meta
import ink.ptms.chemdah.core.quest.meta.MetaType
import ink.ptms.chemdah.util.*
import io.izzel.taboolib.kotlin.kether.KetherShell
import io.izzel.taboolib.util.Reflection
import org.bukkit.configuration.ConfigurationSection
import java.util.concurrent.CompletableFuture

/**
 * Chemdah
 * ink.ptms.chemdah.core.quest.MetaContainer
 *
 * @author sky
 * @since 2021/3/4 12:45 上午
 */
abstract class QuestContainer(val id: String, val config: ConfigurationSection) {

    /**
     * 元数据容器
     */
    protected val metaMap = config.getConfigurationSection("meta")?.getKeys(false)?.mapNotNull {
        val meta = ChemdahAPI.getQuestMeta(it)
        if (meta != null) {
            val metaType = if (meta.isAnnotationPresent(MetaType::class.java)) {
                meta.getAnnotation(MetaType::class.java).type
            } else {
                MetaType.Type.ANY
            }
            it to Reflection.instantiateObject(meta, metaType[config, "meta.$it"], this) as Meta<*>
        } else {
            warning("$it meta not supported.")
            null
        }
    }?.toMap() ?: emptyMap()

    /**
     * 扩展列表
     */
    protected val addonMap = config.getKeys(false)
        .filter { it.startsWith("addon:") }
        .mapNotNull {
            val addonId = it.substring("addon:".length)
            val addon = ChemdahAPI.getQuestAddon(addonId)
            if (addon != null) {
                addonId to Reflection.instantiateObject(addon, config.getConfigurationSection(it)!!, this) as Addon
            } else {
                warning("$addonId addon not supported.")
                null
            }
        }.toMap()

    /**
     * 脚本代理列表
     */
    protected val agentList = config.getKeys(false)
        .filter { it.startsWith("agent:") }
        .map {
            val args = it.substring("agent:".length).split("@").map { a -> a.trim() }
            val type = when (this) {
                is Template -> "quest_${args[0]}"
                is Task -> "task_${args[0]}"
                else -> args[0]
            }
            Agent(
                type.toAgentType(),
                config.get(it)!!.asList(),
                args.getOrNull(1) ?: "self"
            )
        }

    /**
     * 返回所有组件名称
     */
    val addons: Set<String>
        get() = addonMap.keys

    /**
     * 返回所有脚本代理类型
     */
    val agents: List<String>
        get() = agentList.map { "${it.type.name} @ ${it.restrict}" }

    /**
     * 当前节点
     * 任务则返回任务序号，条目则返回条目序号
     */
    val node: String
        get() = when (this) {
            is Template -> id
            is Task -> template.id
            else -> "null"
        }

    /**
     * 任务路径
     * 作为持久化储存的唯一标识符
     */
    val path: String
        get() = when (this) {
            is Template -> id
            is Task -> "${template.id}.${id}"
            else -> "null"
        }

    @Suppress("UNCHECKED_CAST")
    fun <T : Meta<*>> meta(metaId: String): T? {
        return metaMap[metaId] as? T?
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Addon> addon(addonId: String): T? {
        return addonMap[addonId] as? T?
    }

    /**
     * 获取正在进行中的所属任务
     */
    fun getQuest(profile: PlayerProfile, openAPI: Boolean = false): Quest? {
        return when (this) {
            is Template -> profile.getQuests(openAPI).firstOrNull { it.id == id }
            is Task -> template.getQuest(profile, openAPI)
            else -> null
        }
    }

    /**
     * 获取有效的脚本代理列表
     */
    fun getAgentList(agentType: AgentType, restrict: String = "self"): List<Agent> {
        return agentList.filter { it.type == agentType && (it.restrict == "*" || it.restrict == "all" || it.restrict == restrict) }
    }

    /**
     * 指定脚本代理
     * 当高优先级的脚本代理取消行为时后续脚本代理将不再运行
     */
    fun agent(profile: PlayerProfile, agentType: AgentType, restrict: String = "self", reason: String? = null): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        mirrorFuture("QuestContainer:agent") {
            if (QuestEvents.Agent(this@QuestContainer, profile, agentType, restrict).call().isCancelled) {
                future.complete(false)
                finish()
            }
            val agent = getAgentList(agentType, restrict)
            fun process(cur: Int) {
                if (cur < agent.size) {
                    try {
                        KetherShell.eval(agent[cur].action, namespace = agentType.namespaceAll()) {
                            sender = profile.player
                            rootFrame().variables().also { vars ->
                                vars.set("reason", reason)
                                vars.set("@QuestContainer", this@QuestContainer)
                            }
                        }.thenApply {
                            if (it is Boolean && !it) {
                                future.complete(false)
                            } else {
                                process(cur + 1)
                            }
                        }
                    } catch (e: Throwable) {
                        e.print()
                    }
                } else {
                    future.complete(true)
                }
            }
            process(0)
            finish()
        }
        return future
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuestContainer) return false
        if (path != other.path) return false
        return true
    }

    override fun hashCode(): Int {
        return path.hashCode()
    }
}