module io.github.kotlinmania.io.okio {
    requires transitive kotlin.stdlib;
    requires transitive io.github.kotlinmania.io.core;
    requires transitive io.github.kotlinmania.io.bytestring;
    // okio's module is automatic, so don't require it
    // requires transitive okio;

    exports io.github.kotlinmania.io.okio;
}
