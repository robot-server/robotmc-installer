package com.sysbot32.robotmc.installer.progress

import com.sysbot32.robotmc.installer.config.InstallerProperties
import org.springframework.stereotype.Service

/**
 * 설치 단계가 진행 상황을 넘기는 입구.
 * 화면은 붙잡아 둔 [ProgressSink]만 갱신하고, 창은 열지 않는다.
 */
@Service
class ProgressService {
    @Volatile
    private var sink: ProgressSink = ProgressSink.Ignored

    fun attach(sink: ProgressSink) {
        this.sink = sink
    }

    fun setStatus(status: String) {
        this.sink.onStatus(status)
    }

    fun step(n: Int = 1) {
        this.sink.onStep(n)
    }

    fun step(status: String, n: Int = 1) {
        this.setStatus(status)
        this.step(n)
    }
}

interface ProgressSink {
    fun onStatus(status: String)
    fun onStep(n: Int)

    companion object {
        val Ignored = object : ProgressSink {
            override fun onStatus(status: String) = Unit
            override fun onStep(n: Int) = Unit
        }
    }
}

fun InstallerProperties.plannedSteps(): Int {
    return 2 + (this.mod?.mods?.size ?: 0) + this.servers.size + this.resourcePacks.size
}
