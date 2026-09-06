package app.opah.tv.playback.media3

import android.graphics.Color
import android.view.View
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Exact-generation bridge from the process compatibility backend to one black-backed PlayerView. */
internal class PlayerViewMedia3VideoOutputTarget(
    private val playerView: PlayerView,
    private val onAttached: (ExoPlayer) -> Unit,
    private val onDetached: (ExoPlayer) -> Unit,
) : Media3VideoOutputTarget {
    private var attachedPlayer: ExoPlayer? = null
    private var attachedGeneration: Long? = null
    private var highestDetachedGeneration: Long = 0L

    init {
        playerView.useController = false
        playerView.setShutterBackgroundColor(Color.BLACK)
        conceal()
    }

    override fun attach(player: Player, outputGeneration: Long) {
        val exoPlayer = player as? ExoPlayer ?: run {
            if (attachedGeneration == null) conceal()
            return
        }
        // A delayed or duplicate attach can never replace or conceal the exact newer owner.
        if (attachedGeneration != null) return
        if (outputGeneration <= highestDetachedGeneration) {
            conceal()
            return
        }
        attachedPlayer = exoPlayer
        attachedGeneration = outputGeneration
        playerView.player = exoPlayer
        playerView.visibility = View.VISIBLE
        onAttached(exoPlayer)
    }

    override fun detachAndHide(player: Player, outputGeneration: Long): Boolean {
        val exoPlayer = player as? ExoPlayer ?: return false
        if (
            outputGeneration <= highestDetachedGeneration &&
            attachedGeneration == null &&
            playerView.player == null &&
            playerView.visibility != View.VISIBLE
        ) {
            return true
        }
        if (attachedGeneration != outputGeneration || attachedPlayer !== exoPlayer) return false

        conceal()
        attachedPlayer = null
        attachedGeneration = null
        highestDetachedGeneration = maxOf(highestDetachedGeneration, outputGeneration)
        onDetached(exoPlayer)
        return playerView.player == null && playerView.visibility != View.VISIBLE
    }

    private fun conceal() {
        playerView.visibility = View.INVISIBLE
        playerView.player = null
        playerView.setShutterBackgroundColor(Color.BLACK)
    }
}
