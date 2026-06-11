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
import app.olauncher.data.chat.AiClient
import app.olauncher.data.chat.AiProviders
import app.olauncher.data.chat.ChatMessage
import app.olauncher.data.chat.ChatRepository
import app.olauncher.data.chat.ChatSession
import app.olauncher.helper.hideKeyboard
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
    private var inFlightJob: Job? = null

    // Persistent SSH shell for this chat session: a single bash process keeps its
    // state (cwd, env, exports) alive across commands.
    private var sshSession: com.jcraft.jsch.Session? = null
    private var sshChannel: ChannelShell? = null
    private var sshWriter: OutputStream? = null
    private var sshInput: InputStream? = null
    private var sshWasConnected = false
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
            if (requestInFlight) {
                cancelInFlight()
                return@setOnClickListener
            }
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
        // While a request is in flight the Send button doubles as a Stop button.
        chatSend.text = getString(if (sending) R.string.chat_stop else R.string.chat_send)
    }

    private fun cancelInFlight() {
        inFlightJob?.cancel()
        closeShell()
        addSystemMessage("[cancelled]")
        setSending(false)
    }

    private fun sendAiRequest() {
        val provider = AiProviders.byId(prefs.aiProviderId)
        val model = prefs.aiModel.ifEmpty { provider.defaultModels.firstOrNull().orEmpty() }
        val apiKey = prefs.aiApiKey(provider.id)

        if (apiKey.isEmpty()) {
            addSystemMessage("Error: ${provider.displayName} API key is missing. Please configure it in settings.")
            return
        }
        if (model.isEmpty()) {
            addSystemMessage("Error: No model selected. Please pick one in settings.")
            return
        }

        setSending(true)
        inFlightJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val history = session.messages
                    .filter { it.sender != "system" }
                    .takeLast(Constants.Chat.HISTORY_CONTEXT_LIMIT)
                val reply = AiClient.complete(provider, model, apiKey, history)
                withContext(Dispatchers.Main) { addAiMessage(reply) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { addSystemMessage("Error: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) { setSending(false) }
            }
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
        inFlightJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Note a reconnect (state lost) only if we had a live shell before.
                val reconnected = sshWasConnected && !isShellConnected()
                ensureShellConnected(host, port, user, key, password)
                val writer = sshWriter ?: throw IOException("SSH shell is not connected")
                val input = sshInput ?: throw IOException("SSH shell is not connected")

                // Send the command followed by a unique end-marker that also carries
                // the exit code; output is everything up to that marker.
                val channel = sshChannel ?: throw IOException("SSH shell is not connected")
                val marker = "__OLR_END_${sshCommandCounter.incrementAndGet()}__"
                writer.write((command + "\n").toByteArray(Charsets.UTF_8))
                writer.write(("echo $marker\$?\n").toByteArray(Charsets.UTF_8))
                writer.flush()

                val result = readCommandOutput(input, channel, marker)
                val text = buildString {
                    if (reconnected) append("[reconnected — cwd/env reset]\n")
                    append(result.output.trim().ifEmpty { "[no output]" })
                    if (result.truncated) append("\n[output truncated]")
                    if (result.exitStatus != 0) append("\n[exit code ${result.exitStatus}]")
                }
                withContext(Dispatchers.Main) { addSystemMessage(text) }
            } catch (e: Exception) {
                closeShell()
                withContext(Dispatchers.Main) { addSystemMessage("SSH Error: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) { setSending(false) }
            }
        }
    }

    private data class CommandResult(val output: String, val exitStatus: Int, val truncated: Boolean)

    /**
     * Reads raw bytes from the shell until the [marker] is seen, then parses the
     * exit code that follows it. Uses non-blocking polling so a stuck or
     * never-terminating command hits [Constants.Chat.SSH_COMMAND_TIMEOUT_MS]
     * instead of hanging forever. Output is capped at
     * [Constants.Chat.SSH_MAX_OUTPUT_BYTES] to protect against runaway commands.
     */
    private fun readCommandOutput(input: InputStream, channel: ChannelShell, marker: String): CommandResult {
        val markerBytes = marker.toByteArray(Charsets.US_ASCII)
        val lps = computeLps(markerBytes)
        val output = ByteArrayOutputStream()
        // Hold back the last markerBytes.size bytes so the marker itself never
        // reaches the output buffer.
        val pending = ArrayDeque<Byte>(markerBytes.size + 1)
        var matched = 0
        var truncated = false
        val deadline = System.currentTimeMillis() + Constants.Chat.SSH_COMMAND_TIMEOUT_MS

        while (true) {
            val b = readByte(input, channel, deadline)
            when (b) {
                READ_TIMEOUT -> throw IOException("command timed out after ${Constants.Chat.SSH_COMMAND_TIMEOUT_MS / 1000}s")
                READ_CLOSED -> throw IOException("Connection closed")
            }
            val byte = b.toByte()

            while (matched > 0 && byte != markerBytes[matched]) matched = lps[matched - 1]
            if (byte == markerBytes[matched]) matched++

            pending.addLast(byte)
            if (matched == markerBytes.size) {
                repeat(markerBytes.size) { pending.removeLast() }
                while (pending.isNotEmpty()) appendCapped(output, pending.removeFirst()) { truncated = true }
                break
            }
            while (pending.size > markerBytes.size) {
                appendCapped(output, pending.removeFirst()) { truncated = true }
            }
        }

        val exitStatus = readExitCode(input, channel, deadline)
        return CommandResult(output.toString("UTF-8"), exitStatus, truncated)
    }

    /** Knuth-Morris-Pratt longest-proper-prefix-suffix table for marker matching. */
    private fun computeLps(pattern: ByteArray): IntArray {
        val lps = IntArray(pattern.size)
        var len = 0
        var i = 1
        while (i < pattern.size) {
            if (pattern[i] == pattern[len]) {
                len++
                lps[i] = len
                i++
            } else if (len != 0) {
                len = lps[len - 1]
            } else {
                lps[i] = 0
                i++
            }
        }
        return lps
    }

    private inline fun appendCapped(output: ByteArrayOutputStream, byte: Byte, onTruncated: () -> Unit) {
        if (output.size() < Constants.Chat.SSH_MAX_OUTPUT_BYTES) {
            output.write(byte.toInt())
        } else {
            onTruncated()
        }
    }

    private fun readExitCode(input: InputStream, channel: ChannelShell, deadline: Long): Int {
        val sb = StringBuilder()
        while (true) {
            val b = readByte(input, channel, deadline)
            if (b == READ_TIMEOUT || b == READ_CLOSED) break
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString().trim().toIntOrNull() ?: 0
    }

    /** Returns the next byte, or [READ_TIMEOUT]/[READ_CLOSED] sentinels. */
    private fun readByte(input: InputStream, channel: ChannelShell, deadline: Long): Int {
        while (true) {
            if (input.available() > 0) return input.read()
            if (!channel.isConnected) return READ_CLOSED
            if (System.currentTimeMillis() > deadline) return READ_TIMEOUT
            Thread.sleep(20)
        }
    }

    private fun isShellConnected(): Boolean =
        sshChannel?.isConnected == true && sshSession?.isConnected == true

    /**
     * Opens (once) a persistent shell channel so command state survives between
     * messages. The PTY is disabled to avoid input echo and prompts, the shell
     * is forced to /bin/sh for predictable behaviour, stderr is merged into
     * stdout, and any login banner is drained up to a ready marker.
     */
    private fun ensureShellConnected(host: String, port: Int, user: String, key: String, password: String) {
        if (isShellConnected()) return
        closeShell()

        val jsch = JSch()
        if (key.isNotEmpty()) {
            val passphrase = if (password.isNotEmpty()) password.toByteArray(Charsets.UTF_8) else null
            jsch.addIdentity("user_key", key.toByteArray(), null, passphrase)
        }

        val session = jsch.getSession(user, host, port)
        if (key.isEmpty() && password.isNotEmpty()) session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(Constants.Chat.SSH_TIMEOUT_MS)

        val channel = session.openChannel("shell") as ChannelShell
        channel.setPty(false)
        val writer = channel.outputStream
        val input = channel.inputStream
        channel.connect(Constants.Chat.SSH_TIMEOUT_MS)

        writer.write("exec /bin/sh\n".toByteArray(Charsets.UTF_8))
        writer.write("exec 2>&1\n".toByteArray(Charsets.UTF_8))
        writer.write("echo __OLR_READY__\n".toByteArray(Charsets.UTF_8))
        writer.flush()
        val handshakeDeadline = System.currentTimeMillis() + Constants.Chat.SSH_TIMEOUT_MS
        readUntil(input, channel, "__OLR_READY__", handshakeDeadline)

        sshSession = session
        sshChannel = channel
        sshWriter = writer
        sshInput = input
        sshWasConnected = true
    }

    private fun readUntil(input: InputStream, channel: ChannelShell, marker: String, deadline: Long) {
        val markerBytes = marker.toByteArray(Charsets.US_ASCII)
        val lps = computeLps(markerBytes)
        var matched = 0
        while (matched < markerBytes.size) {
            val b = readByte(input, channel, deadline)
            if (b == READ_TIMEOUT) throw IOException("timed out during handshake")
            if (b == READ_CLOSED) throw IOException("Connection closed during handshake")
            val byte = b.toByte()
            while (matched > 0 && byte != markerBytes[matched]) matched = lps[matched - 1]
            if (byte == markerBytes[matched]) matched++
        }
    }

    private fun closeShell() {
        val channel = sshChannel
        val session = sshSession
        sshChannel = null
        sshSession = null
        sshWriter = null
        sshInput = null
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

    companion object {
        private const val READ_TIMEOUT = -2
        private const val READ_CLOSED = -1
    }
}
