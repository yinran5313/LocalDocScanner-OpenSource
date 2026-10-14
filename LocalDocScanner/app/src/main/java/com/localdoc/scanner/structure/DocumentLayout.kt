package com.localdoc.scanner.structure

import kotlin.math.exp

data class LayoutRegion(val kind: String, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val valid get()=listOf(score,left,top,right,bottom).all { it.isFinite() } && left>=0 && top>=0 && right<=1 && bottom<=1 && right>left && bottom>top
}

/** Recursive whitespace cuts support multiple columns and full-width section bands. */
object DocumentReadingOrder {
    fun <T> order(values: List<T>, bounds: (T)->LayoutRegion): List<T> {
        fun cut(items: List<T>, depth: Int): List<T> {
            if(items.size<2 || depth>=32) return items.sortedWith(compareBy({bounds(it).top},{bounds(it).left}))
            fun gap(horizontal: Boolean): Pair<Float,Float>? {
                val sorted=items.sortedBy { if(horizontal)bounds(it).left else bounds(it).top }
                var end=if(horizontal)bounds(sorted.first()).right else bounds(sorted.first()).bottom
                var best: Pair<Float,Float>?=null
                for(v in sorted.drop(1)) { val b=bounds(v); val start=if(horizontal)b.left else b.top
                    if(start-end>(if(horizontal).035f else .025f) && (best==null || start-end>best.second)) best=(end+start)/2 to (start-end)
                    end=maxOf(end,if(horizontal)b.right else b.bottom)
                }; return best
            }
            val x=gap(true)
            if(x!=null) { val (a,b)=items.partition { bounds(it).right<=x.first }; if(a.isNotEmpty() && b.isNotEmpty()) return cut(a,depth+1)+cut(b,depth+1) }
            val y=gap(false)
            if(y!=null) { val (a,b)=items.partition { bounds(it).bottom<=y.first }; if(a.isNotEmpty() && b.isNotEmpty()) return cut(a,depth+1)+cut(b,depth+1) }
            return items.sortedWith(compareBy({bounds(it).top},{bounds(it).left}))
        }
        return cut(values.filter { bounds(it).valid },0)
    }
}

/** Apache-2.0 RapidAI/RapidLayout PPPostProcess.__call__, distribution distances + per-class NMS. */
object PicoLayoutDecode {
    val labels=listOf("text","title","figure","figure_caption","table","table_caption","header","footer","reference","equation")
    fun decode(outputs: List<FloatArray>): List<LayoutRegion> {
        require(outputs.size==8)
        val candidates=mutableListOf<LayoutRegion>()
        for(level in 0..3) {
            val stride=8 shl level; val fw=(608+stride-1)/stride; val fh=(800+stride-1)/stride
            val scores=outputs[level]; val boxes=outputs[level+4]
            require(scores.size==fw*fh*10 && boxes.size==fw*fh*32) { "版面模型输出不匹配" }
            val indices=(0 until fw*fh).filter { i -> (0..9).any { scores[i*10+it].isFinite() && scores[i*10+it]>=.5f } }
                .sortedByDescending { i -> (0..9).maxOf { scores[i*10+it] } }.take(1000)
            for(i in indices) {
                val distance=FloatArray(4) { d ->
                    val at=i*32+d*8; val maximum=(0..7).maxOf { boxes[at+it] }
                    require(maximum.isFinite()) { "版面模型输出无效" }
                    val weights=FloatArray(8) { exp((boxes[at+it]-maximum).toDouble()).toFloat() }
                    (0..7).sumOf { (weights[it]*it).toDouble() }.toFloat()/weights.sum()*stride
                }
                val cx=(i%fw+.5f)*stride; val cy=(i/fw+.5f)*stride
                for(c in 0..9) if(scores[i*10+c].isFinite() && scores[i*10+c]>=.5f) {
                    val b=LayoutRegion(labels[c],scores[i*10+c],((cx-distance[0])/608).coerceIn(0f,1f),((cy-distance[1])/800).coerceIn(0f,1f),((cx+distance[2])/608).coerceIn(0f,1f),((cy+distance[3])/800).coerceIn(0f,1f))
                    if(b.valid) candidates+=b
                }
            }
        }
        val kept=mutableListOf<LayoutRegion>()
        for(c in labels) for(b in candidates.filter { it.kind==c }.sortedByDescending { it.score }.take(200)) {
            if(kept.none { it.kind==c && iou(it,b)>.5f }) kept+=b
            if(kept.count { it.kind==c }>=50) break
        }
        return kept.sortedByDescending { it.score }.take(200)
    }
    fun iou(a: LayoutRegion,b: LayoutRegion): Float {
        val overlap=maxOf(0f,minOf(a.right,b.right)-maxOf(a.left,b.left))*maxOf(0f,minOf(a.bottom,b.bottom)-maxOf(a.top,b.top))
        return overlap/((a.right-a.left)*(a.bottom-a.top)+(b.right-b.left)*(b.bottom-b.top)-overlap).coerceAtLeast(.00001f)
    }
}
