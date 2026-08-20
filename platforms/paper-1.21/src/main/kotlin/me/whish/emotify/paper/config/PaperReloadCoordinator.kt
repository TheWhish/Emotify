package me.whish.emotify.paper.config

import java.util.UUID
import java.util.logging.Level
import me.whish.emotify.paper.PaperPermissions
import me.whish.emotify.paper.runtime.PaperGlobalTasks
import me.whish.emotify.paper.runtime.PaperReloadAdmission
import me.whish.emotify.paper.runtime.PaperReloadGate
import me.whish.emotify.paper.runtime.PaperReloadTicket
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

data class PaperConfigurationApplyResult(
    val changed: Boolean,
    val queuedPolicyRefreshes: Int,
)

internal class PaperConfigurationApplyTransaction<T : Any, R : Any>(
    private val current: () -> T,
    private val apply: (T) -> R,
) {
    fun execute(replacement: T): R {
        val previous = current()
        try {
            return apply(replacement)
        } catch (failure: RuntimeException) {
            try {
                apply(previous)
            } catch (rollbackFailure: RuntimeException) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }
}

class PaperReloadCoordinator(
    private val plugin: JavaPlugin,
    private val loader: BukkitPaperConfigLoader,
    private val state: PaperConfigurationState,
    private val gate: PaperReloadGate,
    private val apply: (PaperRuntimeConfig) -> PaperConfigurationApplyResult,
) : CommandExecutor {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (!plugin.server.isPrimaryThread) {
            PaperGlobalTasks.now(plugin) { executeReloadCommand(sender, label, args) }
            return true
        }
        executeReloadCommand(sender, label, args)
        return true
    }

    private fun executeReloadCommand(sender: CommandSender, label: String, args: Array<out String>) {
        check(plugin.server.isPrimaryThread) { "Paper commands must run on the primary server thread" }
        if (args.size != 1 || !args[0].equals("reload", ignoreCase = true)) {
            sender.sendMessage("Usage: /$label reload")
            return
        }
        if (!sender.hasPermission(PaperPermissions.ADMIN_RELOAD)) {
            sender.sendMessage("You do not have permission to reload Emotify.")
            return
        }
        val requester = ReloadRequester.from(sender)
        when (val admission = gate.tryBegin()) {
            is PaperReloadAdmission.Admitted -> beginReload(requester, admission.ticket)
            PaperReloadAdmission.Pending -> sender.sendMessage("An Emotify reload is already in progress.")
            PaperReloadAdmission.RateLimited -> sender.sendMessage("Wait one second before reloading Emotify again.")
        }
    }

    fun shutdown() {
        gate.invalidate()
    }

    private fun beginReload(requester: ReloadRequester, ticket: PaperReloadTicket) {
        try {
            PaperGlobalTasks.async(plugin) {
                val result = loader.load()
                try {
                    PaperGlobalTasks.now(plugin) {
                        completeReload(requester, ticket, result)
                    }
                } catch (exception: RuntimeException) {
                    gate.complete(ticket)
                    plugin.logger.log(Level.SEVERE, "Failed to schedule Emotify configuration apply", exception)
                }
            }
        } catch (exception: RuntimeException) {
            gate.complete(ticket)
            plugin.logger.log(Level.SEVERE, "Failed to schedule Emotify configuration load", exception)
            requester.send(plugin, "Failed to schedule the Emotify reload. See the server log.")
        }
    }

    private fun completeReload(
        requester: ReloadRequester,
        ticket: PaperReloadTicket,
        result: PaperConfigLoadResult,
    ) {
        if (!gate.complete(ticket) || !plugin.isEnabled) {
            return
        }
        when (result) {
            is PaperConfigLoadResult.Loaded -> applyLoaded(requester, result.config)
            is PaperConfigLoadResult.Invalid -> {
                plugin.logger.severe("Emotify configuration rejected: ${result.violations.joinToString("; ")}")
                requester.send(plugin, "Emotify configuration is invalid. The previous configuration remains active.")
            }
            is PaperConfigLoadResult.FutureVersion -> {
                plugin.logger.severe(
                    "Emotify configuration schema ${result.version} is newer than supported; the file remains unchanged",
                )
                requester.send(plugin, "Emotify configuration is from a newer version. The previous configuration remains active.")
            }
            is PaperConfigLoadResult.Failed -> {
                plugin.logger.log(Level.SEVERE, "Failed to read Emotify configuration", result.failure)
                requester.send(plugin, "Failed to read Emotify configuration. The previous configuration remains active.")
            }
        }
    }

    private fun applyLoaded(requester: ReloadRequester, config: PaperRuntimeConfig) {
        try {
            val result = apply(config)
            if (!result.changed) {
                requester.send(plugin, "Emotify configuration is already up to date.")
                return
            }
            state.replace(config)
            requester.send(
                plugin,
                "Emotify reloaded. Queued ${result.queuedPolicyRefreshes} client policy refreshes.",
            )
        } catch (exception: RuntimeException) {
            plugin.logger.log(Level.SEVERE, "Failed to apply Emotify configuration", exception)
            requester.send(plugin, "Failed to apply Emotify configuration. See the server log.")
        }
    }

    private sealed interface ReloadRequester {
        fun send(plugin: JavaPlugin, message: String)

        data class PlayerRequester(val playerId: UUID) : ReloadRequester {
            override fun send(plugin: JavaPlugin, message: String) {
                plugin.server.getPlayer(playerId)?.sendMessage(message)
            }
        }

        data object ConsoleRequester : ReloadRequester {
            override fun send(plugin: JavaPlugin, message: String) {
                plugin.server.consoleSender.sendMessage(message)
            }
        }

        companion object {
            fun from(sender: CommandSender): ReloadRequester =
                if (sender is Player) PlayerRequester(sender.uniqueId) else ConsoleRequester
        }
    }
}
