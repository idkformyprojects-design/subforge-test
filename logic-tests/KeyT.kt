import ai.subforge.universal.core.KeyHelper
fun main() {
    val k = "AIzaSyD3x_-ab12CD34ef56GH78ij90KL12mnOP"   // 39 chars, like a real key
    var bad = 0
    fun ck(n: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + n); if (!ok) bad++ }
    ck("length is 39", k.length == 39)
    ck("plain key", KeyHelper.find(k) == k)
    ck("key with spaces/newline around", KeyHelper.find("  \n$k \n") == k)
    ck("key inside a sentence", KeyHelper.find("Your API key: $k (keep it secret)") == k)
    ck("not a key: normal text", KeyHelper.find("hello world https://aistudio.google.com") == null)
    ck("not a key: too short", KeyHelper.find("AIzaSyD3x_-ab12") == null)
    ck("empty / null", KeyHelper.find("") == null && KeyHelper.find(null) == null)
    ck("mask hides the middle", KeyHelper.mask(k) == "AIzaSy…nOP")
    if (bad > 0) System.exit(1)
}
