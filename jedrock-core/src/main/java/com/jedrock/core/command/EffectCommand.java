package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.entity.Effect;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.effect.ActiveEffect;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /effect <player> <effect> [seconds] [level]}, {@code /effect <player> clear [effect]} and
 * {@code /effect <player> list} — put somebody under something, or take it away.
 *
 * <p>The level is written the way a person says it: {@code 1} is Speed I. (The wire counts from zero,
 * and the conversion happens here rather than in anybody's head.)
 *
 * <p>Nearly all of this is scenery the client draws and applies for itself. The exceptions are worth
 * knowing when using the command: speed and jump boost widen what the server will believe about your
 * movement, strength / weakness / resistance change what a hit does, instant health and damage are
 * applied by the server outright, and invisibility withholds your avatar from other clients. Poison and
 * regeneration <em>look</em> right and change nothing — the server does not tick health.
 */
public final class EffectCommand implements Command {

    /** An hour is long enough for anything, and stops a typo pinning somebody under an effect. */
    private static final int MAX_SECONDS = 3600;
    private static final int DEFAULT_SECONDS = 30;
    private static final int MAX_LEVEL = 256;

    @Override
    public String name() {
        return "effect";
    }

    @Override
    public String description() {
        return "Give or clear a status effect";
    }

    @Override
    public String usage() {
        return "/effect <player> <effect|clear|list> [seconds] [level]";
    }

    @Override
    public String permission() {
        return "jedrock.command.effect";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(
                CommandArg.required("player", ArgType.PLAYER),
                CommandArg.required("effect", EFFECT),
                CommandArg.optional("seconds", ArgType.INTEGER),
                CommandArg.optional("level", ArgType.INTEGER));
    }

    /** An effect by name, completing to every one there is plus the two verbs. */
    private static final ArgType<String> EFFECT = new ArgType<>() {
        @Override public String label() { return "effect|clear|list"; }
        @Override public String parse(com.jedrock.api.Server s, CommandSender c, String t) {
            return t;   // resolved in execute, which also has to accept 'clear' and 'list'
        }
        @Override public List<String> complete(com.jedrock.api.Server s, CommandSender c, String partial) {
            List<String> options = new ArrayList<>(List.of("clear", "list"));
            for (Effect effect : Effect.values()) {
                options.add(effect.getKey());
            }
            return ArgType.matching(partial, options);
        }
    };

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: {white}" + usage());
            return;
        }
        Optional<Player> found = server.getPlayer(args[0]);
        if (found.isEmpty() || !(found.get() instanceof CorePlayer target)) {
            sender.sendMessage("{red}Player not found: {white}" + ChatText.escape(args[0]));
            return;
        }
        String what = args[1].trim().toLowerCase(Locale.ROOT);

        if (what.equals("list")) {
            list(sender, server, target);
            return;
        }
        if (what.equals("clear")) {
            clear(sender, server, target, args.length >= 3 ? args[2] : null);
            return;
        }

        Effect effect = Effect.fromString(what);
        if (effect == null) {
            sender.sendMessage("{red}'" + ChatText.escape(what) + "' is not an effect. Try {white}"
                    + "speed{red}, {white}strength{red}, {white}invisibility{red} — or {white}/effect "
                    + ChatText.escape(target.getName()) + " list{red}.");
            return;
        }

        int seconds = DEFAULT_SECONDS;
        if (args.length >= 3) {
            Integer parsed = number(sender, args[2], 0, MAX_SECONDS, "seconds");
            if (parsed == null) {
                return;
            }
            seconds = parsed;
        }
        int level = 1;
        if (args.length >= 4) {
            Integer parsed = number(sender, args[3], 1, MAX_LEVEL, "level");
            if (parsed == null) {
                return;
            }
            level = parsed;
        }

        // A level of 0 seconds is how vanilla spells "take it off", and somebody will type it.
        if (seconds == 0 && !effect.isInstant()) {
            boolean had = server.getEffects().remove(target, effect);
            sender.sendMessage(had
                    ? "{green}Took {white}" + effect.getKey() + "{green} off {white}" + name(target)
                    : "{gray}" + ChatText.escape(target.getName()) + " wasn't under " + effect.getKey() + ".");
            return;
        }

        if (!server.getEffects().apply(target, effect, level - 1, seconds, true)) {
            sender.sendMessage("{red}Something refused that effect.");
            return;
        }

        String what0 = "{white}" + effect.getKey() + (level > 1 ? " " + roman(level) : "");
        if (effect.isInstant()) {
            sender.sendMessage("{green}Applied " + what0 + "{green} to {white}" + name(target) + "{green}.");
        } else {
            sender.sendMessage("{green}Gave " + what0 + "{green} to {white}" + name(target)
                    + "{green} for {white}" + seconds + "s{green}.");
            if (target != sender) {
                target.sendMessage("{green}You are under " + what0 + "{green} for {white}" + seconds + "s{green}.");
            }
        }
    }

    private static void list(CommandSender sender, JedrockServer server, CorePlayer target) {
        Map<Effect, ActiveEffect> live = server.getEffects().active(target);
        if (live.isEmpty()) {
            sender.sendMessage("{gray}" + ChatText.escape(target.getName()) + " is under nothing.");
            return;
        }
        long now = System.currentTimeMillis();
        sender.sendMessage("{gold}" + ChatText.escape(target.getName()) + " is under:");
        for (ActiveEffect a : live.values()) {
            sender.sendMessage(" {gray}• {white}" + a.effect().getKey()
                    + (a.amplifier() > 0 ? " " + roman(a.amplifier() + 1) : "")
                    + " {dark_gray}— {gray}" + a.remainingSeconds(now) + "s left");
        }
    }

    private static void clear(CommandSender sender, JedrockServer server, CorePlayer target, String one) {
        if (one == null) {
            int had = server.getEffects().clear(target);
            sender.sendMessage(had == 0
                    ? "{gray}" + ChatText.escape(target.getName()) + " was under nothing."
                    : "{green}Cleared {white}" + had + "{green} effect" + (had == 1 ? "" : "s")
                            + " from {white}" + name(target) + "{green}.");
            return;
        }
        Effect effect = Effect.fromString(one);
        if (effect == null) {
            sender.sendMessage("{red}'" + ChatText.escape(one) + "' is not an effect.");
            return;
        }
        sender.sendMessage(server.getEffects().remove(target, effect)
                ? "{green}Took {white}" + effect.getKey() + "{green} off {white}" + name(target) + "{green}."
                : "{gray}" + ChatText.escape(target.getName()) + " wasn't under " + effect.getKey() + ".");
    }

    /** A bounded whole number, or {@code null} after telling the sender what was wrong with it. */
    private static Integer number(CommandSender sender, String token, int min, int max, String what) {
        try {
            int value = Integer.parseInt(token.trim());
            if (value < min || value > max) {
                sender.sendMessage("{red}The " + what + " must be between {white}" + min
                        + "{red} and {white}" + max + "{red}.");
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            sender.sendMessage("{red}'" + ChatText.escape(token) + "' is not a whole number.");
            return null;
        }
    }

    private static String name(CorePlayer player) {
        return ChatText.escape(player.getName());
    }

    /** Levels are shown the way the game shows them. Past five, the number is clearer than the numeral. */
    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(level);
        };
    }
}
