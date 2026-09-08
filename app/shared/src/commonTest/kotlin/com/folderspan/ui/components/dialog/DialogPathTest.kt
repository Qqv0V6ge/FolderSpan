package com.folderspan.ui.components.dialog

import kotlin.test.Test
import kotlin.test.assertEquals

class DialogPathTest {
    @Test
    fun parentDisplayPathOnlyRemovesFinalComponent() {
        assertEquals("/home/webb", parentDisplayPath("/home/webb/IdeaProjects"))
        assertEquals("/home/homework", parentDisplayPath("/home/homework/home"))
        assertEquals("C:\\Users\\webb", parentDisplayPath("C:\\Users\\webb\\IdeaProjects"))
        assertEquals("/", parentDisplayPath("/IdeaProjects"))
    }
}
