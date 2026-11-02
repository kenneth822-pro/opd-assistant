package np.opd.assistant.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import np.opd.assistant.MainActivity
import np.opd.assistant.OpdApplication
import np.opd.assistant.data.WardRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale

/**
 * Zero-dependency local PC bridge.
 *
 * Serves a small HTML dashboard on the phone's Wi-Fi IP at port 8080 using a plain
 * [ServerSocket] loop (no web framework). A hospital desktop on the same network opens the page,
 * sees the ward census, picks a patient, and copies PC-ready text (discharge summaries, operation
 * note + cardex, SBAR handover) straight into the desktop clipboard. Access is gated by a 4-digit
 * PIN generated per session.
 *
 * Security notes: this serves **cleartext HTTP on the LAN**, which is deliberate (the hospital
 * desktop is on the same trusted network and there is no TLS cert for a raw socket). It is
 * read-only, PIN-gated, and only runs while the doctor explicitly starts it. Do not expose the
 * port to the internet.
 */
class LocalPcBridgeService : Service() {

    private var serverSocket: ServerSocket? = null
    // Bounded concurrency: at most 8 client sockets handled at once, so a burst of requests cannot
    // exhaust threads. Runs on IO with limited parallelism.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(8))
    private lateinit var repository: WardRepository
    private lateinit var provider: BridgeTextProvider

