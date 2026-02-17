package io.github.warleysr.autopix;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import io.github.warleysr.autopix.commands.APMenuCommand;
import io.github.warleysr.autopix.commands.AutoPixCommand;
import io.github.warleysr.autopix.commands.CommandAliasListener;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.expansion.AutoPixExpansion;
import io.github.warleysr.autopix.inventory.InventoryListener;
import io.github.warleysr.autopix.inventory.InventoryManager;
import io.github.warleysr.autopix.mercadopago.MercadoPagoAPI;

public class AutoPix extends JavaPlugin {
    private static String PIX_KEY;
    private static String PIX_NAME;
    private static BukkitTask VALIDATE_TASK, MAPS_TASK, HOLOGRAM_TASK;

    private static AutoPix instance;

    private static final Set<String> ALLOWED_TARGETS = Set.of("autopix", "autopixmenu");
    private final Map<String, String> aliasMap = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        if (!reloadPlugin())
            return;

        getCommand("autopix").setExecutor(new AutoPixCommand());
        getCommand("autopixmenu").setExecutor(new APMenuCommand());

        Bukkit.getPluginManager().registerEvents(new CommandAliasListener(this), this);
        Bukkit.getPluginManager().registerEvents(new InventoryListener(), this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new AutoPixExpansion().register();
        }
    }

    public static AutoPix getInstance() {
        return instance;
    }

    public static String getPixKey() {
        return PIX_KEY;
    }

    public static String getPixName() {
        return PIX_NAME;
    }

    public Map<String, String> getAliasMap() {
        return aliasMap;
    }

    private void loadAliasesFromConfig() {
        aliasMap.clear();

        var section = getConfig().getConfigurationSection("aliases-comandos");
        if (section == null)
            return;

        for (String targetCmd : section.getKeys(false)) {
            String target = targetCmd.toLowerCase();

            if (!ALLOWED_TARGETS.contains(target))
                continue;

            List<String> aliases = getConfig().getStringList("aliases-comandos." + targetCmd);

            for (String a : aliases) {
                if (a == null)
                    continue;
                String alias = a.trim().toLowerCase();
                if (alias.isEmpty())
                    continue;

                // evita alias igual ao comando real
                if (alias.equals(target))
                    continue;

                // grava alias -> comando real
                aliasMap.put(alias, target);
            }
        }
    }

    public static boolean reloadPlugin() {
        AutoPix plugin = getInstance();
        plugin.reloadConfig();
        plugin.loadAliasesFromConfig();

        PIX_KEY = plugin.getConfig().getString("pix.chave");
        PIX_NAME = plugin.getConfig().getString("pix.nome");

        MSG.loadMessages(plugin);

        try {
            if (!(OrderManager.startOrderManager(plugin))) {
                plugin.setEnabled(false);
                return false;
            }

        } catch (SQLException e) {
            Bukkit.getConsoleSender().sendMessage(MSG.getMessage("erro-sql")
                    .replace("{mensagem}", e.getMessage()));
            plugin.setEnabled(false);
            return false;
        }

        InventoryManager.createMenuInventory(plugin);

        // Start async task to validate transactions automatically
        if (plugin.getConfig().getBoolean("automatico.ativado")) {
            if (VALIDATE_TASK != null)
                VALIDATE_TASK.cancel();

            int interval = plugin.getConfig().getInt("automatico.intervalo");

            VALIDATE_TASK = new BukkitRunnable() {
                @Override
                public void run() {
                    OrderManager.validatePendings(plugin);
                }
            }.runTaskTimerAsynchronously(plugin, interval * 20L, interval * 20L);
        }

        // Start task to remove unpaid maps
        if (MAPS_TASK != null)
            MAPS_TASK.cancel();

        int remInterval = plugin.getConfig().getInt("mapa.intervalo");

        MAPS_TASK = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Order order = OrderManager.getLastOrder(p.getName());
                    if (order == null)
                        continue;

                    long diff = System.currentTimeMillis() - order.getCreated().getTime();
                    long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);

                    if (minutes < plugin.getConfig().getInt("mapa.tempo-pagar"))
                        continue;

                    InventoryManager.removeUnpaidMaps(p);

                    PixData pd = OrderManager.getPixData(order);
                    if (pd == null || !pd.isPending())
                        continue;

                    OrderManager.setPixDataStatus(pd, "cancelled");
                    try {
                        MercadoPagoAPI.cancelPayment(plugin, pd.getPaymentId());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }.runTaskTimerAsynchronously(plugin, remInterval * 20L, remInterval * 20L);

        // Start task to update top donors hologram data
        if (HOLOGRAM_TASK != null)
            HOLOGRAM_TASK.cancel();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            int holoInterval = plugin.getConfig().getInt("tempos.holograma", 30);

            HOLOGRAM_TASK = new BukkitRunnable() {
                @Override
                public void run() {
                    AutoPixExpansion.updateTopDonorsCache(OrderManager.getTopDonors());
                }
            }.runTaskTimerAsynchronously(plugin, 0L, holoInterval * 20L);
        }

        return true;
    }

}
