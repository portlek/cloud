//
// MIT License
//
// Copyright (c) 2024 Incendo
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
package cloud.commandframework.sponge7;

import cloud.commandframework.CommandManager;
import cloud.commandframework.SenderMapper;
import cloud.commandframework.SenderMapperHolder;
import cloud.commandframework.exceptions.ArgumentParseException;
import cloud.commandframework.exceptions.CommandExecutionException;
import cloud.commandframework.exceptions.InvalidCommandSenderException;
import cloud.commandframework.exceptions.InvalidSyntaxException;
import cloud.commandframework.exceptions.NoPermissionException;
import cloud.commandframework.exceptions.NoSuchCommandException;
import cloud.commandframework.exceptions.handling.ExceptionContext;
import cloud.commandframework.exceptions.handling.ExceptionHandler;
import cloud.commandframework.execution.ExecutionCoordinator;
import cloud.commandframework.keys.CloudKey;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.util.TextMessageException;

import static java.util.Objects.requireNonNull;

/**
 * A command manager for SpongeAPI 7.
 *
 * @param <C> the command source type
 * @since 1.4.0
 */
@Singleton
public class SpongeCommandManager<C> extends CommandManager<C> implements SenderMapperHolder<CommandSource, C> {

    private static final Text MESSAGE_INTERNAL_ERROR = Text.of(
            TextColors.RED,
            "An internal error occurred while attempting to perform this command."
    );
    private static final Text MESSAGE_NO_PERMS = Text.of(
            TextColors.RED,
            "I'm sorry, but you do not have permission to perform this command. "
                    + "Please contact the server administrators if you believe that this is in error."
    );
    private static final Text MESSAGE_UNKNOWN_COMMAND = Text.of("Unknown command. Type \"/help\" for help.");

    public static final CloudKey<CommandSource> SPONGE_COMMAND_SOURCE_KEY = CloudKey.of(
            "__internal_commandsource__",
            CommandSource.class
    );

    private final PluginContainer owningPlugin;
    private final SenderMapper<CommandSource, C> senderMapper;

    /**
     * Create a new command manager instance.
     *
     * @param container                   The plugin that owns this command manager
     * @param commandExecutionCoordinator Execution coordinator instance. The coordinator is in charge of executing incoming
     *                                    commands. Some considerations must be made when picking a suitable execution coordinator
     *                                    for your platform. For example, an entirely asynchronous coordinator is not suitable
     *                                    when the parsers used in that particular platform are not thread safe. If you have
     *                                    commands that perform blocking operations, however, it might not be a good idea to
     *                                    use a synchronous execution coordinator. In most cases you will want to pick between
     *                                    {@link ExecutionCoordinator#simpleCoordinator()} and
     *                                    {@link ExecutionCoordinator#asyncCoordinator()}
     * @param senderMapper                A function converting from a native {@link CommandSource} to this manager's sender type
     */
    @Inject
    @SuppressWarnings("unchecked")
    public SpongeCommandManager(
            final @NonNull PluginContainer container,
            final @NonNull ExecutionCoordinator<C> commandExecutionCoordinator,
            final @NonNull SenderMapper<CommandSource, C> senderMapper
    ) {
        super(commandExecutionCoordinator, new SpongePluginRegistrationHandler<>());
        this.owningPlugin = requireNonNull(container, "container");
        this.senderMapper = requireNonNull(senderMapper, "senderMapper");
        ((SpongePluginRegistrationHandler<C>) this.commandRegistrationHandler()).initialize(this);
        this.registerDefaultExceptionHandlers();
    }

    @Override
    public final boolean hasPermission(final @NonNull C sender, final @NonNull String permission) {
        return this.senderMapper.reverse(sender).hasPermission(permission);
    }

    final PluginContainer getOwningPlugin() {
        return this.owningPlugin;
    }

    private void registerDefaultExceptionHandlers() {
        this.registerHandler(Throwable.class, (source, throwable) -> {
            source.sendMessage(MESSAGE_INTERNAL_ERROR);
            this.getOwningPlugin().getLogger().error(
                    "An unhandled exception was thrown during command execution",
                    throwable
            );
        });
        this.registerHandler(CommandExecutionException.class, (source, throwable) -> {
            source.sendMessage(MESSAGE_INTERNAL_ERROR);
            this.getOwningPlugin().getLogger().error(
                    "Exception executing command handler",
                    throwable.getCause()
            );
        });
        this.registerHandler(ArgumentParseException.class, (source, throwable) ->
                source.sendMessage(Text.of("Invalid Command Argument: ", this.formatMessage(throwable.getCause())))
        );
        this.registerHandler(NoSuchCommandException.class, (source, throwable) ->
                source.sendMessage(MESSAGE_UNKNOWN_COMMAND)
        );
        this.registerHandler(NoPermissionException.class, (source, throwable) ->
                source.sendMessage(MESSAGE_NO_PERMS)
        );
        this.registerHandler(InvalidCommandSenderException.class, (source, throwable) ->
                source.sendMessage(Text.of(TextColors.RED, throwable.getMessage()))
        );
        this.registerHandler(InvalidSyntaxException.class, (source, throwable) ->
                source.sendMessage(Text.of(
                        TextColors.RED,
                        "Invalid Command Syntax. Correct command syntax is: ",
                        Text.of(TextColors.GRAY, throwable.correctSyntax())
                ))
        );
    }

    private <T extends Throwable> void registerHandler(
            final @NonNull Class<T> exceptionClass,
            final @NonNull SpongeExceptionHandler<C, T> handler
    ) {
        this.exceptionController().registerHandler(exceptionClass, handler);
    }

    private @NonNull Text formatMessage(final @NonNull Throwable exc) {
        if (exc instanceof TextMessageException) {
            final Text response = ((TextMessageException) exc).getText();
            if (response == null) {
                return Text.of(TextColors.GRAY, "null");
            } else if (response.getColor() == TextColors.NONE) {
                return response.toBuilder().color(TextColors.GRAY).build();
            } else {
                return response;
            }
        } else {
            return Text.of(TextColors.GRAY, exc.getMessage());
        }
    }

    @Override
    public final @NonNull SenderMapper<CommandSource, C> senderMapper() {
        return this.senderMapper;
    }


    @FunctionalInterface
    @SuppressWarnings("FunctionalInterfaceMethodChanged")
    private interface SpongeExceptionHandler<C, T extends Throwable> extends ExceptionHandler<C, T> {

        @Override
        default void handle(final @NonNull ExceptionContext<C, T> context) throws Throwable {
            final CommandSource source = context.context().get(SPONGE_COMMAND_SOURCE_KEY);
            this.handle(source, context.exception());
        }

        void handle(@NonNull CommandSource source, @NonNull T throwable);
    }
}
