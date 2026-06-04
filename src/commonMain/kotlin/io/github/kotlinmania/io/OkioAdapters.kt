package io.github.kotlinmania.io

@Deprecated(
    message = "Okio integration has been removed from km-io. Use km-io native RawSource directly.",
    level = DeprecationLevel.ERROR,
)
public fun RawSource.asOkioSource(): Any = throw NotImplementedError("Okio integration has been removed")

@Deprecated(
    message = "Okio integration has been removed from km-io. Use km-io native RawSink directly.",
    level = DeprecationLevel.ERROR,
)
public fun RawSink.asOkioSink(): Any = throw NotImplementedError("Okio integration has been removed")

@Deprecated(
    message = "Okio integration has been removed from km-io. Use km-io native ByteString directly.",
    level = DeprecationLevel.ERROR,
)
public fun ByteString.toOkioByteString(): Any = throw NotImplementedError("Okio integration has been removed")
