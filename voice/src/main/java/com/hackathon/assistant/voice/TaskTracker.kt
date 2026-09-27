package com.hackathon.assistant.voice

import com.hackathon.assistant.core.StepStatus
import com.hackathon.assistant.core.TaskProgress
import com.hackathon.assistant.core.TaskStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The current task's steps, as a [TaskProgress] the agent/pipeline write and the card reads. */
class TaskTracker : TaskProgress {
    private val _steps = MutableStateFlow<List<TaskStep>>(emptyList())
    val steps: StateFlow<List<TaskStep>> = _steps
    private val _active = MutableStateFlow(false)
    /** True from [start] to [finish]: the card stays up for the whole task. */
    val active: StateFlow<Boolean> = _active
    private var nextId = 1

    @Synchronized
    override fun start(request: String) {
        _steps.value = emptyList()
        _active.value = true
    }

    @Synchronized
    override fun step(title: String, detail: String, status: StepStatus): Int {
        val id = nextId++
        _steps.value = _steps.value.map { if (it.status == StepStatus.RUNNING) it.copy(status = StepStatus.DONE) else it } +
            TaskStep(id, title, detail, status)
        return id
    }

    @Synchronized
    override fun update(id: Int, status: StepStatus, title: String?, detail: String?) {
        _steps.value = _steps.value.map {
            if (it.id == id) it.copy(status = status, title = title ?: it.title, detail = detail ?: it.detail) else it
        }
    }

    @Synchronized
    override fun finish(success: Boolean) {
        val end = if (success) StepStatus.DONE else StepStatus.FAILED
        _steps.value = _steps.value.map { if (it.status == StepStatus.RUNNING || it.status == StepStatus.WAITING_FOR_USER) it.copy(status = end) else it }
        _active.value = false
    }

    /** Clears the steps (a new conversation). */
    @Synchronized
    fun clear() {
        _steps.value = emptyList()
        _active.value = false
    }
}
