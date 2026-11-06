package com.buwrt.dingclock.engine

import android.content.Context
import android.os.Process
import android.util.Base64
import androidx.annotation.Keep
import com.buwrt.dingclock.IUserService
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * 运行在 Shizuku 提供的 shell / root 身份进程里，负责真正执行命令。
 * 由 Shizuku 通过 app_process 实例化，不需要在 AndroidManifest 里注册。
 */
@Keep
class ShizukuUserService : IUserService.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(context: Context) : super()

    override fun destroy() {
        Process.killProcess(Process.myPid())
    }

    override fun exit() {
        destroy()
    }

    override fun exec(command: String, timeoutMs: Long): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { input ->
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.length < MAX_CHARS) output.append(buffer, 0, count)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            reader.start()

            val finished = process.waitFor(timeoutMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
            }
            runCatching { reader.join(1500) }
            val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
            encode(code, output.toString())
        } catch (t: Throwable) {
            encode(-1, t.message ?: t.toString())
        }
    }

    private fun encode(code: Int, text: String): String {
        return try {
            val bytes = text.toByteArray()
            if (bytes.size > GZIP_THRESHOLD) {
                val compressed = ByteArrayOutputStream().use { buffer ->
                    GZIPOutputStream(buffer).use { it.write(bytes) }
                    buffer.toByteArray()
                }
                val base64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
                "EXIT:$code:gzip\n$base64"
            } else {
                "EXIT:$code:plain\n$text"
            }
        } catch (t: Throwable) {
            "EXIT:-1:plain\n${t.message}"
        }
    }

    companion object {
        private const val MAX_CHARS = 8 * 1024 * 1024
        private const val GZIP_THRESHOLD = 180_000
    }
}
