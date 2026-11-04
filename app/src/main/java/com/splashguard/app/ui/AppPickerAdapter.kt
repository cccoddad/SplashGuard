package com.splashguard.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.splashguard.app.databinding.ItemAppBinding

data class AppEntry(val pkg: String, val label: String)

class AppPickerAdapter(
    private val onToggle: (AppEntry, Boolean) -> Boolean
) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

    private val items = ArrayList<AppEntry>()
    private val selected = HashSet<String>()

    fun submit(list: List<AppEntry>, selectedPkgs: Set<String>) {
        items.clear()
        items.addAll(list)
        selected.clear()
        selected.addAll(selectedPkgs)
        notifyDataSetChanged()
    }

    fun selectedCount(): Int = selected.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.tvName.text = item.label
        holder.b.tvPkg.text = item.pkg
        holder.b.cbSelected.isChecked = selected.contains(item.pkg)

        holder.b.root.setOnClickListener {
            val nowChecked = !selected.contains(item.pkg)
            if (!onToggle(item, nowChecked)) return@setOnClickListener
            if (nowChecked) selected.add(item.pkg) else selected.remove(item.pkg)
            notifyItemChanged(position)
        }
    }

    class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)
}
