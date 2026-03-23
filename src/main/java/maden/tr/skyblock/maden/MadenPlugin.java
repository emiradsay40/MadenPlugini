package maden.tr.skyblock.maden;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MadenPlugin extends JavaPlugin implements TabExecutor {
    private final Map<String, RewardBlockType> blockTypes = new LinkedHashMap<String, RewardBlockType>();
    private final Map<String, RewardBlockType> blockTypesByMineAndMaterial = new HashMap<String, RewardBlockType>();
    private final Map<String, MineRegion> mineRegions = new LinkedHashMap<String, MineRegion>();
    private final Map<String, TrackedMineBlock> trackedBlocks = new LinkedHashMap<String, TrackedMineBlock>();
    private final Map<String, BukkitTask> respawnTasks = new HashMap<String, BukkitTask>();
    private final Map<String, BukkitTask> mineResetTasks = new HashMap<String, BukkitTask>();
    private final Map<UUID, String> selectedMineByPlayer = new HashMap<UUID, String>();
    private MadenDatabase database;
    private BukkitTask scanTask;
    private volatile boolean scanRunning;

    private File blocksFile;
    private FileConfiguration blocksConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupBlocksStorage();
        ensureDefaults();
        try {
            database = new MadenDatabase(getDataFolder());
            database.initialize();
        } catch (Exception exception) {
            throw new IllegalStateException("SQLite baslatilamadi", exception);
        }
        loadMinesFromConfig();
        loadBlockTypes();
        loadTrackedBlocks();
        schedulePendingMineResets();
        getServer().getPluginManager().registerEvents(new MadenListener(this), this);
        if (getCommand("maden") != null) {
            getCommand("maden").setExecutor(this);
            getCommand("maden").setTabCompleter(this);
        }
        registerPlaceholderExpansion();
        getLogger().info("Maden plugin aktif. Maden: " + mineRegions.size() + ", blok tipi: " + blockTypes.size() + ", takip edilen blok: " + trackedBlocks.size());
    }

    @Override
    public void onDisable() {
        cancelAllRespawns();
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
    }

    private void setupBlocksStorage() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        blocksFile = new File(getDataFolder(), "blocks.yml");
        if (!blocksFile.exists()) {
            saveResource("blocks.yml", false);
        }
        blocksConfig = YamlConfiguration.loadConfiguration(blocksFile);
    }

    private void ensureDefaults() {
        if (!blocksConfig.contains("block-types")) {
            blocksConfig.set("block-types.default.obsidian.source", "OBSIDIAN");
            blocksConfig.set("block-types.default.obsidian.reward.material", "OBSIDIAN");
            blocksConfig.set("block-types.default.obsidian.reward.amount", 1);
            blocksConfig.set("block-types.default.obsidian.reward.name", "&5Maden Tasi");
            blocksConfig.set("block-types.default.obsidian.reward.lore", Collections.singletonList("&7Madenden elde edildi."));
            blocksConfig.set("tracked-blocks", new LinkedHashMap<String, Object>());
            saveBlocksConfig();
        }

        if (!getConfig().contains("mines.default")) {
            getConfig().set("messages.mine-ready", "&aHazir");
            getConfig().set("mines.default.world", "world");
            getConfig().set("mines.default.pos1.world", "world");
            getConfig().set("mines.default.pos1.x", 0);
            getConfig().set("mines.default.pos1.y", 64);
            getConfig().set("mines.default.pos1.z", 0);
            getConfig().set("mines.default.pos2.world", "world");
            getConfig().set("mines.default.pos2.x", 0);
            getConfig().set("mines.default.pos2.y", 64);
            getConfig().set("mines.default.pos2.z", 0);
            getConfig().set("mines.default.respawn-seconds", 60);
            getConfig().set("mines.default.messages.enabled", false);
            getConfig().set("mines.default.messages.break", "&aMadenden &f%item% &aelde ettin.");
            getConfig().set("mines.default.sound.enabled", false);
            getConfig().set("mines.default.sound.name", "ENTITY_EXPERIENCE_ORB_PICKUP");
            getConfig().set("mines.default.sound.volume", 1.0D);
            getConfig().set("mines.default.sound.pitch", 1.0D);
            getConfig().set("mines.default.commands.enabled", false);
            getConfig().set("mines.default.commands.list", Collections.singletonList("say %player_name% maden odulu kazandi"));
            saveConfig();
        }
    }

    private void registerPlaceholderExpansion() {
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi != null && papi.isEnabled()) {
            new MadenPlaceholderExpansion(this).register();
        }
    }

    public boolean isTracked(Block block) {
        return trackedBlocks.containsKey(toLocationKey(block.getLocation()));
    }

    public boolean canHandleManagedBreak(Block block) {
        TrackedMineBlock tracked = trackedBlocks.get(toLocationKey(block.getLocation()));
        if (tracked == null) {
            return false;
        }
        MineRegion mine = mineRegions.get(tracked.mineId);
        RewardBlockType type = blockTypes.get(tracked.mineId + ":" + tracked.typeId);
        if (mine == null || type == null) {
            return false;
        }
        if (!isInsideMine(block.getLocation(), mine)) {
            return false;
        }
        if (block.getType() != type.sourceMaterial) {
            return false;
        }
        return tracked.brokenUntil <= System.currentTimeMillis();
    }

    public void handleMineBreak(Player player, Block block) {
        TrackedMineBlock tracked = trackedBlocks.get(toLocationKey(block.getLocation()));
        if (tracked == null) {
            return;
        }

        MineRegion mine = mineRegions.get(tracked.mineId);
        RewardBlockType type = blockTypes.get(tracked.mineId + ":" + tracked.typeId);
        if (mine == null || type == null) {
            return;
        }

        String key = toLocationKey(block.getLocation());
        cancelRespawn(key);
        block.setType(Material.AIR);
        long resetAt = getMineResetAt(mine.id);
        if (resetAt <= System.currentTimeMillis()) {
            resetAt = System.currentTimeMillis() + (mine.respawnSeconds * 1000L);
            scheduleMineReset(mine.id, resetAt);
        }
        tracked.brokenUntil = resetAt;
        mine.readyTrackedBlocks = Math.max(0, mine.readyTrackedBlocks - 1);
        mine.nextResetAt = resetAt;

        ItemStack rewardItem = createRewardItem(type);
        giveItem(player, block.getLocation(), rewardItem);
        handleOptionalEffects(player, mine, rewardItem);
        saveTrackedBlockAsync(tracked);
    }
    private void handleOptionalEffects(Player player, MineRegion mine, ItemStack rewardItem) {
        if (mine.messagesEnabled) {
            String itemName = rewardItem.getType().name();
            if (rewardItem.hasItemMeta() && rewardItem.getItemMeta().hasDisplayName()) {
                itemName = rewardItem.getItemMeta().getDisplayName();
            }
            player.sendMessage(colorize(mine.breakMessage.replace("%player%", player.getName()).replace("%item%", itemName)));
        }

        if (mine.soundEnabled) {
            Sound sound = parseSound(mine.soundName);
            if (sound != null) {
                player.playSound(player.getLocation(), sound, (float) mine.soundVolume, (float) mine.soundPitch);
            }
        }

        if (mine.commandsEnabled) {
            for (String command : mine.commands) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()).replace("%player_name%", player.getName()));
            }
        }
    }

    private Sound parseSound(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void giveItem(Player player, Location location, ItemStack item) {
        PlayerInventory inventory = player.getInventory();
        Map<Integer, ItemStack> leftovers = inventory.addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            location.getWorld().dropItemNaturally(location.clone().add(0.5D, 0.5D, 0.5D), leftover);
        }
    }

    private ItemStack createRewardItem(RewardBlockType type) {
        Material rewardMaterial = parseMaterial(type.rewardMaterial);
        if (rewardMaterial == null) {
            rewardMaterial = type.sourceMaterial;
        }
        ItemStack item = new ItemStack(rewardMaterial, Math.max(1, type.rewardAmount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colorize(type.rewardName));
            List<String> lore = new ArrayList<String>();
            for (String line : type.rewardLore) {
                lore.add(colorize(line));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void scheduleRespawn(String key, final TrackedMineBlock tracked, final MineRegion mine) {
        long delayTicks = Math.max(20L, mine.respawnSeconds * 20L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                World world = Bukkit.getWorld(tracked.worldName);
                if (world != null) {
                    respawnBlock(normalizeId(world.getName()) + "_" + tracked.x + "_" + tracked.y + "_" + tracked.z);
                }
            }
        }, delayTicks);
        respawnTasks.put(key, task);
    }

    private void respawnBlock(String key) {
        cancelRespawn(key);
        TrackedMineBlock tracked = trackedBlocks.get(key);
        if (tracked == null) {
            return;
        }
        RewardBlockType type = blockTypes.get(tracked.mineId + ":" + tracked.typeId);
        World world = Bukkit.getWorld(tracked.worldName);
        if (type == null || world == null) {
            return;
        }
        world.getBlockAt(tracked.x, tracked.y, tracked.z).setType(type.sourceMaterial);
        tracked.brokenUntil = 0L;
        saveTrackedBlockAsync(tracked);
    }

    private void cancelRespawn(String key) {
        BukkitTask task = respawnTasks.remove(key);
        if (task != null) {
            task.cancel();
        }
    }

    private void cancelAllRespawns() {
        for (BukkitTask task : respawnTasks.values()) {
            task.cancel();
        }
        respawnTasks.clear();
        for (BukkitTask task : mineResetTasks.values()) {
            task.cancel();
        }
        mineResetTasks.clear();
    }

    public String getGlobalTimePlaceholder() {
        long earliest = -1L;
        for (MineRegion mine : mineRegions.values()) {
            long resetAt = mine.nextResetAt;
            if (resetAt > System.currentTimeMillis() && (earliest == -1L || resetAt < earliest)) {
                earliest = resetAt;
            }
        }
        if (earliest == -1L) {
            return colorize(getConfig().getString("messages.mine-ready", "&aHazir"));
        }
        return formatDuration(earliest - System.currentTimeMillis());
    }

    public String getMineTimePlaceholder(String mineId) {
        MineRegion mine = mineRegions.get(normalizeId(mineId));
        if (mine == null) {
            return "";
        }
        long resetAt = mine.nextResetAt;
        if (resetAt <= System.currentTimeMillis()) {
            return colorize(getConfig().getString("messages.mine-ready", "&aHazir"));
        }
        return formatDuration(resetAt - System.currentTimeMillis());
    }

    public String getMinePercentPlaceholder(String mineId) {
        MineRegion mine = mineRegions.get(normalizeId(mineId));
        if (mine == null) {
            return "";
        }
        if (mine.totalTrackedBlocks <= 0) {
            return "0";
        }
        return String.valueOf((mine.readyTrackedBlocks * 100) / mine.totalTrackedBlocks);
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes > 0L) {
            return minutes + "dk " + seconds + "sn";
        }
        return seconds + "sn";
    }

    public void reloadPluginData() {
        reloadConfig();
        cancelAllRespawns();
        selectedMineByPlayer.clear();
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
            scanRunning = false;
        }
        setupBlocksStorage();
        loadMinesFromConfig();
        loadBlockTypes();
        loadTrackedBlocks();
        schedulePendingMineResets();
    }

    private void saveTrackedBlockAsync(final TrackedMineBlock tracked) {
        final TrackedBlockRecord record = new TrackedBlockRecord(
            normalizeId(tracked.worldName) + "_" + tracked.x + "_" + tracked.y + "_" + tracked.z,
            tracked.worldName,
            tracked.x,
            tracked.y,
            tracked.z,
            tracked.mineId,
            tracked.typeId,
            tracked.brokenUntil
        );
        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                try {
                    database.upsert(record);
                } catch (Exception exception) {
                    getLogger().warning("Tracked block kaydedilemedi: " + exception.getMessage());
                }
            }
        });
    }

    private long getMineResetAt(String mineId) {
        MineRegion mine = mineRegions.get(mineId);
        if (mine != null) {
            return mine.nextResetAt;
        }
        long resetAt = -1L;
        for (TrackedMineBlock tracked : trackedBlocks.values()) {
            if (!tracked.mineId.equals(mineId)) {
                continue;
            }
            if (tracked.brokenUntil > System.currentTimeMillis() && (resetAt == -1L || tracked.brokenUntil < resetAt)) {
                resetAt = tracked.brokenUntil;
            }
        }
        return resetAt;
    }

    private void schedulePendingMineResets() {
        for (MineRegion mine : mineRegions.values()) {
            long resetAt = mine.nextResetAt;
            if (resetAt > System.currentTimeMillis()) {
                scheduleMineReset(mine.id, resetAt);
            }
        }
    }

    private void scheduleMineReset(final String mineId, final long resetAt) {
        BukkitTask existingTask = mineResetTasks.remove(mineId);
        if (existingTask != null) {
            existingTask.cancel();
        }

        long delayTicks = Math.max(20L, ((resetAt - System.currentTimeMillis() + 999L) / 1000L) * 20L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                respawnMine(mineId);
            }
        }, delayTicks);
        mineResetTasks.put(mineId, task);
    }

    private void respawnMine(final String mineId) {
        BukkitTask task = mineResetTasks.remove(mineId);
        if (task != null) {
            task.cancel();
        }

        final List<TrackedBlockRecord> records = new ArrayList<TrackedBlockRecord>();
        MineRegion mine = mineRegions.get(mineId);
        for (Map.Entry<String, TrackedMineBlock> entry : trackedBlocks.entrySet()) {
            TrackedMineBlock tracked = entry.getValue();
            if (!tracked.mineId.equals(mineId)) {
                continue;
            }

            RewardBlockType type = blockTypes.get(tracked.mineId + ":" + tracked.typeId);
            World world = Bukkit.getWorld(tracked.worldName);
            if (type != null && world != null) {
                world.getBlockAt(tracked.x, tracked.y, tracked.z).setType(type.sourceMaterial);
            }
            tracked.brokenUntil = 0L;
            records.add(new TrackedBlockRecord(entry.getKey(), tracked.worldName, tracked.x, tracked.y, tracked.z, tracked.mineId, tracked.typeId, tracked.brokenUntil));
        }
        if (mine != null) {
            mine.readyTrackedBlocks = mine.totalTrackedBlocks;
            mine.nextResetAt = 0L;
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                try {
                    database.replaceMineBlocks(mineId, records);
                } catch (Exception exception) {
                    getLogger().warning("Mine reset sqlite kaydi basarisiz: " + exception.getMessage());
                }
            }
        });
    }
    private boolean deleteMine(String mineId) {
        String id = normalizeId(mineId);
        MineRegion removed = mineRegions.remove(id);
        if (removed == null) {
            return false;
        }
        BukkitTask resetTask = mineResetTasks.remove(id);
        if (resetTask != null) {
            resetTask.cancel();
        }
        Iterator<Map.Entry<String, TrackedMineBlock>> iterator = trackedBlocks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TrackedMineBlock> entry = iterator.next();
            if (entry.getValue().mineId.equals(id)) {
                cancelRespawn(entry.getKey());
                iterator.remove();
            }
        }
        Iterator<Map.Entry<UUID, String>> selectedIterator = selectedMineByPlayer.entrySet().iterator();
        while (selectedIterator.hasNext()) {
            if (id.equals(selectedIterator.next().getValue())) {
                selectedIterator.remove();
            }
        }
        getConfig().set("mines." + id, null);
        saveConfig();
        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                try {
                    database.deleteMine(id);
                } catch (Exception exception) {
                    getLogger().warning("Mine kaydi silinemedi: " + exception.getMessage());
                }
            }
        });
        return true;
    }

    private void saveMineToConfig(MineRegion mine) {
        String path = "mines." + mine.id;
        getConfig().set(path + ".world", mine.worldName);
        setLocation(path + ".pos1", mine.pos1);
        setLocation(path + ".pos2", mine.pos2);
        getConfig().set(path + ".respawn-seconds", mine.respawnSeconds);
        getConfig().set(path + ".messages.enabled", mine.messagesEnabled);
        getConfig().set(path + ".messages.break", mine.breakMessage);
        getConfig().set(path + ".sound.enabled", mine.soundEnabled);
        getConfig().set(path + ".sound.name", mine.soundName);
        getConfig().set(path + ".sound.volume", mine.soundVolume);
        getConfig().set(path + ".sound.pitch", mine.soundPitch);
        getConfig().set(path + ".commands.enabled", mine.commandsEnabled);
        getConfig().set(path + ".commands.list", mine.commands);
    }

    private void setLocation(String path, Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        getConfig().set(path + ".world", location.getWorld().getName());
        getConfig().set(path + ".x", location.getBlockX());
        getConfig().set(path + ".y", location.getBlockY());
        getConfig().set(path + ".z", location.getBlockZ());
    }

    private boolean addBlockType(String mineId, String sourceName, String displayName) {
        Material source = parseMaterial(sourceName);
        if (source == null || !source.isBlock()) {
            return false;
        }
        String id = normalizeId(source.name());
        RewardBlockType type = new RewardBlockType();
        type.id = id;
        type.mineId = normalizeId(mineId);
        type.sourceMaterial = source;
        type.rewardMaterial = source.name();
        type.rewardAmount = 1;
        type.rewardName = displayName;
        type.rewardLore = Collections.singletonList("&7Madenden elde edildi.");
        blockTypes.put(type.mineId + ":" + id, type);
        blockTypesByMineAndMaterial.put(type.mineId + ":" + source.name(), type);
        saveBlockTypes();
        return true;
    }

    private boolean removeBlockType(String mineId, String sourceName) {
        Material source = parseMaterial(sourceName);
        if (source == null) {
            return false;
        }
        String id = normalizeId(source.name());
        final String namespacedId = normalizeId(mineId) + ":" + id;
        if (blockTypes.remove(namespacedId) == null) {
            return false;
        }
        blockTypesByMineAndMaterial.remove(normalizeId(mineId) + ":" + source.name());
        Iterator<Map.Entry<String, TrackedMineBlock>> iterator = trackedBlocks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TrackedMineBlock> entry = iterator.next();
            if (entry.getValue().mineId.equals(normalizeId(mineId)) && entry.getValue().typeId.equals(id)) {
                cancelRespawn(entry.getKey());
                iterator.remove();
            }
        }
        saveBlockTypes();
        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                try {
                    database.deleteType(normalizeId(mineId), id);
                } catch (Exception exception) {
                    getLogger().warning("Type kaydi silinemedi: " + exception.getMessage());
                }
            }
        });
        recalculateMineStats(normalizeId(mineId));
        return true;
    }

    public int scanMine(String mineId, final CommandSender sender) {
        final MineRegion mine = mineRegions.get(normalizeId(mineId));
        if (mine == null || mine.pos1 == null || mine.pos2 == null || mine.pos1.getWorld() == null || mine.pos2.getWorld() == null) {
            return -1;
        }
        if (!mine.pos1.getWorld().getUID().equals(mine.pos2.getWorld().getUID())) {
            return -1;
        }
        if (scanRunning) {
            return -2;
        }

        Iterator<Map.Entry<String, TrackedMineBlock>> iterator = trackedBlocks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TrackedMineBlock> entry = iterator.next();
            if (entry.getValue().mineId.equals(mine.id)) {
                cancelRespawn(entry.getKey());
                iterator.remove();
            }
        }
        mine.totalTrackedBlocks = 0;
        mine.readyTrackedBlocks = 0;
        mine.nextResetAt = 0L;

        final World world = mine.pos1.getWorld();
        final int minX = Math.min(mine.pos1.getBlockX(), mine.pos2.getBlockX());
        final int maxX = Math.max(mine.pos1.getBlockX(), mine.pos2.getBlockX());
        final int minY = Math.min(mine.pos1.getBlockY(), mine.pos2.getBlockY());
        final int maxY = Math.max(mine.pos1.getBlockY(), mine.pos2.getBlockY());
        final int minZ = Math.min(mine.pos1.getBlockZ(), mine.pos2.getBlockZ());
        final int maxZ = Math.max(mine.pos1.getBlockZ(), mine.pos2.getBlockZ());
        final List<TrackedBlockRecord> scanResults = new ArrayList<TrackedBlockRecord>();
        final int[] state = new int[] { minX, minY, minZ, 0 };

        scanRunning = true;
        scanTask = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                int processed = 0;
                while (processed < 400 && state[0] <= maxX) {
                    Block block = world.getBlockAt(state[0], state[1], state[2]);
                    RewardBlockType type = getTypeBySource(mine.id, block.getType());
                    if (type != null) {
                        TrackedMineBlock tracked = new TrackedMineBlock();
                        tracked.worldName = world.getName();
                        tracked.x = state[0];
                        tracked.y = state[1];
                        tracked.z = state[2];
                        tracked.mineId = mine.id;
                        tracked.typeId = type.id;
                        tracked.brokenUntil = 0L;
                        String blockKey = toLocationKey(block.getLocation());
                        trackedBlocks.put(blockKey, tracked);
                        scanResults.add(new TrackedBlockRecord(blockKey, tracked.worldName, tracked.x, tracked.y, tracked.z, tracked.mineId, tracked.typeId, tracked.brokenUntil));
                        mine.totalTrackedBlocks++;
                        mine.readyTrackedBlocks++;
                        state[3]++;
                    }

                    processed++;
                    state[2]++;
                    if (state[2] > maxZ) {
                        state[2] = minZ;
                        state[1]++;
                    }
                    if (state[1] > maxY) {
                        state[1] = minY;
                        state[0]++;
                    }
                }

                if (state[0] > maxX) {
                    scanTask.cancel();
                    scanTask = null;
                    scanRunning = false;
                    final int foundCount = state[3];
                    Bukkit.getScheduler().runTaskAsynchronously(MadenPlugin.this, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                database.replaceMineBlocks(mine.id, scanResults);
                            } catch (Exception exception) {
                                getLogger().warning("Scan sqlite kaydi basarisiz: " + exception.getMessage());
                            }
                            Bukkit.getScheduler().runTask(MadenPlugin.this, new Runnable() {
                                @Override
                                public void run() {
                                    sender.sendMessage(colorize("&aMaden taramasi tamamlandi: &f" + mine.id + " &7(" + foundCount + " blok)"));
                                }
                            });
                        }
                    });
                }
            }
        }, 1L, 1L);
        return 0;
    }
    private void loadMinesFromConfig() {
        mineRegions.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("mines");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            String path = "mines." + id;
            MineRegion mine = new MineRegion();
            mine.id = normalizeId(id);
            mine.worldName = getConfig().getString(path + ".world", "world");
            mine.pos1 = getLocation(path + ".pos1", mine.worldName);
            mine.pos2 = getLocation(path + ".pos2", mine.worldName);
            mine.respawnSeconds = Math.max(1L, getConfig().getLong(path + ".respawn-seconds", 60L));
            mine.messagesEnabled = getConfig().getBoolean(path + ".messages.enabled", false);
            mine.breakMessage = getConfig().getString(path + ".messages.break", "&aMadenden &f%item% &aelde ettin.");
            mine.soundEnabled = getConfig().getBoolean(path + ".sound.enabled", false);
            mine.soundName = getConfig().getString(path + ".sound.name", "ENTITY_EXPERIENCE_ORB_PICKUP");
            mine.soundVolume = getConfig().getDouble(path + ".sound.volume", 1.0D);
            mine.soundPitch = getConfig().getDouble(path + ".sound.pitch", 1.0D);
            mine.commandsEnabled = getConfig().getBoolean(path + ".commands.enabled", false);
            mine.commands = getConfig().getStringList(path + ".commands.list");
            mine.totalTrackedBlocks = 0;
            mine.readyTrackedBlocks = 0;
            mine.nextResetAt = 0L;
            mineRegions.put(mine.id, mine);
        }
    }

    private Location getLocation(String path, String fallbackWorld) {
        String worldName = getConfig().getString(path + ".world", fallbackWorld);
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, getConfig().getInt(path + ".x"), getConfig().getInt(path + ".y"), getConfig().getInt(path + ".z"));
    }

    private boolean isInsideMine(Location location, MineRegion mine) {
        if (location == null || location.getWorld() == null || mine == null || mine.pos1 == null || mine.pos2 == null) {
            return false;
        }
        if (mine.pos1.getWorld() == null || mine.pos2.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getUID().equals(mine.pos1.getWorld().getUID()) || !location.getWorld().getUID().equals(mine.pos2.getWorld().getUID())) {
            return false;
        }
        int minX = Math.min(mine.pos1.getBlockX(), mine.pos2.getBlockX());
        int maxX = Math.max(mine.pos1.getBlockX(), mine.pos2.getBlockX());
        int minY = Math.min(mine.pos1.getBlockY(), mine.pos2.getBlockY());
        int maxY = Math.max(mine.pos1.getBlockY(), mine.pos2.getBlockY());
        int minZ = Math.min(mine.pos1.getBlockZ(), mine.pos2.getBlockZ());
        int maxZ = Math.max(mine.pos1.getBlockZ(), mine.pos2.getBlockZ());
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    private void loadBlockTypes() {
        blockTypes.clear();
        blockTypesByMineAndMaterial.clear();
        ConfigurationSection section = blocksConfig.getConfigurationSection("block-types");
        if (section == null) {
            migrateLegacyBlockTypes();
            section = blocksConfig.getConfigurationSection("block-types");
            if (section == null) {
                return;
            }
        }
        for (String mineId : section.getKeys(false)) {
            ConfigurationSection mineSection = section.getConfigurationSection(mineId);
            if (mineSection == null) {
                continue;
            }
            for (String id : mineSection.getKeys(false)) {
                String path = "block-types." + mineId + "." + id;
                Material source = parseMaterial(blocksConfig.getString(path + ".source"));
                if (source == null || !source.isBlock()) {
                    continue;
                }
                RewardBlockType type = new RewardBlockType();
                type.id = normalizeId(id);
                type.mineId = normalizeId(mineId);
                type.sourceMaterial = source;
                type.rewardMaterial = blocksConfig.getString(path + ".reward.material", source.name());
                type.rewardAmount = Math.max(1, blocksConfig.getInt(path + ".reward.amount", 1));
                type.rewardName = blocksConfig.getString(path + ".reward.name", source.name());
                type.rewardLore = blocksConfig.getStringList(path + ".reward.lore");
                blockTypes.put(type.mineId + ":" + type.id, type);
                blockTypesByMineAndMaterial.put(type.mineId + ":" + source.name(), type);
            }
        }
    }

    private void migrateLegacyBlockTypes() {
        ConfigurationSection trackedSection = blocksConfig.getConfigurationSection("tracked-blocks");
        if (trackedSection == null) {
            return;
        }

        boolean changed = false;
        for (String key : trackedSection.getKeys(false)) {
            String basePath = "tracked-blocks." + key;
            String mineId = normalizeId(blocksConfig.getString(basePath + ".mine", "default"));
            String typeId = normalizeId(blocksConfig.getString(basePath + ".type", ""));
            if (typeId.isEmpty()) {
                continue;
            }
            String blockTypePath = "block-types." + mineId + "." + typeId;
            if (blocksConfig.contains(blockTypePath + ".source")) {
                continue;
            }
            blocksConfig.set(blockTypePath + ".source", typeId.toUpperCase(Locale.ROOT));
            blocksConfig.set(blockTypePath + ".reward.material", typeId.toUpperCase(Locale.ROOT));
            blocksConfig.set(blockTypePath + ".reward.amount", 1);
            blocksConfig.set(blockTypePath + ".reward.name", "&f" + typeId);
            blocksConfig.set(blockTypePath + ".reward.lore", Collections.singletonList("&7Madenden elde edildi."));
            changed = true;
        }

        if (changed) {
            saveBlocksConfig();
        }
    }

    private void loadTrackedBlocks() {
        trackedBlocks.clear();
        for (MineRegion mine : mineRegions.values()) {
            mine.totalTrackedBlocks = 0;
            mine.readyTrackedBlocks = 0;
            mine.nextResetAt = 0L;
        }
        try {
            List<TrackedBlockRecord> records = database.loadAll();
            for (TrackedBlockRecord record : records) {
                String mineId = normalizeId(record.mineId);
                String typeId = normalizeId(record.typeId);
                if (!mineRegions.containsKey(mineId) || !blockTypes.containsKey(mineId + ":" + typeId)) {
                    continue;
                }
                TrackedMineBlock tracked = new TrackedMineBlock();
                tracked.worldName = record.worldName;
                tracked.x = record.x;
                tracked.y = record.y;
                tracked.z = record.z;
                tracked.mineId = mineId;
                tracked.typeId = typeId;
                tracked.brokenUntil = record.brokenUntil;
                trackedBlocks.put(record.blockKey, tracked);
                restoreTrackedBlock(tracked);
            }
        } catch (Exception exception) {
            getLogger().warning("Tracked blocklar yuklenemedi: " + exception.getMessage());
        }
    }

    private void restoreTrackedBlock(TrackedMineBlock tracked) {
        World world = Bukkit.getWorld(tracked.worldName);
        RewardBlockType type = blockTypes.get(tracked.mineId + ":" + tracked.typeId);
        MineRegion mine = mineRegions.get(tracked.mineId);
        if (world == null || type == null || mine == null) {
            return;
        }
        Block block = world.getBlockAt(tracked.x, tracked.y, tracked.z);
        mine.totalTrackedBlocks++;
        if (tracked.brokenUntil > System.currentTimeMillis()) {
            block.setType(Material.AIR);
            if (mine.nextResetAt == 0L || tracked.brokenUntil < mine.nextResetAt) {
                mine.nextResetAt = tracked.brokenUntil;
            }
        } else {
            tracked.brokenUntil = 0L;
            block.setType(type.sourceMaterial);
            mine.readyTrackedBlocks++;
        }
    }

    private void saveBlockTypes() {
        blocksConfig.set("block-types", null);
        List<String> ids = new ArrayList<String>(blockTypes.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            RewardBlockType type = blockTypes.get(id);
            String path = "block-types." + type.mineId + "." + type.id;
            blocksConfig.set(path + ".source", type.sourceMaterial.name());
            blocksConfig.set(path + ".reward.material", type.rewardMaterial);
            blocksConfig.set(path + ".reward.amount", type.rewardAmount);
            blocksConfig.set(path + ".reward.name", type.rewardName);
            blocksConfig.set(path + ".reward.lore", type.rewardLore);
        }
        saveBlocksConfig();
    }

    private void saveBlocksConfig() {
        try {
            blocksConfig.save(blocksFile);
        } catch (IOException exception) {
            getLogger().severe("blocks.yml kaydedilemedi: " + exception.getMessage());
        }
    }

    private boolean createMine(String mineId, Player player) {
        String id = normalizeId(mineId);
        if (mineRegions.containsKey(id)) {
            return false;
        }
        MineRegion mine = new MineRegion();
        mine.id = id;
        mine.worldName = player.getWorld().getName();
        mine.pos1 = player.getLocation();
        mine.pos2 = player.getLocation();
        mine.respawnSeconds = 60L;
        mine.messagesEnabled = false;
        mine.breakMessage = "&aMadenden &f%item% &aelde ettin.";
        mine.soundEnabled = false;
        mine.soundName = "ENTITY_EXPERIENCE_ORB_PICKUP";
        mine.soundVolume = 1.0D;
        mine.soundPitch = 1.0D;
        mine.commandsEnabled = false;
        mine.commands = Collections.singletonList("say %player_name% maden odulu kazandi");
        mine.totalTrackedBlocks = 0;
        mine.readyTrackedBlocks = 0;
        mine.nextResetAt = 0L;
        mineRegions.put(id, mine);
        selectedMineByPlayer.put(player.getUniqueId(), id);
        saveMineToConfig(mine);
        saveConfig();
        return true;
    }
    private RewardBlockType getTypeBySource(String mineId, Material material) {
        return blockTypesByMineAndMaterial.get(normalizeId(mineId) + ":" + material.name());
    }

    private void recalculateMineStats(String mineId) {
        MineRegion mine = mineRegions.get(mineId);
        if (mine == null) {
            return;
        }
        mine.totalTrackedBlocks = 0;
        mine.readyTrackedBlocks = 0;
        mine.nextResetAt = 0L;
        long now = System.currentTimeMillis();
        for (TrackedMineBlock tracked : trackedBlocks.values()) {
            if (!mineId.equals(tracked.mineId)) {
                continue;
            }
            mine.totalTrackedBlocks++;
            if (tracked.brokenUntil > now) {
                if (mine.nextResetAt == 0L || tracked.brokenUntil < mine.nextResetAt) {
                    mine.nextResetAt = tracked.brokenUntil;
                }
            } else {
                mine.readyTrackedBlocks++;
            }
        }
    }

    private Material parseMaterial(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String normalizeId(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input.toLowerCase(Locale.ROOT).replace(' ', '_').replace(':', '_');
    }

    private String toLocationKey(Location location) {
        return normalizeId(location.getWorld().getName()) + "_" + location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Bu komut sadece oyuncular tarafindan kullanilabilir.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("maden.admin")) {
            player.sendMessage(colorize("&cBu komut icin yetkin yok."));
            return true;
        }
        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("madenekle")) {
            if (args.length < 2) {
                player.sendMessage(colorize("&cKullanim: &f/maden madenekle <isim>"));
                return true;
            }
            if (!createMine(args[1], player)) {
                player.sendMessage(colorize("&cBu maden zaten var."));
                return true;
            }
            player.sendMessage(colorize("&aMaden olusturuldu ve secildi: &f" + normalizeId(args[1])));
            return true;
        }
        if (sub.equals("madensil")) {
            if (args.length < 2) {
                player.sendMessage(colorize("&cKullanim: &f/maden madensil <isim>"));
                return true;
            }
            if (!deleteMine(args[1])) {
                player.sendMessage(colorize("&cSilinecek maden bulunamadi."));
                return true;
            }
            player.sendMessage(colorize("&aMaden silindi: &f" + normalizeId(args[1])));
            return true;
        }
        if (sub.equals("sec")) {
            if (args.length < 2) {
                player.sendMessage(colorize("&cKullanim: &f/maden sec <isim>"));
                return true;
            }
            String mineId = normalizeId(args[1]);
            if (!mineRegions.containsKey(mineId)) {
                player.sendMessage(colorize("&cMaden bulunamadi."));
                return true;
            }
            selectedMineByPlayer.put(player.getUniqueId(), mineId);
            player.sendMessage(colorize("&aSecilen maden: &f" + mineId));
            return true;
        }
        if (sub.equals("blokekle")) {
            String selectedMine = getSelectedMine(player);
            if (selectedMine == null) {
                player.sendMessage(colorize("&cOnce bir maden sec veya olustur."));
                return true;
            }
            if (args.length < 3) {
                player.sendMessage(colorize("&cKullanim: &f/maden blokekle <minecraft_blok_adi> <isim>"));
                return true;
            }
            if (!addBlockType(selectedMine, args[1], joinFrom(args, 2))) {
                player.sendMessage(colorize("&cGecersiz blok adi."));
                return true;
            }
            player.sendMessage(colorize("&aBlok tipi eklendi: &f" + args[1] + " &7(" + selectedMine + ")"));
            return true;
        }
        if (sub.equals("bloksil")) {
            String selectedMine = getSelectedMine(player);
            if (selectedMine == null) {
                player.sendMessage(colorize("&cOnce bir maden sec veya olustur."));
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(colorize("&cKullanim: &f/maden bloksil <minecraft_blok_adi>"));
                return true;
            }
            if (!removeBlockType(selectedMine, args[1])) {
                player.sendMessage(colorize("&cSilinecek blok tipi bulunamadi."));
                return true;
            }
            player.sendMessage(colorize("&aBlok tipi silindi: &f" + args[1] + " &7(" + selectedMine + ")"));
            return true;
        }
        if (sub.equals("bloklist")) {
            String selectedMine = getSelectedMine(player);
            if (selectedMine == null) {
                player.sendMessage(colorize("&cOnce bir maden sec veya olustur."));
                return true;
            }
            List<String> lines = new ArrayList<String>();
            for (RewardBlockType type : blockTypes.values()) {
                if (type.mineId.equals(selectedMine)) {
                    lines.add("&e" + type.sourceMaterial.name() + " &7-> &f" + type.rewardName);
                }
            }
            if (lines.isEmpty()) {
                player.sendMessage(colorize("&cBu madende tanimli blok yok."));
                return true;
            }
            player.sendMessage(colorize("&6" + selectedMine + " bloklari:"));
            for (String line : lines) {
                player.sendMessage(colorize(line));
            }
            return true;
        }
        if (sub.equals("blokduzenle")) {
            String selectedMine = getSelectedMine(player);
            if (selectedMine == null) {
                player.sendMessage(colorize("&cOnce bir maden sec veya olustur."));
                return true;
            }
            if (args.length < 3) {
                player.sendMessage(colorize("&cKullanim: &f/maden blokduzenle <minecraft_blok_adi> <yeni_isim>"));
                return true;
            }
            String key = normalizeId(selectedMine) + ":" + normalizeId(args[1]);
            RewardBlockType type = blockTypes.get(key);
            if (type == null) {
                player.sendMessage(colorize("&cBu madende o blok tanimli degil."));
                return true;
            }
            type.rewardName = joinFrom(args, 2);
            saveBlockTypes();
            player.sendMessage(colorize("&aBlok ismi guncellendi: &f" + args[1] + " &7-> " + type.rewardName));
            return true;
        }

        String selectedMine = getSelectedMine(player);
        if (selectedMine == null) {
            player.sendMessage(colorize("&cOnce bir maden sec veya olustur. &f/maden madenekle <isim>"));
            return true;
        }
        MineRegion mine = mineRegions.get(selectedMine);
        if (mine == null) {
            player.sendMessage(colorize("&cSecili maden bulunamadi."));
            return true;
        }

        if (sub.equals("pos1")) {
            mine.worldName = player.getWorld().getName();
            mine.pos1 = player.getLocation();
            saveMineToConfig(mine);
            saveConfig();
            player.sendMessage(colorize("&aPos1 ayarlandi: &f" + formatLocation(player.getLocation())));
            return true;
        }
        if (sub.equals("pos2")) {
            mine.worldName = player.getWorld().getName();
            mine.pos2 = player.getLocation();
            saveMineToConfig(mine);
            saveConfig();
            player.sendMessage(colorize("&aPos2 ayarlandi: &f" + formatLocation(player.getLocation())));
            return true;
        }
        if (sub.equals("tara") || sub.equals("scan")) {
            int count = scanMine(selectedMine, player);
            if (count == -2) {
                player.sendMessage(colorize("&cSu anda zaten bir tarama calisiyor."));
                return true;
            }
            if (count < 0) {
                player.sendMessage(colorize("&cMaden pos1/pos2 ayarlanmamis."));
                return true;
            }
            player.sendMessage(colorize("&aMaden taramasi baslatildi: &f" + selectedMine + " &7(batch mod)"));
            return true;
        }
        if (sub.equals("reload")) {
            reloadPluginData();
            player.sendMessage(colorize("&aMaden plugin yeniden yuklendi."));
            return true;
        }
        if (sub.equals("bilgi") || sub.equals("info")) {
            int mineBlockCount = 0;
            for (RewardBlockType type : blockTypes.values()) {
                if (type.mineId.equals(selectedMine)) {
                    mineBlockCount++;
                }
            }
            player.sendMessage(colorize("&eSecili maden: &f" + selectedMine));
            player.sendMessage(colorize("&eMaden sayisi: &f" + mineRegions.size()));
            player.sendMessage(colorize("&eBlok tipi sayisi: &f" + mineBlockCount));
            player.sendMessage(colorize("&eTakip edilen blok sayisi: &f" + trackedBlocks.size()));
            player.sendMessage(colorize("&eGenel sure: &f" + getGlobalTimePlaceholder()));
            player.sendMessage(colorize("&eMaden sure: &f" + getMineTimePlaceholder(selectedMine)));
            player.sendMessage(colorize("&eMaden doluluk: &f%" + getMinePercentPlaceholder(selectedMine)));
            player.sendMessage(colorize("&eTarama durumu: &f" + (scanRunning ? "Calisiyor" : "Bos")));
            return true;
        }
        sendUsage(player);
        return true;
    }
    private String getSelectedMine(Player player) {
        String selectedMine = selectedMineByPlayer.get(player.getUniqueId());
        if (selectedMine != null && mineRegions.containsKey(selectedMine)) {
            return selectedMine;
        }
        if (mineRegions.containsKey("default")) {
            selectedMineByPlayer.put(player.getUniqueId(), "default");
            return "default";
        }
        if (!mineRegions.isEmpty()) {
            String first = mineRegions.keySet().iterator().next();
            selectedMineByPlayer.put(player.getUniqueId(), first);
            return first;
        }
        return null;
    }

    private String joinFrom(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private String formatLocation(Location location) {
        return location.getWorld().getName() + " " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private void sendUsage(Player player) {
        player.sendMessage(colorize("&6/maden madenekle <isim>"));
        player.sendMessage(colorize("&6/maden madensil <isim>"));
        player.sendMessage(colorize("&6/maden sec <isim>"));
        player.sendMessage(colorize("&6/maden pos1"));
        player.sendMessage(colorize("&6/maden pos2"));
        player.sendMessage(colorize("&6/maden blokekle <minecraft_blok_adi> <isim>"));
        player.sendMessage(colorize("&6/maden bloksil <minecraft_blok_adi>"));
        player.sendMessage(colorize("&6/maden bloklist"));
        player.sendMessage(colorize("&6/maden blokduzenle <minecraft_blok_adi> <yeni_isim>"));
        player.sendMessage(colorize("&6/maden tara"));
        player.sendMessage(colorize("&6/maden bilgi"));
        player.sendMessage(colorize("&6/maden reload"));
    }

    public String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterSuggestions(Arrays.asList("madenekle", "madensil", "sec", "pos1", "pos2", "blokekle", "bloksil", "bloklist", "blokduzenle", "tara", "bilgi", "reload"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("madenekle") || args[0].equalsIgnoreCase("madensil") || args[0].equalsIgnoreCase("sec"))) {
            return filterSuggestions(new ArrayList<String>(mineRegions.keySet()), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("blokekle") || args[0].equalsIgnoreCase("bloksil") || args[0].equalsIgnoreCase("blokduzenle"))) {
            List<String> materials = new ArrayList<String>();
            for (Material material : Material.values()) {
                if (material.isBlock()) {
                    materials.add(material.name());
                }
            }
            return filterSuggestions(materials, args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterSuggestions(List<String> values, String token) {
        List<String> results = new ArrayList<String>();
        String lowered = token.toLowerCase(Locale.ROOT);
        Collections.sort(values, String.CASE_INSENSITIVE_ORDER);
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                results.add(value);
            }
            if (results.size() >= 50) {
                break;
            }
        }
        return results;
    }

    private static final class RewardBlockType {
        private String mineId;
        private String id;
        private Material sourceMaterial;
        private String rewardMaterial;
        private int rewardAmount;
        private String rewardName;
        private List<String> rewardLore;
    }

    private static final class MineRegion {
        private String id;
        private String worldName;
        private Location pos1;
        private Location pos2;
        private long respawnSeconds;
        private boolean messagesEnabled;
        private String breakMessage;
        private boolean soundEnabled;
        private String soundName;
        private double soundVolume;
        private double soundPitch;
        private boolean commandsEnabled;
        private List<String> commands;
        private int totalTrackedBlocks;
        private int readyTrackedBlocks;
        private long nextResetAt;
    }

    private static final class TrackedMineBlock {
        private String worldName;
        private int x;
        private int y;
        private int z;
        private String mineId;
        private String typeId;
        private long brokenUntil;
    }

    public static final class TrackedBlockRecord {
        public final String blockKey;
        public final String worldName;
        public final int x;
        public final int y;
        public final int z;
        public final String mineId;
        public final String typeId;
        public final long brokenUntil;

        public TrackedBlockRecord(String blockKey, String worldName, int x, int y, int z, String mineId, String typeId, long brokenUntil) {
            this.blockKey = blockKey;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.mineId = mineId;
            this.typeId = typeId;
            this.brokenUntil = brokenUntil;
        }
    }
}
