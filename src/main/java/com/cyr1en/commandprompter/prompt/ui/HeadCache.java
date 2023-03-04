package com.cyr1en.commandprompter.prompt.ui;

import com.cyr1en.commandprompter.CommandPrompter;
import com.cyr1en.commandprompter.PluginLogger;
import com.cyr1en.commandprompter.hook.hooks.SuperVanishHook;
import com.cyr1en.commandprompter.util.Util;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class HeadCache implements Listener {

    private final LoadingCache<Player, Optional<ItemStack>> HEAD_CACHE;

    private final CommandPrompter plugin;
    private final String format;

    public HeadCache(CommandPrompter plugin) {
        this.plugin = plugin;
        this.format = plugin.getPromptConfig().skullNameFormat();
        HEAD_CACHE = CacheBuilder.newBuilder().maximumSize(plugin.getPromptConfig().cacheSize())
                .build(new CacheLoader<>() {
                    @Override
                    public @NotNull Optional<ItemStack> load(@NotNull Player key) {
                        if (!Bukkit.getOnlinePlayers().contains(key))
                            return Optional.empty();
                        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                        var skullMeta = makeSkullMeta(key, plugin.getPluginLogger());
                        skull.setItemMeta(skullMeta);
                        return Optional.of(skull);
                    }
                });
    }

    public Optional<ItemStack> getHeadFor(Player player) {
        return HEAD_CACHE.getUnchecked(player);
    }

    public void invalidate(Player player) {
        HEAD_CACHE.invalidate(player);
    }

    public ImmutableMap<Player, Optional<ItemStack>> getHeadFor(Iterable<? extends Player> key) {
        try {
            return HEAD_CACHE.getAll(key);
        } catch (ExecutionException e) {
            return ImmutableMap.of();
        }
    }

    private List<ItemStack> sortHeads(ArrayList<ItemStack> headList) {
        @SuppressWarnings("unchecked")
        var copy = (ArrayList<ItemStack>) headList.clone();
        copy.sort((s1, s2) -> {
            var n1 = Util.stripColor(Objects.requireNonNull(s1.getItemMeta()).getDisplayName());
            var n2 = Util.stripColor(Objects.requireNonNull(s2.getItemMeta()).getDisplayName());
            return n1.compareToIgnoreCase(n2);
        });
        return copy;
    }

    public List<ItemStack> getHeadsFor(List<Player> players) {
        var result = new ArrayList<ItemStack>();
        for (Player player : players) {
            CommandPrompter.getInstance().getPluginLogger().debug("Player: " + player);
            getHeadFor(player).ifPresent(result::add);
        }
        return result;
    }

    public List<ItemStack> getHeadsSortedFor(List<Player> players) {
        return sortHeads((ArrayList<ItemStack>) getHeadsFor(players));
    }

    public List<ItemStack> getHeadsSorted() {
        var keys = HEAD_CACHE.asMap().entrySet().stream()
                .filter(entry -> entry.getValue().isPresent())
                .map(Map.Entry::getKey).toList();
        return sortHeads((ArrayList<ItemStack>) getHeadsFor(keys));
    }

    public List<ItemStack> getHeads() {
        return HEAD_CACHE.asMap().values().stream()
                .filter(Optional::isPresent)
                .map(Optional::get).toList();
    }

    private boolean checkNameFromItemStack(ItemStack is, String pName) {
        if (Objects.isNull(is) || Objects.isNull(is.getItemMeta())) return false;
        return Util.stripColor(is.getItemMeta().getDisplayName()).equals(pName);
    }


    private SkullMeta makeSkullMeta(Player owningPlayer, PluginLogger logger) {
        var skullMeta = (SkullMeta) Bukkit.getItemFactory().getItemMeta(Material.PLAYER_HEAD);
        Objects.requireNonNull(skullMeta).setOwningPlayer(owningPlayer);

        var papi = plugin.getHookContainer().getHook(PapiHook.class);
        var nameRef = new AtomicReference<String>();
        var skullFormat = plugin.getPromptConfig().skullNameFormat();
        papi.ifHooked(p -> nameRef.set(p.setPlaceholder(owningPlayer, skullFormat)))
                .orElse(() -> nameRef.set(String.format(skullFormat, owningPlayer.getName())));
        skullMeta.setDisplayName(Util.color(nameRef.get()));
        logger.debug("Skull Meta: {%s. %s}", skullMeta.getDisplayName(), skullMeta.getOwningPlayer());
        return skullMeta;
    }

    @EventHandler
    @SuppressWarnings("unused")
    public void onPlayerLogin(PlayerLoginEvent e) {
        var isInv = new AtomicBoolean(false);
        var svHook = plugin.getHookContainer().getHook(SuperVanishHook.class);
        plugin.getPluginLogger().debug("SV Hooked: " + svHook.isHooked());
        svHook.ifHooked(hook -> {
            if (hook.isInvisible(e.getPlayer()))
                isInv.set(true);
        });
        if (isInv.get()) {
            plugin.getPluginLogger().debug("Player is vanished (SuperVanish) skipping skull cache");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> HEAD_CACHE.getUnchecked(e.getPlayer()));
    }

    @EventHandler
    @SuppressWarnings("unused")
    public void onPlayerQuit(PlayerQuitEvent e) {
        HEAD_CACHE.invalidate(e.getPlayer());
    }
}
