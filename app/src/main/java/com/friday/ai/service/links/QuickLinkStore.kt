package com.friday.ai.service.links

import com.friday.ai.core.links.QuickLink
import com.friday.ai.core.links.QuickLinks
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The owner's quick links, kept on the phone with the other settings (and
 * so in the encrypted settings export). Held in memory as well: a phrase is
 * matched before anything else, on every request, without a database read.
 */
class QuickLinkStore(private val prefs: UserPreferenceDao) {

    companion object {
        const val PREF_QUICK_LINKS = "quick_links"
    }

    private val _links = MutableStateFlow<List<QuickLink>>(emptyList())
    val links: StateFlow<List<QuickLink>> = _links.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { reload() }
    }

    /** Re-reads the stored list (after a settings import, say). */
    suspend fun reload() {
        _links.value = QuickLinks.decode(prefs.get(PREF_QUICK_LINKS))
    }

    /** The link [said] calls for, if any. */
    fun match(said: String): QuickLink? = QuickLinks.match(said, _links.value)

    /** Adds [link], or replaces the one with its id. */
    suspend fun save(link: QuickLink) = store(_links.value.filterNot { it.id == link.id } + link)

    suspend fun delete(id: String) = store(_links.value.filterNot { it.id == id })

    private suspend fun store(links: List<QuickLink>) {
        prefs.set(UserPreferenceEntity(PREF_QUICK_LINKS, QuickLinks.encode(links)))
        _links.value = links
    }
}
