package com.jedrock.core.plugin;

import com.jedrock.api.Server;
import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.command.ArgType;
import com.jedrock.core.command.Command;
import org.mozilla.javascript.Function;

import java.util.List;

/**
 * A {@link Command} backed by a script function — the adapter behind {@code commands.register(...)}. It
 * carries the name / description / usage / aliases a script supplied and, on {@link #execute}, routes to the
 * script's handler through {@link PluginManager} (under the script lock, in a Rhino context) with the sender
 * as an api {@code Player} and the raw {@code String[]} args. A thrown error propagates to
 * {@link com.jedrock.core.command.CommandManager}, which reports "command failed" to the sender — the command
 * contract, unlike the swallow-and-log used for events and scheduled tasks.
 */
final class ScriptCommand implements Command {

    private final PluginManager manager;
    private final ScriptPlugin plugin;
    private final String name;
    private final String description;
    private final String usage;
    private final List<String> aliases;
    private final Function handler;
    /** Optional {@code complete} function supplying tab-completion suggestions; null = none. */
    private final Function completer;

    ScriptCommand(PluginManager manager, ScriptPlugin plugin, String name, String description, String usage,
                  List<String> aliases, Function handler, Function completer) {
        this.manager = manager;
        this.plugin = plugin;
        this.name = name;
        this.description = description;
        this.usage = usage;
        this.aliases = aliases;
        this.handler = handler;
        this.completer = completer;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<String> aliases() {
        return aliases;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String usage() {
        return usage;
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        manager.callCommand(plugin, handler, sender, args);
    }

    @Override
    public List<String> complete(Server server, CommandSender sender, String[] args) {
        if (completer == null || args.length == 0) {
            return List.of();
        }
        // The script returns candidates; narrow them to the partial (the last token) so a script can
        // just return its whole list, the way the built-in arg types complete.
        List<String> candidates = manager.callComplete(plugin, completer, sender, args);
        return ArgType.matching(args[args.length - 1], candidates);
    }
}
