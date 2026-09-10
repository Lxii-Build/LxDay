package com.linxi.diary.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenSnapshotPolicyTest {
    @Test
    fun `newer or equal room revisions are accepted`() {
        assertTrue(shouldApplyListenSnapshot(currentRevision = 0L, incomingRevision = 0L))
        assertTrue(shouldApplyListenSnapshot(currentRevision = 3L, incomingRevision = 3L))
        assertTrue(shouldApplyListenSnapshot(currentRevision = 3L, incomingRevision = 4L))
    }

    @Test
    fun `older and legacy snapshots cannot roll back a versioned room`() {
        assertFalse(shouldApplyListenSnapshot(currentRevision = 4L, incomingRevision = 3L))
        assertFalse(shouldApplyListenSnapshot(currentRevision = 4L, incomingRevision = 0L))
    }
}
