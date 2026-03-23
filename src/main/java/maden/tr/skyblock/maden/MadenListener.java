package maden.tr.skyblock.maden;

import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public final class MadenListener implements Listener {
    private final MadenPlugin plugin;

    public MadenListener(MadenPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!plugin.canHandleManagedBreak(block)) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            event.setCancelled(true);
            return;
        }

        if (event.isCancelled()) {
            event.setCancelled(false);
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        plugin.handleMineBreak(player, block);
    }
}
