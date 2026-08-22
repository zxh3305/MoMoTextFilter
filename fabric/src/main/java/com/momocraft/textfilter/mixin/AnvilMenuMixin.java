package com.momocraft.textfilter.mixin;

import com.momocraft.textfilter.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 铁砧重命名检测。
 * 注入 {@link AnvilMenu#onTake(Player, ItemStack)} 被调用时（玩家取走铁砧结果物品），
 * 检查结果物品的显示名称，若含违禁词则就地过滤并发送警告。
 * <p>
 * 注意：此方法在物品被移出铁砧结果槽后调用，参数中的 {@code stack} 即玩家取走的物品，
 * 对其就地修改（{@link ItemStack#set}）会直接反映到玩家背包/手持物品中。
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {

    @Inject(method = "onTake", at = @At("HEAD"))
    private void onAnvilTake(Player player, ItemStack stack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (stack == null || stack.isEmpty()) return;

        MoMoTextFilterMod mod = MoMoTextFilterMod.getInstance();
        if (mod == null) return;

        // Bypass: OP 玩家或 LuckPerms 授予 textfilter.anvil.bypass 者跳过
        if (PermissionHelper.hasPermission(serverPlayer, "textfilter.anvil.bypass")) {
            return;
        }

        // 只检查有自定义名称的物品（玩家重命名过才需要检测）
        if (!stack.has(DataComponents.CUSTOM_NAME)) {
            return;
        }

        String displayName = stack.getHoverName().getString();
        if (displayName == null || displayName.isEmpty()) {
            return;
        }

        BannedWordDetection detection = filterTextWithDetection(displayName, mod.getConfigManager());
        boolean foundBannedWord = !displayName.equals(detection.getFilteredText());

        if (foundBannedWord) {
            // 就地过滤名称（影响玩家背包/手持中的物品）
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(detection.getFilteredText()));
            // 发送警告
            mod.getCrossMessageTracker().removePlayer(serverPlayer.getUUID());
            mod.sendWarnings(serverPlayer, mod.getConfigManager().getContextName("anvil"),
                    detection.getFirstBannedWord(), detection.getFirstLevel(), detection.getDetectedWords());
        }
    }

    private BannedWordDetection filterTextWithDetection(String text, FabricConfigManager config) {
        if (text == null || text.isEmpty()) {
            return new BannedWordDetection(text);
        }

        boolean fuzzyMatch = config.isFuzzyMatchEnable();
        CharGapLimits defaultLimits = config.getDefaultMaxCharGap();
        boolean reverseMatch = config.isReverseMatchEnable();

        return ColorCodeUtils.filterAllWithRecheck(text, config.getBannedWordsByLevel(),
                fuzzyMatch, defaultLimits, config.getMaxCharGapByLevel(),
                reverseMatch, config.getReverseMatchByLevel(), config.getWhitelist());
    }
}