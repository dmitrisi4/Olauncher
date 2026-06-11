package app.olauncher.data.chat

/**
 * How a provider's HTTP API is shaped. Most popular providers speak the
 * OpenAI-compatible Chat Completions protocol; Gemini and Anthropic each have
 * their own request/response format.
 */
enum class ProviderApi { OPENAI_COMPAT, GEMINI, ANTHROPIC }

data class AiProvider(
    val id: String,
    val displayName: String,
    val api: ProviderApi,
    val baseUrl: String,
    val defaultModels: List<String>,
    val keysUrl: String
)

object AiProviders {

    val ALL: List<AiProvider> = listOf(
        AiProvider(
            "gemini", "Google Gemini", ProviderApi.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta",
            listOf("gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-flash", "gemini-1.5-pro"),
            "https://aistudio.google.com/app/apikey"
        ),
        AiProvider(
            "openrouter", "OpenRouter", ProviderApi.OPENAI_COMPAT,
            "https://openrouter.ai/api/v1",
            listOf(
                "openai/gpt-4o-mini",
                "anthropic/claude-3.5-sonnet",
                "google/gemini-2.0-flash-exp:free",
                "meta-llama/llama-3.3-70b-instruct",
                "deepseek/deepseek-chat"
            ),
            "https://openrouter.ai/keys"
        ),
        AiProvider(
            "openai", "OpenAI", ProviderApi.OPENAI_COMPAT,
            "https://api.openai.com/v1",
            listOf("gpt-4o-mini", "gpt-4o", "o4-mini", "gpt-4.1-mini"),
            "https://platform.openai.com/api-keys"
        ),
        AiProvider(
            "anthropic", "Anthropic Claude", ProviderApi.ANTHROPIC,
            "https://api.anthropic.com/v1",
            listOf("claude-3-5-haiku-latest", "claude-3-5-sonnet-latest", "claude-3-7-sonnet-latest"),
            "https://console.anthropic.com/settings/keys"
        ),
        AiProvider(
            "groq", "Groq", ProviderApi.OPENAI_COMPAT,
            "https://api.groq.com/openai/v1",
            listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen-2.5-32b"),
            "https://console.groq.com/keys"
        ),
        AiProvider(
            "deepseek", "DeepSeek", ProviderApi.OPENAI_COMPAT,
            "https://api.deepseek.com/v1",
            listOf("deepseek-chat", "deepseek-reasoner"),
            "https://platform.deepseek.com/api_keys"
        ),
        AiProvider(
            "mistral", "Mistral", ProviderApi.OPENAI_COMPAT,
            "https://api.mistral.ai/v1",
            listOf("mistral-small-latest", "mistral-large-latest", "open-mistral-nemo"),
            "https://console.mistral.ai/api-keys"
        ),
        AiProvider(
            "xai", "xAI Grok", ProviderApi.OPENAI_COMPAT,
            "https://api.x.ai/v1",
            listOf("grok-2-latest", "grok-beta"),
            "https://console.x.ai"
        ),
        AiProvider(
            "together", "Together AI", ProviderApi.OPENAI_COMPAT,
            "https://api.together.xyz/v1",
            listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "deepseek-ai/DeepSeek-V3"),
            "https://api.together.xyz/settings/api-keys"
        )
    )

    val DEFAULT: AiProvider get() = ALL.first()

    fun byId(id: String?): AiProvider = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
