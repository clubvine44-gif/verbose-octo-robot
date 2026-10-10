package com.pulselens.app

import kotlin.math.*

/**
 * Experimental optical PPG quality and pulse-interval estimator.
 * Rejects unstable windows. It is NOT an ECG or an arrhythmia detector.
 */
object PpgAnalyzer {
    data class Result(
        val intervals: List<Double>,
        val wave: List<Double>,
        val quality: Int,
        val acceptedBeats: Int,
        val rejectedBeats: Int,
        val reason: String
    )
    private fun median(a: List<Double>): Double {
        if (a.isEmpty()) return 0.0
        val s = a.sorted()
        return if (s.size % 2 == 1) s[s.size/2] else (s[s.size/2-1] + s[s.size/2])/2
    }
    fun analyze(data: List<Pair<Double, Double>>): Result {
        if (data.size < 120) return Result(emptyList(), emptyList(), 0, 0, 0, "Нужно больше данных")
        // Last 25s for stable live estimates; ignore camera stabilization first 5s.
        val start = data.first().first + 5.0
        val end = data.last().first
        val src = data.filter { it.first >= max(start, end - 25.0) }
        if (src.size < 90) return Result(emptyList(), emptyList(), 0, 0, 0, "Ожидание стабилизации")
        val gaps = src.zipWithNext().map { (a,b) -> b.first-a.first }
        val dt = median(gaps)
        if (dt !in 0.025..0.14 || gaps.count { it > 0.2 } > 2)
            return Result(emptyList(), emptyList(), 0, 0, 0, "Нестабильная частота кадров")
        // Local detrending (approx. 1.8 seconds), then 5-point smoothing.
        val radius = (0.9/dt).roundToInt().coerceIn(5, 40)
        val prefix = DoubleArray(src.size+1)
        src.forEachIndexed { i, v -> prefix[i+1] = prefix[i]+v.second }
        val centered = DoubleArray(src.size) { i ->
            val l=max(0,i-radius)
            val r=min(src.size-1,i+radius)
            src[i].second-(prefix[r+1]-prefix[l])/(r-l+1)
        }
        val smooth = DoubleArray(src.size) { i ->
            val l=max(0,i-2); val r=min(src.size-1,i+2)
            var sum=0.0
            for(j in l..r) sum+=centered[j]
            sum/(r-l+1)
        }
        val recent = smooth.toList()
        val sortedAmp = recent.sorted()
        val p10=sortedAmp[(sortedAmp.size*.10).toInt()]
        val p90=sortedAmp[(sortedAmp.size*.90).toInt()]
        val amplitude = p90-p10
        if(amplitude < .23)
            return Result(emptyList(), recent.takeLast(220), 0, 0, 0, "Слабая пульсовая волна")
        val peakTimes=mutableListOf<Double>()
        val peakAmp=mutableListOf<Double>()
        var rejected=0
        val refractory=0.40
        // Find troughs in detrended signal. Require sufficient prominence in local ±0.25s.
        val window = (0.24/dt).roundToInt().coerceAtLeast(2)
        for(i in window until smooth.size-window) {
            if(smooth[i] > smooth[i-1] || smooth[i] >= smooth[i+1]) continue
            var leftMax=Double.NEGATIVE_INFINITY
            var rightMax=Double.NEGATIVE_INFINITY
            for(j in i-window until i) leftMax=max(leftMax,smooth[j])
            for(j in i+1..i+window) rightMax=max(rightMax,smooth[j])
            val prom=min(leftMax,rightMax)-smooth[i]
            if(prom < amplitude*.22) continue
            val time=src[i].first
            if(peakTimes.isNotEmpty() && time-peakTimes.last() < refractory) {
                if(prom>peakAmp.last()) {peakTimes[peakTimes.lastIndex]=time;peakAmp[peakAmp.lastIndex]=prom}
                rejected++
                continue
            }
            peakTimes.add(time);peakAmp.add(prom)
        }
        if(peakTimes.size < 5)
            return Result(emptyList(), recent.takeLast(220), 15, peakTimes.size, rejected, "Мало достоверных волн")
        val rawIntervals=peakTimes.zipWithNext().map { (a,b)->b-a }
        val plausible=rawIntervals.filter {it in .43..1.5}
        val typical=median(plausible)
        if(typical <= 0) return Result(emptyList(),recent.takeLast(220),0,0,rejected,"Не найден пульс")
        // Median-based filtering removes tracking errors (not physiological irregularity diagnosis).
        val clean=rawIntervals.filter { it in .43..1.5 && abs(it-typical) <= typical*.23 }
        rejected += rawIntervals.size-clean.size
        val stability=(clean.size.toDouble()/rawIntervals.size).coerceIn(0.0,1.0)
        val quality=(100*stability*min(1.0,amplitude/1.1)).toInt().coerceIn(0,100)
        return Result(clean,recent.takeLast(220),quality,clean.size,rejected,
            if(quality<60 || clean.size<5) "Низкая достоверность, повтори измерение"
            else if(rejected>0) "Есть непроверенные волны: часть исключена"
            else "Оптический сигнал устойчивый")
    }
}
