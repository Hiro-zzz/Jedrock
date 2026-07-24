package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.Server;

import java.util.List;

/**
 * The rule that turns a command's {@link CommandArg} list into suggestions for the token under the cursor.
 * Shared by {@link Command#complete}'s default and anyone else deriving completion from a signature.
 */
final class CommandCompletion {

    private CommandCompletion() {}

    /**
     * Suggestions for the partial last token of {@code tokens}, given a command's declared arguments.
     *
     * <p>The cursor sits on argument {@code tokens.length - 1} (zero-based) — the token being typed. If a
     * declared argument covers that position, its type supplies the suggestions; a trailing
     * {@linkplain ArgType#greedy greedy} argument covers every position from its own onward (you can keep
     * completing a player name deep into a {@code /msg <player> <text...>}). Past the last declared,
     * non-greedy argument there is nothing to suggest.
     */
    static List<String> forArguments(List<CommandArg> arguments, Server server,
                                     CommandSender sender, String[] tokens) {
        if (arguments.isEmpty() || tokens.length == 0) {
            return List.of();
        }
        int cursor = tokens.length - 1;         // the argument index being typed
        String partial = tokens[cursor];
        CommandArg arg;
        if (cursor < arguments.size()) {
            arg = arguments.get(cursor);
        } else {
            // Beyond the declared list — only a trailing greedy argument still applies.
            CommandArg last = arguments.get(arguments.size() - 1);
            if (!last.type().greedy()) {
                return List.of();
            }
            arg = last;
        }
        return arg.type().complete(server, sender, partial);
    }
}
