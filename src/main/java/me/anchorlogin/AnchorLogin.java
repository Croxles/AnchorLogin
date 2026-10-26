package me.anchorlogin;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AnchorLogin extends JavaPlugin implements Listener {

private final Map<UUID, String> passwords = new HashMap<>();
private final Set<UUID> loggedIn = new HashSet<>();
private final Map<UUID, Integer> attempts = new HashMap<>();
private final Map<UUID, Location> joinLocations = new HashMap<>();
private final SecureRandom random = new SecureRandom();

@Override
public void onEnable() {
    saveDefaultConfig();
    loadPasswords();
    getServer().getPluginManager().registerEvents(this, this);
    getLogger().info("AnchorLogin aktif!");
}

@Override
public void onDisable() {
    savePasswords();
}

private void loadPasswords() {
    passwords.clear();
    for (String key : getConfig().getConfigurationSection("users") == null
            ? new HashSet<String>()
            : getConfig().getConfigurationSection("users").getKeys(false)) {
        passwords.put(UUID.fromString(key), getConfig().getString("users." + key));
    }
}

private void savePasswords() {
    for (UUID id : passwords.keySet()) {
        getConfig().set("users." + id, passwords.get(id));
    }
    saveConfig();
}

private String hashPassword(String password) {
    try {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] input = new byte[salt.length + password.getBytes(StandardCharsets.UTF_8).length];
        System.arraycopy(salt, 0, input, 0, salt.length);
        System.arraycopy(password.getBytes(StandardCharsets.UTF_8), 0, input, salt.length,
                password.getBytes(StandardCharsets.UTF_8).length);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
        return Base64.getEncoder().encodeToString(salt) + ":" +
                Base64.getEncoder().encodeToString(digest);
    } catch (Exception e) {
        throw new IllegalStateException("Şifre hashlenemedi", e);
    }
}

private boolean passwordMatches(String password, String stored) {
    try {
        String[] parts = stored.split(":");
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] inputPassword = password.getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[salt.length + inputPassword.length];
        System.arraycopy(salt, 0, input, 0, salt.length);
        System.arraycopy(inputPassword, 0, input, salt.length, inputPassword.length);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        return MessageDigest.isEqual(digest, expected);
    } catch (Exception e) {
        return false;
    }
}

@EventHandler
public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    UUID id = player.getUniqueId();
    loggedIn.remove(id);
    attempts.put(id, 0);
    joinLocations.put(id, player.getLocation().clone());

    if (passwords.containsKey(id)) {
        player.sendMessage(ChatColor.GREEN + "Giriş yapmak için: /gir <şifre>");
    } else {
        player.sendMessage(ChatColor.YELLOW + "Kayıt olmak için: /kayit <şifre> <şifre>");
    }
}

@EventHandler
public void onQuit(PlayerQuitEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    loggedIn.remove(id);
    attempts.remove(id);
    joinLocations.remove(id);
}

@EventHandler
public void onMove(PlayerMoveEvent event) {
    Player player = event.getPlayer();
    if (!loggedIn.contains(player.getUniqueId()) &&
            event.getTo() != null &&
            (event.getFrom().getBlockX() != event.getTo().getBlockX()
            || event.getFrom().getBlockY() != event.getTo().getBlockY()
            || event.getFrom().getBlockZ() != event.getTo().getBlockZ())) {
        Location old = joinLocations.get(player.getUniqueId());
        if (old != null) {
            event.setTo(old);
        }
    }
}

@EventHandler
public void onChat(AsyncPlayerChatEvent event) {
    if (!loggedIn.contains(event.getPlayer().getUniqueId())) {
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "Önce giriş yapmalısın!");
    }
}

@EventHandler
public void onCommand(PlayerCommandPreprocessEvent event) {
    Player player = event.getPlayer();
    if (loggedIn.contains(player.getUniqueId())) return;

    String message = event.getMessage().toLowerCase();
    if (!message.startsWith("/gir ") &&
            !message.startsWith("/kayit ") &&
            !message.startsWith("/sifredeyisdir ")) {
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "Önce kayıt ol veya giriş yap!");
    }
}

@Override
public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) {
        sender.sendMessage("Bu komut oyuncular içindir.");
        return true;
    }

    UUID id = player.getUniqueId();
    String cmd = command.getName().toLowerCase();

    if (cmd.equals("kayit")) {
        if (passwords.containsKey(id)) {
            player.sendMessage(ChatColor.RED + "Zaten kayıtlısın!");
            return true;
        }
        if (args.length != 2 || !args[0].equals(args[1])) {
            player.sendMessage(ChatColor.YELLOW + "Kullanım: /kayit <şifre> <şifre>");
            return true;
        }
        if (args[0].length() < getConfig().getInt("minimum-password-length", 6)) {
            player.sendMessage(ChatColor.RED + "Şifre en az 6 karakter olmalı.");
            return true;
        }

        passwords.put(id, hashPassword(args[0]));
        savePasswords();
        loggedIn.add(id);
        player.sendMessage(ChatColor.GREEN + "Kayıt başarılı! Giriş yaptın.");
        return true;
    }

    if (cmd.equals("gir")) {
        if (!passwords.containsKey(id)) {
            player.sendMessage(ChatColor.RED + "Önce /kayit <şifre> <şifre> yaz.");
            return true;
        }
        if (args.length != 1 || !passwordMatches(args[0], passwords.get(id))) {
            int count = attempts.merge(id, 1, Integer::sum);
            int max = getConfig().getInt("max-attempts", 3);
            player.sendMessage(ChatColor.RED + "Şifre yanlış! Deneme: " + count + "/" + max);
            if (count >= max) {
                player.kickPlayer("Çok fazla yanlış şifre denemesi.");
            }
            return true;
        }
        loggedIn.add(id);
        attempts.remove(id);
        player.sendMessage(ChatColor.GREEN + "Başarıyla giriş yaptın!");
        return true;
    }

    if (cmd.equals("sifredeyisdir")) {
        if (!loggedIn.contains(id)) {
            player.sendMessage(ChatColor.RED + "Önce giriş yapmalısın.");
            return true;
        }
        if (args.length != 2 || !passwordMatches(args[0], passwords.get(id))) {
            player.sendMessage(ChatColor.RED + "Kullanım: /sifredeyisdir <eski> <yeni>");
            return true;
        }
        if (args[1].length() < getConfig().getInt("minimum-password-length", 6)) {
            player.sendMessage(ChatColor.RED + "Yeni şifre en az 6 karakter olmalı.");
            return true;
        }
        passwords.put(id, hashPassword(args[1]));
        savePasswords();
        player.sendMessage(ChatColor.GREEN + "Şifren değiştirildi!");
        return true;
    }

    return false;
}

      }
