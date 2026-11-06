package me.kev.sva;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import me.kev.sva.chat.ChatListener;
import me.kev.sva.chat.ConversationManager;
import me.kev.sva.ai.AiGateway;
import me.kev.sva.ai.ProviderThrottleRegistry;
import me.kev.sva.ai.external.AiServiceImpl;
import me.kev.sva.ai.external.ApiSettings;
import me.kev.sva.api.AiException;
import me.kev.sva.api.AiService;
import me.kev.sva.ai.ProviderSettings;
import me.kev.sva.chat.tools.ToolManager;
import me.kev.sva.commands.CommandManager;
import me.kev.sva.config.ConfigurationManager;
import me.kev.sva.constants.Constants;
import me.kev.sva.integrations.IntegrationManager;
import me.kev.sva.memory.ActivityJournal;
import me.kev.sva.memory.RelationshipManager;
import me.kev.sva.utils.MessageSender;

public final class ServerAssistantPlugin extends JavaPlugin {

    private ConversationManager conversationManager;
    private ChatListener chatListener;
    private ToolManager toolManager;
    private IntegrationManager integrationManager;
    private ConfigurationManager configurationManager;
    private ActivityJournal activityJournal;
    private RelationshipManager relationshipManager;
    /** Model access shared by Isolda and the external AI API; recreated on /sva reload. */
    private AiGateway aiGateway;
    /** Public AI API for other plugins. Created once per enable so their references survive reloads. */
    private AiServiceImpl aiService;

    /** Survives /sva reload so provider cooldowns cannot be bypassed by reloading. */
    private final ProviderThrottleRegistry providerThrottleRegistry = new ProviderThrottleRegistry();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configurationManager = new ConfigurationManager(this);
        configurationManager.loadAndUpdate();
        migrateLegacyProviderConfig();

        CommandManager commandManager = new CommandManager(this);
        if (getCommand("sva") != null) {
            getCommand("sva").setExecutor(commandManager);
            getCommand("sva").setTabCompleter(commandManager);
        }

        activityJournal = new ActivityJournal(this);
        relationshipManager = new RelationshipManager(this);
        aiService = new AiServiceImpl(this);
        initializePlugin();
        getServer().getServicesManager().register(AiService.class, aiService, this, ServicePriority.Normal);

