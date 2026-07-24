package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.world.Weather;
import com.jedrock.core.JedrockServer;
import com.jedrock.utils.text.ChatText;

import java.util.Locale;

/**
 * {@code /weather <clear|rain|thunder>} — set the world's (purely cosmetic) weather. No argument
 * reports the current state. The change is broadcast to every player, cross-edition, and a late
 * joiner walks into the same sky.
 */
public final class WeatherCommand implements Command {

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "Set the weather (clear / rain / thunder)";
    }

    @Override
    public String usage() {
        return "/weather <clear|rain|thunder>";
    }

    @Override
    public String permission() {
        return "jedrock.command.weather";
    }

    @Override
    public java.util.List<CommandArg> arguments() {
        // Declared for tab-completion only; execute() below still parses the raw args.
        return java.util.List.of(
                CommandArg.required("weather", ArgType.choice("clear", "rain", "thunder")));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{gray}Weather is {white}"
                    + server.getDefaultWorld().getWeather().name().toLowerCase(Locale.ROOT));
            return;
        }
        Weather weather;
        try {
            weather = Weather.valueOf(args[0].trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage("{red}Unknown weather: {white}" + ChatText.escape(args[0])
                    + "{red}. Usage: " + usage());
            return;
        }
        server.getDefaultWorld().setWeather(weather);
        sender.sendMessage("{green}Weather set to {white}" + weather.name().toLowerCase(Locale.ROOT));
    }
}
