package app.olauncher.data.chat

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ChatRepository(context: Context) {
    private val PREFS_NAME = "olauncher_chat_prefs"
    private val SESSIONS_KEY = "chat_sessions"
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getSessions(type: String): List<ChatSession> {
        val json = prefs.getString(SESSIONS_KEY, null) ?: return emptyList()
        val typeToken = object : TypeToken<List<ChatSession>>() {}.type
        val allSessions: List<ChatSession> = gson.fromJson(json, typeToken)
        return allSessions.filter { it.type == type }.sortedByDescending { it.timestamp }
    }

    fun getSession(id: String): ChatSession? {
        val json = prefs.getString(SESSIONS_KEY, null) ?: return null
        val typeToken = object : TypeToken<List<ChatSession>>() {}.type
        val allSessions: List<ChatSession> = gson.fromJson(json, typeToken)
        return allSessions.find { it.id == id }
    }

    fun saveSession(session: ChatSession) {
        val json = prefs.getString(SESSIONS_KEY, null)
        val typeToken = object : TypeToken<MutableList<ChatSession>>() {}.type
        val allSessions: MutableList<ChatSession> = if (json != null) {
            gson.fromJson(json, typeToken)
        } else {
            mutableListOf()
        }

        // Limit the history to prevent SharedPreferences from growing too large
        val historyLimit = 100
        val messagesToSave = if (session.messages.size > historyLimit) {
            session.messages.takeLast(historyLimit).toMutableList()
        } else {
            session.messages
        }

        val sessionToSave = session.copy(messages = messagesToSave, timestamp = System.currentTimeMillis())

        val existingIndex = allSessions.indexOfFirst { it.id == session.id }
        if (existingIndex != -1) {
            allSessions[existingIndex] = sessionToSave
        } else {
            allSessions.add(sessionToSave)
        }

        // Keep maximum of 50 sessions
        val limitedSessions = allSessions.sortedByDescending { it.timestamp }.take(50).toMutableList()

        // Write asynchronously
        prefs.edit().putString(SESSIONS_KEY, gson.toJson(limitedSessions)).apply()
    }

    fun deleteSession(id: String) {
        val json = prefs.getString(SESSIONS_KEY, null) ?: return
        val typeToken = object : TypeToken<MutableList<ChatSession>>() {}.type
        val allSessions: MutableList<ChatSession> = gson.fromJson(json, typeToken)

        allSessions.removeAll { it.id == id }
        prefs.edit().putString(SESSIONS_KEY, gson.toJson(allSessions)).apply()
    }
}
