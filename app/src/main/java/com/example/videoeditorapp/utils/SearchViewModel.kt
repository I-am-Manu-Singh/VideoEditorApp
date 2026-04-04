package com.example.videoeditorapp.utils
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SearchViewModel : ViewModel() {
    val searchQuery = MutableLiveData<String>()

    fun updateQuery(query: String) {
        searchQuery.value = query
    }
}