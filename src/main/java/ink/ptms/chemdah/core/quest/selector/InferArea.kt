package ink.ptms.chemdah.core.quest.selector

import ink.ptms.chemdah.util.warning
import io.izzel.taboolib.kotlin.navigation.pathfinder.bukkit.BoundingBox
import io.izzel.taboolib.util.Coerce
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.util.Vector

/**
 * Chemdah
 * ink.ptms.chemdah.util.selector.InferArea
 *
 * @author sky
 * @since 2021/3/2 2:51 下午
 */
abstract class InferArea(val source: String, val noWorld: Boolean) {

    fun inside(vector: Vector): Boolean {
        return inside(vector.toLocation(Bukkit.getWorlds()[0]))
    }

    abstract fun inside(location: Location): Boolean

    /**
     * world 0 0 0 > 10 10 10
     */
    class Area(source: String, noWorld: Boolean) : InferArea(source, noWorld) {

        val world: String
        val box: BoundingBox

        init {
            val index = if (noWorld) 0 else 1
            val args = source.split(" ")
            world = args[0]
            box = BoundingBox(
                Coerce.toDouble(args[index + 0]),
                Coerce.toDouble(args[index + 1]),
                Coerce.toDouble(args[index + 2]),
                Coerce.toDouble(args[index + 4]),
                Coerce.toDouble(args[index + 5]),
                Coerce.toDouble(args[index + 6])
            )
        }

        override fun inside(location: Location): Boolean {
            return (noWorld || world == location.world?.name) && box.contains(location.x, location.y, location.z)
        }
    }

    /**
     * world 0 0 0 ~ 10
     */
    class Range(source: String, noWorld: Boolean) : InferArea(source, noWorld) {

        val position: Location
        val r: Double

        init {
            val args = source.split(" ")
            if (noWorld) {
                r = Coerce.toDouble(args[4])
                position = Location(Bukkit.getWorlds()[0], Coerce.toDouble(args[0]), Coerce.toDouble(args[1]), Coerce.toDouble(args[2]))
            } else {
                r = Coerce.toDouble(args[5])
                position = Location(Bukkit.getWorld(args[0]), Coerce.toDouble(args[1]), Coerce.toDouble(args[2]), Coerce.toDouble(args[3]))
            }
        }

        override fun inside(location: Location): Boolean {
            return (noWorld || position.world == location.world) && position.distance(location) <= r
        }
    }

    /**
     * world 0 0 0 & 1 1 1 & 2 2 2
     */
    class Single(source: String, noWorld: Boolean) : InferArea(source, noWorld) {

        val positions = ArrayList<Location>()

        init {
            source.split("&").forEach {
                val args = it.trim().split(" ")
                positions += if (args.size == 3) {
                    Location(null, Coerce.toDouble(args[0]), Coerce.toDouble(args[1]), Coerce.toDouble(args[2]))
                } else {
                    Location(Bukkit.getWorld(args[0]), Coerce.toDouble(args[1]), Coerce.toDouble(args[2]), Coerce.toDouble(args[3]))
                }
            }
        }

        override fun inside(location: Location): Boolean {
            return positions.any {
                (it.world == null || it.world == location.world)
                        && it.blockX == location.blockX
                        && it.blockY == location.blockY
                        && it.blockZ == location.blockZ
            }
        }
    }

    class Unrecognized(source: String, val message: String): InferArea(source, false) {

        override fun inside(location: Location): Boolean {
            warning("Unrecognized area format: $source ($message)")
            return false
        }
    }

    companion object {

        fun String.toInferArea(noWorld: Boolean = false): InferArea {
            return try {
                when {
                    ">" in this -> Area(this, noWorld)
                    "~" in this -> Range(this, noWorld)
                    else -> Single(this, noWorld)
                }
            } catch (e: Throwable) {
                Unrecognized(this, e.message.toString())
            }
        }
    }
}