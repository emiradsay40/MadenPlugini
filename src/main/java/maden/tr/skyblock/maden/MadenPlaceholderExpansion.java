package maden.tr.skyblock.maden;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class MadenPlaceholderExpansion extends PlaceholderExpansion {
    private final MadenPlugin plugin;

    public MadenPlaceholderExpansion(MadenPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "maden";
    }

    @Override
    public String getAuthor() {
        return "Codex";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) {
            return "";
        }
        if (params.equalsIgnoreCase("time")) {
            return plugin.getGlobalTimePlaceholder();
        }
        if (params.startsWith("time_")) {
            return plugin.getMineTimePlaceholder(params.substring("time_".length()));
        }
        if (params.startsWith("percent_")) {
            return plugin.getMinePercentPlaceholder(params.substring("percent_".length()));
        }
        return "";
    }
}
