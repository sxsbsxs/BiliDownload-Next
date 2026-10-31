package app.bilidownload.next.parser
object VideoInputParser {
    private val bv = Regex("""BV[0-9A-Za-z]{10}""", RegexOption.IGNORE_CASE)
    private val av = Regex("""(?:av|AV)([0-9]+)""")
    fun parse(raw:String):String? {
        val s=raw.trim()
        bv.find(s)?.value?.let{return it}
        av.find(s)?.groupValues?.getOrNull(1)?.let{return it}
        return null
    }
}