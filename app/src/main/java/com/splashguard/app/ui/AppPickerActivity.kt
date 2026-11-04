package com.splashguard.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.splashguard.app.R
import com.splashguard.app.core.PerAppMode
import com.splashguard.app.core.Prefs
import com.splashguard.app.databinding.ActivityAppPickerBinding
import com.splashguard.app.net.SplashGuardVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppPickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppPickerBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppPickerAdapter
    private var syncingRadios = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.toolbar.setNavigationOnClickListener { finish() }

        adapter = AppPickerAdapter { entry, checked -> onToggle(entry, checked) }
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        syncingRadios = true
        if (prefs.perAppMode == PerAppMode.ONLY_SELECTED) b.rbSelected.isChecked = true else b.rbAll.isChecked = true
        syncingRadios = false
        updateHint()

        b.rgMode.setOnCheckedChangeListener { _, id ->
            if (syncingRadios) return@setOnCheckedChangeListener
            if (id == R.id.rbSelected) {
                if (prefs.selectedApps.isEmpty()) {
                    toast(getString(R.string.apps_empty_selection))
                    syncingRadios = true
                    b.rbAll.isChecked = true
                    syncingRadios = false
                    return@setOnCheckedChangeListener
                }
                prefs.perAppMode = PerAppMode.ONLY_SELECTED
            } else {
                prefs.perAppMode = PerAppMode.ALL
            }
            updateHint()
            restartIfRunning()
        }

        loadApps()
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .mapNotNull { ri ->
                        val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                        val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                        AppEntry(pkg, label)
                    }
                    .distinctBy { it.pkg }
                    .filter { it.pkg != packageName }
                    .sortedBy { it.label.lowercase() }
            }
            adapter.submit(list, prefs.selectedApps)
            b.tvLoading.visibility = View.GONE
        }
    }

    private fun onToggle(entry: AppEntry, checked: Boolean): Boolean {
        if (prefs.perAppMode != PerAppMode.ONLY_SELECTED) {
            toast(getString(R.string.apps_need_mode))
            return false
        }
        val next = prefs.selectedApps.toMutableSet()
        if (checked) {
            next.add(entry.pkg)
        } else {
            next.remove(entry.pkg)
            if (next.isEmpty()) {
                toast(getString(R.string.apps_empty_selection))
                return false
            }
        }
        prefs.selectedApps = next
        restartIfRunning()
        return true
    }

    private fun updateHint() {
        b.tvModeHint.text = getString(
            if (prefs.perAppMode == PerAppMode.ALL) R.string.apps_hint_all else R.string.apps_hint_selected
        )
    }

    private fun restartIfRunning() {
        if (!SplashGuardVpnService.isRunning) return
        SplashGuardVpnService.restart(this)
        toast(getString(R.string.apps_restart_hint))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
