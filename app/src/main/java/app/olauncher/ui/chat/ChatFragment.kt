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
import app.olauncher.data.Prefs
import app.olauncher.data.chat.ChatMessage
import app.olauncher.data.chat.ChatRepository
import app.olauncher.data.chat.ChatSession
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showToast
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

        chatTitle.text = sessionName ?: "Chat"

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

        if (sessionType == "ai") {
            sendAiRequest(text)
        } else if (sessionType == "ssh") {
            sendSshRequest(text)
        }
    }

    private fun sendAiRequest(prompt: String) {
        val apiKey = prefs.geminiApiKey
        if (apiKey.isEmpty()) {
            addSystemMessage("Error: Gemini API Key is missing. Please configure it in settings.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                // Build simple context from last few messages
                val contents = org.json.JSONArray()

                // Get up to 10 latest messages, excluding system messages
                val history = session.messages.filter { it.sender != "system" }.takeLast(10)

                var lastRole = ""
                for (msg in history) {
                    val role = if (msg.sender == "user") "user" else "model"

                    // Gemini requires alternating roles, starting with user
                    if (role == lastRole) {
                        continue // Skip adjacent messages of the same role for simplicity
                    }
                    lastRole = role

                    val parts = org.json.JSONArray()
                    parts.put(JSONObject().put("text", msg.text))

                    val content = JSONObject()
                    content.put("role", role)
                    content.put("parts", parts)

                    contents.put(content)
                }

                val body = JSONObject().put("contents", contents).toString()

                connection.outputStream.use { os ->
                    val input = body.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = InputStreamReader(connection.inputStream, Charsets.UTF_8)
                    val responseStr = reader.readText()
                    val responseJson = JSONObject(responseStr)

                    val text = responseJson.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    withContext(Dispatchers.Main) {
                        addAiMessage(text)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        addSystemMessage("Error: HTTP $responseCode")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addSystemMessage("Error: ${e.message}")
                }
            }
        }
    }

    private fun sendSshRequest(command: String) {
        val host = prefs.sshHost
        val port = prefs.sshPort.toIntOrNull() ?: 22
        val user = prefs.sshUser
        val key = prefs.sshKey

        if (host.isEmpty() || user.isEmpty()) {
            addSystemMessage("Error: SSH Host or User is missing. Please configure in settings.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsch = JSch()

                if (key.isNotEmpty()) {
                    jsch.addIdentity("user_key", key.toByteArray(), null, null)
                }

                val session = jsch.getSession(user, host, port)
                session.setConfig("StrictHostKeyChecking", "no")

                // Connect with short timeout
                session.connect(5000)

                val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                channel.setCommand(command)
                channel.inputStream = null
                val errStream = channel.getErrStream()
                val inStream = channel.getInputStream()

                channel.connect(5000)

                val reader = InputStreamReader(inStream, Charsets.UTF_8)
                val errReader = InputStreamReader(errStream, Charsets.UTF_8)

                val output = StringBuilder()
                var c: Int
                while (reader.read().also { c = it } != -1) {
                    output.append(c.toChar())
                }
                while (errReader.read().also { c = it } != -1) {
                    output.append(c.toChar())
                }

                channel.disconnect()
                session.disconnect()

                withContext(Dispatchers.Main) {
                    addSystemMessage(output.toString().trim())
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addSystemMessage("SSH Error: ${e.message}")
                }
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
