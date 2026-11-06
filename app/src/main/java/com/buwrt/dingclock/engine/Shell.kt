package com.buwrt.dingclock.engine

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Base64
import com.buwrt.dingclock.IUserService
import com.buwrt.dingclock.model.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class ShellResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) {
    val ok: Boolean get() = !timedOut && code == 0

    val output: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr.trim())
            }
        }.trim()
}

interface Shell : AutoCloseable {
    val name: String
    suspend fun exec(command: String, timeoutMs: Long = 20_000L): ShellResult
    override fun close() {}
}

object RootShell {
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", "id").start()
            val result = ProcessRunner.collect(process, 12_000L)
            result.ok && result.stdout.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
    }
}

class RootShellImpl : Shell {
    override val name: String = "Root"

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", command).start()
                ProcessRunner.collect(process, timeoutMs)
            } catch (t: Throwable) {
                ShellResult(-1, "", t.message ?: t.toString())
            }
        }
}

object ShizukuShell {
    fun ready(): Boolean = try {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    suspend fun obtain(context: Context, timeoutMs: Long = 15_000L): IUserService? =
        withContext(Dispatchers.IO) {
            if (!ready()) return@withContext null
            ShizukuBinder.get(context.applicationContext, timeoutMs)
        }
}

/**
 * Shizuku 13 的 newProcess 已是私有 API，这里用官方推荐的 UserService 方案：
 * 把命令交给以 shell（或 root）身份运行的 [ShizukuUserService]，再通过 Binder 取回结果。
 */
class ShizukuShellImpl(private val remote: IUserService) : Shell {
    override val name: String = "Shizuku"

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            try {
                decode(remote.exec(command, timeoutMs))
            } catch (t: Throwable) {
                ShellResult(-1, "", t.message ?: t.toString())
            }
        }

    private fun decode(raw: String): ShellResult {
        val newline = raw.indexOf('\n')
        val header = if (newline >= 0) raw.substring(0, newline) else raw
        val payload = if (newline >= 0) raw.substring(newline + 1) else ""
        val parts = header.split(':')
        if (parts.size < 2 || parts[0] != "EXIT") {
            return ShellResult(-1, raw, "")
        }
        val code = parts[1].toIntOrNull() ?: -1
        val encoding = parts.getOrNull(2) ?: "plain"
        val text = if (encoding == "gzip") {
            runCatching {
                val bytes = Base64.decode(payload, Base64.DEFAULT)
                GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader().use { it.readText() }
            }.getOrElse { "解压失败：${it.message}" }
        } else {
            payload
        }
        return ShellResult(code, text, "")
    }
}

object ShellFactory {
    suspend fun obtain(preference: Backend, context: Context): Shell? {
        val rootAvailable = RootShell.probe()
        return when (preference) {
            Backend.ROOT -> if (rootAvailable) RootShellImpl() else null
            Backend.SHIZUKU ->
                ShizukuShell.obtain(context)?.let { ShizukuShellImpl(it) }
            Backend.AUTO -> when {
                rootAvailable -> RootShellImpl()
                else -> ShizukuShell.obtain(context)?.let { ShizukuShellImpl(it) }
            }
        }
    }
}

private object ShizukuBinder {
    @Volatile
    private var service: IUserService? = null

    @Volatile
    private var connecting = false

    private val lock = Any()

    fun get(context: Context, timeoutMs: Long): IUserService? {
        aliveService()?.let { return it }

        synchronized(lock) {
            aliveService()?.let { return it }
            if (!connecting) {
                connecting = true
                val latch = CountDownLatch(1)
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, ShizukuUserService::class.java.name)
                )
                    .daemon(false)
                    .version(1)
                    .processNameSuffix("shell")

                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        service = IUserService.Stub.asInterface(binder)
                        connecting = false
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                        connecting = false
                        latch.countDown()
                    }
                }

                try {
                    Shizuku.bindUserService(args, connection)
                } catch (_: Throwable) {
                    connecting = false
                    return null
                }

                try {
                    latch.await(timeoutMs.coerceAtLeast(2000L), TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                connecting = false
            }
        }

        return aliveService()
    }

    private fun aliveService(): IUserService? {
        val current = service ?: return null
        return if (current.asBinder().isBinderAlive) current else null
    }
}

internal object ProcessRunner {
    private const val MAX_OUTPUT = 4 * 1024 * 1024

    fun collect(process: Process, timeoutMs: Long): ShellResult {
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = readerThread(process.inputStream) { appendCapped(out, it) }
        val errThread = readerThread(process.errorStream) { appendCapped(err, it) }
        outThread.start()
        errThread.start()

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            runCatching { process.destroyForcibly() }
        }
        runCatching { outThread.join(1500) }
        runCatching { errThread.join(1500) }

        val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return ShellResult(code, out.toString(), err.toString(), timedOut = !finished)
    }

    private fun readerThread(stream: java.io.InputStream, sink: (String) -> Unit): Thread =
        Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        sink(String(buffer, 0, count))
                    }
                }
            } catch (_: Throwable) {
            }
        }

    private fun appendCapped(builder: StringBuilder, chunk: String) {
        synchronized(builder) {
            if (builder.length < MAX_OUTPUT) builder.append(chunk)
        }
    }
}
