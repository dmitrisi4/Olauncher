package app.olauncher.data.chat

data class ChatMessage(
    val sender: String, // "user", "ai", "system"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String,
    val type: String, // "ai" or "ssh"
    val name: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val timestamp: Long = System.currentTimeMillis()
)
