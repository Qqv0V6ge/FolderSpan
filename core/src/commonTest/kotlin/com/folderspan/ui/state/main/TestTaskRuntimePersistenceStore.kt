package com.folderspan.ui.state.main

import strings.AppStrings


class TestTaskRuntimePersistenceStore : TaskRuntimePersistenceStore {
    val taskSnapshots = linkedMapOf<Long, Task>()
    val metas = mutableMapOf<Long, TaskRuntimeMeta>()
    val runStates = mutableMapOf<Long, TaskRuntimeRunState>()
    val transferCheckpoints = mutableMapOf<Pair<Long, String>, TaskRuntimeTransferCheckpoint>()
    val pendingEntries =
        mutableMapOf<Long, MutableMap<TaskRuntimeStage, MutableMap<TaskRuntimeQueueCategory, LinkedHashMap<String, MutableList<TaskRuntimeQueueEntry>>>>>()
    val corruptedQueueFiles = mutableSetOf<QueueFileKey>()
    val peekCounts = mutableMapOf<QueueFileKey, Int>()

    override fun loadTaskSnapshots(): List<Task> = taskSnapshots.values.toList()

    override fun loadTaskSnapshot(taskKey: Long): Task? = taskSnapshots[taskKey]

    override fun saveTaskSnapshot(task: Task) {
        taskSnapshots[task.key] = task
    }

    override fun deleteTaskSnapshot(taskKey: Long) {
        taskSnapshots.remove(taskKey)
    }

    override fun loadMeta(taskKey: Long): TaskRuntimeMeta? = metas[taskKey]

    override fun saveMeta(meta: TaskRuntimeMeta) {
        metas[meta.taskKey] = meta
    }

    override fun loadRunState(taskKey: Long): TaskRuntimeRunState? = runStates[taskKey]

    override fun saveRunState(runState: TaskRuntimeRunState) {
        runStates[runState.taskKey] = runState
    }

    override fun deleteRunState(taskKey: Long) {
        runStates.remove(taskKey)
    }

    override fun appendQueueEntries(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        entries: List<TaskRuntimeQueueEntry>,
    ) {
        if (entries.isEmpty()) return
        val categoryQueues = categoryQueues(taskKey, stage, category)
        var nextFileIndex = categoryQueues.keys.lastOrNull()
            ?.substringBefore('.')
            ?.toIntOrNull()
            ?.plus(1)
            ?: 1
        entries.chunked(256).forEach { chunk ->
            val fileName = nextFileIndex.toString().padStart(6, '0') + ".pb64l"
            categoryQueues[fileName] = chunk.toMutableList()
            nextFileIndex++
        }
    }

    override fun peekNextQueueEntry(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        preferredFile: String?,
    ): TaskRuntimeQueuedEntry? {
        val queueFiles = listPendingQueueFiles(taskKey, stage, category)
        if (queueFiles.isEmpty()) return null
        val selectedFile = preferredFile?.takeIf { it in queueFiles } ?: queueFiles.first()
        val key = QueueFileKey(taskKey, stage, category, selectedFile)
        peekCounts[key] = (peekCounts[key] ?: 0) + 1
        if (key in corruptedQueueFiles) {
            throw IllegalStateException(
                AppStrings.ui_test_test_task_runtime_persistence_store_queue_file_damaged_taskkey_arg0.format(arg0 = (taskKey).toString(), arg1 = (stage).toString(), arg2 = (category).toString(), arg3 = (selectedFile).toString())
            )
        }
        val entry = categoryQueues(taskKey, stage, category)[selectedFile]?.firstOrNull() ?: return null
        return TaskRuntimeQueuedEntry(selectedFile, entry)
    }

    override fun ackQueueEntry(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        fileName: String,
        entryId: String?,
    ) {
        val categoryQueues = categoryQueues(taskKey, stage, category)
        val entries = categoryQueues[fileName] ?: return
        if (entryId.isNullOrBlank() && entries.isNotEmpty()) {
            entries.removeAt(0)
        } else if (!entryId.isNullOrBlank()) {
            val index = entries.indexOfFirst { entry -> entry.entryId == entryId }
            if (index >= 0) {
                entries.removeAt(index)
            }
        }
        if (entries.isEmpty()) {
            categoryQueues.remove(fileName)
        }
    }

    override fun listPendingQueueFiles(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
    ): List<String> {
        return categoryQueues(taskKey, stage, category)
            .filterValues { entries -> entries.isNotEmpty() }
            .keys
            .sorted()
    }

    override fun loadPendingQueueEntries(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
        limit: Int,
    ): List<TaskRuntimeQueuedEntry> {
        if (limit <= 0) return emptyList()
        val result = mutableListOf<TaskRuntimeQueuedEntry>()
        categoryQueues(taskKey, stage, category)
            .filterValues { entries -> entries.isNotEmpty() }
            .entries
            .sortedBy { entry -> entry.key }
            .forEach { (fileName, entries) ->
                entries.forEach { entry ->
                    result += TaskRuntimeQueuedEntry(fileName, entry)
                    if (result.size >= limit) {
                        return result
                    }
                }
            }
        return result
    }

    override fun countPendingEntries(
        taskKey: Long,
        stage: TaskRuntimeStage?,
        category: TaskRuntimeQueueCategory?,
    ): Int {
        return when {
            stage == null -> TaskRuntimeStage.entries.sumOf { currentStage ->
                countPendingEntries(taskKey, currentStage, category)
            }

            category == null -> TaskRuntimeQueueCategory.entries.sumOf { currentCategory ->
                countPendingEntries(taskKey, stage, currentCategory)
            }

            else -> categoryQueues(taskKey, stage, category).values.sumOf { entries -> entries.size }
        }
    }

    override fun hasPendingEntries(taskKey: Long): Boolean {
        return TaskRuntimeStage.entries.any { stage ->
            TaskRuntimeQueueCategory.entries.any { category ->
                countPendingEntries(taskKey, stage, category) > 0
            }
        }
    }

    override fun loadTransferCheckpoint(taskKey: Long, entryId: String): TaskRuntimeTransferCheckpoint? {
        return transferCheckpoints[taskKey to entryId]
    }

    override fun saveTransferCheckpoint(checkpoint: TaskRuntimeTransferCheckpoint) {
        transferCheckpoints[checkpoint.taskKey to checkpoint.entryId] = checkpoint
    }

    override fun deleteTransferCheckpoint(taskKey: Long, entryId: String) {
        transferCheckpoints.remove(taskKey to entryId)
    }

    override fun clearTaskRuntime(taskKey: Long) {
        metas.remove(taskKey)
        runStates.remove(taskKey)
        pendingEntries.remove(taskKey)
        transferCheckpoints.keys.removeAll { key -> key.first == taskKey }
    }

    private fun categoryQueues(
        taskKey: Long,
        stage: TaskRuntimeStage,
        category: TaskRuntimeQueueCategory,
    ): LinkedHashMap<String, MutableList<TaskRuntimeQueueEntry>> {
        val taskQueues = pendingEntries.getOrPut(taskKey) { mutableMapOf() }
        val stageQueues = taskQueues.getOrPut(stage) { mutableMapOf() }
        return stageQueues.getOrPut(category) { linkedMapOf() }
    }

    data class QueueFileKey(
        val taskKey: Long,
        val stage: TaskRuntimeStage,
        val category: TaskRuntimeQueueCategory,
        val fileName: String,
    )
}
