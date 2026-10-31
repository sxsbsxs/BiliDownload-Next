package app.bilidownload.next.network

import java.net.URLEncoder
import java.security.MessageDigest

object WbiSigner {
    private val order = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)
    fun sign(params:Map<String,String>,imgKey:String,subKey:String,timestamp:Long=System.currentTimeMillis()/1000):Map<String,String>{
        val raw=imgKey+subKey
        val mix=order.mapNotNull{raw.getOrNull(it)}.joinToString("").take(32)
        val clean=(params+("wts" to timestamp.toString())).toSortedMap().mapValues{(_,v)->v.replace(Regex("[!'()*]"),"")}
        val query=clean.entries.joinToString("&"){(k,v)->"${enc(k)}=${enc(v)}"}
        val digest=MessageDigest.getInstance("MD5").digest((query+mix).toByteArray())
        val rid=digest.joinToString(""){"%02x".format(it)}
        return clean+("w_rid" to rid)
    }
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8").replace("+","%20")
}