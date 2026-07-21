package com.momocraft.textfilter;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

public class SchedulerCompat {

    private static boolean isFolia = false;

    static {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            isFolia = true;
        } catch (ClassNotFoundException e) {
            isFolia = false;
        }
    }

    public static boolean isFolia() {
        return isFolia;
    }

    public static void runTaskLater(Plugin plugin, Location location, Runnable task, long delayTicks) {
        if (isFolia) {
            try {
                Method getRegionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler");
                Object regionScheduler = getRegionScheduler.invoke(Bukkit.getServer());
                
                Method runDelayed = regionScheduler.getClass().getMethod("runDelayed", Plugin.class, Location.class, Consumer.class, long.class);
                runDelayed.invoke(regionScheduler, plugin, location, (Consumer<?>) scheduledTask -> task.run(), delayTicks);
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("[SchedulerCompat] Failed to use Folia RegionScheduler: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public static void runTaskLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (isFolia) {
            try {
                Method getScheduler = entity.getClass().getMethod("getScheduler");
                Object entityScheduler = getScheduler.invoke(entity);
                
                Method runDelayed = entityScheduler.getClass().getMethod("runDelayed", Plugin.class, Entity.class, Consumer.class, long.class);
                runDelayed.invoke(entityScheduler, plugin, entity, (Consumer<?>) scheduledTask -> task.run(), delayTicks);
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("[SchedulerCompat] Failed to use Folia EntityScheduler: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public static void runTaskTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (isFolia) {
            try {
                Method getGlobalRegionScheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
                Object globalRegionScheduler = getGlobalRegionScheduler.invoke(Bukkit.getServer());
                
                Method runAtFixedRate = globalRegionScheduler.getClass().getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                runAtFixedRate.invoke(globalRegionScheduler, plugin, (Consumer<?>) scheduledTask -> task.run(), delayTicks, periodTicks);
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("[SchedulerCompat] Failed to use Folia GlobalRegionScheduler runAtFixedRate: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    public static void runTask(Plugin plugin, Runnable task) {
        if (isFolia) {
            try {
                Method getGlobalRegionScheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
                Object globalRegionScheduler = getGlobalRegionScheduler.invoke(Bukkit.getServer());
                
                Method run = globalRegionScheduler.getClass().getMethod("run", Plugin.class, Consumer.class);
                run.invoke(globalRegionScheduler, plugin, (Consumer<?>) scheduledTask -> task.run());
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("[SchedulerCompat] Failed to use Folia GlobalRegionScheduler run: " + e.getMessage());
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
