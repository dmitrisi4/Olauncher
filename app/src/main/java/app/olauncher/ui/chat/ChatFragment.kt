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
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ChatFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var chatRepository: ChatRepository
    private lateinit var adapter: ChatAdapter
    private lateinit var session: ChatSession

    private var sessionId: String? = null
    private var sessionType: String? = null
    private var sessionName: String? = null
    private var requestInFlight = false

    // Persistent SSH shell for this chat session: a single bash process keeps its
    // state (cwd, env, exports) alive across commands.
    private var sshSession: com.jcraft.jsch.Session? = null
    private var sshChannel: ChannelShell? = null
    private var sshWriter: OutputStream? = null
    private var sshReader: BufferedReader? = null
    private val sshCommandCounter = AtomicInteger(0)

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
            try {
                ensureShellConnected(host, port, user, key, password)
                val writer = sshWriter ?: throw IOException("SSH shell is not connected")
                val reader = sshReader ?: throw IOException("SSH shell is not connected")

                // Send the command followed by a unique end-marker carrying the exit
                // code, then read shell output until that marker appears.
                val marker = "__OLR_END_${sshCommandCounter.incrementAndGet()}__"
                writer.write((command + "\n").toByteArray(Charsets.UTF_8))
                writer.write(("echo $marker\$?\n").toByteArray(Charsets.UTF_8))
                writer.flush()

                val output = StringBuilder()
                var exitStatus = 0
                while (true) {
                    val line = reader.readLine() ?: throw IOException("Connection closed")
                    if (line.startsWith(marker)) {
                        exitStatus = line.substring(marker.length).trim().toIntOrNull() ?: 0
                        break
                    }
                    output.append(line).append('\n')
                }
                if (exitStatus != 0) output.append("[exit code $exitStatus]\n")

                val result = output.toString().trim().ifEmpty { "[no output]" }
                withContext(Dispatchers.Main) { addSystemMessage(result) }
            } catch (e: Exception) {
                closeShell()
                withContext(Dispatchers.Main) { addSystemMessage("SSH Error: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) { setSending(false) }
            }
        }
    }

    /**
     * Opens (once) a persistent shell channel so command state survives between
     * messages. The PTY is disabled to avoid input echo and prompts, stderr is
     * merged into stdout, and any login banner is drained up to a ready marker
     * so later output parsing stays clean.
     */
    private fun ensureShellConnected(host: String, port: Int, user: String, key: String, password: String) {
        if (sshChannel?.isConnected == true && sshSession?.isConnected == true) return
        closeShell()

        val jsch = JSch()
        if (key.isNotEmpty()) jsch.addIdentity("user_key", key.toByteArray(), null, null)

        val session = jsch.getSession(user, host, port)
        if (password.isNotEmpty()) session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(Constants.Chat.SSH_TIMEOUT_MS)

        val channel = session.openChannel("shell") as ChannelShell
        channel.setPty(false)
        val writer = channel.outputStream
        val reader = BufferedReader(InputStreamReader(channel.inputStream, Charsets.UTF_8))
        channel.connect(Constants.Chat.SSH_TIMEOUT_MS)

        writer.write("exec 2>&1\n".toByteArray(Charsets.UTF_8))
        writer.write("echo __OLR_READY__\n".toByteArray(Charsets.UTF_8))
        writer.flush()
        while (true) {
            val line = reader.readLine() ?: throw IOException("Connection closed during handshake")
            if (line.contains("__OLR_READY__")) break
        }

        sshSession = session
        sshChannel = channel
        sshWriter = writer
        sshReader = reader
    }

    private fun closeShell() {
        val channel = sshChannel
        val session = sshSession
        sshChannel = null
        sshSession = null
        sshWriter = null
        sshReader = null
        if (channel != null || session != null) {
            Thread {
                try { channel?.disconnect() } catch (_: Exception) {}
                try { session?.disconnect() } catch (_: Exception) {}
            }.start()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        closeShell()
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
