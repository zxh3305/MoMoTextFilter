package com.momocraft.textfilter;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 权限检查助手，优先使用 LuckPerms，未安装时回退到 OP 检查。
 * <p>
 * 权限节点无需在 LuckPerms 中显式注册即可工作，管理员直接在 LuckPerms
 * 控制台创建节点即可（如 /lp user &lt;player&gt; permission set textfilter.anvil.bypass true）。
 * </p>
 * <p>
 * 支持的权限节点：
 * <ul>
 *   <li>{@code textfilter.admin} — 接收管理员违规警告</li>
 *   <li>{@code textfilter.command} — 允许使用 /textfilter 命令</li>
 *   <li>{@code textfilter.anvil.bypass} — 绕过铁砧命名过滤</li>
 * </ul>
 * </p>
 */
public class PermissionHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("MoMoTextFilter");
    private static LuckPerms luckPerms;
    private static boolean luckPermsAvailable;
    private static boolean luckPermsChecked;

    /**
     * 检查玩家是否拥有指定权限。
     * 优先使用 LuckPerms，若不可用则回退到 OP 检查。
     */
    public static boolean hasPermission(ServerPlayer player, String permission) {
        if (tryLuckPerms(player.getUUID(), permission)) {
            return true;
        }
        // 回退：检查 OP 状态（level >= 2）
        return checkOp(player);
    }

    /**
     * 检查命令权限（/textfilter 命令）。
     * LuckPerms 不可用时回退到 true（允许使用），确保无 LP 时命令仍可用。
     */
    public static boolean hasCommandPermission(ServerPlayer player) {
        if (tryLuckPerms(player.getUUID(), "textfilter.command")) {
            return true;
        }
        // LuckPerms 不可用时，允许所有玩家使用命令
        return true;
    }

    private static boolean tryLuckPerms(UUID uuid, String permission) {
        if (!luckPermsChecked) {
            try {
                luckPerms = LuckPermsProvider.get();
                luckPermsAvailable = true;
                LOGGER.info("LuckPerms 集成已启用");
            } catch (Exception e) {
                luckPermsAvailable = false;
                LOGGER.info("LuckPerms 未安装，使用 OP 权限检查");
            }
            luckPermsChecked = true;
        }

        if (!luckPermsAvailable || luckPerms == null) {
            return false;
        }

        try {
            User user = luckPerms.getUserManager().getUser(uuid);
            if (user != null) {
                return user.getCachedData().getPermissionData()
                        .checkPermission(permission).asBoolean();
            }
        } catch (Exception e) {
            LOGGER.warn("LuckPerms 权限检查失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * OP 回退检查：通过 {@link NameAndId} 查询服务器 OP 列表。
     * 需要 {@link MoMoTextFilterMod#getServer()} 已就绪（游戏运行中）。
     */
    private static boolean checkOp(ServerPlayer player) {
        MinecraftServer server = MoMoTextFilterMod.getInstance().getServer();
        if (server == null) return false;
        try {
            // 26.1.2 使用 NameAndId 记录 OP 名单，而非 GameProfile
            NameAndId nameAndId = new NameAndId(player.getUUID(), player.getName().getString());
            return server.getPlayerList().isOp(nameAndId);
        } catch (Exception e) {
            LOGGER.warn("OP 检查失败: {}", e.getMessage());
            return false;
        }
    }
}