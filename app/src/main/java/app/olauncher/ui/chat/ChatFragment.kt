package app.olauncher.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.chat.ChatMessage
import app.olauncher.data.chat.ChatRepository
import app.olauncher.data.chat.ChatSession
import app.olauncher.helper.hideKeyboard
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ChatFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var chatRepository: ChatRepository
    private lateinit var adapter: ChatAdapter
    private lateinit var session: ChatSession

    private var sessionId: String? = null
    private var sessionType: String? = null
    private var sessionName: String? = null
    private var requestInFlight = false

    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatInput: EditText
    private lateinit var chatSend: TextView
    private lateinit var chatTitle: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_chat, container, false)
        prefs = Prefs(requireContext())
        chatRepository = ChatRepository(requireContext())

        sessionId = arguments?.getString("session_id")
        sessionType = arguments?.getString("session_type")
        sessionName = arguments?.getString("session_name")

        chatRecyclerView = view.findViewById(R.id.chatRecyclerView)
        chatInput = view.findViewById(R.id.chatInput)
        chatSend = view.findViewById(R.id.chatSend)
        chatTitle = view.findViewById(R.id.chatTitle)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (sessionType == null) {
            findNavController().popBackStack()
            return
        }

        chatTitle.text = sessionName ?: getString(R.string.chat_title)

        if (sessionId != null && !sessionId!!.startsWith("new_")) {
            session = chatRepository.getSession(sessionId!!) ?: createNewSession()
        } else {
            session = createNewSession()
        }

        adapter = ChatAdapter()
        chatRecyclerView.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        chatRecyclerView.adapter = adapter
        adapter.setMessages(session.messages)

        chatSend.setOnClickListener {
            val text = chatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
            }
        }
    }

    private fun createNewSession(): ChatSession {
        return ChatSession(
            id = UUID.randomUUID().toString(),
            type = sessionType!!,
            name = sessionName ?: "New Session"
        )
    }

    private fun sendMessage(text: String) {
        if (requestInFlight) return

        val userMsg = ChatMessage("user", text)
        session.messages.add(userMsg)
        adapter.addMessage(userMsg)
        chatRecyclerView.scrollToPosition(adapter.itemCount - 1)
        chatInput.setText("")
        chatInput.hideKeyboard()

        // Auto-rename on first message
        if (session.messages.size == 1 && session.name.startsWith("New Session")) {
            val preview = if (text.length > 20) text.substring(0, 20) + "..." else text
            session = session.copy(name = preview)
            chatTitle.text = preview
        }

        chatRepository.saveSession(session)

        when (sessionType) {
            Constants.Chat.TYPE_AI -> sendAiRequest()
            Constants.Chat.TYPE_SSH -> sendSshRequest(text)
        }
    }

    private fun setSending(sending: Boolean) {
        requestInFlight = sending
        chatSend.isEnabled = !sending
        chatSend.text = getString(if (sending) R.string.chat_sending else R.string.chat_send)
    }

    private fun sendAiRequest() {
        val apiKey = prefs.geminiApiKey
        if (apiKey.isEmpty()) {
            addSystemMessage("Error: Gemini API Key is missing. Please configure it in settings.")
            return
        }

        setSending(true)
        lifecycleScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(Constants.Chat.GEMINI_ENDPOINT)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-goog-api-key", apiKey)
                    doOutput = true
                }

                val body = JSONObject().put("contents", buildAiContext()).toString()
                connection.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseStr = InputStreamReader(connection.inputStream, Charsets.UTF_8).use { it.readText() }
                    val text = JSONObject(responseStr)
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    withContext(Dispatchers.Main) { addAiMessage(text) }
                } else {
                    val errorBody = connection.errorStream?.let {
                        InputStreamReader(it, Charsets.UTF_8).use { reader -> reader.readText() }
                    }
                    val message = parseApiError(errorBody) ?: "HTTP $responseCode"
                    withContext(Dispatchers.Main) { addSystemMessage("Error: $message") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addSystemMessage("Error: ${e.message}") }
            } finally {
                connection?.disconnect()
                withContext(Dispatchers.Main) { setSending(false) }
            }
        }
    }

    /**
     * Builds Gemini contents with alternating user/model roles. Consecutive
     * messages from the same role are merged (rather than dropped) so the latest
     * user prompt is always included, and any leading model turns are trimmed
     * because Gemini requires the conversation to start with a user turn.
     */
    private fun buildAiContext(): JSONArray {
        val raw = session.messages
            .filter { it.sender != "system" }
            .takeLast(Constants.Chat.HISTORY_CONTEXT_LIMIT)

        val merged = mutableListOf<Pair<String, String>>()
        for (msg in raw) {
            val role = if (msg.sender == "user") "user" else "model"
            val last = merged.lastOrNull()
            if (last != null && last.first == role) {
                merged[merged.size - 1] = role to (last.second + "\n" + msg.text)
            } else {
                merged.add(role to msg.text)
            }
        }
        while (merged.isNotEmpty() && merged.first().first == "model") {
            merged.removeAt(0)
        }

        val contents = JSONArray()
        for ((role, text) in merged) {
            val parts = JSONArray().put(JSONObject().put("text", text))
            contents.put(JSONObject().put("role", role).put("parts", parts))
        }
        return contents
    }

    private fun parseApiError(body: String?): String? {
        if (body.isNullOrEmpty()) return null
        return try {
            JSONObject(body).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            null
        }
    }

    private fun sendSshRequest(command: String) {
        val host = prefs.sshHost
        val port = prefs.sshPort.toIntOrNull() ?: Constants.Chat.SSH_DEFAULT_PORT
        val user = prefs.sshUser
        val key = prefs.sshKey
        val password = prefs.sshPassword

        if (host.isEmpty() || user.isEmpty()) {
            addSystemMessage("Error: SSH Host or User is missing. Please configure in settings.")
            return
        }
        if (key.isEmpty() && password.isEmpty()) {
            addSystemMessage("Error: SSH key or password is missing. Please configure in settings.")
            return
        }

        setSending(true)
        lifecycleScope.launch(Dispatchers.IO) {
            var sshSession: com.jcraft.jsch.Session? = null
            var channel: ChannelExec? = null
            try {
                val jsch = JSch()
                if (key.isNotEmpty()) {
                    jsch.addIdentity("user_key", key.toByteArray(), null, null)
                }

                sshSession = jsch.getSession(user, host, port)
                if (password.isNotEmpty()) sshSession.setPassword(password)
                sshSession.setConfig("StrictHostKeyChecking", "no")
                sshSession.connect(Constants.Chat.SSH_TIMEOUT_MS)

                channel = sshSession.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                channel.inputStream = null

                val errStream = ByteArrayOutputStream()
                channel.setErrStream(errStream)
                val inStream = channel.inputStream

                channel.connect(Constants.Chat.SSH_TIMEOUT_MS)

                // Drain stdout while the command runs; stderr is pumped into errStream
                // by JSch so reading only stdout here cannot deadlock.
                val output = StringBuilder()
                val buffer = ByteArray(1024)
                while (true) {
                    while (inStream.available() > 0) {
                        val read = inStream.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        output.append(String(buffer, 0, read, Charsets.UTF_8))
                    }
                    if (channel.isClosed) {
                        if (inStream.available() > 0) continue
                        break
                    }
                    Thread.sleep(100)
                }

                val errText = errStream.toString("UTF-8")
                if (errText.isNotEmpty()) output.append(errText)
                val exitStatus = channel.exitStatus
                if (exitStatus > 0) output.append("\n[exit code $exitStatus]")

                val result = output.toString().trim().ifEmpty { "[no output]" }
                withContext(Dispatchers.Main) { addSystemMessage(result) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addSystemMessage("SSH Error: ${e.message}") }
            } finally {
                channel?.disconnect()
                sshSession?.disconnect()
                withContext(Dispatchers.Main) { setSending(false) }
            }
        }
    }

    private fun addAiMessage(text: String) {
        val msg = ChatMessage("ai", text)
        session.messages.add(msg)
        adapter.addMessage(msg)
        chatRecyclerView.scrollToPosition(adapter.itemCount - 1)
        chatRepository.saveSession(session)
    }

    private fun addSystemMessage(text: String) {
        val msg = ChatMessage("system", text)
        session.messages.add(msg)
        adapter.addMessage(msg)
        chatRecyclerView.scrollToPosition(adapter.itemCount - 1)
        chatRepository.saveSession(session)
    }
}
