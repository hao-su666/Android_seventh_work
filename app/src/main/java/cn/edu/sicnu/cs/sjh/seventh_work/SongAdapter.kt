package cn.edu.sicnu.cs.sjh.seventh_work

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private val onSongClick: (Int) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var songs: List<Song> = emptyList()
    private var playingIndex = -1

    fun submitList(list: List<Song>) {
        songs = list
        notifyDataSetChanged()
    }

    fun setPlayingIndex(index: Int) {
        if (index == playingIndex) return
        val old = playingIndex
        playingIndex = index
        if (old in songs.indices) notifyItemChanged(old)
        if (index in songs.indices) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(songs[position], position == playingIndex)
    }

    override fun getItemCount(): Int = songs.size

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val root: View = itemView.findViewById(R.id.itemRoot)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvSongTitle)
        private val tvInfo: TextView = itemView.findViewById(R.id.tvSongInfo)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onSongClick(position)
                }
            }
        }

        fun bind(song: Song, isPlaying: Boolean) {
            tvTitle.text = song.title
            tvTitle.setTypeface(null, if (isPlaying) Typeface.BOLD else Typeface.NORMAL)
            tvInfo.text = itemView.context.getString(
                R.string.song_info,
                song.artist,
                formatTime(song.durationMs.toInt())
            )
            root.setBackgroundColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (isPlaying) R.color.playing_highlight else android.R.color.transparent
                )
            )
        }
    }
}
