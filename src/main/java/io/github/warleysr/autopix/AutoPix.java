package io.github.warleysr.autopix;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;

import io.github.warleysr.autopix.commands.APMenuCommand;
import io.github.warleysr.autopix.commands.AutoPixCommand;
import io.github.warleysr.autopix.domain.Order;
import io.github.warleysr.autopix.domain.PixData;
import io.github.warleysr.autopix.expansion.AutoPixExpansion;
import io.github.warleysr.autopix.inventory.InventoryListener;
import io.github.warleysr.autopix.inventory.InventoryManager;
import io.github.warleysr.autopix.mercadopago.MercadoPagoAPI;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

public class AutoPix extends JavaPlugin {
    private static String PIX_KEY;
    private static String PIX_NAME;
    private static BukkitTask VALIDATE_TASK, MAPS_TASK, HOLOGRAM_TASK;

    private static AutoPix instance;

    private static final Set<String> ALLOWED_TARGETS = Set.of("autopix", "autopixmenu");

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        reloadConfig();

        getCommand("autopix").setExecutor(new AutoPixCommand());
        getCommand("autopixmenu").setExecutor(new APMenuCommand());

        registerBrigadierAliases();

        if (!reloadPlugin())
            return;

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

    private void registerBrigadierAliases() {
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            // Lê do config aqui dentro pra garantir que, em /reload do servidor,
            // o Paper re-registre usando a config atual (Lifecycle é reloadable).
            Map<String, String> aliases = readAliasesFromConfig();

            for (Map.Entry<String, String> entry : aliases.entrySet()) {
                final String alias = entry.getKey();
                final String target = entry.getValue();

                if (!alias.matches("[a-z0-9_-]{1,32}")) {
                    getLogger().warning(() -> "Alias inválido ignorado: '" + alias + "' (target: " + target + ")");
                    continue;
                }

                // /alias -> executa /target
                // /alias <args...> -> executa /target <args...>
                var node = Commands.literal(alias)
                        .executes(ctx -> {
                            forward(ctx.getSource().getSender(), target, "");
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String args = StringArgumentType.getString(ctx, "args");
                                    forward(ctx.getSource().getSender(), target, args);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .build();

                commands.register(
                        node,
                        "Alias para /" + target,
                        List.of() // sem aliases aqui, porque ESTE node já é o alias
                );
            }
        });
    }

    private void forward(CommandSender sender, String target, String args) {
        String cmd = target + (args == null || args.isBlank() ? "" : " " + args);
        Bukkit.dispatchCommand(sender, cmd);
    }

    private Map<String, String> readAliasesFromConfig() {
        Map<String, String> map = new HashMap<>();

        var section = getConfig().getConfigurationSection("aliases-comandos");
        if (section == null)
            return map;

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
                if (alias.equals(target))
                    continue;

                map.put(alias, target);
            }
        }

        return map;
    }

    public static boolean reloadPlugin() {
        AutoPix plugin = getInstance();
        plugin.reloadConfig();

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
