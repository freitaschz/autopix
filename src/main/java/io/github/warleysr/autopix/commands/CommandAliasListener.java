package io.github.warleysr.autopix.commands;

import io.github.warleysr.autopix.AutoPix;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public class CommandAliasListener implements Listener {
    private final AutoPix plugin;

    public CommandAliasListener(AutoPix plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        if (msg == null || msg.length() < 2 || msg.charAt(0) != '/')
            return;

        String withoutSlash = msg.substring(1);
        String[] parts = withoutSlash.split("\\s+", 2);

        String label = parts[0].toLowerCase();
        String rest = (parts.length > 1) ? parts[1] : "";

        String target = plugin.getAliasMap().get(label);
        if (target == null)
            return;

        e.setMessage("/" + target + (rest.isEmpty() ? "" : " " + rest));
    }
}
