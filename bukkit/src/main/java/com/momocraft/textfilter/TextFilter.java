package com.momocraft.textfilter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TextFilter extends JavaPlugin {

    private ConfigManager configManager;
    private CrossMessageTracker crossMessageTracker;
    private PunishmentManager punishmentManager;
    private final MiniMessage miniMessage;

    public TextFilter() {
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        crossMessageTracker = new CrossMessageTracker(this);
        punishmentManager = new PunishmentManager(this);
        
        getServer().getPluginManager().registerEvents(new TextFilterListener(this), this);
        getServer().getPluginManager().registerEvents(new AnvilRenameLimiterListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatFilterListener(this), this);
        getServer().getPluginManager().registerEvents(new ExternalPluginListener(this), this);
        
        getCommand("textfilter").setExecutor(new TextFilterCommand(this));
        
        SchedulerCompat.runTaskTimer(this, () -> {
            crossMessageTracker.cleanupAll();
            punishmentManager.cleanupAll();
        }, 300, 300);
        
        checkSoftDependencies();
        
        getLogger().info("MoMoTextFilter 插件已启用！");
    }

    private void checkSoftDependencies() {
        List<String> missingPlugins = new ArrayList<>();
        
        if (Bukkit.getPluginManager().getPlugin("CMI") == null) {
            missingPlugins.add("CMI");
        }
        
        if (!missingPlugins.isEmpty()) {
            String pluginList = String.join(", ", missingPlugins);
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("plugins", pluginList);
            String warning = configManager.getMessage("no-dependency-warning", placeholders);
            getLogger().warning(warning);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("MoMoTextFilter 插件已禁用！");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public CrossMessageTracker getCrossMessageTracker() {
        return crossMessageTracker;
    }

    public PunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    public void sendWarnings(Player player, String context) {
        sendWarnings(player, context, "", "");
    }

    public void sendWarnings(Player player, String context, String bannedWord, String level) {
        sendWarnings(player, context, bannedWord, level, null);
    }

    public void sendWarnings(Player player, String context, String bannedWord, String level, List<BannedWordDetection.BannedWordInfo> allDetectedWords) {
        String playerWarning = getPlayerWarningForLevel(level);
        Component playerMessage = miniMessage.deserialize(playerWarning);
        player.sendMessage(playerMessage);

        Map<String, String> adminPlaceholders = new HashMap<>();
        adminPlaceholders.put("player", player.getName());
        adminPlaceholders.put("context", context);
        
        if (allDetectedWords != null && !allDetectedWords.isEmpty()) {
            List<String> words = new ArrayList<>();
            for (BannedWordDetection.BannedWordInfo info : allDetectedWords) {
                words.add(info.getWord());
            }
            adminPlaceholders.put("bannedword", String.join(", ", words));
        } else {
            adminPlaceholders.put("bannedword", bannedWord);
        }
        
        adminPlaceholders.put("level", level);
        String adminWarning = getAdminWarningForLevel(level, adminPlaceholders);
        Component adminMessage = miniMessage.deserialize(adminWarning);

        for (Player onlinePlayer : getServer().getOnlinePlayers()) {
            if (onlinePlayer.hasPermission("textfilter.admin")) {
                onlinePlayer.sendMessage(adminMessage);
            }
        }

        if (level != null && !level.isEmpty()) {
            punishmentManager.onTriggered(player, level);
        }
    }

    private String getPlayerWarningForLevel(String level) {
        if (level != null && !level.isEmpty()) {
            String levelWarning = configManager.getMessage("levels." + level + ".player-warning");
            if (!levelWarning.equals("levels." + level + ".player-warning")) {
                return levelWarning;
            }
        }
        return configManager.getMessage("warnings.player-warning");
    }

    private String getAdminWarningForLevel(String level, Map<String, String> placeholders) {
        if (level != null && !level.isEmpty()) {
            String levelWarning = configManager.getMessage("levels." + level + ".admin-warning", placeholders);
            if (!levelWarning.equals("levels." + level + ".admin-warning")) {
                return levelWarning;
            }
        }
        return configManager.getMessage("warnings.admin-warning", placeholders);
    }
}
