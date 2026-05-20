module io.github.kotlinmania.io.core {
    requires transitive kotlin.stdlib;
    requires transitive io.github.kotlinmania.io.bytestring;

    exports io.github.kotlinmania.io;
    exports io.github.kotlinmania.io.files;
    exports io.github.kotlinmania.io.unsafe;
}
