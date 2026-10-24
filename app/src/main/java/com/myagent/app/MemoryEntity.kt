package com.myagent.app

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.HnswIndex

@Entity
class MemoryEntity(
    @Id var id: Long = 0,
    var content: String = "",
    var type: String = "fact",
    var timestamp: Long = System.currentTimeMillis(),
    @HnswIndex(dimensions = 768) var embedding: FloatArray? = null
)
