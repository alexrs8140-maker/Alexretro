package com.swordfish.lemuroid.app.tv.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DiffCallback
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.GameInteractor
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.shared.savesync.SaveSyncWork
import com.swordfish.lemuroid.app.shared.settings.StorageFrameworkPickerLauncher
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.app.tv.folderpicker.TVFolderPickerLauncher
import com.swordfish.lemuroid.app.tv.settings.TVSettingsActivity
import com.swordfish.lemuroid.app.tv.shared.GamePresenter
import com.swordfish.lemuroid.app.tv.shared.TVHelper
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject

class TVHomeFragment : BrowseSupportFragment() {
    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    @Inject
    lateinit var gameInteractor: GameInteractor

    @Inject
    lateinit var saveSyncManager: SaveSyncManager

    private val showAllConsoles: Boolean
        get() = arguments?.getBoolean("show_all_consoles", false) ?: false

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        onItemViewClickedListener =
            OnItemViewClickedListener { _, item, _, _ ->
                when (item) {
                    is Game -> gameInteractor.onGamePlay(item)
                    is MetaSystemInfo -> {
                        if (item.count == 0) {
                            Toast.makeText(requireContext(), R.string.alexretro_empty_console, Toast.LENGTH_LONG).show()
                        } else {
                            findNavController().navigate(
                                R.id.navigation_games,
                                bundleOf("meta_system_id" to item.metaSystem.name),
                            )
                        }
                    }
                    is TVSetting -> {
                        when (item.type) {
                            TVSettingType.ALL_CONSOLES ->
                                findNavController().navigate(R.id.navigation_all_consoles)
                            TVSettingType.STOP_RESCAN ->
                                LibraryIndexScheduler.cancelLibrarySync(
                                    requireContext().applicationContext,
                                )
                            TVSettingType.RESCAN ->
                                LibraryIndexScheduler.scheduleLibrarySync(
                                    requireContext().applicationContext,
                                )
                            TVSettingType.CHOOSE_DIRECTORY -> launchFolderPicker()
                            TVSettingType.SETTINGS -> launchTVSettings()
                            TVSettingType.SHOW_ALL_FAVORITES -> launchFavorites()
                            TVSettingType.SAVE_SYNC ->
                                SaveSyncWork.enqueueManualWork(
                                    requireContext().applicationContext,
                                )
                        }
                    }
                }
            }

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        title = getString(if (showAllConsoles) R.string.alexretro_all_consoles else R.string.alexretro_choose_console)
        headersState = HEADERS_DISABLED
        brandColor = Color.rgb(20, 20, 20)
        view.setBackgroundColor(Color.rgb(20, 20, 20))
        recreateAdapter(
            includeFavorites = false,
            includeRecentGames = false,
            includeSystems = true,
        )
        setOnSearchClickedListener {
            findNavController().navigate(R.id.navigation_search)
        }

        val factory = TVHomeViewModel.Factory(retrogradeDb, requireContext().applicationContext)
        val homeViewModel = ViewModelProvider(this, factory)[TVHomeViewModel::class.java]

