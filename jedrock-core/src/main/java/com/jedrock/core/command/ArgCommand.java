package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;
import com.jedrock.utils.text.ChatText;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Command} that declares its arguments and lets the core parse them. A subclass gives an
 * {@link #arguments()} list and implements {@link #run}; the tokens are validated against the types once,
 * with uniform error messages, before the body ever sees them — so a command stops hand-rolling
 * {@code Integer.parseInt} and "player not found" checks, and gets tab-completion from the same
 * declaration for free.
 *
 * <p>Rules the parser enforces before {@link #run}:
 * <ul>
 *   <li>a required argument that is missing → a usage error, and the body doesn't run;</li>
 *   <li>a token that its type rejects → the type's message plus the usage;</li>
 *   <li>extra tokens past the last (non-greedy) argument → a usage error;</li>
 *   <li>a {@linkplain ArgType#greedy greedy} final argument swallows all remaining tokens as one value.</li>
 * </ul>
 *
 * <p>{@link #usage()} defaults to a line built from the declared arguments, so a subclass need not repeat
 * it; override to say something more specific.
 */
public abstract class ArgCommand implements Command {

    /** The parsed arguments handed to {@link #run}. Must be stable across calls (it drives completion too). */
    @Override
    public abstract List<CommandArg> arguments();

    /** Run the command with its arguments already parsed and validated. */
    protected abstract void run(JedrockServer server, CommandSender sender, CommandContext context);

    @Override
    public String usage() {
        StringBuilder sb = new StringBuilder("/").append(name());
        for (CommandArg arg : arguments()) {
            sb.append(' ').append(arg.usageToken());
        }
        return sb.toString();
    }

    @Override
    public final void execute(JedrockServer server, CommandSender sender, String[] args) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<CommandArg> declared = arguments();
        int token = 0;
        for (int i = 0; i < declared.size(); i++) {
            CommandArg arg = declared.get(i);
            boolean present = token < args.length;
            if (!present) {
                if (arg.required()) {
                    sender.sendMessage("{red}Missing argument {white}<" + ChatText.escape(arg.name())
                            + ">{red}. Usage: {white}" + ChatText.escape(usage()));
                    return;
                }
                continue; // an absent optional — leave it out of the context
            }
            String raw = arg.type().greedy() ? joinFrom(args, token) : args[token];
            Object value;
            try {
                value = arg.type().parse(server, sender, raw);
            } catch (ArgParseException e) {
                sender.sendMessage("{red}" + ChatText.escape(e.getMessage())
                        + "{red}. Usage: {white}" + ChatText.escape(usage()));
                return;
            }
            values.put(arg.name(), value);
            token = arg.type().greedy() ? args.length : token + 1;
        }
        if (token < args.length) {
            sender.sendMessage("{red}Too many arguments. Usage: {white}" + ChatText.escape(usage()));
            return;
        }
        run(server, sender, new CommandContext(values));
    }

    /** Join {@code args[from..]} back into one whitespace-separated string for a greedy argument. */
    private static String joinFrom(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
