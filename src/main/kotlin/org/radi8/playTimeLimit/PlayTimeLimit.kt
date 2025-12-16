package org.radi8.playTimeLimit

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import kotlin.math.abs
import kotlin.use

data class Config(
    val kickTime: Long,
    val kickMessage: String,
    val kickBroadcast: String,
    val kickIgnoreUsers: List<String>,
    var timezone: String
)

data class Coords(
    val x: Double,
    val y: Double,
)

class PlayTimeLimit : JavaPlugin(), CommandExecutor, Listener {
    val playtime: MutableMap<UUID, Long> = ConcurrentHashMap()
    var coords: MutableMap<UUID, Coords> = ConcurrentHashMap()
    private lateinit var pluginConfig: Config

    companion object {
        private val ARG_OPTIONS = listOf("all", "today")
    }

    private var connection: Connection? = null

    private fun getDatabaseFile(): File {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        return File(dataFolder, "database.db")
    }

    private fun initializeDatabase() {
        val dbFile = getDatabaseFile()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"

        try {
            connection = DriverManager.getConnection(url)
            logger.info("successfully connected to sqlite!")

            connection?.createStatement().use { statement ->
                statement?.execute(
                    """
                        CREATE TABLE IF NOT EXISTS playtime (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid VARCHAR(36) NOT NULL,
                            length INTEGER NOT NULL,
                            time INTEGER DEFAULT (strftime('%s', 'now'))
                        )
                    """.trimIndent()
                )
                logger.info("database table 'playtime' init'd!")
            }
        } catch (e: SQLException) {
            logger.severe("failed to connect to or init sqlite: ${e.message}")
            server.pluginManager.disablePlugin(this);
        }
    }

    private fun closeDatabaseConnection() {
        try {
            connection?.takeIf { !it.isClosed }?.close()
            logger.info("db connection closed")
        } catch (e: SQLException) {
            logger.severe("failed to close db connection: ${e.message}")
        }
    }

    fun getPluginConfig(): Config {
        val kickTime = config.getLong("kick-time")
        val kickMessage = config.getString("kick-message") ?: ""
        val kickBroadcast = config.getString("kick-broadcast") ?: ""
        val kickIgnoreUsers = config.getStringList("kick-ignore-users")
        val timezone = config.getString("timezone") ?: "Etc/UTC"

        return Config(
            kickTime = kickTime,
            kickMessage = kickMessage,
            kickBroadcast = kickBroadcast,
            kickIgnoreUsers = kickIgnoreUsers,
            timezone = timezone,
        )
    }

