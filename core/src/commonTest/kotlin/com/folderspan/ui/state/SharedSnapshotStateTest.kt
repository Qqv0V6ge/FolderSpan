package com.folderspan.ui.state

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedSnapshotStateTest {
    private class State {
        val scalar = sharedSnapshotState { mutableStateOf(0) }
        val primitive = sharedSnapshotState { mutableIntStateOf(0) }
        val list = sharedSnapshotState { mutableStateListOf<Int>() }
        val map = sharedSnapshotState { mutableStateMapOf<Int, String>() }
    }

    @Test
    fun initialValuesAreReadableFromAnOlderSnapshot() {
        val reader = Snapshot.takeMutableSnapshot()
        try {
            val state = State()
            reader.enter { assertInitialValues(state) }
        } finally {
            reader.dispose()
        }
    }

    @Test
    fun initializationInsideAReadOnlySnapshotPreservesTheReader() {
        val reader = Snapshot.takeSnapshot()
        try {
            val state = reader.enter {
                State().also {
                    assertSame(reader, Snapshot.current)
                    assertInitialValues(it)
                }
            }
            assertInitialValues(state)
        } finally {
            reader.dispose()
        }
    }

    @Test
    fun initialStateSurvivesDisposingTheConstructingSnapshot() {
        val constructing = Snapshot.takeMutableSnapshot()
        val state = try {
            constructing.enter { State() }
        } finally {
            constructing.dispose()
        }
        assertInitialValues(state)
    }

    @Test
    fun laterUpdatesRemainIsolatedUntilApplied() {
        val state = State()
        val reader = Snapshot.takeSnapshot()
        val writer = Snapshot.takeMutableSnapshot()
        try {
            writer.enter { updateValues(state) }
            assertInitialValues(state)
            writer.apply().check()
            assertUpdatedValues(state)
            reader.enter { assertInitialValues(state) }
        } finally {
            writer.dispose()
            reader.dispose()
        }
    }

    @Test
    fun initializationDoesNotPublishOtherChangesInTheParentSnapshot() {
        val existing = mutableStateOf(0)
        val parent = Snapshot.takeMutableSnapshot()
        try {
            val state = parent.enter {
                existing.value = 1
                State().also { assertSame(parent, Snapshot.current) }
            }
            assertEquals(0, existing.value)
            assertInitialValues(state)
            parent.apply().check()
            assertEquals(1, existing.value)
        } finally {
            parent.dispose()
        }
    }

    @Test
    fun laterUpdatesStillNotifySnapshotObservers() {
        val state = State()
        val observed = mutableListOf<Any>()
        val observer = Snapshot.registerApplyObserver { changes, _ -> observed.addAll(changes) }
        try {
            Snapshot.withMutableSnapshot { updateValues(state) }
            assertUpdatedValues(state)
            assertTrue(observed.any { it === state.scalar })
            assertTrue(observed.any { it === state.primitive })
            assertTrue(observed.any { it === state.list })
            assertTrue(observed.any { it === state.map })
        } finally {
            observer.dispose()
        }
    }

    private fun assertInitialValues(state: State) {
        assertEquals(0, state.scalar.value)
        assertEquals(0, state.primitive.intValue)
        assertTrue(state.list.isEmpty())
        assertTrue(state.map.isEmpty())
    }

    private fun updateValues(state: State) {
        state.scalar.value = 1
        state.primitive.intValue = 2
        state.list.add(3)
        state.map[4] = "four"
    }

    private fun assertUpdatedValues(state: State) {
        assertEquals(1, state.scalar.value)
        assertEquals(2, state.primitive.intValue)
        assertEquals(listOf(3), state.list.toList())
        assertEquals(mapOf(4 to "four"), state.map.toMap())
    }
}
