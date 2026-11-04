package com.splashguard.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.splashguard.app.R
import com.splashguard.app.databinding.ItemLogBinding
import com.splashguard.app.log.BlockLog

class LogAdapter(
    private val labelOf: (String?) -> String
) : RecyclerView.Adapter<LogAdapter.VH>() {

    private val items = ArrayList<BlockLog.Entry>()

    fun submit(list: List<BlockLog.Entry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.b.tvHost.text = e.host
        val time = BlockLog.formatTime(e.time)
        holder.b.tvMeta.text = if (e.pkg == null) {
            holder.b.root.context.getString(R.string.log_meta_unknown, time)
        } else {
            holder.b.root.context.getString(R.string.log_meta, labelOf(e.pkg), time)
        }
    }

    class VH(val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root)
}
