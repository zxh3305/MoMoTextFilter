package com.momocraft.textfilter;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class PunishmentManager {

    private final TextFilterVelocity plugin;
    private final ConcurrentHashMap<UUID, Map<String, Integer>> triggerCounts;

    public PunishmentManager(TextFilterVelocity plugin) {
        this.plugin = plugin;
        this.triggerCounts = new ConcurrentHashMap<>();
    }

    public void onTriggered(Player player, String level) {
        if (level == null || level.isEmpty()) {
            return;
        }

        Map<String, Map<Integer, List<String>>> punishCommands = plugin.getConfigManager().getPunishCommands();
        Map<Integer, List<String>> levelCommands = punishCommands.get(level);
        if (levelCommands == null || levelCommands.isEmpty()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        Map<String, Integer> playerCounts = triggerCounts.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        int currentCount = playerCounts.getOrDefault(level, 0) + 1;
        playerCounts.put(level, currentCount);

        List<String> commands = levelCommands.get(currentCount);
        if (commands != null && !commands.isEmpty()) {
            executeCommands(player, commands);
        }
    }

    private void executeCommands(Player player, List<String> commands) {
        ProxyServer proxy = plugin.getProxy();
        String playerName = player.getUsername();

        for (String command : commands) {
            String finalCommand = command.replace("%player%", playerName);
            proxy.getScheduler().buildTask(plugin, () -> {
                proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), finalCommand);
            }).schedule();
        }
    }

    public void resetPlayer(UUID playerId) {
        triggerCounts.remove(playerId);
    }

    public void resetPlayer(UUID playerId, String level) {
        Map<String, Integer> playerCounts = triggerCounts.get(playerId);
        if (playerCounts != null) {
            playerCounts.remove(level);
            if (playerCounts.isEmpty()) {
                triggerCounts.remove(playerId);
            }
        }
    }

    public int getTriggerCount(UUID playerId, String level) {
        Map<String, Integer> playerCounts = triggerCounts.get(playerId);
        if (playerCounts == null) {
            return 0;
        }
        return playerCounts.getOrDefault(level, 0);
    }

    public void cleanupAll() {
        triggerCounts.clear();
    }
}
