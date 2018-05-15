package com.simplypatrick.loveyourplayer

import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import com.simplypatrick.loveyourplayer.ui.player.PlayerFragment

class PlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.player_activity)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, PlayerFragment.newInstance())
                    .commitNow()
        }
    }

}