        Bukkit.getConsoleSender().sendMessage(Constants.ASCII_LOGO);
        MessageSender.Success("Plugin enabled successfully.");
    }

    /**
     * One-time, non-destructive migration from the old flat provider keys.
     * Unlike 1.3.x, it NEVER rewrites a user's OpenAI selection back to Gemini.
     */
    private void migrateLegacyProviderConfig() {
        if (getConfig().isSet("ai.provider")) {
            return;
        }

        ProviderSettings legacy = ProviderSettings.primary(this);
        getConfig().set("ai.provider", legacy.type());
        getConfig().set("ai.api-key-env", legacy.apiKeyEnv());
        getConfig().set("ai.api-key", legacy.apiKey());
        getConfig().set("ai.base-url", legacy.baseUrl());
        getConfig().set("ai.model", legacy.model());
        if (!getConfig().contains("ai.max-output-tokens")) {
            getConfig().set("ai.max-output-tokens", 160);
        }
        if (!getConfig().contains("ai.temperature")) {
            getConfig().set("ai.temperature", 0.75);
        }
        saveConfig();
        getLogger().info("Migrated legacy V1 AI settings to the provider-neutral ai.* section (OpenAI-compatible).");
    }

    void initializePlugin() {
        if (conversationManager != null) {
            try {
                conversationManager.shutdown();
            } catch (Exception ignored) {
            }
            conversationManager = null;
        }

        if (chatListener != null) {
            try {
                HandlerList.unregisterAll(chatListener);
            } catch (Exception ignored) {
            }
            chatListener = null;
        }

        if (toolManager != null) {
            try {
                toolManager.shutdown();
            } catch (Exception ignored) {
            }
            toolManager = null;
        }

        integrationManager = null;

        if (activityJournal == null) activityJournal = new ActivityJournal(this);
        if (relationshipManager == null) relationshipManager = new RelationshipManager(this);
        else relationshipManager.reloadSettings();

        // Optional read-only integrations are created before tools so the profile
        // context tool can query MMOCore/MDVSocial without hard dependencies.
        integrationManager = new IntegrationManager(this);

        // Tools are created before ConversationManager because request-context
        // construction and the assistant prompt use the local/action tool registry.
        toolManager = new ToolManager(this);

        // The gateway is created before ConversationManager, whose request queue
        // builds Isolda's AssistantManager on top of it.
        if (aiService != null) aiService.cancelAll(AiException.Reason.RELOADING);
        shutdownGateway();
        aiGateway = new AiGateway(this, providerThrottleRegistry);
        if (aiService != null) aiService.swapGateway(aiGateway, ApiSettings.load(getConfig()));
        conversationManager = new ConversationManager(this);
        chatListener = new ChatListener(this, conversationManager);
        getServer().getPluginManager().registerEvents(chatListener, this);
    }

    public ConversationManager getConversationManager() {
        return conversationManager;
    }

    public AiServiceImpl getAiService() {
        return aiService;
    }

    public AiGateway getAiGateway() {
        return aiGateway;
    }

    private void shutdownGateway() {
        if (aiGateway != null) {
            try {
                aiGateway.shutdown();
            } catch (Exception ignored) {
            }
            aiGateway = null;
        }
    }

    public ProviderThrottleRegistry getProviderThrottleRegistry() {
        return providerThrottleRegistry;
    }

    public ToolManager getToolManager() {
        return toolManager;
    }

    public IntegrationManager getIntegrationManager() {
        return integrationManager;
    }

    public org.bukkit.configuration.file.FileConfiguration getPersonalityConfig() {
        return configurationManager.personality();
    }

    public org.bukkit.configuration.file.FileConfiguration getWikiConfig() {
        return configurationManager.wiki();
    }

    public org.bukkit.configuration.file.FileConfiguration getIntegrationsConfig() {
        return configurationManager.integrations();
    }

    public org.bukkit.configuration.file.FileConfiguration getRelationshipsConfig() {
        return configurationManager.relationships();
    }

    public void saveRelationshipsConfig() {
        configurationManager.saveRelationships();
    }

    public ActivityJournal getActivityJournal() {
        return activityJournal;
    }

    public RelationshipManager getRelationshipManager() {
        return relationshipManager;
    }

    public void saveIntegrationsConfig() {
        configurationManager.saveIntegrations();
    }

    /** Reloads config and runtime routing while keeping provider throttle state. */
    public void reloadPlugin() {
        if (configurationManager == null) {
            configurationManager = new ConfigurationManager(this);
        }
        configurationManager.loadAndUpdate();
        migrateLegacyProviderConfig();
        initializePlugin();
    }

    @Override
    public void onDisable() {
        if (aiService != null) {
            try {
                aiService.cancelAll(AiException.Reason.SHUTDOWN);
            } catch (Exception ignored) {
            }
        }
        getServer().getServicesManager().unregisterAll(this);

        if (conversationManager != null) {
            try {
                conversationManager.shutdown();
            } catch (Exception ignored) {
            }
            conversationManager = null;
        }

        shutdownGateway();

        if (chatListener != null) {
            try {
                HandlerList.unregisterAll(chatListener);
            } catch (Exception ignored) {
            }
            chatListener = null;
        }

        if (toolManager != null) {
            try {
                toolManager.shutdown();
            } catch (Exception ignored) {
            }
            toolManager = null;
        }

        integrationManager = null;

        if (relationshipManager != null) {
            try { relationshipManager.shutdown(); } catch (Exception ignored) { }
            relationshipManager = null;
        }
        activityJournal = null;

        MessageSender.Error("Plugin Disabled!");
    }
}
