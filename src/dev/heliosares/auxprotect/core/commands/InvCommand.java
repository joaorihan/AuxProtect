package dev.heliosares.auxprotect.core.commands;

import dev.heliosares.auxprotect.adapters.SenderAdapter;
import dev.heliosares.auxprotect.core.*;
import dev.heliosares.auxprotect.core.Language.L;
import dev.heliosares.auxprotect.database.*;
import dev.heliosares.auxprotect.exceptions.BusyException;
import dev.heliosares.auxprotect.exceptions.CommandException;
import dev.heliosares.auxprotect.exceptions.PlatformException;
import dev.heliosares.auxprotect.exceptions.SyntaxException;
import dev.heliosares.auxprotect.spigot.AuxProtectSpigot;
import dev.heliosares.auxprotect.utils.*;
import dev.heliosares.auxprotect.utils.InvSerialization.PlayerInventoryRecord;
import dev.heliosares.auxprotect.utils.Pane.Type;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class InvCommand extends Command {
    public InvCommand(IAuxProtect plugin) {
        super(plugin, "inv", APPermission.INV, true);
    }

    public static void openSync(IAuxProtect plugin, Player player, Inventory inventory) {
        plugin.runSync(() -> player.openInventory(inventory));
    }

    public static Inventory makeInventory(IAuxProtect plugin_, Player player, OfflinePlayer target,
                                          PlayerInventoryRecord inv, long when) {
        if (plugin_ instanceof AuxProtectSpigot plugin) {
            final Player targetO = target.getPlayer();
            final String targetName = target.getName() == null ? "unknown" : target.getName();
            final String targetName_possessive = targetName + "'" + (targetName.toLowerCase().endsWith("s") ? "" : "s");
            Pane enderpane = new Pane(Type.SHOW, player);

            Inventory enderinv = Bukkit.getServer().createInventory(enderpane, 27, targetName + " Ender Chest - "
                    + TimeUtil.millisToString(System.currentTimeMillis() - when) + " ago.");
            enderinv.setContents(inv.ender());

            Pane pane = new Pane(Type.SHOW, player);
            String ago = TimeUtil.millisToString(System.currentTimeMillis() - when);
            Inventory mainInv = Bukkit.getServer().createInventory(pane, 54,
                    targetName + " " + ago + " ago.");
            pane.setInventory(mainInv);
            Container<Boolean> closed = new Container<>(false);

            if (APPermission.INV_RECOVER.hasPermission(player)) {
                if (targetO != null) {
                    Container<Long> lastClick = new Container<>(0L);
                    pane.addButton(49, Material.GREEN_STAINED_GLASS_PANE, () -> plugin.runAsync(() -> {
                                if (System.currentTimeMillis() - lastClick.get() > 500) {
                                    lastClick.set(System.currentTimeMillis());
                                    return;
                                }
                                if (closed.get()) return;
                                closed.set(true);
                                plugin.runSync(player::closeInventory);
                                player.sendMessage(L.COMMAND__LOOKUP__LOOKING.translate());
                                try {
                                    update(plugin, player, when);
                                } catch (Exception e) {
                                    plugin.print(e);
                                    player.sendMessage(L.ERROR.translate());
                                    return;
                                }
                                plugin.runSync(() -> {
                                    PlayerInventoryRecord inv_;
                                    inv_ = InvDiffManager.listToPlayerInv(Arrays.asList(mainInv.getContents()), inv.exp());

                                    targetO.getInventory().setStorageContents(inv_.storage());
                                    targetO.getInventory().setArmorContents(inv_.armor());
                                    targetO.getInventory().setExtraContents(inv_.extra());
                                    try {
                                        Experience.setExp(targetO, inv_.exp());
                                    } catch (Exception e) {
                                        player.sendMessage("�cUnable to recover experience.");
                                    }
                                    player.closeInventory();
                                    plugin.broadcast(L.COMMAND__CLAIMINV__FORCE_RECOVERED.translate(player.getName(), targetName_possessive, ago), APPermission.INV_NOTIFY);
                                    player.sendMessage("�aYou recovered " + targetName_possessive + " inventory.");
                                    targetO.sendMessage("�a" + player.getName() + " recovered your inventory from "
                                            + TimeUtil.millisToString(System.currentTimeMillis() - when) + " ago.");
                                    targetO.playSound(targetO.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1, 1);
                                    plugin.add(new DbEntry(AuxProtectSpigot.getLabel(player), EntryAction.RECOVER, false, player.getLocation(), AuxProtectSpigot.getLabel(target), "force"));
                                });

                            }), "�2�lForce Recover Inventory", "", "�a�lDouble click", "",
                            "�7This will �c�loverwrite �7the player's", "�7current inventory and exp with",
                            "�7what is in the view above.");
                } else {
                    pane.addButton(49, Material.GRAY_STAINED_GLASS_PANE, null,
                            "�8�lForce Recover Inventory Unavailable", "�cPlayer must be online to",
                            "�cforce recover their inventory.");
                }
                pane.addButton(50, Material.GREEN_STAINED_GLASS_PANE, () -> plugin.runAsync(() -> {
                            if (closed.get()) return;
                            closed.set(true);
                            plugin.runSync(player::closeInventory);
                            ItemStack[] output = new ItemStack[45];
                            for (int i = 0; i < output.length; i++) {
                                output[i] = mainInv.getItem(i);
                            }
                            byte[] recover = null;
                            try {
                                recover = InvSerialization.toByteArray(output);
                            } catch (Exception e1) {
                                plugin.warning("Error serializing inventory recovery");
                                plugin.print(e1);
                                player.sendMessage(Language.translate(L.ERROR));
                            }
                            try {
                                plugin.getSqlManager().getUserManager().setPendingInventory(plugin.getSqlManager()
                                                .getUserManager().getUIDFromUUID("$" + target.getUniqueId(), true),
                                        recover);
                                update(plugin, player, when);
                            } catch (Exception e) {
                                plugin.print(e);
                                player.sendMessage(L.ERROR.translate());
                                return;
                            }
                            assert target.getName() != null;
                            plugin.broadcast(L.COMMAND__CLAIMINV__RECOVERED.translate(player.getName(), targetName_possessive, ago), APPermission.INV_NOTIFY);
                            player.sendMessage("�aYou recovered " + target.getName() + "'"
                                    + (target.getName().endsWith("s") ? "" : "s") + " inventory.");
                            if (targetO != null) {
                                targetO.sendMessage("�a" + player.getName() + " recovered your inventory from "
                                        + TimeUtil.millisToString(System.currentTimeMillis() - when) + " ago.");
                                targetO.sendMessage("�7Ensure you have room in your inventory before claiming!");
                                ComponentBuilder message = new ComponentBuilder();
                                message.append("�f\n         ");
                                message.append("�a[Claim]")
                                        .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/claiminv"))
                                        .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                new Text("�aClick to claim your recovered inventory")));
                                message.append("\n�f").event((ClickEvent) null).event((HoverEvent) null);
                                targetO.spigot().sendMessage(message.create());
                                targetO.playSound(targetO.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1, 1);
                            }

                            plugin.add(new DbEntry(AuxProtectSpigot.getLabel(player), EntryAction.RECOVER, false,
                                    player.getLocation(), AuxProtectSpigot.getLabel(target), "regular"));
                        }), "�a�lRecover Inventory", "", "�7This will give the player a", "�7prompt to claim this inventory as",
                        "�7if they were opening a chest with", "�7the above contents. They will also get",
                        "�7the exp stated here.", "", "�cThis will not overwrite anything and may",
                        "�cduplicate items �7if they weren't", "�7actually lost originally.");
            }
            int space = 53;
            pane.addButton(space--, Material.RED_STAINED_GLASS_PANE, player::closeInventory, "�c�lClose");
            pane.addButton(space--, Material.BLACK_STAINED_GLASS_PANE, () -> player.openInventory(enderinv), "�8�lView Enderchest");
            // TODO zz backpack goes here
            pane.addButton(space, Material.GREEN_STAINED_GLASS_PANE, null,
                    inv.exp() >= 0 ? ("�2�lPlayer had " + inv.exp() + "xp") : "�8�lNo XP data");

            int i1 = 0;
            for (int i = 9; i < inv.storage().length; i++) {
                if (inv.storage()[i] != null)
                    mainInv.setItem(i1, inv.storage()[i]);
                i1++;
            }
            for (int i = 0; i < 9; i++) {
                if (inv.storage()[i] != null)
                    mainInv.setItem(i1, inv.storage()[i]);
                i1++;
            }
            for (int i = inv.armor().length - 1; i >= 0; i--) {
                if (inv.armor()[i] != null)
                    mainInv.setItem(i1, inv.armor()[i]);
                i1++;
            }
            for (int i = 0; i < inv.extra().length; i++) {
                if (inv.extra()[i] != null)
                    mainInv.setItem(i1, inv.extra()[i]);
                i1++;
            }

            enderpane.onClose((p) -> plugin.getServer().getScheduler().runTaskLater(plugin, () -> player.openInventory(mainInv), 1));
            return mainInv;
        }
        return null;
    }

    private static void update(IAuxProtect plugin, Player staff, long time) throws SQLException {
        plugin.getSqlManager().execute(
                "UPDATE " + Table.AUXPROTECT_INVENTORY + " SET data=ifnull(data,'')||? WHERE time=? AND action_id=?",
                30000L, LocalDateTime.now().format(XrayCommand.ratedByDateFormatter) + ": Recovered by " + staff.getName() + "; ",
                time, EntryAction.INVENTORY.id);
    }

    @Override
    public void onCommand(SenderAdapter sender, String label, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new SyntaxException();
        }
        if (sender.getPlatform() != PlatformType.SPIGOT) {
            throw new PlatformException();
        }
        if (sender.getSender() instanceof Player player && plugin instanceof AuxProtectSpigot) {
            int index = -1;
            try {
                index = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {

            }
            if (index < 0) {
                throw new SyntaxException();
            }
            Results results = LookupCommand.results.get(player.getUniqueId().toString());
            if (results == null || index >= results.getSize()) {
                throw new SyntaxException();
            }

            DbEntry entry = results.get(index);
            if (entry == null) {
                throw new SyntaxException();
            }
            if (entry.getAction().equals(EntryAction.INVENTORY)) {
                PlayerInventoryRecord inv_;
                try {
                    inv_ = InvSerialization.toPlayerInventory(entry.getBlob());
                } catch (Exception e1) {
                    plugin.warning("Error serializing inventory lookup");
                    plugin.print(e1);
                    sender.sendLang(Language.L.ERROR);
                    return;
                }
                final PlayerInventoryRecord inv = inv_;
                OfflinePlayer target;
                try {
                    target = Bukkit.getOfflinePlayer(UUID.fromString(entry.getUserUUID().substring(1)));
                } catch (BusyException e) {
                    sender.sendLang(L.DATABASE_BUSY);
                    return;
                } catch (SQLException e) {
                    sender.sendLang(L.ERROR);
                    return;
                }
                openSync(plugin, player, makeInventory(plugin, player, target, inv, entry.getTime()));
            } else if (entry.hasBlob() && entry.getAction().getTable() == Table.AUXPROTECT_INVENTORY) {
                Pane pane = new Pane(Type.SHOW, player);
                try {
                    Inventory inv;
                    if (entry instanceof SingleItemEntry sientry) {
                        inv = InvSerialization.toInventory(pane, "Item Viewer", sientry.getItem());
                    } else {
                        inv = InvSerialization.toInventory(entry.getBlob(), pane, "Item Viewer");
                    }
                    pane.setInventory(inv);
                    openSync(plugin, player, inv);
                } catch (Exception e1) {
                    plugin.warning("Error serializing itemviewer");
                    plugin.print(e1);
                    sender.sendLang(Language.L.ERROR);
                }
            }
        } else {
            throw new PlatformException();
        }
    }

    @Override
    public boolean exists() {
        return plugin.getPlatform() == PlatformType.SPIGOT;
    }

    @Override
    public List<String> onTabComplete(SenderAdapter sender, String label, String[] args) {
        return null;
    }
}
