package com.momocraft.textfilter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class AnvilRenameLimiterListener implements Listener {

    private final TextFilter plugin;
    private final LegacyComponentSerializer legacySerializer;

    public AnvilRenameLimiterListener(TextFilter plugin) {
        this.plugin = plugin;
        this.legacySerializer = LegacyComponentSerializer.legacyAmpersand();
    }

    @EventHandler
    public void onAnvilClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory)) {
            return;
        }

        if (event.getRawSlot() != 2) {
            return;
        }

        AnvilInventory anvil = (AnvilInventory) event.getInventory();
        ItemStack result = anvil.getItem(2);

        if (result == null) {
            return;
        }

        Player player = (Player) event.getWhoClicked();

        if (player.hasPermission("textfilter.anvil.bypass")) {
            return;
        }

        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return;
        }

        Component displayNameComponent = meta.displayName();
        if (displayNameComponent == null) {
            return;
        }

        String displayName = legacySerializer.serialize(displayNameComponent);
        if (displayName == null || displayName.isEmpty()) {
            return;
        }

        BannedWordDetection detection = filterTextWithDetection(displayName);
        boolean foundBannedWord = !displayName.equals(detection.getFilteredText());

        if (foundBannedWord) {
            event.setCancelled(true);
            plugin.getCrossMessageTracker().removePlayer(player.getUniqueId());
            plugin.sendWarnings(player, plugin.getConfigManager().getContextName("anvil"),
                    detection.getFirstBannedWord(), detection.getFirstLevel(), detection.getDetectedWords());

            SchedulerCompat.runTaskLater(plugin, player, () -> {
                ItemStack handItem = player.getInventory().getItemInMainHand();
                if (handItem != null && handItem.hasItemMeta()) {
                    ItemMeta handMeta = handItem.getItemMeta();
                    if (handMeta.displayName() != null) {
                        String handName = legacySerializer.serialize(handMeta.displayName());
                        BannedWordDetection handDetection = filterTextWithDetection(handName);
                        String filteredName = handDetection.getFilteredText();
                        if (!handName.equals(filteredName)) {
                            handMeta.displayName(legacySerializer.deserialize(filteredName));
                            handItem.setItemMeta(handMeta);
                            player.getInventory().setItemInMainHand(handItem);
                        }
                    }
                }
            }, 1);
        }
    }

    private BannedWordDetection filterTextWithDetection(String text) {
        if (text == null || text.isEmpty()) {
            return new BannedWordDetection(text);
        }

        boolean fuzzyMatch = plugin.getConfigManager().isFuzzyMatchEnable();
        int defaultMaxCharGap = plugin.getConfigManager().getDefaultMaxCharGap();
        boolean reverseMatch = plugin.getConfigManager().isReverseMatchEnable();

        return ColorCodeUtils.filterAllBannedWordsWithDetection(text, plugin.getConfigManager().getBannedWordsByLevel(),
                fuzzyMatch, defaultMaxCharGap, plugin.getConfigManager().getMaxCharGapByLevel(),
                reverseMatch, plugin.getConfigManager().getReverseMatchByLevel(), plugin.getConfigManager().getWhitelist());
    }
}