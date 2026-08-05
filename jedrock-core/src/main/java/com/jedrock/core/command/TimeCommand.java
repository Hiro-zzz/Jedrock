package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.World;
import com.jedrock.core.JedrockServer;

import java.util.List;
import java.util.Locale;

/**
 * {@code /time set|add|query|freeze|resume} — move the sun.
 *
 * <p>Scenery, like the weather beside it, and not simulation: the server holds a number and tells clients
 * what it is, and the <em>client</em> animates the sky between updates. That is why a day passes here with
 * nothing on this side ticking to make it, and why a world with nobody in it costs nothing to keep the
 * time of.
 *
 * <p>Freezing is the client's own mechanism rather than a rule enforced here — Java reads a negative time
 * as "stop counting", 0.14 has a flag for it, and 1.1.5 has neither, so there the sky is held still by
 * being told again. That difference is the only place the four editions do not behave identically.
 *
 * <p>Acts on the world the sender is standing in; from the console, on the default world.
 */
public final class TimeCommand implements Command {

    /** The named hours everybody actually types. */
    private static final long DAY = 1000L;
    private static final long NOON = 6000L;
    private static final long SUNSET = 12000L;
    private static final long NIGHT = 13000L;
    private static final long MIDNIGHT = 18000L;

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description() {
        return "Set or read the time of day";
    }

    @Override
    public String usage() {
        return "/time set <day|noon|night|midnight|ticks> | add <ticks> | query | freeze | resume";
    }

    @Override
    public String permission() {
        return "jedrock.command.time";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(
                CommandArg.optional("action", ArgType.choice("set", "add", "query", "freeze", "resume")),
                CommandArg.optional("value", ArgType.WORD));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        World world = sender instanceof Player player ? player.getWorld() : server.getDefaultWorld();
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "query";

        switch (action) {
            case "query" -> report(sender, world);
            case "freeze" -> {
                world.setDaylightCycle(false);
                sender.sendMessage("{green}The sun has stopped {gray}at " + clock(world.getTime())
                        + " in {white}" + world.getName());
            }
            case "resume" -> {
                world.setDaylightCycle(true);
                sender.sendMessage("{green}The sun is moving again {gray}in {white}" + world.getName());
            }
            case "set" -> {
                if (args.length < 2) {
                    sender.sendMessage("{red}Usage: /time set <day|noon|night|midnight|ticks>");
                    return;
                }
                Long ticks = parse(args[1]);
                if (ticks == null) {
                    sender.sendMessage("{red}'" + args[1] + "' is not a time — try {white}day{red}, "
                            + "{white}noon{red}, {white}night{red}, {white}midnight{red}, or a number "
                            + "of ticks {gray}(0-23999, 0 is sunrise)");
                    return;
                }
                world.setTime(ticks);
                report(sender, world);
            }
            case "add" -> {
                if (args.length < 2) {
                    sender.sendMessage("{red}Usage: /time add <ticks>");
                    return;
                }
                try {
                    world.setTime(world.getTime() + Long.parseLong(args[1].trim()));
                } catch (NumberFormatException e) {
                    sender.sendMessage("{red}'" + args[1] + "' is not a whole number of ticks.");
                    return;
                }
                report(sender, world);
            }
            default -> sender.sendMessage("{red}Usage: " + usage());
        }
    }

    private static void report(CommandSender sender, World world) {
        long time = world.getTime();
        sender.sendMessage("{gray}Time in {white}" + world.getName() + "{gray}: {white}" + time
                + " {dark_gray}(" + clock(time) + ", " + phase(time) + ")"
                + (world.isDaylightCycle() ? "" : " {yellow}frozen"));
    }

    /** A tick count as the hour it actually is, because 13000 means nothing to anyone. */
    static String clock(long ticks) {
        // Tick 0 is 06:00 in Minecraft, and a day is 24000 ticks over 24 hours — 1000 ticks to the hour.
        long minutesOfDay = Math.floorMod((ticks + 6000L) * 60L / 1000L, 1440L);
        return String.format(Locale.ROOT, "%02d:%02d", minutesOfDay / 60, minutesOfDay % 60);
    }

    /** Which part of the day it is, in the terms the game actually behaves by. */
    static String phase(long ticks) {
        long t = Math.floorMod(ticks, 24000L);
        if (t < SUNSET) {
            return "day";
        }
        if (t < NIGHT) {
            return "sunset";
        }
        return t < 23000L ? "night" : "sunrise";
    }

    /** A named hour or a raw tick count; {@code null} if it is neither. */
    static Long parse(String value) {
        String word = value.trim().toLowerCase(Locale.ROOT);
        switch (word) {
            case "day", "sunrise", "morning" -> {
                return DAY;
            }
            case "noon", "midday" -> {
                return NOON;
            }
            case "sunset", "evening" -> {
                return SUNSET;
            }
            case "night" -> {
                return NIGHT;
            }
            case "midnight" -> {
                return MIDNIGHT;
            }
            default -> {
                try {
                    return Long.parseLong(word);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
    }
}