    override fun onEnable() {
        initializeDatabase()
        if (connection == null || connection?.isClosed == true) {
            logger.severe("db connection failed, disabling plugin")
            server.pluginManager.disablePlugin(this)
        }
        Bukkit.getPluginManager().registerEvents(this, this)

        getCommand("get-time")?.setExecutor(GetTime(this))
        getCommand("get-time")?.tabCompleter = object : TabCompleter {
            override fun onTabComplete(
                sender: CommandSender,
                command: Command,
                alias: String,
                args: Array<out String>
            ): List<String>? {

                if (args.size == 1) {
                    val partialArgument = args[0].lowercase()
                    return ARG_OPTIONS.filter { it.startsWith(partialArgument) }
                }

                return emptyList()
            }
        }
        getCommand("leaderboard")?.setExecutor(Leaderboard(this))

        this.saveDefaultConfig()
        config.addDefault("kick-time", 1)
        config.addDefault(
            "kick-message",
            "You have exceeded your allotted 4 hours on this server today. Try again tomorrow."
        )
        config.addDefault(
            "kick-broadcast",
            "{player} has exceeded their allotted 4 hours on this server today."
        )
        config.addDefault("timezone", "Etc/UTC")
        config.addDefault("kick-ignore-users", listOf<String>())
        config.options().copyDefaults(true)

        pluginConfig = getPluginConfig()

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            server.onlinePlayers.forEach { player ->
                val uuid = player.uniqueId
                val loc = player.location
                val x = loc.x
                val y = loc.y

                if (coords.contains(uuid)) {
                    if (abs(coords[uuid]?.x?.minus(x) ?: 0.0) > 5 || abs(coords[uuid]?.y?.minus(y) ?: 0.0) > 5) {
                        coords[uuid] = Coords(x, y)
                    } else {
                        logger.info("player $uuid is likely afk; not adding time.")
                        return@forEach
                    }
                } else {
                    coords[uuid] = Coords(x, y)
                }

                playtime.compute(uuid) { _, currentTime ->
                    (currentTime ?: 0L) + 1200L
                }

                val playtimePastSessionsToday = getPlaytimePastSessionsToday(uuid)

                val playtimeToday = (playtime[uuid]?.plus(playtimePastSessionsToday * 1200L))?.div(1200L)
                if (playtimeToday != null) {
                    if (!pluginConfig.kickIgnoreUsers.contains(player.uniqueId.toString())) {
                        if (playtimeToday >= pluginConfig.kickTime) {
                            Bukkit.getScheduler().runTask(this, Runnable {
                                if (player.isOnline) {
                                    player.kickPlayer(pluginConfig.kickMessage)
                                }
                                Bukkit.broadcastMessage(pluginConfig.kickBroadcast.replace("{player}", player.name))
                            })
                        }
                    }
                }

                logger.info("$uuid has played for $playtimeToday minute(s) today.")
            }
        }, 0L, 1200L)

        logger.info("${description.name} version ${description.version} enabled!")
    }

    fun pluralize(text: String, num: Long): String {
        var returnText = text

        if (num.toInt() == 0 || num > 1) {
            returnText += "s"
        }

        return returnText
    }

    private fun getPlaytimePastSessionsToday(uuid: UUID): Long {
        val timezoneId = ZoneId.of(pluginConfig.timezone)
        val startOfDay = ZonedDateTime.now(timezoneId)
            .toLocalDate()
            .atStartOfDay(timezoneId)
            .toEpochSecond()

        try {
            connection?.prepareStatement(
                "SELECT SUM(length) FROM playtime WHERE uuid = ? AND time >= ?"
            ).use { preparedStatement ->
                preparedStatement?.setString(1, uuid.toString())
                preparedStatement?.setLong(2, startOfDay)

                val resultSet = preparedStatement?.executeQuery()
                return if (resultSet?.next() == true) {
                    resultSet.getLong(1)
                } else {
                    0L
                }
            }
        } catch (e: SQLException) {
            logger.severe("failed to get playtime for ${uuid}: ${e.message}")
            return 0L
        }
    }

    private fun generateTodaySummarization(): String {
        val sessions = ConcurrentHashMap<String, Long>()

        val timezoneId = ZoneId.of(pluginConfig.timezone)
        val startOfDay = ZonedDateTime.now(timezoneId)
            .toLocalDate()
            .atStartOfDay(timezoneId)
            .toEpochSecond()

        try {
            connection?.prepareStatement(
                "SELECT uuid, SUM(length) as total_length FROM playtime WHERE time >= ? GROUP BY uuid"
            )?.use { preparedStatement ->
                preparedStatement.setLong(1, startOfDay)

                val resultSet = preparedStatement.executeQuery()
                while (resultSet.next()) {
                    val uuid = resultSet.getString("uuid")
                    val totalLength = resultSet.getLong("total_length")
                    sessions[uuid] = totalLength
                }
            }
        } catch (e: SQLException) {
            logger.severe("failed to get today's past sessions summarization: ${e.message}")
        }

        val currentPlaytime = HashMap(playtime)
        currentPlaytime.forEach { (uuid, currentSessionTicks) ->
            val currentSessionMinutes = currentSessionTicks / 1200L
            if (currentSessionMinutes > 0) {
                sessions.compute(uuid.toString()) { _, existingMinutes ->
                    (existingMinutes ?: 0L) + currentSessionMinutes
                }
            }
        }

        if (sessions.isEmpty()) {
            return "No time played today."
        }

        val messageBuilder = StringBuilder()

        var sortedSessions = sessions.toList().sortedByDescending { it.second }.toMap()

        sortedSessions.entries
            .forEachIndexed { i, (uuidString, totalMinutes) ->
                val uuid = UUID.fromString(uuidString)
                val username = Bukkit.getOfflinePlayer(uuid).name ?: "Unknown ($uuidString)"

                if (totalMinutes > 0) {
                    messageBuilder.append("#${i + 1}: $username - $totalMinutes ${pluralize("minute", totalMinutes)}\n")
                }
            }

        if (messageBuilder.isEmpty()) {
            return "No significant time played today."
        }

        return messageBuilder.toString().trimEnd()
    }

    class GetTime(private val plugin: PlayTimeLimit) : CommandExecutor {
        override fun onCommand(
            sender: CommandSender,
            command: Command,
            label: String,
            args: Array<out String?>
        ): Boolean {
            val player = sender as? Player
            if (player != null) {
                if (args.isEmpty()) {
                    sender.sendMessage("Usage: /get-time [today|all]")
                    return true
                }

                val time = plugin.playtime[player.uniqueId] ?: 0L
                val sessionTime = time / 1200L

                // if they use /get-time all - default to anything past epoch second 0
                var lookupTimePast: Long = 0

                var previousTimePlayed: Long = 0

                val subAction = args[0]?.lowercase()

                when (subAction) {
                    "today" -> {
                        val timezoneId = ZoneId.of(plugin.pluginConfig.timezone)
                        lookupTimePast = ZonedDateTime.now(timezoneId)
                            .toLocalDate()
                            .atStartOfDay(timezoneId)
                            .toEpochSecond()
                    }

                    "all" -> {
                        // already assigned
                    }

                    else -> {
                        sender.sendMessage("Usage: /get-time [today|all]")
                        return true
                    }
                }

                try {
                    plugin.connection?.prepareStatement(
                        "SELECT SUM(length) as total_length FROM playtime WHERE time >= ? AND uuid = ?"
                    )?.use { preparedStatement ->
                        preparedStatement.setLong(1, lookupTimePast)
                        preparedStatement.setString(2, player.uniqueId.toString())

                        val resultSet = preparedStatement.executeQuery()
                        while (resultSet.next()) {
                            previousTimePlayed = resultSet.getLong("total_length")
                        }
                    }
                } catch (e: Exception) {
                }

                val totalTime = previousTimePlayed + sessionTime

                if (lookupTimePast.toInt() == 0) {
                    sender.sendMessage(
                        "You've played for $totalTime ${
                            plugin.pluralize(
                                "minute",
                                totalTime
                            )
                        } since the start of this server."
                    )
                } else {
                    sender.sendMessage(
                        "You've played for $totalTime ${
                            plugin.pluralize(
                                "minute",
                                totalTime
                            )
                        } today, and $sessionTime ${
                            plugin.pluralize(
                                "minute",
                                sessionTime
                            )
                        } during this session."
                    )
                }
            } else {
                sender.sendMessage("This command can only be run by a player.")
            }

            return true
        }
    }

    class Leaderboard(private val plugin: PlayTimeLimit) : CommandExecutor {
        override fun onCommand(
            sender: CommandSender,
            command: Command,
            label: String,
            args: Array<out String?>
        ): Boolean {
            val player = sender as? Player
            if (player != null) {
                val message = plugin.generateTodaySummarization()
                sender.sendMessage(message)
            } else {
                sender.sendMessage("This command can only be run by a player.")
            }

            return true
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId

        logger.info("player ${player.name} joined!")

        playtime[uuid] = 0L

        val playtimePastSessionsTodayFuture = CompletableFuture.supplyAsync {
            getPlaytimePastSessionsToday(uuid)
        }

        Bukkit.getScheduler().runTask(this, Runnable {
            val playtimePastSessionsToday = playtimePastSessionsTodayFuture.get()
            val playtimeToday =
                (playtime[uuid]?.plus(playtimePastSessionsToday * 1200L))?.div(1200L) ?: 0L // Provide a default value

            if (!pluginConfig.kickIgnoreUsers.contains(player.uniqueId.toString())) {
                if (playtimeToday >= pluginConfig.kickTime) {
                    Bukkit.getScheduler().runTask(this, Runnable {
                        if (player.isOnline) {
                            player.kickPlayer(pluginConfig.kickMessage)
                        }
                    })
                }
            }
        })
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val uuid = player.uniqueId
        val time = (playtime.remove(uuid) ?: 0L) / 1200L // inserts session time in minutes

        if (time < 1L) {
            return
        }

        logger.info("saving playtime for $uuid: $time minute(s)")

        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
            try {
                connection?.prepareStatement(
                    "INSERT INTO playtime (uuid, length) VALUES (?, ?)"
                ).use { preparedStatement ->
                    preparedStatement?.setString(1, uuid.toString())
                    preparedStatement?.setLong(2, time)
                    preparedStatement?.executeUpdate()

                    logger.info("saved playtime for $uuid: $time minute(s)")
                }
            } catch (e: SQLException) {
                logger.severe("failed to save playtime for $uuid: ${e.message}")
            }
        })
    }

    override fun onDisable() {
        Bukkit.getScheduler().cancelTasks(this)
        server.onlinePlayers.forEach { player ->
            val time = (playtime.remove(player.uniqueId) ?: 0L) / 1200L
            if (time < 1L) {
                return@forEach
            }

            logger.info("saving playtime for ${player.uniqueId}: $time minute(s)")

            try {
                connection?.prepareStatement(
                    "INSERT INTO playtime (uuid, length) VALUES (?, ?)"
                ).use { preparedStatement ->
                    preparedStatement?.setString(1, player.uniqueId.toString())
                    preparedStatement?.setLong(2, time)
                    preparedStatement?.executeUpdate()

                    logger.info("saved playtime for ${player.uniqueId}: $time minute(s)")
                }
            } catch (e: SQLException) {
                logger.severe("failed to save playtime for ${player.uniqueId}: ${e.message}")
            }
        }

        closeDatabaseConnection()
        logger.info("${description.name} disabled.")
    }
}
