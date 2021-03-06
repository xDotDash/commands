package co.aikar.commands;

import co.aikar.commands.apachecommonslang.ApacheCommonsExceptionUtil;
import com.google.common.collect.Maps;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.ChannelType;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JDACommandManager extends CommandManager<
        MessageReceivedEvent,
        JDACommandEvent,
        String,
        MessageFormatter<String>,
        JDACommandExecutionContext,
        JDAConditionContext
        > {

    private final JDA jda;

    private Logger logger;
    private CommandConfig defaultConfig;
    private CommandConfigProvider configProvider;
    private CommandPermissionResolver permissionResolver;
    protected JDACommandCompletions completions;
    protected JDACommandContexts contexts;
    protected JDALocales locales;
    private long botOwner = 0L;

    protected Map<String, JDARootCommand> commands = Maps.newHashMap();

    public JDACommandManager(JDA jda) {
        this(jda, null);
    }

    public JDACommandManager(JDA jda, JDAOptions options) {
        if (options == null) {
            options = new JDAOptions();
        }
        this.jda = jda;
        this.permissionResolver = options.permissionResolver;
        jda.addEventListener(new JDAListener(this));
        this.defaultConfig = options.defaultConfig == null ? new JDACommandConfig() : options.defaultConfig;
        this.configProvider = options.configProvider;
        this.completions = new JDACommandCompletions(this);
        this.logger = Logger.getLogger(this.getClass().getSimpleName());

        getCommandConditions().addCondition("owneronly", context -> {
            if (context.getIssuer().getEvent().getAuthor().getIdLong() != getBotOwnerId()) {
                throw new ConditionFailedException("Only the bot owner can use this command."); // TODO: MessageKey
            }
        });

        getCommandConditions().addCondition("guildonly", context -> {
            if (context.getIssuer().getEvent().getChannelType() != ChannelType.TEXT) {
                throw new ConditionFailedException("This command must be used in guild chat."); // TODO: MessageKey
            }
        });

        getCommandConditions().addCondition("privateonly", context -> {
            if (context.getIssuer().getEvent().getChannelType() != ChannelType.PRIVATE) {
                throw new ConditionFailedException("This command must be used in private chat."); // TODO: MessageKey
            }
        });

        getCommandConditions().addCondition("grouponly", context -> {
            if (context.getIssuer().getEvent().getChannelType() != ChannelType.GROUP) {
                throw new ConditionFailedException("This command must be used in group chat."); // TODO: MessageKey
            }
        });
    }

    void initializeBotOwner() {
        if (botOwner == 0L) {
            if (jda.getAccountType() == AccountType.BOT) {
                botOwner = jda.asBot().getApplicationInfo().complete().getOwner().getIdLong();
            } else {
                botOwner = jda.getSelfUser().getIdLong();
            }
        }
    }

    private long getBotOwnerId() {
        // Just in case initialization on ReadyEvent fails.
        initializeBotOwner();
        return botOwner;
    }


    public static JDAOptions options() {
        return new JDAOptions();
    }

    public JDA getJDA() {
        return jda;
    }

    public Logger getLogger() {
        return logger;
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    public CommandConfig getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(@NotNull CommandConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public CommandConfigProvider getConfigProvider() {
        return configProvider;
    }

    public void setConfigProvider(CommandConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    public CommandPermissionResolver getPermissionResolver() {
        return permissionResolver;
    }

    public void setPermissionResolver(CommandPermissionResolver permissionResolver) {
        this.permissionResolver = permissionResolver;
    }

    @Override
    public CommandContexts<?> getCommandContexts() {
        if (this.contexts == null) {
            this.contexts = new JDACommandContexts(this);
        }
        return this.contexts;
    }

    @Override
    public CommandCompletions<?> getCommandCompletions() {
        return this.completions;
    }

    @Override
    public void registerCommand(BaseCommand command) {
        command.onRegister(this);
        for (Map.Entry<String, RootCommand> entry : command.registeredCommands.entrySet()) {
            String commandName = entry.getKey().toLowerCase();
            JDARootCommand cmd = (JDARootCommand) entry.getValue();
            if (!cmd.isRegistered) {
                cmd.isRegistered = true;
                commands.put(commandName, cmd);
            }
        }
    }

    @Override
    public boolean hasRegisteredCommands() {
        return !this.commands.isEmpty();
    }

    @Override
    public boolean isCommandIssuer(Class<?> type) {
        return JDACommandEvent.class.isAssignableFrom(type);
    }

    @Override
    public JDACommandEvent getCommandIssuer(Object issuer) {
        if (!(issuer instanceof MessageReceivedEvent)) {
            throw new IllegalArgumentException(issuer.getClass().getName() + " is not a Message Received Event.");
        }
        return new JDACommandEvent(this, (MessageReceivedEvent) issuer);
    }

    @Override
    public RootCommand createRootCommand(String cmd) {
        return new JDARootCommand(this, cmd);
    }

    @Override
    public Locales getLocales() {
        if (this.locales == null) {
            this.locales = new JDALocales(this);
            this.locales.loadLanguages();
        }
        return this.locales;
    }

    @Override
    public CommandExecutionContext createCommandContext(RegisteredCommand command, CommandParameter parameter, CommandIssuer sender, List<String> args, int i, Map<String, Object> passedArgs) {
        return new JDACommandExecutionContext(command, parameter, (JDACommandEvent) sender, args, i, passedArgs);
    }

    @Override
    public CommandCompletionContext createCompletionContext(RegisteredCommand command, CommandIssuer sender, String input, String config, String[] args) {
        // Not really going to be used;
        //noinspection unchecked
        return new CommandCompletionContext(command, sender, input, config, args);
    }

    @Override
    public void log(LogLevel level, String message, Throwable throwable) {
        Level logLevel = level == LogLevel.INFO ? Level.INFO : Level.SEVERE;
        logger.log(logLevel, LogLevel.LOG_PREFIX + message);
        if (throwable != null) {
            for (String line : ACFPatterns.NEWLINE.split(ApacheCommonsExceptionUtil.getFullStackTrace(throwable))) {
                logger.log(logLevel, LogLevel.LOG_PREFIX + line);
            }
        }
    }

    void dispatchEvent(MessageReceivedEvent event) {
        Message message = event.getMessage();
        String msg = message.getContentDisplay();

        CommandConfig config = this.defaultConfig;
        if (this.configProvider != null) {
            CommandConfig provided = this.configProvider.provide(event);
            if (provided != null) {
                config = provided;
            }
        }

        String prefixFound = null;
        for (String prefix : config.getCommandPrefixes()) {
            if (msg.startsWith(prefix)) {
                prefixFound = prefix;
                break;
            }
        }
        if (prefixFound == null) {
            return;
        }

        String[] args = ACFPatterns.SPACE.split(msg.substring(prefixFound.length()), -1);
        if (args.length == 0) {
            return;
        }
        String cmd = args[0].toLowerCase();
        JDARootCommand rootCommand = this.commands.get(cmd);
        if (rootCommand == null) {
            return;
        }
        if (args.length > 1) {
            args = Arrays.copyOfRange(args, 1, args.length);
        } else {
            args = new String[0];
        }
        rootCommand.execute(this.getCommandIssuer(event), cmd, args);
    }
}
