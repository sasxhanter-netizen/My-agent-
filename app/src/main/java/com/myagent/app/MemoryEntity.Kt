package com.myagent.app

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.HnswIndex

@Entity
data class MemoryEntity(
    @Id var id: Long = 0,
    var content: String = "",
    var type: String = "fact",
    var timestamp: Long = System.currentTimeMillis(),
    // هذا الحقل هو سر الذكاء: سنخزن فيه "متجه" المعنى لاحقاً
    @HnswIndex(dimensions = 384) var vector: FloatArray? = null 
)