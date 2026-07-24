package com.jedrock.core.command;

/**
 * Thrown by an {@link ArgType} when a token can't be turned into its value. The message is written for the
 * sender to read — {@link ArgCommand} catches it, shows it with the command's usage, and never runs the
 * command body, so a command never has to hand-check its own arguments.
 */
public final class ArgParseException extends Exception {

    public ArgParseException(String message) {
        super(message);
    }
}
