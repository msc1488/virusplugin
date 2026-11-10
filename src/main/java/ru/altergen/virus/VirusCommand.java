package ru.altergen.virus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Административные команды /virus infect и /virus cure. */
public final class VirusCommand implements TabExecutor {

    private final VirusManager virusManager;

    public VirusCommand(VirusManager virusManager) {
        this.virusManager = virusManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("virus.admin")) {
            sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
            return true;
        }

        if (args.length != 2
                || (!args[0].equalsIgnoreCase("infect") && !args[0].equalsIgnoreCase("cure"))) {
            sender.sendMessage(ChatColor.YELLOW + "Использование: /virus <infect|cure> <ник>");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "Игрок с таким ником не найден или еще не заходил на сервер.");
            return true;
        }

        boolean infectCommand = args[0].equalsIgnoreCase("infect");
        boolean changed = infectCommand
                ? virusManager.infect(target.getUniqueId())
                : virusManager.cure(target.getUniqueId());

        if (!changed) {
            sender.sendMessage(infectCommand
                    ? ChatColor.YELLOW + "Игрок уже заражен."
                    : ChatColor.YELLOW + "Игрок не заражен.");
            return true;
        }

        sender.sendMessage((infectCommand ? ChatColor.GREEN + "Игрок заражен: " : ChatColor.GREEN + "Игрок исцелен: ")
                + target.getName());
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1) {
            return partial(args[0], List.of("infect", "cure"));
        }

        if (args.length == 2
                && (args[0].equalsIgnoreCase("infect") || args[0].equalsIgnoreCase("cure"))) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return partial(args[1], names);
        }

        return List.of();
    }

    private List<String> partial(String input, List<String> values) {
        String lowerInput = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowerInput))
                .toList();
    }
}
