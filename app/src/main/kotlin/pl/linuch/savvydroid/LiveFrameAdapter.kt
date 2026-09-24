package pl.linuch.savvydroid

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import pl.linuch.savvydroid.databinding.ItemLiveFrameBinding
import pl.linuch.savvydroid.gvret.LiveRow

/** RecyclerView adapter for the live "overwrite" table -- one row per CAN ID. */
class LiveFrameAdapter : ListAdapter<LiveRow, LiveFrameAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemLiveFrameBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLiveFrameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        holder.binding.idText.text = String.format("%08X", row.frame.id)
        holder.binding.fpsText.text = "${row.fps}/s"

        val sb = SpannableStringBuilder()
        for (i in row.frame.data.indices) {
            val hex = String.format("%02X", row.frame.data[i])
            val start = sb.length
            sb.append(hex)
            if ((row.changedBytesMask shr i) and 1 == 1) {
                sb.setSpan(BackgroundColorSpan(Color.YELLOW), start, sb.length, 0)
            }
            sb.append(' ')
        }
        holder.binding.dataText.text = sb
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LiveRow>() {
            override fun areItemsTheSame(old: LiveRow, new: LiveRow) = old.frame.id == new.frame.id
            override fun areContentsTheSame(old: LiveRow, new: LiveRow) = old == new
        }
    }
}
