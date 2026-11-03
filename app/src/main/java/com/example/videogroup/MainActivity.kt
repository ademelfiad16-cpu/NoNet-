package com.example.videogroup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File
import java.util.UUID

/** مجموعة واحدة ثابتة لكل المستخدمين، بدون خروج. الاتصال بالبلوتوث + Wi-Fi Direct تلقائيًا. */
class MainActivity : AppCompatActivity() {
    private val serviceId = "com.example.videogroup"
    private val group = "main"          // المجموعة الوحيدة
    private val pickCode = 11
    private val videoExt = setOf("mp4", "mkv", "3gp", "webm", "mov", "avi")

    private lateinit var client: ConnectionsClient
    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var dir: File

    private val myId = UUID.randomUUID().toString().take(6)
    private val peers = linkedSetOf<String>()
    private val incoming = mutableMapOf<Long, Payload>()
    private val names = mutableMapOf<Long, String>()
    private val finished = mutableSetOf<Long>()
    private var files = listOf<File>()
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = Nearby.getConnectionsClient(this)
        dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        status = TextView(this).apply { text = "جارٍ البحث عن أعضاء المجموعة..."; setPadding(0, 0, 0, 16) }
        val sendBtn = Button(this).apply { text = "إرسال فيديو"; setOnClickListener { pick() } }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, i, _ ->
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java)
                    .putExtra("path", files[i].absolutePath))
            }
        }
        root.addView(status); root.addView(sendBtn); root.addView(list)
        setContentView(root)
        refresh()

        val need = perms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) start() else ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    private fun perms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (r.isNotEmpty() && r.all { it == PackageManager.PERMISSION_GRANTED }) start()
        else setStatus("الصلاحيات مطلوبة لتعمل المجموعة، أعد فتح التطبيق")
    }

    // ---------- الاتصال التلقائي ----------
    private fun start() {
        if (started) return
        started = true
        client.startAdvertising("$group|$myId", serviceId, connCb,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
        startDiscovery()
        setStatus("جارٍ البحث عن أعضاء المجموعة...")
    }

    private fun startDiscovery() {
        client.startDiscovery(serviceId, discCb,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            val parts = info.endpointName.split("|")
            if (parts.size == 2 && parts[0] == group && myId < parts[1]) {
                client.requestConnection("$group|$myId", id, connCb)
            }
        }
        override fun onEndpointLost(id: String) {}
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            if (info.endpointName.startsWith("$group|")) client.acceptConnection(id, payloadCb)
            else client.rejectConnection(id)
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (r.status.isSuccess) {
                peers.add(id); showPeers()
                sendHave(id)
            }
        }
        override fun onDisconnected(id: String) {
            peers.remove(id); showPeers()
            client.stopDiscovery(); startDiscovery()   // إعادة البحث تلقائيًا
        }
    }

    private fun showPeers() = setStatus("المجموعة: متصل بـ ${peers.size} عضو")

    // ---------- مزامنة القادم الجديد ----------
    private fun sendHave(id: String) {
        val list = files.joinToString("\n") { it.name }
        client.sendPayload(id, Payload.fromBytes("H|$list".toByteArray()))
    }

    // ---------- الاستقبال (فيديو فقط) ----------
    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, p: Payload) {
            when (p.type) {
                Payload.Type.BYTES -> {
                    val s = String(p.asBytes() ?: return)
                    if (s.startsWith("H|")) {
                        val remote = s.substring(2).split("\n").toSet()
                        files.filter { it.name !in remote }.forEach { sendTo(listOf(endpointId), it) }
                    } else if (s.startsWith("V|")) {
                        val m = s.split("|", limit = 3)
                        val pid = m.getOrNull(1)?.toLongOrNull()
                        if (pid != null && m.size == 3) { names[pid] = m[2]; tryFinish(pid) }
                    }
                }
                Payload.Type.FILE -> incoming[p.id] = p
                else -> {}
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, u: PayloadTransferUpdate) {
            when (u.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    setStatus("نقل: ${u.bytesTransferred * 100 / maxOf(1L, u.totalBytes)}%")
                PayloadTransferUpdate.Status.SUCCESS -> {
                    if (incoming.containsKey(u.payloadId)) { finished.add(u.payloadId); tryFinish(u.payloadId) }
                    else showPeers()
                }
                PayloadTransferUpdate.Status.FAILURE -> showPeers()
                else -> {}
            }
        }
    }

    private fun tryFinish(id: Long) {
        val p = incoming[id] ?: return
        val n = names[id] ?: return
        if (id !in finished) return
        incoming.remove(id); names.remove(id); finished.remove(id)
        val safe = File(n).name
        val uri = p.asFile()?.asUri()
        Thread {
            val out = File(dir, safe)
            if (uri != null && !out.exists() && safe.substringAfterLast('.', "").lowercase() in videoExt) {
                val tmp = File(dir, "$safe.tmp")
                contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(out)
            }
            try { if (uri != null) contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            runOnUiThread { refresh(); showPeers() }
        }.start()
    }

    // ---------- الإرسال ----------
    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
        }, pickCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        val uri = d?.data ?: return
        if (r != pickCode || c != RESULT_OK) return
        Thread {
            val out = File(dir, "${System.currentTimeMillis()}${(100..999).random()}_${displayName(uri)}")
            val tmp = File(dir, out.name + ".tmp")
            contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(out)
            runOnUiThread {
                refresh()
                if (peers.isEmpty()) toast("سيُرسل تلقائيًا عند اتصال أي عضو")
                else sendTo(peers.toList(), out)
            }
        }.start()
    }

    private fun sendTo(targets: List<String>, f: File) {
        val fp = Payload.fromFile(f)
        client.sendPayload(targets, Payload.fromBytes("V|${fp.id}|${f.name}".toByteArray()))
        client.sendPayload(targets, fp)
    }

    // ---------- أدوات ----------
    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return File(it.getString(idx)).name.replace("|", "_")
            }
        }
        return "video.mp4"
    }

    private fun refresh() {
        files = dir.listFiles()?.filter { it.extension.lowercase() in videoExt }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        adapter.clear()
        adapter.addAll(files.map { it.name.substringAfter('_', it.name) })
        adapter.notifyDataSetChanged()
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

class PlayerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val v = VideoView(this)
        setContentView(v)
        val mc = MediaController(this)
        mc.setAnchorView(v)
        v.setMediaController(mc)
        v.setVideoPath(intent.getStringExtra("path"))
        v.start()
    }
}
