package com.yaprak.reader.reader

enum class ContentsLoadFailure { LIMIT, UNSUPPORTED, SOURCE }
class ContentsLoadException(val reason: ContentsLoadFailure) : Exception(reason.name)
