package com.michael.netlock

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

object RootShell {

    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    private var seq = 0

    /**
     * 把脚本落到应用私有缓存目录后以 root 执行。
     * 用文件而不是 su -c 长命令, 是为了绕开参数长度与引号转义问题。
     */
    fun run(ctx: Context, script: String, timeoutSec: Long = 90): Result {
        val f = File(ctx.cacheDir, "run_${++seq}.sh")
        f.writeText(script)
        f.setReadable(true, false)
        return try {
            val pb = ProcessBuilder("su", "-c", "sh " + f.absolutePath)
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                Result(-1, out + "\n[超时 ${timeoutSec}s]")
            } else {
                Result(p.exitValue(), out)
            }
        } catch (e: Exception) {
            Result(-1, "调用 su 失败: " + e)
        } finally {
            f.delete()
        }
    }

    fun isRootAvailable(ctx: Context): Boolean =
        run(ctx, "id -u", 15).output.trim() == "0"
}
