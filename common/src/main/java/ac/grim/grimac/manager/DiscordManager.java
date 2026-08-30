package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.manager.init.ReloadableInitable;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.common.arguments.CommonGrimArguments;
import ac.grim.grimac.utils.data.webhook.discord.CompiledDiscordTemplate;
import ac.grim.grimac.utils.data.webhook.discord.Embed;
import ac.grim.grimac.utils.data.webhook.discord.EmbedField;
import ac.grim.grimac.utils.data.webhook.discord.EmbedFooter;
import ac.grim.grimac.utils.data.webhook.discord.WebhookMessage;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class DiscordManager implements StartableInitable, ReloadableInitable {
    private static final Predicate<String> WEBHOOK_REGEX = Pattern.compile("^https://(?:canary\\.)?discord\\.com/api(?:/v\\d+)?/webhooks/\\d+/[\\w-]+(\\?thread_id=\\d+)?$").asMatchPredicate();
    private static final Predicate<String> HTTPS_URL_REGEX = Pattern.compile("^https://[^/\\s]+/\\S+$").asMatchPredicate();
    private static final Duration defaultTimeout = Duration.ofMillis(CommonGrimArguments.URL_TIMEOUT.value());
    private static volatile HttpClient client = HttpClient.newBuilder().connectTimeout(defaultTimeout).build();
    private static final ConcurrentLinkedDeque<PendingRequest> requests = new ConcurrentLinkedDeque<>();
    private static final AtomicBoolean taskStarted = new AtomicBoolean();
    private static final AtomicBoolean sending = new AtomicBoolean();
    private static long rateLimitedUntil;
    private URI url;
    private Duration requestTimeout = defaultTimeout;
    private int maxRetries = 2;
    private int embedColor;
    private CompiledDiscordTemplate compiledContent;
    private char backtickReplacement = '\u02CB';
    private String embedTitle = "";
    private boolean includeTimestamp;
    private boolean includeVerbose;
    private @Nullable String embedImageUrl;
    private @Nullable String embedThumbnailUrl;
    private @Nullable String embedFooterUrl;
    private String embedFooterText = "";
    private String webhookUsername = "MacCaughtYouCheating";
    private @Nullable String webhookAvatarUrl;

    private static final Pattern URL_PATTERN = Pattern.compile("^https?://(?:www\\.)?[-a-z0-9@:%._+~#=]{1,256}\\.[a-z0-9()]{1,6}\\b[-a-z0-9()@:%_+.~#?&/=]*$", Pattern.CASE_INSENSITIVE);

    private static String validatedConfigURL(String configPath, String defaultURL) {
        String url = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse(configPath, defaultURL);
        if (url == null || url.isBlank()) return null;
        if (URL_PATTERN.matcher(url).matches()) {
            return url;
        } else {
            LogUtil.warn("Invalid URL configured at Discord config path '" + configPath + "'");
            return defaultURL;
        }
    }

    @Override
    public void start() {
        reload();
    }

    @Override
    public void reload() {
        try {
            // Yes all of these fields should technically be volatile so they will be updated correctly on reload for HTTP threads to read
            // No we're not going to pay for atomic reads in the hot loop however cheap for a one in a billion chance to read an outdated config
            // When your discord webhook settings are changed (who changes them in prod?) that can be fixed with a restart
            ConfigManager config = GrimAPI.INSTANCE.getConfigManager().getConfig();
            if (!config.getBooleanElse("enabled", false)) {
                url = null;
                return;
            }

            String webhook = config.getStringElse("webhook", "");
            boolean strictValidation = !config.getBooleanElse("disable-webhook-validation", false);
            int connectTimeoutMillis = Math.max(1000, config.getIntElse("connect-timeout-ms", (int) defaultTimeout.toMillis()));
            int requestTimeoutMillis = Math.max(1000, config.getIntElse("request-timeout-ms", (int) defaultTimeout.toMillis()));
            requestTimeout = Duration.ofMillis(requestTimeoutMillis);
            maxRetries = Math.max(0, Math.min(5, config.getIntElse("max-retries", 2)));
            client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMillis)).build();

            if (webhook.isEmpty()) {
                url = null;
            } else if (strictValidation) {
                if (!WEBHOOK_REGEX.test(webhook)) {
                    LogUtil.error("Discord webhook URL does not match the expected"
                            + " https://discord.com/api/webhooks/<id>/<token> format.");
                    LogUtil.error("If you are using a proxy or custom endpoint,"
                            + " set 'disable-webhook-validation: true' in the Discord config.");
                    url = null;
                } else {
                    url = new URI(webhook);
                }
            } else {
                if (!HTTPS_URL_REGEX.test(webhook)) {
                    LogUtil.error("Discord webhook endpoint is not a valid HTTPS URL.");
                    url = null;
                } else {
                    LogUtil.info("Discord webhook validation is disabled; using a custom HTTPS endpoint.");
                    url = new URI(webhook);
                }
            }
            // not adding these to the config since they may change in the future
            // mainly for just for allowing more customization
            embedImageUrl = validatedConfigURL("embed-image-url", null);
            embedThumbnailUrl = validatedConfigURL("embed-thumbnail-url", "https://crafthead.net/helm/%uuid%");
            embedFooterUrl = validatedConfigURL("embed-footer-url", null);
            embedFooterText = config.getStringElse("embed-footer-text", "v%maccaughtyoucheating_version%");
            embedTitle = config.getStringElse("embed-title", "**MacCaughtYouCheating Alert**");
            webhookUsername = config.getStringElse("username", "MacCaughtYouCheating");
            webhookAvatarUrl = validatedConfigURL("avatar-url", null);

            try {
                embedColor = Color.decode(config.getStringElse("embed-color", "#00FFFF")).getRGB();
            } catch (NumberFormatException e) {
                LogUtil.warn("Discord embed color is invalid");
            }

            StringBuilder sb = new StringBuilder();
            for (String string : config.getStringListElse("violation-content", getDefaultContents())) {
                sb.append(string).append("\n");
            }
            includeTimestamp = config.getBooleanElse("include-timestamp", true);
            includeVerbose = config.getBooleanElse("include-verbose", true);
            String btReplace = config.getStringElse("backtick-replacement-char", "\u02CB");
            backtickReplacement = (btReplace.isEmpty()) ? '\u02CB' : btReplace.charAt(0);
            compiledContent = CompiledDiscordTemplate.compile(sb.toString());
            LogUtil.info("Discord alerts enabled (request timeout: " + requestTimeoutMillis
                    + "ms, retries: " + maxRetries + ").");
        } catch (Exception e) {
            LogUtil.error("Failed to load Discord webhook configuration", e);
        }
    }

    @Contract(value = " -> new", pure = true)
    private @NotNull @Unmodifiable List<@NotNull String> getDefaultContents() {
        return List.of(
                "**Player**: `%player%`",
                "**Check**: %check%",
                "**Violations**: %violations%",
                "**Client Version**: %version%",
                "**Brand**: `%brand%`",
                "**Ping**: %ping%",
                "**TPS**: %tps%"
        );
    }

    public void sendAlert(@NotNull GrimPlayer player, String verbose, String checkName, int violations) {
        if (isDisabled()) {
            return;
        }

        // Per-alert overlay — avoids polluting the global static map
        Map<String, String> statics = new HashMap<>(GrimAPI.INSTANCE.getExternalAPI().getStaticReplacements());
        statics.put("%check%", checkName);
        statics.put("%violations%", Integer.toString(violations));

        Map<String, Function<GrimUser, String>> dynamics = GrimAPI.INSTANCE.getExternalAPI().getVariableReplacements();

        String content = compiledContent.render(player, statics, dynamics, backtickReplacement);

        Embed embed = new Embed(content)
                .color(embedColor)
                .title(embedTitle)
                .imageURL(MessageUtil.replacePlaceholders(player, embedImageUrl, false))
                .thumbnailURL(MessageUtil.replacePlaceholders(player, embedThumbnailUrl, false))
                .footer(new EmbedFooter(
                        MessageUtil.replacePlaceholders(player, embedFooterText, false),
                        MessageUtil.replacePlaceholders(player, embedFooterUrl, false)
                ));

        if (includeTimestamp) embed.timestamp(Instant.now());

        if (!verbose.isEmpty() && includeVerbose) {
            embed.addFields(new EmbedField("Verbose", CompiledDiscordTemplate.escapeMarkdown(verbose), true));
        }

        sendWebhookMessage(new WebhookMessage().addEmbeds(embed));
    }

    public CompletableFuture<Boolean> sendWebhookMessage(WebhookMessage message) {
        if (isDisabled()) return CompletableFuture.completedFuture(false);

        if (!webhookUsername.isBlank() && message.username() == null) message.username(webhookUsername);
        if (webhookAvatarUrl != null && message.avatar() == null) message.avatar(webhookAvatarUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message.toJson().toString()))
                .timeout(requestTimeout)
                .build();

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        requests.add(new PendingRequest(request, future, 0));

        if (!taskStarted.getAndSet(true)) {
            // there's probably a better way to handle rate limits, but this works, so whatever.
            GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(GrimAPI.INSTANCE.getGrimPlugin(), DiscordManager::tick, 0, 1);
        }

        return future;
    }

    public boolean isDisabled() {
        return url == null;
    }

    private static void tick() {
        PendingRequest pending = requests.peek();
        if (pending != null && rateLimitedUntil < System.currentTimeMillis() && !sending.getAndSet(true)) {
            HttpRequest request = pending.request();
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, throwable) -> {
                try {
                    if (throwable != null) {
                        retryOrFail(pending, "Discord webhook request failed", throwable);
                        return;
                    }

                    if (response.statusCode() == 429) {
                        rateLimitedUntil = Math.max(System.currentTimeMillis() + retryAfterMillis(response), rateLimitedUntil);
                        LogUtil.warn("Discord rate limited webhook delivery; retrying after "
                                + Math.max(0, rateLimitedUntil - System.currentTimeMillis()) + "ms.");
                        return;
                    }

                    requests.remove(pending);
                    if (response.statusCode() == 408 || response.statusCode() == 425 || response.statusCode() >= 500) {
                        retryOrFail(pending, "Discord webhook returned retryable HTTP " + response.statusCode(), null);
                    } else if (response.statusCode() >= 400) {
                        LogUtil.error("Discord webhook rejected the request with HTTP " + response.statusCode() + ".");
                        pending.future().complete(false);
                    } else {
                        LogUtil.debug("Discord webhook delivered successfully with HTTP " + response.statusCode() + ".");
                        pending.future().complete(true);
                    }
                } finally {
                    sending.set(false);
                }
            });
        }
    }

    private static long retryAfterMillis(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After")
                .or(() -> response.headers().firstValue("X-RateLimit-Reset-After"))
                .orElse("1");
        try {
            return Math.max(100, (long) (Double.parseDouble(value) * 1000));
        } catch (NumberFormatException ignored) {
            return 1000;
        }
    }

    private static void retryOrFail(PendingRequest pending, String message, @Nullable Throwable throwable) {
        requests.remove(pending);
        if (pending.attempt() < GrimAPI.INSTANCE.getDiscordManager().maxRetries) {
            requests.addLast(new PendingRequest(pending.request(), pending.future(), pending.attempt() + 1));
            LogUtil.warn(message + "; retry " + (pending.attempt() + 1) + " queued.");
        } else {
            if (throwable == null) {
                LogUtil.error(message + "; retry limit reached.");
            } else {
                LogUtil.error(message + "; retry limit reached.", throwable);
            }
            pending.future().complete(false);
        }
    }

    private record PendingRequest(HttpRequest request, CompletableFuture<Boolean> future, int attempt) {}
}
