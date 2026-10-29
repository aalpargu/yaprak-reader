# Readium and Room ship their own consumer rules. Add only verified app-specific rules here.

# PDFBox metadata parsing does not use its optional JP2Android image decoder.
# JPXFilter checks Class.forName and throws MissingImageReaderException when absent;
# the actual dependency path is exercised by the optional-jpx verification probe.
-dontwarn com.gemalto.jp2.JP2Decoder
