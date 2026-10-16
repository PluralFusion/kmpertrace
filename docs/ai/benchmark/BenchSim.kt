package benchmark

import dev.goquick.kmpertrace.core.Level
import dev.goquick.kmpertrace.log.KmperTrace
import dev.goquick.kmpertrace.log.Log
import dev.goquick.kmpertrace.log.LogSink
import dev.goquick.kmpertrace.trace.captureTraceSnapshot
import dev.goquick.kmpertrace.trace.traceSpan
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.test.Test

private val OUT = File(System.getenv("BENCH_DIR"), "logs")
private val TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

class Run(name: String) {
    private val dir = File(OUT, name).also { it.mkdirs() }
    private val plainFile = File(dir, "plain.log").also { it.writeText("") }
    private val plusFile = File(dir, "plainplus.log").also { it.writeText("") }
    private val ktFile = File(dir, "kmpertrace.log").also { it.writeText("") }
    private val lock = Any()

    init {
        KmperTrace.configure(
            minLevel = Level.DEBUG, serviceName = "demo-app", renderGlyphs = false,
            sinks = listOf(LogSink { r -> synchronized(lock) { ktFile.appendText(r.line + "\n") } }),
        )
    }

    fun plain(level: String, comp: String, msg: String, x: String = "") = synchronized(lock) {
        val head = "${LocalDateTime.now().format(TS)} [${Thread.currentThread().name}] ${level.padEnd(5)} $comp - "
        plainFile.appendText(head + msg + "\n")
        plusFile.appendText(head + msg + (if (x.isNotEmpty()) " [$x]" else "") + "\n")
    }

    fun log(level: String, comp: String, msg: String, x: String = "") {
        plain(level, comp, msg, x)
        val l = Log.forComponent(comp)
        when (level) { "DEBUG" -> l.d { msg }; "WARN" -> l.w { msg }; "ERROR" -> l.e { msg }; else -> l.i { msg } }
    }

    suspend fun <T> span(comp: String, op: String, attrs: Map<String, String> = emptyMap(), x: String = "", block: suspend () -> T): T {
        val t0 = System.currentTimeMillis()
        val a = attrs.entries.joinToString(" ") { "${it.key}=${it.value}" }
        plain("INFO", comp, "$op start" + if (a.isNotEmpty()) " $a" else "", x)
        return traceSpan(component = comp, operation = op, attributes = attrs) {
            try {
                block().also { plain("INFO", comp, "$op done in ${System.currentTimeMillis() - t0} ms", x) }
            } catch (t: Throwable) {
                plain("ERROR", comp, "$op failed: ${t.message}", x)
                throw t
            }
        }
    }
}

class BenchSim {
    // ---------------- S1: late callback marks the wrong device as connected ----------------
    @Test
    fun s1() = runBlocking {
        val r = Run("s1")
        val sdkThread = Executors.newSingleThreadExecutor { Thread(it, "ble-callback") }
        var pendingMac = ""
        var connectedDevice: String? = null

        fun onState(state: String, n: Int) {
            r.log("INFO", "PairingService", "state=$state", "attempt=$n")
            if (state == "CONNECTED") {
                connectedDevice = pendingMac
                r.log("INFO", "PairingService", "device connected: $pendingMac", "attempt=$n")
                r.log("INFO", "PairingUi", "showing Connected to $pendingMac", "attempt=$n")
            }
        }

        suspend fun connect(n: Int, mac: String, connectedAtMs: Long?) =
            r.span("PairingService", "connect", mapOf("mac" to mac), "attempt=$n") {
                pendingMac = mac
                val snap = captureTraceSnapshot()
                r.log("INFO", "PairingService", "calling sdk.connect", "attempt=$n")
                sdkThread.execute {
                    Thread.sleep(50)
                    snap.withTraceSnapshot { onState("CONNECTING", n) }
                    if (connectedAtMs != null) {
                        Thread.sleep(connectedAtMs - 50)
                        snap.withTraceSnapshot { onState("CONNECTED", n) }
                    }
                }
                delay(30)
            }

        val noise = launch(Dispatchers.Default) {
            repeat(14) { i ->
                r.span("BatteryPoller", "poll", mapOf("seq" to "$i")) {
                    r.log("DEBUG", "BatteryPoller", "level=${80 - i}%")
                    delay(40)
                }
                delay(60)
            }
        }
        val sync = launch(Dispatchers.Default) {
            delay(200)
            r.span("CloudSync", "push", mapOf("items" to "3")) {
                repeat(3) { r.log("DEBUG", "CloudSync", "uploaded item ${it + 1}/3"); delay(120) }
            }
        }
        r.span("PairingUi", "tapPair", mapOf("attempt" to "1")) { connect(1, "AA:11", connectedAtMs = 900) }
        delay(270)
        r.span("PairingUi", "tapPair", mapOf("attempt" to "2")) { connect(2, "BB:22", connectedAtMs = null) }
        delay(3200)
        r.span("WeightStream", "open", mapOf("device" to (connectedDevice ?: "none"))) {
            r.log("INFO", "WeightStream", "waiting for weight packets from ${connectedDevice}")
            delay(1500)
            r.log("ERROR", "WeightStream", "no data from $connectedDevice after 1500 ms")
        }
        r.log("WARN", "PairingService", "connect timeout for BB:22 after 3500 ms")
        noise.join(); sync.join()
        sdkThread.shutdown()
    }

