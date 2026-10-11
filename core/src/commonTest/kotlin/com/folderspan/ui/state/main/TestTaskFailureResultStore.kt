package com.folderspan.ui.state.main

class TestTaskFailureResultStore : TaskFailureResultStore {
    private val entriesByTask = mutableMapOf<Long, MutableList<TaskRetryEntry>>()
    var fileAvailable: Boolean = true
    var loadFailuresCount: Int = 0
        private set

    override fun recordFailure(taskKey: Long, entry: TaskRetryEntry, message: String) {
        val entries = entriesByTask.getOrPut(taskKey) { mutableListOf() }
        val normalized = entry.withState(TaskRetryState.FAILURE).withFailureMessage(message)
        val index = entries.indexOfFirst { item -> item.entryKey == entry.entryKey }
        if (index >= 0) {
            entries[index] = normalized
        } else {
            entries += normalized
        }
    }

    override fun recordResolved(taskKey: Long, entry: TaskRetryEntry) {
        entriesByTask[taskKey]?.removeAll { item -> item.entryKey == entry.entryKey }
    }

    override fun loadFailures(taskKey: Long): TaskFailureLoadedResult {
        loadFailuresCount++
        return TaskFailureLoadedResult(
            entries = entriesByTask[taskKey].orEmpty().toList(),
            fileAvailable = fileAvailable,
        )
    }

    override fun replaceFailures(taskKey: Long, entries: List<TaskRetryEntry>) {
        if (entries.isEmpty()) {
            entriesByTask.remove(taskKey)
        } else {
            entriesByTask[taskKey] = entries.toMutableList()
        }
    }

    override fun deleteTask(taskKey: Long) {
        entriesByTask.remove(taskKey)
    }
}