    private val pin: String = (1000..9999).random().toString()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = WardRepository((application as OpdApplication).database)
        provider = BridgeTextProvider(repository)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (serverSocket == null) startServer()
        return START_NOT_STICKY
    }

    private fun startServer() {
        scope.launch {
            try {
                val server = ServerSocket(PORT)
                serverSocket = server
                _state.value = BridgeState(running = true, ip = wifiIp(), port = PORT, pin = pin)
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (e: Exception) { break }
                    scope.launch { handle(client) }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(running = false, error = "Could not start bridge: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return
                // Drain headers.
                while (true) { val l = reader.readLine(); if (l.isNullOrEmpty()) break }
                val method = requestLine.split(" ").getOrNull(0) ?: "GET"
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val out = socket.getOutputStream()
                if (method == "OPTIONS") {
                    // CORS preflight.
                    writeRaw(out, "text/plain", ByteArray(0))
                    return
                }
                val (uri, query) = path.split("?", limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
                val params = parseQuery(query)
                route(uri, params, out)
            }
        } catch (e: java.net.SocketException) {
            // Broken pipe / client closed the tab: expected, never crash the accept loop.
        } catch (e: Exception) {
            // A broken client must never take the server down.
        }
    }

    private fun route(uri: String, params: Map<String, String>, out: OutputStream) {
        when (uri) {            "/", "/index.html" -> writeHtml(out, DASHBOARD_HTML)
            "/api/patients" -> {
                if (!authorized(params)) { writeJson(out, """{"error":"auth"}"""); return }
                val list = runBlocking { provider.patientList() }
                val json = list.joinToString(",", "[", "]") { (id, label) ->
                    """{"id":${json(id)},"label":${json(label)}}"""
                }
                writeJson(out, json)
            }
            "/api/document" -> {
                if (!authorized(params)) { writeJson(out, """{"error":"auth"}"""); return }
                val pid = params["patient"] ?: ""
                val docKey = params["kind"] ?: ""
                val doc = BridgeDoc.entries.firstOrNull { it.key == docKey }
                if (pid.isBlank() || doc == null) { writeJson(out, """{"error":"bad request"}"""); return }
                val text = runBlocking { provider.document(pid, doc) }
                writeJson(out, """{"text":${json(text)}}""")
            }
            "/api/verify" -> {
                writeJson(out, if (params["pin"] == pin) """{"ok":true}""" else """{"ok":false}""")
            }
            "/favicon.ico" -> writeRaw(out, "image/x-icon", ByteArray(0))
            else -> {
                // /api/patient/{id}/labs and /api/patient/{id}/opnotes
                val m = Regex("^/api/patient/([^/]+)/(labs|opnotes)$").find(uri)
                if (m != null) {
                    if (!authorized(params)) { writeJson(out, """{"error":"auth"}"""); return }
                    val pid = m.groupValues[1]
                    val text = runBlocking {
                        if (m.groupValues[2] == "labs") provider.labTrends(pid) else (provider.latestOpNoteText(pid) ?: "No operative note recorded.")
                    }
                    writeJson(out, """{"text":${json(text)}}""")
                } else {
                    writeRaw(out, "text/plain", "Not found".toByteArray())
                }
            }
        }
    }

    private fun authorized(params: Map<String, String>) = params["pin"] == pin

    /* ------------------------------- HTTP helpers ------------------------------- */

    private fun writeJson(out: OutputStream, body: String) = writeRaw(out, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))
    private fun writeHtml(out: OutputStream, body: String) = writeRaw(out, "text/html; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun writeRaw(out: OutputStream, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type, Authorization, X-Bridge-Pin\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun parseQuery(q: String): Map<String, String> =
        if (q.isBlank()) emptyMap() else q.split("&").mapNotNull {
            val i = it.indexOf('='); if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()

    private fun json(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append("\"").toString()
    }

    /** Best-effort Wi-Fi IPv4 address. */
    private fun wifiIp(): String = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && it.name.lowercase().let { n -> n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("en") } }
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "phone-ip"
    } catch (e: Exception) {
        "phone-ip"
    }

    /* ------------------------------- notification ------------------------------- */

    private fun startForegroundCompat() {
        createChannel()
        val open = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        val notif = builder
            .setContentTitle("OPD Assistant PC Bridge Active")
            .setContentText("Open http://${wifiIp()}:$PORT on hospital desktop")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else startForeground(NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "foreground start failed: ${e.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "PC Bridge", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
    }

    override fun onDestroy() {
        try { serverSocket?.close() } catch (e: Exception) { }
        serverSocket = null
        scope.cancel()
        _state.value = BridgeState()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LocalPcBridge"
        private const val CHANNEL_ID = "opd_bridge"
        private const val NOTIFICATION_ID = 1002
        const val PORT = 8080

        private val _state = MutableStateFlow(BridgeState())
        val state: StateFlow<BridgeState> = _state.asStateFlow()

        fun start(context: Context) {
            try {
                val intent = Intent(context, LocalPcBridgeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not start PC bridge: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, LocalPcBridgeService::class.java)) } catch (e: Exception) { }
        }

        val DASHBOARD_HTML = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>OPD Assistant — Hospital Desktop Bridge</title>
<style>
 body{font-family:system-ui,Segoe UI,Roboto,sans-serif;margin:0;background:#f4f6f8;color:#1a2733}
 header{background:#0b5c66;color:#fff;padding:14px 20px;font-size:18px;font-weight:600}
 main{max-width:900px;margin:18px auto;padding:0 16px}
 .card{background:#fff;border-radius:10px;padding:16px;margin-bottom:14px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
 label{display:block;font-size:12px;color:#5b6b78;margin:8px 0 4px}
 select,input,button{font-size:15px;padding:8px;border-radius:8px;border:1px solid #cfd8de;width:100%;box-sizing:border-box}
 button{background:#0b5c66;color:#fff;border:0;cursor:pointer;font-weight:600;margin-top:8px}
 button.ghost{background:#e8eef1;color:#123a5a}
 .row{display:flex;gap:10px;flex-wrap:wrap}
 .row button{width:auto;flex:1;min-width:180px}
 pre{background:#0f1c22;color:#d7e6ea;padding:12px;border-radius:8px;white-space:pre-wrap;max-height:420px;overflow:auto;font-size:12.5px}
 .ok{color:#14532d}.err{color:#8c1d18}
</style></head><body>
<header>OPD Assistant — Hospital Desktop Bridge</header>
<main>
 <div class="card">
  <label>Session PIN (shown on the phone)</label>
  <input id="pin" placeholder="4-digit PIN" inputmode="numeric">
  <button onclick="verify()">Unlock</button>
  <div id="authmsg"></div>
 </div>
 <div class="card">
  <label>Service unit</label>
  <select id="unit"><option value="">All</option><option>Unit 1</option><option>Unit 2</option><option>Neurosurgery</option></select>
  <label>Patient</label>
  <select id="patient"></select>
  <div class="row">
   <button onclick="doc('ip_discharge')">📋 Copy Inpatient Discharge Summary</button>
   <button class="ghost" onclick="doc('er_discharge')">📋 Copy ER Discharge Note</button>
   <button class="ghost" onclick="doc('op_note')">📋 Copy Operation Note &amp; Cardex</button>
   <button class="ghost" onclick="doc('sbar')">📋 Copy Ward SBAR Handover</button>
   <button class="ghost" onclick="doc('lab_trends')">📋 Copy Cumulative Lab Trends</button>
   <button class="ghost" onclick="opnote()">📋 Copy Latest Operation Note</button>
  </div>
 </div>
 <div class="card"><label>Preview</label><pre id="preview">Unlock, choose a patient and a document.</pre>
  <textarea id="fallback" style="display:none;width:100%;height:120px;margin-top:8px" placeholder="If clipboard is blocked, the text appears here to copy manually"></textarea>
  <button class="ghost" onclick="copyPreview()">📋 Copy preview to clipboard</button></div>
</main>
<script>
let PIN="";
function q(id){return document.getElementById(id)}
async function verify(){const pin=q('pin').value.trim();
 const r=await fetch('/api/verify?pin='+encodeURIComponent(pin));const j=await r.json();
 if(j.ok){PIN=pin;q('authmsg').innerHTML='<span class="ok">Unlocked.</span>';loadPatients()}else{q('authmsg').innerHTML='<span class="err">Wrong PIN.</span>'}}
async function loadPatients(){const r=await fetch('/api/patients?pin='+encodeURIComponent(PIN));const list=await r.json();
 const sel=q('patient');sel.innerHTML='';list.forEach(p=>{const o=document.createElement('option');o.value=p.id;o.textContent=p.label;sel.appendChild(o)})}
async function doc(kind){const pid=q('patient').value;if(!pid){q('preview').textContent='No patient selected.';return}
 q('preview').textContent='Loading…';
 const r=await fetch('/api/document?pin='+encodeURIComponent(PIN)+'&patient='+encodeURIComponent(pid)+'&kind='+encodeURIComponent(kind));
 const j=await r.json();
 if(j.text!==undefined){q('preview').textContent=j.text}
}
function manualCopy(t){const f=q('fallback');f.style.display='block';f.value=t;f.focus();f.select();
 try{document.execCommand('copy')}catch(e){};}
async function opnote(){const pid=q('patient').value;if(!pid){q('preview').textContent='No patient selected.';return}
 q('preview').textContent='Loading…';
 const r=await fetch('/api/patient/'+encodeURIComponent(pid)+'/opnotes?pin='+encodeURIComponent(PIN));
 const j=await r.json();
 if(j.text!==undefined){q('preview').textContent=j.text}
}
async function copyPreview(){const t=q('preview').textContent;
 // Clipboard API requires a secure context (HTTPS/localhost); over a raw LAN IP it is blocked,
 // so fall back to a visible, pre-selected textarea for manual Ctrl+C.
 if(navigator.clipboard&&window.isSecureContext){
   try{await navigator.clipboard.writeText(t);q('preview').textContent='Copied to clipboard.\n\n'+t;return}catch(e){}
 }
 manualCopy(t);
}
</script></body></html>
        """.trimIndent()
    }
}

/** Live state of the bridge, surfaced to the Compose dialog. */
data class BridgeState(
    val running: Boolean = false,
    val ip: String = "",
    val port: Int = 8080,
    val pin: String = "",
    val error: String? = null
)
