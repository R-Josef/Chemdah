package ink.ptms.chemdah.core.quest.objective.bukkit

import ink.ptms.chemdah.core.quest.objective.Dependency
import ink.ptms.chemdah.core.quest.objective.ObjectiveCountableI
import org.bukkit.event.player.PlayerMoveEvent

/**
 * Chemdah
 * ink.ptms.chemdah.core.quest.objective.bukkit.IPlayerMove
 *
 * @author sky
 * @since 2021/3/2 5:09 下午
 */
@Dependency("minecraft")
object IPlayerMove : ObjectiveCountableI<PlayerMoveEvent>() {

    override val name = "player move"
    override val event = PlayerMoveEvent::class
    override val isAsync = true

    init {
        handler {
            if (from.toVector() != to.toVector()) player else null
        }
        addCondition("position") { e ->
            toPosition().inside(e.to)
        }
        addCondition("position:to") { e ->
            toPosition().inside(e.to)
        }
        addCondition("position:from") { e ->
            toPosition().inside(e.from)
        }
    }
}