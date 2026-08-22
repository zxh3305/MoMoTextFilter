package com.momocraft.textfilter;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.HashMap;
import java.util.Map;

public class TextFilterCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, MoMoTextFilterMod mod) {
        dispatcher.register(
            Commands.literal("textfilter")
                .requires(source -> {
                    if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p) {
                        return PermissionHelper.hasCommandPermission(p);
                    }
                    return true; // Console always has permission
                })
                .executes(ctx -> sendHelp(ctx, mod))
                .then(Commands.literal("reload")
                    .executes(ctx -> handleReload(ctx, mod)))
                .then(Commands.literal("info")
                    .executes(ctx -> handleInfo(ctx, mod)))
        );
    }

    private static int handleReload(CommandContext<CommandSourceStack> ctx, MoMoTextFilterMod mod) {
        mod.getConfigManager().loadConfig();
        ctx.getSource().sendSystemMessage(
            ComponentFormatter.format(mod.getConfigManager().getMessage("warnings.reload-success"))
        );
        return 1;
    }

    private static int handleInfo(CommandContext<CommandSourceStack> ctx, MoMoTextFilterMod mod) {
        var source = ctx.getSource();
        source.sendSystemMessage(ComponentFormatter.format(mod.getConfigManager().getMessage("info.title")));
        source.sendSystemMessage(ComponentFormatter.format(mod.getConfigManager().getMessage("info.version")));
        source.sendSystemMessage(ComponentFormatter.format(mod.getConfigManager().getMessage("info.author")));
        source.sendSystemMessage(ComponentFormatter.format(mod.getConfigManager().getMessage("info.desc")));

        Map<String, String> countPlaceholders = new HashMap<>();
        countPlaceholders.put("count", String.valueOf(mod.getConfigManager().getBannedWords().size()));
        source.sendSystemMessage(ComponentFormatter.format(mod.getConfigManager().getMessage("info.banned-words-count", countPlaceholders)));

        source.sendSystemMessage(ComponentFormatter.format(mod.getConfigManager().getMessage("info.footer")));
        return 1;
    }

    private static int sendHelp(CommandContext<CommandSourceStack> ctx, MoMoTextFilterMod mod) {
        var source = ctx.getSource();
        source.sendSystemMessage(ComponentFormatter.format(mod.getConfigManager().getMessage("command.help-title")));
        source.sendSystemMessage(ComponentFormatter.format(mod.getConfigManager().getMessage("command.help-reload")));
        source.sendSystemMessage(ComponentFormatter.format(mod.getConfigManager().getMessage("command.help-info")));
        source.sendSystemMessage(ComponentFormatter.format(mod.getConfigManager().getMessage("command.help-footer")));
        return 1;
    }
}