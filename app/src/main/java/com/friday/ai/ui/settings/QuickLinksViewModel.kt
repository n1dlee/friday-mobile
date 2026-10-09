package com.friday.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friday.ai.core.links.QuickLink
import com.friday.ai.core.links.QuickLinks
import com.friday.ai.service.links.QuickLinkStore
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Settings → Быстрые ссылки: the owner's phrases and the sites they open. */
class QuickLinksViewModel(private val store: QuickLinkStore) : ViewModel() {

    val links: StateFlow<List<QuickLink>> = store.links

    init {
        // A settings import may have brought new ones.
        viewModelScope.launch { store.reload() }
    }

    /**
     * Saves what was typed; the reason it can't be saved otherwise.
     * [id] null adds a new link.
     */
    fun save(id: String?, name: String, phrases: String, address: String, silent: Boolean): String? {
        val said = QuickLinks.phrasesOf(phrases)
        val url = QuickLinks.webAddress(address)
        return when {
            said.isEmpty() -> "Добавьте хотя бы одну фразу"
            url == null -> "Это не похоже на адрес сайта"
            else -> {
                val title = name.trim().ifEmpty { said.first() }
                val link = QuickLink(id ?: UUID.randomUUID().toString(), title, said, url, silent)
                viewModelScope.launch { store.save(link) }
                null
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }
}
