package me.whish.emotify.paper.runtime

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.plugin.java.JavaPlugin

internal object PaperGlobalTasks {
    fun now(plugin: JavaPlugin, task: Runnable) {
        plugin.server.globalRegionScheduler.execute(plugin, task)
    }

    fun repeating(plugin: JavaPlugin, task: Runnable, periodTicks: Long): ScheduledTask =
        plugin.server.globalRegionScheduler.runAtFixedRate(
            plugin,
            { task.run() },
            1L,
            periodTicks,
        )

    fun async(plugin: JavaPlugin, task: Runnable): ScheduledTask =
        plugin.server.asyncScheduler.runNow(plugin) { task.run() }
}
