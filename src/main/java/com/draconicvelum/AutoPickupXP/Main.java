package com.draconicvelum.AutoPickupXP;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Main extends JavaPlugin implements Listener, org.bukkit.command.TabCompleter, org.bukkit.command.CommandExecutor {

    private final Map<String, UUID> recentPlayers = new HashMap<>();
    private final Map<UUID, Boolean> enabledPlayers = new HashMap<>();


    private boolean pluginEnabled;
    private int delayTicks;
    private boolean useNearest;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        loadSettings();
        loadToggles();

        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("autopickupxp");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("AutoPickupXP enabled!");
    }

    private void loadSettings() {
        pluginEnabled = getConfig().getBoolean("enabled", true);
        delayTicks = getConfig().getInt("delay-ticks", 2);
        useNearest = getConfig().getBoolean("use-nearest-player", true);
    }

    @Override
    public void onDisable() {
        saveToggles();
        getLogger().info("AutoPickupXP disabled!");
    }

    // ======================
    // TOGGLE SYSTEM
    // ======================

    private void loadToggles() {
        FileConfiguration config = getConfig();

        var section = config.getConfigurationSection("players");

        if (section != null) {
            for (String uuidStr : section.getKeys(false)) {
                enabledPlayers.put(
                        UUID.fromString(uuidStr),
                        section.getBoolean(uuidStr)
                );
            }
        }
    }

    private void saveToggles() {
        FileConfiguration config = getConfig();
        config.set("players", null);

        for (Map.Entry<UUID, Boolean> entry : enabledPlayers.entrySet()) {
            config.set("players." + entry.getKey().toString(), entry.getValue());
        }

        saveConfig();
    }

    private boolean isEnabled(Player player) {
        return enabledPlayers.getOrDefault(player.getUniqueId(), true);
    }

    private void toggle(Player player) {
        UUID uuid = player.getUniqueId();
        boolean newState = !isEnabled(player);

        enabledPlayers.put(uuid, newState);
        saveToggles();
        player.sendMessage("§8[§bAutoPickupXP§8] §7» " + (newState ? "§aEnabled" : "§cDisabled"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§8[§bAutoPickupXP§8] §7» §eOnly players can use this.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("autopickupxp.reload")) {
                player.sendMessage("§8[§bAutoPickupXP§8] §7» §cNo permission.");
                return true;
            }

            reloadConfig();
            loadSettings();
            player.sendMessage("§8[§bAutoPickupXP§8] §7» §aAutoPickupXP config reloaded!");
            return true;
        }

        if (!player.hasPermission("autopickupxp.use")) {
            player.sendMessage("§8[§bAutoPickupXP§8] §7» §cNo permission.");
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("toggle")) {
            toggle(player);
            return true;
        }


        player.sendMessage("§8[§bAutoPickupXP§8] §7» §e/autopickupxp toggle");
        return true;
    }

    // ======================
    // EVENTS
    // ======================
    private String getKey(Location loc) {
        if (loc.getWorld() == null) return "unknown:0:0:0";

        return loc.getWorld().getName() + ":" +
                loc.getBlockX() + ":" +
                loc.getBlockY() + ":" +
                loc.getBlockZ();
    }
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!pluginEnabled) return;
        Player player = event.getPlayer();

        if (!isEnabled(player)) return;

        Location loc = event.getBlock().getLocation();
        String key = getKey(loc);
        recentPlayers.putIfAbsent(key, player.getUniqueId());

        int xp = event.getExpToDrop();
        if (xp > 0) {
            int remainingXp = applyMending(player, xp);
            if (remainingXp > 0) {
                player.giveExp(remainingXp);
            }
            event.setExpToDrop(0);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> recentPlayers.remove(key), 20L);
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        if (!pluginEnabled) return;
        Player killer = event.getEntity().getKiller();

        if (killer == null || !isEnabled(killer)) return;

        Location loc = event.getEntity().getLocation();
        String key = getKey(loc);
        recentPlayers.put(key, killer.getUniqueId());

        int xp = event.getDroppedExp();
        if (xp > 0) {
            int remainingXp = applyMending(killer, xp);
            if (remainingXp > 0) {
                killer.giveExp(remainingXp);
            }
            event.setDroppedExp(0);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> recentPlayers.remove(key), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOrbSpawn(EntitySpawnEvent event) {
        if (!pluginEnabled) return;
        if (!(event.getEntity() instanceof ExperienceOrb orb)) return;


        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!orb.isValid()) return;

            int xp = orb.getExperience();

            Player target = null;

            if (xp <= 0) {
                orb.remove();
                return;
            }

            Location loc = orb.getLocation();
            String key = getKey(loc);
            UUID uuid = recentPlayers.get(key);
            if (uuid != null) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    target = p;
                }
            }

            if (target == null && useNearest) {
                target = getNearestPlayer(orb);
            }
            if (target == null) {
                orb.remove();
                return;
            }

            if (target.isOnline() && isEnabled(target)) {
                int remainingXp = applyMending(target, xp);
                if (remainingXp > 0) {
                    target.giveExp(remainingXp);
                }
            }

            orb.remove();

        }, delayTicks);
    }

    private Player getNearestPlayer(ExperienceOrb orb) {
        Player nearest = null;
        double closestDistance = Double.MAX_VALUE;

        for (Player player : orb.getWorld().getPlayers()) {
            double distance = player.getLocation().distanceSquared(orb.getLocation());

            if (distance > 100) continue;

            if (distance < closestDistance) {
                closestDistance = distance;
                nearest = player;
            }
        }

        return nearest;
    }

    @Override
    public java.util.List<String> onTabComplete(
            org.bukkit.command.CommandSender sender,
            org.bukkit.command.Command command,
            String alias,
            String[] args
    ) {
        if (command.getName().equalsIgnoreCase("autopickupxp")) {

            if (args.length == 1) {
                String input = args[0].toLowerCase();

                java.util.List<String> list = new java.util.ArrayList<>();

                if ("toggle".startsWith(input)) list.add("toggle");
                if ("reload".startsWith(input)) list.add("reload");

                return list;
            }
        }

        return java.util.Collections.emptyList();
    }

    private int applyMending(Player player, int xp) {

        java.util.List<ItemStack> mendable = new java.util.ArrayList<>();

        // Main hand
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (isMendable(mainHand)) mendable.add(mainHand);

        // Off-hand
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isMendable(offHand)) mendable.add(offHand);

        // Armor
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (isMendable(armor)) mendable.add(armor);
        }

        if (mendable.isEmpty()) return xp;

        ItemStack item = mendable.get(new java.util.Random().nextInt(mendable.size()));
        org.bukkit.inventory.meta.Damageable meta =
                (org.bukkit.inventory.meta.Damageable) item.getItemMeta();

        int damage = meta.getDamage();

        int repairAmount = Math.min(damage, xp * 2);
        int xpUsed = (int) Math.ceil(repairAmount / 2.0);

        meta.setDamage(damage - repairAmount);
        item.setItemMeta(meta);

        return xp - xpUsed;
    }
    private boolean isMendable(ItemStack item) {
        if (item == null) return false;
        if (!item.containsEnchantment(Enchantment.MENDING)) return false;

        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable meta) {
            return meta.getDamage() > 0;
        }

        return false;
    }
}