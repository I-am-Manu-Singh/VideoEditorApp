package com.example.videoeditorapp.utils

import java.util.*

/** A generic History Manager for Undo/Redo functionality. Stores snapshots of the state. */
class HistoryManager<T>(private val maxHistorySize: Int = 20) {

    private val undoStack = Stack<T>()
    private val redoStack = Stack<T>()

    /** Call this before making a change to save the current state. */
    fun saveState(state: T) {
        undoStack.push(state)
        if (undoStack.size > maxHistorySize) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    /**
     * Returns the previous state if available, and moves current state to redo stack.
     * @param currentState The state before undoing.
     */
    fun undo(currentState: T): T? {
        if (undoStack.isEmpty()) return null
        redoStack.push(currentState)
        return undoStack.pop()
    }

    /**
     * Returns the next state if available, and moves current state to undo stack.
     * @param currentState The state before redoing.
     */
    fun redo(currentState: T): T? {
        if (redoStack.isEmpty()) return null
        undoStack.push(currentState)
        return redoStack.pop()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