        launchOnState(Lifecycle.State.RESUMED) {
            homeViewModel.getViewStates()
                .collect { update(it) }
        }
    }

    private fun update(viewState: TVHomeViewModel.HomeViewState) {
        val counts = viewState.metaSystems.associateBy { it.metaSystem }
        val systems =
            if (showAllConsoles) {
                MetaSystemID.values().sortedBy { getString(it.titleResId) }
            } else {
                listOf(MetaSystemID.NES, MetaSystemID.SNES, MetaSystemID.N64, MetaSystemID.GBA)
            }
        val items: List<Any> =
            systems.map { counts[it] ?: MetaSystemInfo(it, 0) } +
                if (showAllConsoles) emptyList() else listOf(TVSetting(TVSettingType.ALL_CONSOLES))
        findAdapterById<ArrayObjectAdapter>(SYSTEM_ADAPTER)?.setItems(items, LEANBACK_MULTI_DIFF_CALLBACK)

        findAdapterById<ArrayObjectAdapter>(SETTINGS_ADAPTER)?.setItems(
            buildSettingsRowItems(viewState.indexInProgress, viewState.scanInProgress),
            LEANBACK_SETTING_DIFF_CALLBACK,
        )
    }

    private fun <T> findAdapterById(id: Long): T? {
        for (i: Int in 0 until adapter.size()) {
            val listRow = adapter.get(i) as ListRow
            if (listRow.headerItem.id == id) {
                return listRow.adapter as T
            }
        }
        return null
    }

    private fun recreateAdapter(
        includeFavorites: Boolean,
        includeRecentGames: Boolean,
        includeSystems: Boolean,
    ) {
        val result = ArrayObjectAdapter(ListRowPresenter())
        val cardSize = resources.getDimensionPixelSize(com.swordfish.lemuroid.lib.R.dimen.card_size)
        val systemsCardPadding =
            resources.getDimensionPixelSize(
                com.swordfish.lemuroid.lib.R.dimen.systems_card_padding,
            )
        val settingsCardPadding =
            resources.getDimensionPixelSize(
                com.swordfish.lemuroid.lib.R.dimen.settings_card_padding,
            )

        if (includeFavorites) {
            val presenter = ClassPresenterSelector()
            presenter.addClassPresenter(Game::class.java, GamePresenter(cardSize, gameInteractor))
            presenter.addClassPresenter(TVSetting::class.java, SettingPresenter(cardSize, settingsCardPadding))
            val favouritesItems = ArrayObjectAdapter(presenter)
            val title = resources.getString(R.string.tv_home_section_favorites)
            result.add(ListRow(HeaderItem(FAVORITES_ADAPTER, title), favouritesItems))
        }

        if (includeRecentGames) {
            val recentItems = ArrayObjectAdapter(GamePresenter(cardSize, gameInteractor))
            val title = resources.getString(R.string.tv_home_section_recents)
            result.add(ListRow(HeaderItem(RECENTS_ADAPTER, title), recentItems))
        }

        if (includeSystems) {
            val profileSize = (120 * resources.displayMetrics.density).toInt()
            val presenter = ClassPresenterSelector()
            presenter.addClassPresenter(MetaSystemInfo::class.java, SystemPresenter(profileSize, systemsCardPadding))
            presenter.addClassPresenter(TVSetting::class.java, SettingPresenter(profileSize, settingsCardPadding))
            val systemItems = ArrayObjectAdapter(presenter)
            val title = getString(R.string.alexretro_consoles)
            result.add(ListRow(HeaderItem(SYSTEM_ADAPTER, title), systemItems))
        }

        val settingsItems = ArrayObjectAdapter(SettingPresenter(cardSize, settingsCardPadding))
        settingsItems.setItems(
            buildSettingsRowItems(indexInProgress = false, scanInProgress = false),
            LEANBACK_SETTING_DIFF_CALLBACK,
        )
        val settingsTitle = resources.getString(R.string.tv_home_section_settings)
        result.add(ListRow(HeaderItem(SETTINGS_ADAPTER, settingsTitle), settingsItems))

        adapter = result
    }

    private fun buildSettingsRowItems(
        indexInProgress: Boolean,
        scanInProgress: Boolean,
    ): List<TVSetting> {
        return mutableListOf<TVSetting>().apply {
            if (saveSyncManager.isSupported() && saveSyncManager.isConfigured()) {
                add(TVSetting(TVSettingType.SAVE_SYNC, !indexInProgress))
            }
            if (scanInProgress) {
                add(TVSetting(TVSettingType.STOP_RESCAN, true))
            } else {
                add(TVSetting(TVSettingType.RESCAN, !indexInProgress))
            }

            add(TVSetting(TVSettingType.CHOOSE_DIRECTORY, !indexInProgress))
            add(TVSetting(TVSettingType.SETTINGS, !indexInProgress))
        }
    }

    private fun launchFolderPicker() {
        if (TVHelper.isSAFSupported(requireContext())) {
            StorageFrameworkPickerLauncher.pickFolder(requireContext())
        } else {
            TVFolderPickerLauncher.pickFolder(requireContext())
        }
    }

    private fun launchTVSettings() {
        startActivity(Intent(requireContext(), TVSettingsActivity::class.java))
    }

    private fun launchFavorites() {
        findNavController().navigate(R.id.navigation_favorites)
    }

    companion object {
        const val RECENTS_ADAPTER = 1L
        const val SYSTEM_ADAPTER = 2L
        const val SETTINGS_ADAPTER = 3L
        const val FAVORITES_ADAPTER = 4L

        val LEANBACK_MULTI_DIFF_CALLBACK =
            object : DiffCallback<Any>() {
                override fun areContentsTheSame(
                    oldItem: Any,
                    newItem: Any,
                ): Boolean {
                    return when {
                        (oldItem is MetaSystemInfo && newItem is MetaSystemInfo) -> oldItem == newItem
                        (oldItem is Game && newItem is Game) -> {
                            LEANBACK_GAME_DIFF_CALLBACK.areContentsTheSame(oldItem, newItem)
                        }
                        (oldItem is TVSetting && newItem is TVSetting) -> {
                            LEANBACK_SETTING_DIFF_CALLBACK.areContentsTheSame(oldItem, newItem)
                        }
                        else -> false
                    }
                }

                override fun areItemsTheSame(
                    oldItem: Any,
                    newItem: Any,
                ): Boolean {
                    return when {
                        (oldItem is MetaSystemInfo && newItem is MetaSystemInfo) ->
                            oldItem.metaSystem == newItem.metaSystem
                        (oldItem is Game && newItem is Game) -> {
                            LEANBACK_GAME_DIFF_CALLBACK.areItemsTheSame(oldItem, newItem)
                        }
                        (oldItem is TVSetting && newItem is TVSetting) -> {
                            LEANBACK_SETTING_DIFF_CALLBACK.areItemsTheSame(oldItem, newItem)
                        }
                        else -> false
                    }
                }
            }

        val LEANBACK_GAME_DIFF_CALLBACK =
            object : DiffCallback<Game>() {
                override fun areContentsTheSame(
                    oldItem: Game,
                    newItem: Game,
                ): Boolean {
                    return oldItem == newItem
                }

                override fun areItemsTheSame(
                    oldItem: Game,
                    newItem: Game,
                ): Boolean {
                    return oldItem.id == newItem.id
                }
            }

        val LEANBACK_SYSTEM_DIFF_CALLBACK =
            object : DiffCallback<MetaSystemInfo>() {
                override fun areContentsTheSame(
                    oldInfo: MetaSystemInfo,
                    newInfo: MetaSystemInfo,
                ): Boolean {
                    return oldInfo == newInfo
                }

                override fun areItemsTheSame(
                    oldInfo: MetaSystemInfo,
                    newInfo: MetaSystemInfo,
                ): Boolean {
                    return oldInfo.metaSystem.name == newInfo.metaSystem.name
                }
            }

        val LEANBACK_SETTING_DIFF_CALLBACK =
            object : DiffCallback<TVSetting>() {
                override fun areContentsTheSame(
                    oldItem: TVSetting,
                    newItem: TVSetting,
                ): Boolean {
                    return oldItem == newItem
                }

                override fun areItemsTheSame(
                    oldItem: TVSetting,
                    newItem: TVSetting,
                ): Boolean {
                    return oldItem.type == newItem.type
                }
            }
    }

    @dagger.Module
    class Module
}
