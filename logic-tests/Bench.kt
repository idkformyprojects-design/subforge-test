import ai.subforge.universal.core.*
import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

var failures = 0
fun check(name: String, ok: Boolean, detail: Any? = "") {
    println((if (ok) "  PASS " else "  FAIL ") + name + if (ok) "" else "   -> $detail")
    if (!ok) failures++
}
fun control(json: String) {
    val c = URL("http://127.0.0.1:18777/control").openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(json.toByteArray()) }; c.inputStream.readBytes()
}
fun aboutFlags(): List<Boolean> {
    val a = org.json.JSONObject(URL("http://127.0.0.1:18777/calls").readText()).getJSONArray("calls")
    return (0 until a.length()).map { a.getJSONArray(it).optBoolean(2) }
}
fun calls(): List<Pair<String, String>> {
    val t = URL("http://127.0.0.1:18777/calls").readText()
    val a = org.json.JSONObject(t).getJSONArray("calls")
    return (0 until a.length()).map { a.getJSONArray(it).getString(0) to a.getJSONArray(it).getString(1) }
}
class Ev : Worker.Events {
    var firstPass = 0; var improvingFlag: Boolean? = null; var onFirstPass: (() -> Unit)? = null
    override fun progress(text: String, fraction: Double) {}
    override fun firstPassDone(lines: Int, stillImproving: Boolean) { firstPass++; improvingFlag = stillImproving; onFirstPass?.invoke() }
}
val MAIN = "best"; val BACKUPS = listOf("flash-latest", "lite")
fun ctx(): Context = Context(createTempDir("sfbench"))
fun job(c: Context, ev: Ev, speech: Boolean = true, doubleCheck: Boolean = false, lang: Int = 1): Worker {
    val w = Worker(c, MAIN, BACKUPS, "TESTKEY", ev)
    ProjectStore.replace(c, w.prepare(Uri.parse("content://video/1"), "movie.mp4", lang, speech, doubleCheck))
    return w
}
fun main() {
    Gemini.baseUrl = "http://127.0.0.1:18777"; Gemini.retryWaitMs = 20; Gemini.minGapMs = 0
    Worker.roundWaitMs = 200; Worker.improveMs = 3000

    println("1. Best model free: everything by the best model")
    control("{}"); var c = ctx(); var ev = Ev(); var st = job(c, ev).run(); var p = ProjectStore.get(c)!!
    check("state done", st == Project.STATE_DONE, st)
    check("6 lines, all by best", p.cues.size == 6 && p.cues.all { it.text.endsWith("by best") && it.mark == Cue.MARK_NONE }, p.cues)
    check("times shifted per part", p.cues.map { it.start } == listOf(1.0, 11.0, 301.0, 311.0, 601.0, 611.0), p.cues.map { it.start })
    check("one 'ready' event, nothing to improve", ev.firstPass == 1 && ev.improvingFlag == false)
    check("only 3 requests", calls().size == 3, calls())

    println("2. Best model busy at first, free later: backup used, then everything re-done by best")
    control("""{"best":{"busy":6}}"""); c = ctx(); ev = Ev()
    val w2 = job(c, ev)
    ev.onFirstPass = {
        val pp = ProjectStore.get(c)!!
        check("after first pass all lines are from backup and marked", pp.cues.size == 6 && pp.cues.all { it.text.endsWith("by flash-latest") && it.mark == Cue.MARK_BACKUP }, pp.cues)
    }
    st = w2.run(); p = ProjectStore.get(c)!!
    check("state done", st == Project.STATE_DONE, st)
    check("now all by best, marked corrected", p.cues.size == 6 && p.cues.all { it.text.endsWith("by best") && it.mark == Cue.MARK_FIXED }, p.cues)
    check("'ready' event said improving", ev.improvingFlag == true)
    val bestCalls = calls().count { it.first == "best" }
    check("waited 4 tries on part 1, then 1 try per part while busy (4+1+1+3=9)", bestCalls == 9, calls())
    check("no backup parts left", p.backupCount() == 0)

    println("3. Double-check on: every part reviewed, corrected lines marked")
    control("{}"); c = ctx(); ev = Ev(); st = job(c, ev, doubleCheck = true).run(); p = ProjectStore.get(c)!!
    check("state done", st == Project.STATE_DONE, st)
    check("3 transcribe + 3 review requests", calls().count { it.second == "transcribe" } == 3 && calls().count { it.second == "review" } == 3, calls())
    check("3 lines corrected, 3 unchanged", p.cues.count { it.mark == Cue.MARK_FIXED && it.text.endsWith("(fixed)") } == 3 && p.cues.count { it.mark == Cue.MARK_NONE } == 3, p.cues)
    check("all parts reviewed", p.parts.all { it.reviewed })
    check("message mentions double-checked", p.message.contains("double-checked"), p.message)

    println("4. User edits a line while re-checking: that part is left alone")
    control("""{"best":{"busy":6}}"""); c = ctx(); ev = Ev()
    val w4 = job(c, ev)
    ev.onFirstPass = { ProjectStore.edit(c) { pr -> pr.cues.first { it.part == 1 }.apply { text = "MY EDIT"; edited = true } } }
    st = w4.run(); p = ProjectStore.get(c)!!
    check("user's edit kept", p.cues.any { it.text == "MY EDIT" && it.edited }, p.cues)
    check("other line of edited part untouched (still backup)", p.cues.filter { it.part == 1 && !it.edited }.all { it.text.endsWith("by flash-latest") }, p.cues)
    check("parts 1 and 3 re-done by best", p.cues.filter { it.part != 1 }.all { it.text.endsWith("by best") }, p.cues)

    println("5. Best model name does not exist: backups used, clear message")
    control("""{"best":{"missing":true}}"""); c = ctx(); ev = Ev(); st = job(c, ev).run(); p = ProjectStore.get(c)!!
    check("all lines from backup", p.cues.size == 6 && p.cues.all { it.text.endsWith("by flash-latest") }, p.cues)
    check("message says model not found", p.message.contains("not found"), p.message)
    check("did not keep retrying the missing model", calls().count { it.first == "best" } == 1, calls())

    println("6. Everything busy: job stops safely, then Continue finishes it")
    control("""{"best":{"busy":999},"flash-latest":{"busy":999},"lite":{"busy":999}}"""); c = ctx(); ev = Ev()
    st = job(c, ev).run(); p = ProjectStore.get(c)!!
    check("state stopped", st == Project.STATE_STOPPED, st)
    check("3 parts still to do, message says Continue", p.todoCount() == 3 && p.message.contains("Continue"), p.message)
    check("no 'ready' notification", ev.firstPass == 0)
    control("{}"); ev = Ev()
    st = Worker(c, MAIN, BACKUPS, "TESTKEY", ev).run(); p = ProjectStore.get(c)!!
    check("continue: state done", st == Project.STATE_DONE, st)
    check("continue: 6 lines by best", p.cues.size == 6 && p.cues.all { it.text.endsWith("by best") }, p.cues)
    check("continue: 'ready' notification sent", ev.firstPass == 1)

    println("7. Subtitle-track mode: translated, backup batch re-done by best")
    control("""{"best":{"busy":4}}"""); c = ctx(); ev = Ev(); st = job(c, ev, speech = false).run(); p = ProjectStore.get(c)!!
    check("state done", st == Project.STATE_DONE, st)
    check("2 lines translated by best, marked corrected", p.cues.size == 2 && p.cues.all { it.text.startsWith("T[best]:") && it.mark == Cue.MARK_FIXED }, p.cues)
    check("source kept for re-translation", p.source == listOf("Hello", "Goodbye"), p.source)

    println("8. Original language in track mode: nothing sent to Gemini")
    control("{}"); c = ctx(); ev = Ev(); st = job(c, ev, speech = false, lang = 0).run(); p = ProjectStore.get(c)!!
    check("done with 0 requests", st == Project.STATE_DONE && calls().isEmpty(), calls())

    println("9. Cancel while waiting")
    control("""{"best":{"busy":999},"flash-latest":{"busy":999},"lite":{"busy":999}}"""); c = ctx(); ev = Ev()
    Gemini.retryWaitMs = 5000
    val w9 = job(c, ev); var err: Throwable? = null
    val t = Thread { try { w9.run() } catch (e: Throwable) { err = e } }; t.start()
    Thread.sleep(500); val t0 = System.currentTimeMillis(); w9.cancel(); t.join(5000)
    check("stopped within 1 second", !t.isAlive && System.currentTimeMillis() - t0 < 1000)
    check("stopped with Cancelled", err is GeminiCancelledException, err)

    println("10. Saved project survives a restart (reload from disk)")
    Gemini.retryWaitMs = 20
    control("""{"best":{"busy":6}}"""); c = ctx(); ev = Ev(); job(c, ev, doubleCheck = true).run()
    val before = ProjectStore.get(c)!!
    val loaded = Project.load(c)!!
    check("same lines, marks, parts and settings after reload",
        loaded.cues == before.cues && loaded.parts.size == 3 && loaded.parts.all { it.status == Part.DONE && it.reviewed } &&
            loaded.doubleCheck && loaded.state == before.state, loaded.cues)

    println("11. Best model's daily free limit used up: no pointless waiting, backup used, clear message")
    Worker.roundWaitMs = 60_000
    control("""{"best":{"daily":true}}"""); c = ctx(); ev = Ev()
    var t1 = System.currentTimeMillis(); st = job(c, ev).run(); p = ProjectStore.get(c)!!
    check("finished quickly (no 2-minute waits)", System.currentTimeMillis() - t1 < 5000, System.currentTimeMillis() - t1)
    check("state partial", st == Project.STATE_PARTIAL, st)
    check("all lines from backup", p.cues.all { it.text.endsWith("by flash-latest") }, p.cues)
    check("best model asked only once", calls().count { it.first == "best" } == 1, calls())
    check("message says daily limit, re-check tomorrow", p.message.contains("daily limit") && p.message.contains("tomorrow"), p.message)
    check("audio kept for re-check later", Project.partsDir(c).listFiles()?.size == 3)

    println("12. Per-minute limit with Google's wait hint (1.5 s): waits as told, then best model")
    control("""{"best":{"rpm_busy":1}}"""); c = ctx(); ev = Ev()
    t1 = System.currentTimeMillis(); st = job(c, ev).run(); p = ProjectStore.get(c)!!
    val took = System.currentTimeMillis() - t1
    check("waited about 1.5 s, not the short default", took >= 1400 && took < 6000, took)
    check("everything by best", st == Project.STATE_DONE && p.cues.all { it.text.endsWith("by best") }, p.cues)

    println("13. Cancel in the middle of a slow request stops at once")
    control("""{"best":{"delay":20}}"""); c = ctx(); ev = Ev()
    val w13 = job(c, ev); var err13: Throwable? = null
    val t13 = Thread { try { w13.run() } catch (e: Throwable) { err13 = e } }; t13.start()
    Thread.sleep(800); t1 = System.currentTimeMillis(); w13.cancel(); t13.join(5000)
    check("stopped within 1 second (request was going to take 20 s)", !t13.isAlive && System.currentTimeMillis() - t1 < 1000, System.currentTimeMillis() - t1)
    check("reported as Cancelled", err13 is GeminiCancelledException, err13)
    Thread.sleep(300)

    println("14. 'Try now' ends a long wait immediately; 'next try at' time is shown")
    Worker.roundWaitMs = 600_000; Worker.improveMs = 3_600_000
    control("""{"best":{"busy":6}}"""); c = ctx()
    var waitedUntil = 0L
    val ev14 = object : Worker.Events {
        override fun progress(text: String, fraction: Double) {}
        override fun firstPassDone(lines: Int, stillImproving: Boolean) {}
        override fun waiting(untilWallMs: Long) { if (untilWallMs > 0) waitedUntil = untilWallMs }
    }
    val w14 = Worker(c, MAIN, BACKUPS, "TESTKEY", ev14)
    ProjectStore.replace(c, w14.prepare(Uri.parse("content://video/1"), "movie.mp4", 1, true, false))
    control("""{"best":{"busy":999}}""")
    val t14 = Thread { w14.run() }; t14.start()
    var spins = 0; while (waitedUntil == 0L && spins++ < 100) Thread.sleep(100)
    check("waiting reported with a next-try time ~10 min ahead", waitedUntil - System.currentTimeMillis() in 500_000..600_000, waitedUntil - System.currentTimeMillis())
    control("{}"); t1 = System.currentTimeMillis(); Worker.tryNow(); t14.join(10_000)
    check("finished right after Try now", !t14.isAlive && System.currentTimeMillis() - t1 < 5000, System.currentTimeMillis() - t1)
    p = ProjectStore.get(c)!!
    check("and everything is now by the best model", p.cues.all { it.text.endsWith("by best") }, p.cues)
    Worker.roundWaitMs = 200; Worker.improveMs = 3000

    println("15. Saved audio is deleted automatically when the job is fully done")
    control("{}"); c = ctx(); ev = Ev(); st = job(c, ev).run()
    check("done and audio folder gone", st == Project.STATE_DONE && !Project.partsDir(c).exists(), Project.partsDir(c).listFiles()?.size)

    println("16. History: previous subtitles kept when a new job starts; can be reopened")
    Project.archiveCurrent(c)
    check("current moved to history", !Project.exists(c) && Project.history(c).size == 1)
    ProjectStore.forget()
    control("{}"); ev = Ev(); job(c, ev).run()
    Project.archiveCurrent(c, deleteAudio = false); ProjectStore.forget()
    check("two entries in history", Project.history(c).size == 2)
    val entry = Project.history(c).last()
    val restored = Project.restore(c, entry)!!
    ProjectStore.replace(c, restored)
    check("reopened entry is current, finished, with its lines", ProjectStore.get(c)!!.cues.size == 6 && restored.state == Project.STATE_DONE)
    check("history now holds the other one", Project.history(c).size == 1)

    println("17. 'About the video' text is sent with every request, and kept in the project")
    control("{}"); c = ctx(); ev = Ev()
    val w17 = Worker(c, MAIN, BACKUPS, "TESTKEY", ev)
    ProjectStore.replace(c, w17.prepare(Uri.parse("content://video/1"), "movie.mp4", 1, true, true, "Bengali drama Aparajito; characters Apu, Durga"))
    w17.run(); p = ProjectStore.get(c)!!
    check("all 6 requests (3 write + 3 double-check) carried it", aboutFlags().size == 6 && aboutFlags().all { it }, aboutFlags())
    check("saved with the project and survives reload", Project.load(c)!!.about.contains("Aparajito"))
    control("{}"); c = ctx(); ev = Ev(); job(c, ev).run()
    check("not sent when the box is empty", aboutFlags().none { it }, aboutFlags())

    println("18. A missing backup model is asked once, not on every part")
    control("""{"best":{"busy":999},"flash-latest":{"missing":true}}"""); c = ctx(); ev = Ev(); st = job(c, ev).run(); p = ProjectStore.get(c)!!
    check("all lines made by the next backup", p.cues.size == 6 && p.cues.all { it.text.endsWith("by lite") }, p.cues)
    check("missing backup asked only once", calls().count { it.first == "flash-latest" } == 1, calls())

    println("19. A backup that hit its daily limit is skipped for the rest of the job")
    control("""{"best":{"busy":999},"flash-latest":{"daily":true}}"""); c = ctx(); ev = Ev(); st = job(c, ev).run(); p = ProjectStore.get(c)!!
    check("all lines made by the next backup", p.cues.all { it.text.endsWith("by lite") }, p.cues)
    check("used-up backup asked only once", calls().count { it.first == "flash-latest" } == 1, calls())

    println(if (failures == 0) "\nALL SCENARIOS PASSED" else "\n$failures CHECK(S) FAILED")
    if (failures > 0) System.exit(1)
}
