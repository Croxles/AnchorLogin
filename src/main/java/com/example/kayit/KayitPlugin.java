package com.example.kayit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class KayitPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private static final int MIN_SIFRE = 4;
    private static final int MAX_SIFRE = 32;
    private static final int MAX_HAK = 3;

    private File dataFile;
    private YamlConfiguration data;

    /** Giriş yapmış oyuncular (bellekte, çıkınca silinir). */
    private final Set<UUID> girisYapanlar = new HashSet<>();
    /** Kalan şifre hakları (bellekte, oyuna girince sıfırlanır). */
    private final Map<UUID, Integer> kalanHaklar = new HashMap<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "kayitlar.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        for (String name : new String[]{"kayit", "gir", "sifredeyisdir", "sifresifirla"}) {
            var cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
            }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("KayitPlugin aktif!");
    }

    // ---------------------------------------------------------------- olaylar

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        girisYapanlar.remove(id);
        kalanHaklar.put(id, MAX_HAK); // oyuna her girişte 3 hak
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        girisYapanlar.remove(id);
        kalanHaklar.remove(id);
    }

    // --------------------------------------------------------------- komutlar

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("sifresifirla")) {
            return sifirla(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Bu komutu sadece oyuncular kullanabilir.", NamedTextColor.RED));
            return true;
        }

        switch (cmd) {
            case "kayit" -> kayit(player, args);
            case "gir" -> gir(player, args);
            case "sifredeyisdir" -> sifreDegistir(player, args);
            default -> { }
        }
        return true;
    }

    private void kayit(Player player, String[] args) {
        String key = key(player.getUniqueId());

        if (data.contains(key)) {
            hata(player, "Zaten kayıtlısınız! Giriş için: /gir <şifre>");
            return;
        }
        if (args.length != 2) {
            hata(player, "Kullanım: /kayit <şifre> <şifre>");
            return;
        }
        if (!args[0].equals(args[1])) {
            hata(player, "Şifreler birbiriyle eşleşmiyor!");
            return;
        }
        if (!sifreUygunMu(args[0])) {
            hata(player, "Şifre " + MIN_SIFRE + " ile " + MAX_SIFRE + " karakter arasında olmalıdır!");
            return;
        }

        sifreyiKaydet(player.getUniqueId(), player.getName(), args[0]);
        if (!kaydet()) {
            data.set(key, null);
            hata(player, "Bir hata oluştu, lütfen yetkiliye bildirin.");
            return;
        }

        girisYapanlar.add(player.getUniqueId());
        basari(player, "Başarıyla kayıt oldunuz.");
    }

    private void gir(Player player, String[] args) {
        UUID id = player.getUniqueId();
        String key = key(id);

        if (!data.contains(key)) {
            hata(player, "Kayıtlı değilsiniz! Önce: /kayit <şifre> <şifre>");
            return;
        }
        if (girisYapanlar.contains(id)) {
            hata(player, "Zaten giriş yaptınız!");
            return;
        }
        if (args.length != 1) {
            hata(player, "Kullanım: /gir <şifre>");
            return;
        }

        if (sifreDogruMu(id, args[0])) {
            girisYapanlar.add(id);
            kalanHaklar.put(id, MAX_HAK);
            basari(player, "Başarıyla giriş yaptınız.");
        } else {
            yanlisSifre(player);
        }
    }

    private void sifreDegistir(Player player, String[] args) {
        UUID id = player.getUniqueId();

        if (!data.contains(key(id))) {
            hata(player, "Kayıtlı değilsiniz! Önce: /kayit <şifre> <şifre>");
            return;
        }
        if (args.length != 2) {
            hata(player, "Kullanım: /sifredeyisdir <eski şifre> <yeni şifre>");
            return;
        }
        if (!sifreDogruMu(id, args[0])) {
            yanlisSifre(player);
            return;
        }
        if (!sifreUygunMu(args[1])) {
            hata(player, "Yeni şifre " + MIN_SIFRE + " ile " + MAX_SIFRE + " karakter arasında olmalıdır!");
            return;
        }

        sifreyiKaydet(id, player.getName(), args[1]);
        if (!kaydet()) {
            hata(player, "Bir hata oluştu, lütfen yetkiliye bildirin.");
            return;
        }
        kalanHaklar.put(id, MAX_HAK);
        basari(player, "İşlem başarılı.");
    }

    private boolean sifirla(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kayit.admin")) {
            sender.sendMessage(Component.text("Bu komutu sadece yetkililer kullanabilir!", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(Component.text("Kullanım: /sifresifirla <oyuncu>", NamedTextColor.RED));
            return true;
        }

        String hedefIsim = args[0];
        ConfigurationSection section = data.getConfigurationSection("oyuncular");
        String bulunanKey = null;
        if (section != null) {
            for (String uuidStr : section.getKeys(false)) {
                String isim = section.getString(uuidStr + ".isim", "");
                if (isim.equalsIgnoreCase(hedefIsim)) {
                    bulunanKey = uuidStr;
                    break;
                }
            }
        }

        if (bulunanKey == null) {
            sender.sendMessage(Component.text(hedefIsim + " adlı kayıtlı oyuncu bulunamadı!", NamedTextColor.RED));
            return true;
        }

        data.set("oyuncular." + bulunanKey, null);
        if (!kaydet()) {
            sender.sendMessage(Component.text("Dosya kaydedilemedi, konsolu kontrol edin.", NamedTextColor.RED));
            return true;
        }

        UUID id = UUID.fromString(bulunanKey);
        girisYapanlar.remove(id);
        kalanHaklar.put(id, MAX_HAK);

        Player hedef = Bukkit.getPlayer(id);
        if (hedef != null) {
            hedef.sendMessage(Component.text(
                    "Şifreniz bir yetkili tarafından sıfırlandı. Yeniden kayıt olun: /kayit <şifre> <şifre>",
                    NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text(hedefIsim + " adlı oyuncunun şifresi sıfırlandı.", NamedTextColor.GREEN));
        return true;
    }

    // ---------------------------------------------------------------- yardımcı

    private void yanlisSifre(Player player) {
        UUID id = player.getUniqueId();
        int kalan = kalanHaklar.getOrDefault(id, MAX_HAK) - 1;
        kalanHaklar.put(id, kalan);

        if (kalan <= 0) {
            kalanHaklar.remove(id);
            player.kick(Component.text("HATALI ŞİFRE", NamedTextColor.RED, TextDecoration.BOLD));
            return;
        }

        player.sendMessage(Component.text("Yanlış şifre girdiniz!", NamedTextColor.RED));
        player.sendMessage(Component.text("Kalan Hak: " + kalan, NamedTextColor.RED));
    }

    private boolean sifreUygunMu(String sifre) {
        return sifre.length() >= MIN_SIFRE && sifre.length() <= MAX_SIFRE;
    }

    private boolean sifreDogruMu(UUID id, String girilen) {
        String salt = data.getString(key(id) + ".salt");
        String kayitliHash = data.getString(key(id) + ".hash");
        if (salt == null || kayitliHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(girilen, salt).getBytes(StandardCharsets.UTF_8),
                kayitliHash.getBytes(StandardCharsets.UTF_8));
    }

    private void sifreyiKaydet(UUID id, String isim, String sifre) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltStr = Base64.getEncoder().encodeToString(salt);

        data.set(key(id) + ".isim", isim);
        data.set(key(id) + ".salt", saltStr);
        data.set(key(id) + ".hash", hash(sifre, saltStr));
    }

    private boolean kaydet() {
        try {
            data.save(dataFile);
            return true;
        } catch (IOException e) {
            getLogger().severe("Kayıt dosyası kaydedilemedi: " + e.getMessage());
            return false;
        }
    }

    private static String key(UUID id) {
        return "oyuncular." + id;
    }

    private static void hata(Player p, String mesaj) {
        p.sendMessage(Component.text(mesaj, NamedTextColor.RED));
    }

    private static void basari(Player p, String mesaj) {
        p.sendMessage(Component.text(mesaj, NamedTextColor.GREEN));
    }

    private static String hash(String sifre, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest(sifre.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
          }
