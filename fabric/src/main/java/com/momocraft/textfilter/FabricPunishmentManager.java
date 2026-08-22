package com.momocraft.textfilter;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FabricPunishmentManager {

    private final MoMoTextFilterMod mod;
    private final ConcurrentHashMap<UUID, Map<String, Integer>> triggerCounts;

    public FabricPunishmentManager(MoMoTextFilterMod mod) {
        this.mod = mod;
        this.triggerCounts = new ConcurrentHashMap<>();
    }

    public void onTriggered(ServerPlayer player, String level) {
        if (level == null || level.isEmpty()) {
            return;
        }

        Map<String, Map<Integer, List<String>>> punishCommands = mod.getConfigManager().getPunishCommands();
        Map<Integer, List<String>> levelCommands = punishCommands.get(level);
        if (levelCommands == null || levelCommands.isEmpty()) {
            return;
        }

        UUID playerId = player.getUUID();
        Map<String, Integer> playerCounts = triggerCounts.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        int currentCount = playerCounts.getOrDefault(level, 0) + 1;
        playerCounts.put(level, currentCount);

        List<String> commands = levelCommands.get(currentCount);
        if (commands != null && !commands.isEmpty()) {
            executeCommands(player, commands);
        }
    }

    private void executeCommands(ServerPlayer player, List<String> commands) {
        MinecraftServer server = mod.getServer();
        if (server == null) {
            return;
        }

        server.execute(() -> {
            CommandSourceStack console = server.createCommandSourceStack();
            String playerName = player.getName().getString();

            for (String command : commands) {
                String finalCommand = command.replace("%player%", playerName);
                server.getCommands().performPrefixedCommand(console, finalCommand);
            }
        });
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