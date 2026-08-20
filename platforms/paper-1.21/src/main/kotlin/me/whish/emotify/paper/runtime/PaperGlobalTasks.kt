package me.whish.emotify.paper.runtime

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Server
import org.bukkit.plugin.java.JavaPlugin

internal object PaperGlobalTasks {
    private val isGlobalThreadContext = ThreadLocal.withInitial { false }
    val isFolia: Boolean = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        true
    }.getOrDefault(false)

    fun isGlobalThread(server: Server): Boolean =
        if (isFolia) isGlobalThreadContext.get() else server.isPrimaryThread

    fun <T> withGlobalContext(block: () -> T): T {
        isGlobalThreadContext.set(true)
        try {
            return block()
        } finally {
            isGlobalThreadContext.set(false)
        }
    }

    fun now(plugin: JavaPlugin, task: Runnable) {
        plugin.server.globalRegionScheduler.execute(plugin) {
            withGlobalContext { task.run() }
        }
    }

    fun now(plugin: JavaPlugin, task: () -> Unit) {
        plugin.server.globalRegionScheduler.execute(plugin) {
            withGlobalContext { task() }
        }
    }

    fun repeating(plugin: JavaPlugin, task: Runnable, periodTicks: Long): ScheduledTask =
        plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            { withGlobalContext { task.run() } },
            1L,
            periodTicks,
        )

    fun async(plugin: JavaPlugin, task: Runnable): ScheduledTask =
        plugin.server.asyncScheduler.runNow(plugin) { task.run() }
}
