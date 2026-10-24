package com.myagent.app

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class MemoryEntity(
    @Id var id: Long = 0,
    var content: String = "",
    var type: String = "fact", // النوع: fact (معلومة) أو lesson (درس)
    var timestamp: Long = System.currentTimeMillis()
)