package com.jedrock.core.command;

/**
 * One declared argument of a command: a name (for the usage line and {@link CommandContext} lookups), its
 * {@link ArgType}, and whether it is required. A command's argument list is what the core parses and
 * completes against.
 *
 * @param name     identifier used in usage and to fetch the parsed value; unique within a command
 * @param type     how the token is parsed and completed
 * @param required {@code true} if the command can't run without it; an optional arg may simply be absent
 */
public record CommandArg(String name, ArgType<?> type, boolean required) {

    /** A required argument. */
    public static CommandArg required(String name, ArgType<?> type) {
        return new CommandArg(name, type, true);
    }

    /** An optional argument — absent is fine; {@link CommandContext#has} tells the body whether it was given. */
    public static CommandArg optional(String name, ArgType<?> type) {
        return new CommandArg(name, type, false);
    }

    /** How this argument reads in a usage line: {@code <name>} when required, {@code [name]} when optional. */
    public String usageToken() {
        return required ? "<" + name + ">" : "[" + name + "]";
    }
}
