package com.sifresistemi;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SifreSistemi extends JavaPlugin implements Listener, TabExecutor {

    private static final int MAX_ATTEMPTS = 3;
    private static final int MIN_LENGTH = 4;
    private static final int ITERATIONS = 20000;
    private static final String ADMIN_PERM = "sifre.admin";
    private static final Set<String> ALLOWED_WHEN_LOCKED = Set.of("kayit", "kayıt", "gir", "giris");

    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();

    private File dataFile;
    private YamlConfiguration data;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        getServer().getPluginManager().registerEvents(this, this);

        for (String name : List.of("kayit", "gir", "sifredeyisdir", "sifresifirla")) {
            var cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }

        for (Player p : Bukkit.getOnlinePlayers()) lock(p);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isLocked(p)) sendPrompt(p);
            }
        }, 200L, 200L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isLocked(p)) p.removePotionEffect(PotionEffectType.BLINDNESS);
        }
    }

    private boolean isLocked(Player p) {
        return !authed.contains(p.getUniqueId());
    }

    private void lock(Player p) {
        authed.remove(p.getUniqueId());
        attempts.remove(p.getUniqueId());
        p.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        sendPrompt(p);
    }

    private void unlock(Player p) {
        authed.add(p.getUniqueId());
        attempts.remove(p.getUniqueId());
        p.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    private void sendPrompt(Player p) {
        if (isRegistered(p.getName())) {
            p.sendMessage(Component.text("ɢɪʀɪꜱ ʏᴀᴘᴍᴀᴋ ɪᴄɪɴ ʏᴀᴢɪɴ /gir sifre", NamedTextColor.GREEN));
        } else {
            p.sendMessage(Component.text("ᴋᴀʏɪᴛ ᴏʟᴍᴀᴋ ɪᴄɪɴ ʏᴀᴢɪɴ /kayıt sifre sifre", NamedTextColor.GREEN));
        }
    }

    private void err(Player p, String msg) {
        p.sendMessage(Component.text(msg, NamedTextColor.RED));
    }

    private void ok(Player p, String msg) {
        p.sendMessage(Component.text(msg, NamedTextColor.GREEN));
    }

    private String path(String name) {
        return "players." + name.toLowerCase(Locale.ROOT).replace('.', '_');
    }

    private boolean isRegistered(String name) {
        return data.contains(path(name));
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("data.yml kaydedilemedi: " + ex.getMessage());
        }
    }

    private byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        Base64.Encoder enc = Base64.getEncoder();
        return ITERATIONS + ":" + enc.encodeToString(salt) + ":" + enc.encodeToString(pbkdf2(password, salt, ITERATIONS));
    }

    private boolean verify(String password, String stored) {
        try {
            String[] parts = stored.split(":");
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        if (name.equals("sifresifirla")) {
            resetCommand(sender, args);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Bu komut sadece oyuncular icindir.");
            return true;
        }

        switch (name) {
            case "kayit" -> register(p, args);
            case "gir" -> login(p, args);
            case "sifredeyisdir" -> changePassword(p, args);
            default -> { }
        }
        return true;
    }

    private void register(Player p, String[] args) {
        if (!isLocked(p)) { err(p, "Zaten giriş yaptınız."); return; }
        if (isRegistered(p.getName())) { err(p, "Zaten kayıtlısınız. Giriş için: /gir sifre"); return; }
        if (args.length != 2) { err(p, "Kullanım: /kayit sifre sifre"); return; }
        if (!args[0].equals(args[1])) { err(p, "Şifreler eşleşmiyor."); return; }
        if (args[0].length() < MIN_LENGTH) { err(p, "Şifre en az " + MIN_LENGTH + " karakter olmalı."); return; }

        data.set(path(p.getName()), hash(args[0]));
        save();
        unlock(p);
        ok(p, "Başarıyla kayıt oldunuz.");
    }

    private void login(Player p, String[] args) {
        if (!isLocked(p)) { err(p, "Zaten giriş yaptınız."); return; }
        String stored = data.getString(path(p.getName()));
        if (stored == null) { err(p, "Önce kayıt olmalısınız: /kayit sifre sifre"); return; }
        if (args.length != 1) { err(p, "Kullanım: /gir sifre"); return; }
        if (!verify(args[0], stored)) { wrongPassword(p); return; }

        unlock(p);
        ok(p, "Başarıyla giriş yaptınız.");
    }

    private void changePassword(Player p, String[] args) {
        if (isLocked(p)) { err(p, "Önce giriş yapmalısınız."); return; }
        if (args.length != 2) { err(p, "Kullanım: /sifredeyisdir eskisifre yenisifre"); return; }
        String stored = data.getString(path(p.getName()));
        if (stored == null || !verify(args[0], stored)) { wrongPassword(p); return; }
        if (args[1].length() < MIN_LENGTH) { err(p, "Yeni şifre en az " + MIN_LENGTH + " karakter olmalı."); return; }

        data.set(path(p.getName()), hash(args[1]));
        save();
        ok(p, "İşlem başarılı! Şifreniz değiştirildi.");
    }

    private void wrongPassword(Player p) {
        int used = attempts.merge(p.getUniqueId(), 1, Integer::sum);
        int left = MAX_ATTEMPTS - used;

        if (left <= 0) {
            attempts.remove(p.getUniqueId());
            p.kick(Component.text("HATALI SIFRE", NamedTextColor.RED, TextDecoration.BOLD));
            return;
        }

        p.sendMessage(Component.text("Yanlış Şifre", NamedTextColor.RED));
        p.sendMessage(Component.text("Kalan Hakkı: " + left, NamedTextColor.RED));
        p.showTitle(Title.title(
                Component.text("Yanlış Şifre", NamedTextColor.RED),
                Component.text("Kalan Hakkı: " + left, NamedTextColor.RED)));
    }

    private void resetCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERM)) {
            sender.sendMessage(Component.text("Bu komutu sadece adminler kullanabilir.", NamedTextColor.RED));
            return;
        }
        if (args.length != 1) {
            sender.sendMessage(Component.text("Kullanım: /sifresifirla oyuncu_ismi", NamedTextColor.RED));
            return;
        }
        if (!isRegistered(args[0])) {
            sender.sendMessage(Component.text("Bu isimde kayıtlı oyuncu bulunamadı.", NamedTextColor.RED));
            return;
        }

        data.set(path(args[0]), null);
        save();

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target != null) {
            lock(target);
            target.sendMessage(Component.text("Şifreniz bir admin tarafından sıfırlandı. Yeniden kayıt olun.", NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text(args[0] + " oyuncusunun şifresi sıfırlandı.", NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("sifresifirla") && args.length == 1 && sender.hasPermission(ADMIN_PERM)) {
            ConfigurationSection sec = data.getConfigurationSection("players");
            if (sec == null) return List.of();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return sec.getKeys(false).stream().filter(k -> k.startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        lock(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (isLocked(p)) p.removePotionEffect(PotionEffectType.BLINDNESS);
        authed.remove(p.getUniqueId());
        attempts.remove(p.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!isLocked(e.getPlayer())) return;
        Location f = e.getFrom();
        Location t = e.getTo();
        if (f.getX() != t.getX() || f.getY() != t.getY() || f.getZ() != t.getZ()
                || f.getYaw() != t.getYaw() || f.getPitch() != t.getPitch()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (isLocked(p)) {
            e.setCancelled(true);
            err(p, "Önce kayıt olun veya giriş yapın.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!isLocked(p)) return;

        String msg = e.getMessage().trim();
        if (msg.startsWith("/")) msg = msg.substring(1);
        String label = msg.split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        if (!ALLOWED_WHEN_LOCKED.contains(label)) {
            e.setCancelled(true);
            sendPrompt(p);
        }
    }

    private void cancelIfLocked(Player p, Cancellable c) {
        if (isLocked(p)) c.setCancelled(true);
    }

    @EventHandler public void onInteract(PlayerInteractEvent e) { cancelIfLocked(e.getPlayer(), e); }
    @EventHandler public void onInteractEntity(PlayerInteractEntityEvent e) { cancelIfLocked(e.getPlayer(), e); }
    @EventHandler public void onBreak(BlockBreakEvent e) { cancelIfLocked(e.getPlayer(), e); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { cancelIfLocked(e.getPlayer(), e); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { cancelIfLocked(e.getPlayer(), e); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { cancelIfLocked(e.getPlayer(), e); }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) cancelIfLocked(p, e);
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) cancelIfLocked(p, e);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) cancelIfLocked(p, e);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() instanceof Player p) cancelIfLocked(p, e);
    }
              }
