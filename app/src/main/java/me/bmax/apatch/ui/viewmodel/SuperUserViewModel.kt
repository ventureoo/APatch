package me.bmax.apatch.ui.viewmodel

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dev.utils.app.AppUtils.getPackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.bmax.apatch.Natives
import me.bmax.apatch.apApp
import me.bmax.apatch.util.HanziToPinyin
import me.bmax.apatch.util.PkgConfig
import java.text.Collator
import java.util.Locale
import kotlin.concurrent.thread


class SuperUserViewModel : ViewModel() {
    companion object {
        private const val TAG = "SuperUserViewModel"
        private var apps by mutableStateOf<List<AppInfo>>(emptyList())
    }

    @Parcelize
    data class AppInfo(
        val label: String, val applicationInfo: ApplicationInfo, val config: PkgConfig.Config
    ) : Parcelable {
        val packageName: String
            get() = applicationInfo.packageName
        val uid: Int
            get() = applicationInfo.uid
    }

    var search by mutableStateOf("")
    var showSystemApps by mutableStateOf(false)
    var isRefreshing by mutableStateOf(false)
        private set

    private val sortedList by derivedStateOf {
        val comparator = compareBy<AppInfo> {
            when {
                it.config.allow != 0 -> 0
                it.config.exclude == 1 -> 1
                else -> 2
            }
        }.then(compareBy(Collator.getInstance(Locale.getDefault()), AppInfo::label))
        apps.sortedWith(comparator).also {
            isRefreshing = false
        }
    }

    val appList by derivedStateOf {
        sortedList.filter {
            it.label.lowercase().contains(search.lowercase()) || it.packageName.lowercase()
                .contains(search.lowercase()) || HanziToPinyin.getInstance()
                .toPinyinString(it.label).contains(search.lowercase())
        }.filter {
            it.uid == 2000 // Always show shell
                    || showSystemApps || it.applicationInfo.flags.and(ApplicationInfo.FLAG_SYSTEM) == 0
        }
    }

    suspend fun fetchAppList() {
        isRefreshing = true

        withContext(Dispatchers.IO) {
            val pm = getPackageManager()
            val allPackages = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)

            val uids = Natives.suUids().toList()
            Log.d(TAG, "all allows: $uids")

            var configs: HashMap<Int, PkgConfig.Config> = HashMap()
            thread {
                Natives.su()
                configs = PkgConfig.readConfigs()
            }.join()

            Log.d(TAG, "all configs: $configs")

            apps = allPackages.map {
                val uid = it.uid
                val actProfile = if (uids.contains(uid)) Natives.suProfile(uid) else null
                val config = configs.getOrDefault(
                    uid, PkgConfig.Config(it.packageName, Natives.isUidExclude(uid), 0, Natives.Profile(uid = uid))
                )
                config.allow = 0

                // from kernel
                if (actProfile != null) {
                    config.allow = 1
                    config.profile = actProfile
                }
                AppInfo(
                    label = it.loadLabel(apApp.packageManager).toString(),
                    applicationInfo = it,
                    config = config
                )
            }.filter { it.packageName != apApp.packageName }
        }
    }
}
