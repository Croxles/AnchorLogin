package com.sifresistemi;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import java.util.Arrays;
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
    private static final int LOGIN_SECONDS = 120; // kayit / giris icin sure
    private static final int ITERATIONS = 20000;
    private static final String ADMIN_PERM = "sifre.admin";

    // Herkesin kullanabildigi komutlar (izin sistemine takilmadan dogrudan islenir)
    private static final Set<String> OWN_COMMANDS =
            Set.of("kayit", "kayıt", "gir", "giris", "sifredeyisdir", "sifredegistir");

    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> authed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();

    // Geri sayim
    private final Map<UUID, Integer> timeLeft = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    // Giris ekranindayken oyuncunun eski gamemode'u (cikista / giriste geri verilir)
    private final Map<UUID, GameMode> previousMode = new ConcurrentHashMap<>();
    // Pluginin kendi yaptigi gamemode degisikliklerinin engellenmemesi icin
    private final Set<UUID> gmBypass = ConcurrentHashMap.newKeySet();

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

        // Her saniye: geri sayim
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            hideBar(p);
            if (isLocked(p)) {
                p.removePotionEffect(PotionEffectType.BLINDNESS);
                restoreMode(p);
            }
        }
    }

    // ---------------------------------------------------------------- geri sayim

    private void tick() {
        for (UUID id : timeLeft.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !isLocked(p)) {
                timeLeft.remove(id);
                BossBar old = bars.remove(id);
                if (old != null && p != null) p.hideBossBar(old);
                continue;
            }

            int left = timeLeft.merge(id, -1, Integer::sum);

            if (left <= 0) {
                timeLeft.remove(id);
                hideBar(p);
                p.kick(Component.text("SÜRE DOLDU", NamedTextColor.RED, TextDecoration.BOLD)
                        .append(Component.newline())
                        .append(Component.text(
                                LOGIN_SECONDS + " saniye içinde kayıt olmalı veya giriş yapmalıydınız.",
                                NamedTextColor.GRAY)));
                continue;
            }

            updateBar(p, left);
            if (left % 10 == 0) sendPrompt(p);
        }
    }

    private Component timerText(int left) {
        return Component.text("Kalan süre: " + left + " saniye", NamedTextColor.WHITE, TextDecoration.BOLD);
    }

    private void startTimer(Player p) {
        UUID id = p.getUniqueId();
        hideBar(p);
        timeLeft.put(id, LOGIN_SECONDS);
        BossBar bar = BossBar.bossBar(timerText(LOGIN_SECONDS), 1.0f,
                BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        bars.put(id, bar);
        p.showBossBar(bar);
    }

    private void stopTimer(Player p) {
        timeLeft.remove(p.getUniqueId());
        hideBar(p);
    }

    private void updateBar(Player p, int left) {
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) return;
        bar.name(timerText(left));
        bar.progress(Math.max(0f, Math.min(1f, left / (float) LOGIN_SECONDS)));
        bar.color(left > 60 ? BossBar.Color.GREEN : left > 30 ? BossBar.Color.YELLOW : BossBar.Color.RED);
    }

    private void hideBar(Player p) {
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    // ---------------------------------------------------------------- durum

    private boolean isLocked(Player p) {
        return !authed.contains(p.getUniqueId());
    }

    private void forceMode(Player p, GameMode mode) {
        UUID id = p.getUniqueId();
        gmBypass.add(id);
        try {
            p.setGameMode(mode);
        } finally {
            gmBypass.remove(id);
        }
    }

    private void restoreMode(Player p) {
        GameMode prev = previousMode.remove(p.getUniqueId());
        if (prev != null && p.getGameMode() != prev) {
            forceMode(p, prev);
        }
    }

    private void lock(Player p) {
        UUID id = p.getUniqueId();
        authed.remove(id);
        attempts.remove(id);

        previousMode.putIfAbsent(id, p.getGameMode());
        if (p.getGameMode() != GameMode.SURVIVAL) {
            forceMode(p, GameMode.SURVIVAL);
        }

        startTimer(p);
        p.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        sendPrompt(p);
    }

    private void unlock(Player p) {
        authed.add(p.getUniqueId());
        attempts.remove(p.getUniqueId());
        stopTimer(p);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        restoreMode(p);
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

    // ---------------------------------------------------------------- veri

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

    // ---------------------------------------------------------------- komutlar

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
        if (!(sender.isOp() || sender.hasPermission(ADMIN_PERM))) {
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
        if (cmd.getName().equalsIgnoreCase("sifresifirla") && args.length == 1
                && (sender.isOp() || sender.hasPermission(ADMIN_PERM))) {
            ConfigurationSection sec = data.getConfigurationSection("players");
            if (sec == null) return List.of();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return sec.getKeys(false).stream().filter(k -> k.startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    // ---------------------------------------------------------------- olaylar

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        lock(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (isLocked(p)) {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            restoreMode(p); // oyuncu verisi eski gamemode ile kaydedilsin
        }
        hideBar(p);
        timeLeft.remove(p.getUniqueId());
        authed.remove(p.getUniqueId());
        attempts.remove(p.getUniqueId());
        previousMode.remove(p.getUniqueId());
        gmBypass.remove(p.getUniqueId());
    }

    // Giris ekranindayken gamemode degistirme (F3+F4, F3+N, /gamemode) engeli
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (gmBypass.contains(id)) return;
        if (previousMode.containsKey(id) && isLocked(p)) {
            e.setCancelled(true);
            err(p, "Bu işlem için izniniz yok.");
        }
    }

    // Yurume ve donme engeli
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

    // Sohbet engeli
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (isLocked(p)) {
            e.setCancelled(true);
            err(p, "Önce kayıt olun veya giriş yapın.");
        }
    }

    // Komutlar: kendi komutlarimizi dogrudan isleriz (herkes kullanir),
    // giris yapmayanlar baska komut kullanamaz
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();

        String msg = e.getMessage().trim();
        if (msg.startsWith("/")) msg = msg.substring(1);
        String[] parts = msg.split("\\s+");
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        if (OWN_COMMANDS.contains(label)) {
            e.setCancelled(true);
            String[] args = Arrays.copyOfRange(parts, 1, parts.length);
            switch (label) {
                case "kayit", "kayıt" -> register(p, args);
                case "gir", "giris" -> login(p, args);
                default -> changePassword(p, args);
            }
            return;
        }

        if (isLocked(p)) {
            e.setCancelled(true);
            sendPrompt(p);
        }
    }

    // Diger kisitlamalar
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
