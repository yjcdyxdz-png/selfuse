package com.example.mediahub.ui.rules

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediahub.App
import com.example.mediahub.data.RuleEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RuleImportViewModel : ViewModel() {
    private val repo = App.instance.rules
    val rules = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _busy = MutableStateFlow(false); val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null); val message = _message.asStateFlow()

    fun import(uris: List<Uri>) = viewModelScope.launch {
        _busy.value = true
        var ok = 0; val errs = mutableListOf<String>()
        uris.forEach { val r = repo.import(it); ok += r.imported.size; errs += r.errors }
        _busy.value = false
        _message.value = buildString {
            append("成功导入 $ok 条规则")
            if (errs.isNotEmpty()) append("\n失败 ${errs.size} 条：\n" + errs.take(10).joinToString("\n"))
        }
    }
    fun toggle(r: RuleEntity) = viewModelScope.launch { repo.setEnabled(r.id, !r.enabled) }
    fun delete(r: RuleEntity) = viewModelScope.launch { repo.delete(r) }
    fun dismiss() { _message.value = null }
}
