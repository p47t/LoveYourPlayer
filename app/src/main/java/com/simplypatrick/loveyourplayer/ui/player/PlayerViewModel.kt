package com.simplypatrick.loveyourplayer.ui.player

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.view.Surface
import com.simplypatrick.loveyourplayer.YourPlayer

class PlayerViewModel : ViewModel() {
    var error = MutableLiveData<String>()
    var player: YourPlayer? = null

    fun init(surface: Surface) {
        player = YourPlayer().apply {
            Thread {
                val ok = init(surface, "/sdcard/Movies/big_buck_bunny_480p_h264.mov")
                if (!ok) {
                    error.postValue("Failed to play video")
                }
                playbackLoop()
            }.start()
        }
    }

    fun stop() {
        player?.stop()
        player = null
    }
}
