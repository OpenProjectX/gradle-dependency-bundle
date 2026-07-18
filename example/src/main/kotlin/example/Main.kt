package example

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec

fun main() {
    val type = TypeSpec.objectBuilder("Bundled").build()
    println(FileSpec.builder("example.generated", "Bundled").addType(type).build())
}