    // ---------------- S2: retry skips a chunk in one of six uploads ----------------
    @Test
    fun s2() = runBlocking {
        val r = Run("s2")
        suspend fun upload(fileId: String, chunks: Int, fail503At: Int?) =
            r.span("Uploader", "upload", mapOf("fileId" to fileId, "chunks" to "$chunks")) {
                var i = 1
                val sent = mutableListOf<Int>()
                while (i <= chunks) {
                    val ok = r.span("Uploader", "sendChunk", mapOf("fileId" to fileId, "chunk" to "$i")) {
                        delay((30..90).random().toLong())
                        if (fail503At == i && !sent.contains(-i)) {
                            sent.add(-i)
                            r.log("WARN", "Uploader", "chunk $i of $fileId got 503, will retry")
                            false
                        } else {
                            r.log("DEBUG", "Uploader", "chunk $i of $fileId accepted")
                            true
                        }
                    }
                    if (!ok) { delay(100); i++; continue }  // retry path advances the index
                    i++
                }
                val missing = (1..chunks).filter { c -> r.let { true } && !(fail503At != null && c == fail503At) }
                if (fail503At != null) {
                    r.log("ERROR", "Uploader", "finalize rejected for $fileId: missing chunk $fail503At")
                    error("finalize failed for $fileId: missing chunk $fail503At")
                }
                r.log("INFO", "Uploader", "finalize ok for $fileId")
            }
        val results = (1..6).map { n ->
            async(Dispatchers.Default) {
                delay(n * 15L)
                runCatching { upload("f$n", chunks = 4, fail503At = if (n == 4) 2 else null) }
            }
        }
        results.awaitAll()
        r.log("INFO", "UploadScreen", "summary: ${results.count { it.await().isSuccess }} ok, ${results.count { it.await().isFailure }} failed")
    }

    // ---------------- S3: slow screen load from sequential per-contact queries ----------------
    @Test
    fun s3() = runBlocking {
        val r = Run("s3")
        r.span("ProfileScreen", "load", mapOf("userId" to "42")) {
            coroutineScope {
                val a = async(Dispatchers.Default) {
                    r.span("ProfileRepository", "loadProfile") { delay(280); r.log("DEBUG", "ProfileRepository", "profile loaded") }
                }
                val b = async(Dispatchers.Default) {
                    r.span("ActivityRepository", "loadActivity") {
                        repeat(3) { r.log("DEBUG", "ActivityRepository", "page ${it + 1} loaded"); delay(90) }
                    }
                }
                val c = async(Dispatchers.Default) {
                    r.span("ContactsRepository", "loadAll") {
                        r.span("Database", "listContacts") { delay(60); r.log("DEBUG", "Database", "25 contacts") }
                        for (id in 1..25) {
                            r.span("Database", "loadAvatar", mapOf("contactId" to "$id")) {
                                delay(120)
                                r.log("DEBUG", "Database", "avatar for contact $id loaded")
                            }
                        }
                    }
                }
                a.await(); b.await(); c.await()
            }
            r.log("INFO", "ProfileScreen", "render complete")
        }
    }

    // ---------------- S4: three overlapping pairing attempts, shared pendingMac ----------------
    @Test
    fun s4() = runBlocking {
        val r = Run("s4")
        val sdk = java.util.concurrent.ScheduledThreadPoolExecutor(1) { Thread(it, "ble-callback") }
        var pendingMac = ""
        val connected = mutableListOf<String>()

        fun onState(state: String, n: Int) {
            r.log("INFO", "PairingService", "state=$state", "attempt=$n")
            if (state == "CONNECTED") {
                connected.add(pendingMac)
                r.log("INFO", "PairingService", "device connected: $pendingMac", "attempt=$n")
            }
        }

        suspend fun connect(n: Int, mac: String, connectedAfterMs: Long?) =
            r.span("PairingService", "connect", mapOf("mac" to mac), "attempt=$n") {
                pendingMac = mac
                val snap = captureTraceSnapshot()
                r.log("INFO", "PairingService", "calling sdk.connect", "attempt=$n")
                sdk.schedule({ snap.withTraceSnapshot { onState("CONNECTING", n) } }, 60, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (connectedAfterMs != null) {
                    sdk.schedule({ snap.withTraceSnapshot { onState("CONNECTED", n) } }, connectedAfterMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                delay(20)
            }

        val noise = launch(Dispatchers.Default) {
            repeat(16) { i ->
                r.span("BatteryPoller", "poll", mapOf("seq" to "$i")) { r.log("DEBUG", "BatteryPoller", "level=${80 - i}%"); delay(30) }
                delay(70)
            }
        }
        val sync = launch(Dispatchers.Default) {
            delay(150)
            r.span("CloudSync", "push", mapOf("items" to "3")) {
                repeat(3) { r.log("DEBUG", "CloudSync", "uploaded item ${it + 1}/3"); delay(110) }
            }
        }
        launch { r.span("PairingUi", "tapPair", mapOf("attempt" to "1"), "attempt=1") { connect(1, "AA:11", 700) } }
        delay(200)
        launch { r.span("PairingUi", "tapPair", mapOf("attempt" to "2"), "attempt=2") { connect(2, "BB:22", null) } }
        delay(200)
        launch { r.span("PairingUi", "tapPair", mapOf("attempt" to "3"), "attempt=3") { connect(3, "CC:33", 450) } }
        delay(1500)
        r.log("WARN", "PairingService", "connect timeout for BB:22 after 1500 ms")
        r.log("INFO", "PairingUi", "connected devices: $connected")
        noise.join(); sync.join()
        sdk.shutdown()
    }
}
