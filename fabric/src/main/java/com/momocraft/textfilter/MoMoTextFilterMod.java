package com.momocraft.textfilter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MoMoTextFilterMod implements ModInitializer {

    public static final String MOD_ID = "momotextfilter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static MoMoTextFilterMod instance;
    private FabricConfigManager configManager;
    private CrossMessageTracker crossMessageTracker;
    private FabricPunishmentManager punishmentManager;
    private MinecraftServer server;
    private int tickCounter = 0;

    @Override
    public void onInitialize() {
        instance = this;

        configManager = new FabricConfigManager();
        configManager.loadConfig();

        crossMessageTracker = new CrossMessageTracker(this);
        punishmentManager = new FabricPunishmentManager(this);

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            this.server = server;
            tickCounter++;
            if (tickCounter % 300 == 0) {
                crossMessageTracker.cleanupAll();
                punishmentManager.cleanupAll();
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            TextFilterCommand.register(dispatcher, this);
        });

        LOGGER.info("MoMoTextFilter Mod 已启用！");
    }

    public static MoMoTextFilterMod getInstance() {
        return instance;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public FabricConfigManager getConfigManager() {
        return configManager;
    }

    public CrossMessageTracker getCrossMessageTracker() {
        return crossMessageTracker;
    }

    public FabricPunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    public void sendWarnings(ServerPlayer player, String context, String bannedWord, String level) {
        sendWarnings(player, context, bannedWord, level, null);
    }

    public void sendWarnings(ServerPlayer player, String context, String bannedWord, String level,
                             List<BannedWordDetection.BannedWordInfo> allDetectedWords) {
        String playerWarning = getPlayerWarningForLevel(level);
        player.sendSystemMessage(ComponentFormatter.format(playerWarning));

        Map<String, String> adminPlaceholders = new HashMap<>();
        adminPlaceholders.put("player", player.getName().getString());
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
        Component adminMessage = ComponentFormatter.format(adminWarning);

        if (server != null) {
            for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
                if (PermissionHelper.hasPermission(onlinePlayer, "textfilter.admin")) {
                    onlinePlayer.sendSystemMessage(adminMessage);
                }
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