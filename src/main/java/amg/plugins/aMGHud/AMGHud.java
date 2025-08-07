package amg.plugins.aMGHud;

import org.bstats.bukkit.Metrics;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.*;

public class AMGHud extends JavaPlugin implements Listener {
    private Plugin amgCore;
    private Method getPlayerDataManagerMethod;
    private Method getPlayerDataMethod;
    private Method getMoneyMethod;
    private Method getJobMethod;
    private boolean amgCoreEnabled = false;
    private Map<UUID, BossBar> playerBossBars = new HashMap<>();
    private Map<UUID, Scoreboard> playerScoreboards;
    private boolean placeholderAPIEnabled;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    private LuckPerms luckPerms;

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();
        int pluginId = 26730;
        Metrics metrics = new Metrics(this, pluginId);
        // Initialize variables
        playerScoreboards = new HashMap<>();

        // Setup AMGCore using reflection to avoid class loader issues
        setupAMGCore();

        placeholderAPIEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (placeholderAPIEnabled) {
            getLogger().info("PlaceholderAPI found! Placeholders will be available.");
        } else {
            getLogger().warning("PlaceholderAPI not found! Placeholders will not work.");
        }

        // Initialize LuckPerms
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                luckPerms = LuckPermsProvider.get();
                getLogger().info("LuckPerms found! LuckPerms placeholders will be available.");
            } catch (Exception e) {
                getLogger().warning("Failed to initialize LuckPerms: " + e.getMessage());
                if (getConfig().getBoolean("debug", false)) {
                    e.printStackTrace();
                }
            }
        } else {
            getLogger().warning("LuckPerms not found! LuckPerms placeholders will not work.");
        }

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Initialize HUD elements
        if (getConfig().getBoolean("bossbar.enabled")) {
            initializeBossBar();
        }

        // Start update tasks
        startUpdateTasks();

        getLogger().info("AMGHud has been enabled!");
    }

    private void setupAMGCore() {
        amgCore = Bukkit.getPluginManager().getPlugin("AMGCore");
        if (amgCore == null) {
            getLogger().warning("AMGCore plugin not found! AMGCore features (money, job) will be disabled.");
            getLogger().info("The plugin will continue to work with basic features (server info, player info, etc.)");
            amgCoreEnabled = false;
            return;
        }

        try {
            // Use reflection to access methods to avoid class loader issues
            getPlayerDataManagerMethod = amgCore.getClass().getMethod("getPlayerDataManager");
            Object playerDataManager = getPlayerDataManagerMethod.invoke(amgCore);
            
            if (playerDataManager == null) {
                getLogger().warning("AMGCore PlayerDataManager is null! AMGCore features will be disabled.");
                amgCoreEnabled = false;
                return;
            }
            
            getPlayerDataMethod = playerDataManager.getClass().getMethod("getPlayerData", Player.class);
            amgCoreEnabled = true;
            getLogger().info("Successfully connected to AMGCore API v" + amgCore.getPluginMeta().getVersion());
        } catch (Exception e) {
            getLogger().warning("Failed to setup AMGCore integration: " + e.getMessage());
            if (getConfig().getBoolean("debug", false)) {
                e.printStackTrace();
            }
            amgCoreEnabled = false;
        }
    }

    @Override
    public void onDisable() {
        // Clean up all player boss bars
        playerBossBars.values().forEach(BossBar::removeAll);
        playerBossBars.clear();
        
        playerScoreboards.clear();
        getLogger().info("AMGHud has been disabled!");
    }

    private void initializeBossBar() {
        // Clean up existing boss bars first
        playerBossBars.values().forEach(BossBar::removeAll);
        playerBossBars.clear();

        // Initialize boss bars for online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(getConfig().getString("bossbar.permission", "amghud.bossbar"))) {
                createPlayerBossBar(player);
            }
        }
    }

    private BossBar createPlayerBossBar(Player player) {
        FileConfiguration config = getConfig();
        BarColor color = BarColor.valueOf(config.getString("bossbar.color", "YELLOW"));
        BarStyle style = BarStyle.valueOf(config.getString("bossbar.style", "SOLID"));
        List<String> flagStrings = config.getStringList("bossbar.flags");
        BarFlag[] flags = flagStrings.stream()
                .map(BarFlag::valueOf)
                .toArray(BarFlag[]::new);

        BossBar bar = Bukkit.createBossBar(colorize("&7Loading..."), color, style, flags);
        bar.setProgress(1.0);
        bar.setVisible(true);
        bar.addPlayer(player);
        
        playerBossBars.put(player.getUniqueId(), bar);
        return bar;
    }

    private void startUpdateTasks() {
        FileConfiguration config = getConfig();
        int bossBarInterval = config.getInt("bossbar.update-interval", 20);
        int tabInterval = config.getInt("tab.update-interval", 20);

        // BossBar update task
        if (config.getBoolean("bossbar.enabled")) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    updateBossBar();
                }
            }.runTaskTimer(this, 0L, bossBarInterval);
        }

        // Tab update task
        if (config.getBoolean("tab.enabled")) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    updateTabScoreboards();
                }
            }.runTaskTimer(this, 0L, tabInterval);
        }
    }

    private Object getPlayerData(Player player) {
        if (!amgCoreEnabled || player == null || amgCore == null) {
            return null;
        }
        
        try {
            Object playerDataManager = getPlayerDataManagerMethod.invoke(amgCore);
            return getPlayerDataMethod.invoke(playerDataManager, player);
        } catch (Exception e) {
            if (getConfig().getBoolean("debug", false)) {
                getLogger().warning("Failed to get player data for " + player.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        }
    }

    private double getPlayerMoney(Object playerData) {
        if (!amgCoreEnabled || playerData == null) {
            return 0.0;
        }
        
        try {
            if (getMoneyMethod == null) {
                getMoneyMethod = playerData.getClass().getMethod("getMoney");
            }
            double money = (double) getMoneyMethod.invoke(playerData);
            
            // Validate the money value
            if (Double.isNaN(money) || Double.isInfinite(money)) {
                if (getConfig().getBoolean("debug", false)) {
                    getLogger().warning("Invalid money value returned: " + money + ", defaulting to 0.0");
                }
                return 0.0;
            }
            return money;
        } catch (Exception e) {
            if (getConfig().getBoolean("debug", false)) {
                getLogger().warning("Failed to get player money: " + e.getMessage());
                e.printStackTrace();
            }
            return 0.0;
        }
    }

    private String getPlayerJob(Object playerData) {
        if (!amgCoreEnabled || playerData == null) {
            return null; // Return null instead of default job when AMGCore is disabled
        }
        
        try {
            if (getJobMethod == null) {
                getJobMethod = playerData.getClass().getMethod("getJob");
            }
            String jobId = (String) getJobMethod.invoke(playerData);
            return getLocalizedJobTitle(jobId);
        } catch (Exception e) {
            if (getConfig().getBoolean("debug", false)) {
                getLogger().warning("Failed to get player job: " + e.getMessage());
                e.printStackTrace();
            }
            return null;
        }
    }
    
    private String getLocalizedJobTitle(String jobId) {
        if (jobId == null || jobId.isEmpty() || jobId.equals("unknown")) {
            jobId = "unemployed";
        }
        String title = getConfig().getString("job-titles." + jobId.toLowerCase(), null);
        if (title == null) {
            // If no localization found, capitalize first letter of job ID
            title = "&7" + jobId.substring(0, 1).toUpperCase() + jobId.substring(1).toLowerCase();
        }
        return title;
    }

    private String replaceServerPlaceholders(String text) {
        if (text == null) return "";
        return text.replace("%server_name%", getConfig().getString("tab.server-name", "My Server"))
                  .replace("%server_online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                  .replace("%server_max_players%", String.valueOf(Bukkit.getMaxPlayers()))
                  .replace("%server_tps%", String.format("%.1f", Bukkit.getTPS()[0]));
    }

    private String replaceAMGCorePlaceholders(String text, Player player) {
        if (text == null || player == null) {
            return text != null ? text : "";
        }
        
        // If AMGCore is not enabled, remove AMGCore placeholders entirely
        if (!amgCoreEnabled) {
            // Remove AMGCore placeholders and any surrounding text patterns
            text = text.replaceAll("%amgcore_money%[^|]*\\|?\\s*", "")
                      .replaceAll("\\|?\\s*%amgcore_money%[^|]*", "")
                      .replaceAll("%amgcore_job%[^|]*\\|?\\s*", "")
                      .replaceAll("\\|?\\s*%amgcore_job%[^|]*", "")
                      .replaceAll("%amgcore_money%", "")
                      .replaceAll("%amgcore_job%", "")
                      .replaceAll("\\|\\s*\\|", "|") // Clean up double separators
                      .replaceAll("^\\s*\\|\\s*", "") // Clean up leading separators
                      .replaceAll("\\s*\\|\\s*$", "") // Clean up trailing separators
                      .trim();
            return text;
        }
        
        Object playerData = getPlayerData(player);
        if (playerData != null) {
            try {
                double money = getPlayerMoney(playerData);
                String job = getPlayerJob(playerData);
                
                text = text.replace("%amgcore_money%", String.format("%.2f", money));
                if (job != null) {
                    text = text.replace("%amgcore_job%", job);
                } else {
                    // Remove job placeholder if job is null
                    text = text.replaceAll("%amgcore_job%[^|]*\\|?\\s*", "")
                              .replaceAll("\\|?\\s*%amgcore_job%[^|]*", "")
                              .replaceAll("%amgcore_job%", "");
                }
            } catch (Exception e) {
                if (getConfig().getBoolean("debug", false)) {
                    getLogger().warning("Error replacing AMGCore placeholders for player " + player.getName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
                // Remove placeholders on error
                text = text.replaceAll("%amgcore_money%", "")
                          .replaceAll("%amgcore_job%", "");
            }
        } else {
            // Remove placeholders when player data is not available
            text = text.replaceAll("%amgcore_money%", "")
                      .replaceAll("%amgcore_job%", "");
        }
        
        // Clean up any remaining formatting issues
        text = text.replaceAll("\\|\\s*\\|", "|")
                  .replaceAll("^\\s*\\|\\s*", "")
                  .replaceAll("\\s*\\|\\s*$", "")
                  .trim();
        
        return text;
    }

    private String colorize(String text) {
        if (text == null) return "";
        return text.replace('&', '§');
    }

    private void updateBossBar() {
        String format = getConfig().getString("bossbar.format", "");
        String permission = getConfig().getString("bossbar.permission", "amghud.bossbar");
        
        // Remove offline players' bossbars
        playerBossBars.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                entry.getValue().removeAll();
                return true;
            }
            return false;
        });

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(permission)) {
                BossBar bar = playerBossBars.remove(player.getUniqueId());
                if (bar != null) {
                    bar.removeAll();
                }
                continue;
            }

            // Get or create player's bossbar
            BossBar bar = playerBossBars.computeIfAbsent(player.getUniqueId(), uuid -> createPlayerBossBar(player));

            String text = format;
            
            // Replace server placeholders
            text = replaceServerPlaceholders(text);
            
            // Replace AMGCore placeholders
            text = replaceAMGCorePlaceholders(text, player);
            
            // Replace player-specific placeholders
            text = text.replace("%player_name%", player.getName())
                      .replace("%player_ping%", String.valueOf(player.getPing()));

            // Replace PlaceholderAPI placeholders last
            if (placeholderAPIEnabled) {
                text = PlaceholderAPI.setPlaceholders(player, text);
            }

            // Update the title
            bar.setTitle(colorize(text));
        }
    }

    private void updateTabScoreboards() {
        FileConfiguration config = getConfig();
        String header = config.getString("tab.header", "");
        String footer = config.getString("tab.footer", "");
        String playerFormat = config.getString("tab.player-format", "%luckperms_prefix%%player_name% &8[&e%player_ping%ms&8]");
        String sortBy = config.getString("tab.sort-by", "NAME").toUpperCase();
        String permission = config.getString("tab.permission", "amghud.tab");
        boolean hideDefaultName = config.getBoolean("tab.hide-default-name", true);
        Team.OptionStatus collisionRule = Team.OptionStatus.valueOf(config.getString("tab.collision-rule", "NEVER"));
        Team.OptionStatus nameTagVisibility = Team.OptionStatus.valueOf(config.getString("tab.name-tag-visibility", "NEVER"));
        boolean removeVanillaTab = config.getBoolean("tab.remove-vanilla-tab", true);

        // Debug logging
        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("Updating tab scoreboards with format: " + playerFormat);
            getLogger().info("PlaceholderAPI enabled: " + placeholderAPIEnabled);
            getLogger().info("LuckPerms available: " + (luckPerms != null));
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(permission)) continue;

            Scoreboard scoreboard = playerScoreboards.computeIfAbsent(player.getUniqueId(), k -> Bukkit.getScoreboardManager().getNewScoreboard());
            
            // Process header and footer
            String processedHeader = header;
            String processedFooter = footer;

            // Replace server placeholders
            processedHeader = replaceServerPlaceholders(processedHeader);
            processedFooter = replaceServerPlaceholders(processedFooter);

            // Replace AMGCore placeholders
            processedHeader = replaceAMGCorePlaceholders(processedHeader, player);
            processedFooter = replaceAMGCorePlaceholders(processedFooter, player);

            // Replace player-specific placeholders
            processedHeader = processedHeader.replace("%player_name%", player.getName())
                                          .replace("%player_ping%", String.valueOf(player.getPing()));
            processedFooter = processedFooter.replace("%player_name%", player.getName())
                                          .replace("%player_ping%", String.valueOf(player.getPing()));

            // Replace PlaceholderAPI placeholders last
            if (placeholderAPIEnabled) {
                processedHeader = PlaceholderAPI.setPlaceholders(player, processedHeader);
                processedFooter = PlaceholderAPI.setPlaceholders(player, processedFooter);
            }

            // Set header/footer
            player.sendPlayerListHeaderAndFooter(
                legacySerializer.deserialize(colorize(processedHeader)),
                legacySerializer.deserialize(colorize(processedFooter))
            );

            // Sort players based on configuration
            List<Player> sortedPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (sortBy.equals("PING")) {
                sortedPlayers.sort((p1, p2) -> Integer.compare(p1.getPing(), p2.getPing()));
            } else if (sortBy.equals("GROUP") && luckPerms != null) {
                sortedPlayers.sort((p1, p2) -> {
                    User user1 = luckPerms.getUserManager().getUser(p1.getUniqueId());
                    User user2 = luckPerms.getUserManager().getUser(p2.getUniqueId());
                    String prefix1 = user1 != null ? user1.getCachedData().getMetaData().getPrefix() : "";
                    String prefix2 = user2 != null ? user2.getCachedData().getMetaData().getPrefix() : "";
                    if (prefix1 == null) prefix1 = "";
                    if (prefix2 == null) prefix2 = "";
                    return prefix2.compareTo(prefix1); // Sort by prefix in reverse order
                });
            } else {
                // Default to sorting by name
                sortedPlayers.sort(Comparator.comparing(Player::getName));
            }

            // Update player list
            for (Player target : sortedPlayers) {
                String teamName = target.getName().length() > 14 ? target.getName().substring(0, 14) : target.getName();
                Team team = scoreboard.getTeam(teamName);
                if (team == null) {
                    team = scoreboard.registerNewTeam(teamName);
                }

                String displayName = playerFormat;
                
                // Replace server placeholders
                displayName = replaceServerPlaceholders(displayName);
                
                // Replace AMGCore placeholders
                displayName = replaceAMGCorePlaceholders(displayName, target);
                
                // Replace player-specific placeholders
                displayName = displayName.replace("%player_name%", target.getName())
                                       .replace("%player_ping%", String.valueOf(target.getPing()));

                // Replace PlaceholderAPI placeholders last
                if (placeholderAPIEnabled) {
                    String before = displayName;
                    displayName = PlaceholderAPI.setPlaceholders(target, displayName);
                    
                    // Debug logging
                    if (getConfig().getBoolean("debug", false) && !before.equals(displayName)) {
                        getLogger().info("PlaceholderAPI replaced: " + before + " -> " + displayName);
                    }
                }

                // Set the team prefix with the formatted display name
                Component formattedName = legacySerializer.deserialize(colorize(displayName));
                team.prefix(formattedName);
                
                // Hide default name if configured
                if (hideDefaultName) {
                    team.suffix(Component.empty());
                }
                
                team.addEntry(target.getName());
                
                // Set team options from config
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, nameTagVisibility);
                team.setOption(Team.Option.COLLISION_RULE, collisionRule);

                // Set player list name if removing vanilla tab
                if (removeVanillaTab) {
                    target.playerListName(formattedName);
                }
            }

            player.setScoreboard(scoreboard);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Add player to bossbar if they have permission
        if (player.hasPermission(getConfig().getString("bossbar.permission", "amghud.bossbar")) && getConfig().getBoolean("bossbar.enabled")) {
            createPlayerBossBar(player);
        }
        
        // Force update tab list for all players
        if (getConfig().getBoolean("tab.enabled")) {
            // Run the update in the next tick to ensure all data is ready
            getServer().getScheduler().runTask(this, () -> {
                updateTabScoreboards();
            });
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // Remove and cleanup player's bossbar
        BossBar bar = playerBossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removeAll();
        }
        
        playerScoreboards.remove(player.getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("amghud")) {
            return false;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            
            // Reinitialize bossbars if enabled
            if (getConfig().getBoolean("bossbar.enabled")) {
                initializeBossBar();
            } else {
                // Clean up all bossbars if disabled
                playerBossBars.values().forEach(BossBar::removeAll);
                playerBossBars.clear();
            }
            
            // Force update tab list
            updateTabScoreboards();
            
            sender.sendMessage("§aAMGHud configuration reloaded!");
            return true;
        } else if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            if (sender.hasPermission("amghud.admin")) {
                boolean debug = !getConfig().getBoolean("debug", false);
                getConfig().set("debug", debug);
                saveConfig();
                sender.sendMessage("§aDebug mode " + (debug ? "enabled" : "disabled"));
                return true;
            } else {
                sender.sendMessage("§cYou don't have permission to use this command!");
                return true;
            }
        }

        sender.sendMessage("§cUsage: /amghud reload|debug");
        return true;
    }
}