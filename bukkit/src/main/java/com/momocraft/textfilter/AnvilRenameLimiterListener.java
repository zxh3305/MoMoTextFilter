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
            // 不取消事件（取消事件仍会消耗经验等级），
            // 改为将结果物品的显示名称替换为过滤后的版本，让玩家正常取走。
            String filteredName = detection.getFilteredText();
            meta.displayName(legacySerializer.deserialize(filteredName));
            result.setItemMeta(meta);
            // 同步更新铁砧结果槽，确保取走的是过滤后的物品
            anvil.setItem(2, result);

            plugin.getCrossMessageTracker().removePlayer(player.getUniqueId());
            plugin.sendWarnings(player, plugin.getConfigManager().getContextName("anvil"),
                    detection.getFirstBannedWord(), detection.getFirstLevel(), detection.getDetectedWords());
        }
    }

    private BannedWordDetection filterTextWithDetection(String text) {
        if (text == null || text.isEmpty()) {
            return new BannedWordDetection(text);
        }

        boolean fuzzyMatch = plugin.getConfigManager().isFuzzyMatchEnable();
        CharGapLimits defaultLimits = plugin.getConfigManager().getDefaultMaxCharGap();
        boolean reverseMatch = plugin.getConfigManager().isReverseMatchEnable();

        // 使用 filterAllWithRecheck：替换后继续复核，检出二次组合的违禁词
        return ColorCodeUtils.filterAllWithRecheck(text, plugin.getConfigManager().getBannedWordsByLevel(),
                fuzzyMatch, defaultLimits, plugin.getConfigManager().getMaxCharGapByLevel(),
                reverseMatch, plugin.getConfigManager().getReverseMatchByLevel(), plugin.getConfigManager().getWhitelist());
    }
}