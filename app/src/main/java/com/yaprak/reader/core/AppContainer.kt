package com.yaprak.reader.core

import android.content.Context
import androidx.room.Room
import com.yaprak.reader.covers.BookCoverLoader
import com.yaprak.reader.data.covers.LocalBookCoverLoader
import com.yaprak.reader.data.covers.LocalCoverFiles
import com.yaprak.reader.data.covers.VerifiedCoverFile
import com.yaprak.reader.data.covers.AndroidCoverDocumentProvider
import com.yaprak.reader.data.covers.DefaultBookCoverEditor
import com.yaprak.reader.domain.library.BookCoverEditor
import com.yaprak.reader.domain.library.BookDiscovery
import com.yaprak.reader.data.discovery.DefaultBookDiscovery
import com.yaprak.reader.data.discovery.AndroidDiscoveryDocumentProvider
import com.yaprak.reader.data.importing.AndroidDocumentProvider
import com.yaprak.reader.data.importing.DefaultBookImporter
import com.yaprak.reader.data.persistence.LibraryDatabase
import com.yaprak.reader.data.persistence.RoomLibraryRepository
import com.yaprak.reader.data.persistence.RoomReadingStateRepository
import com.yaprak.reader.data.storage.LocalBookFiles
import com.yaprak.reader.data.storage.BookOperationCoordinator
import com.yaprak.reader.data.backup.AndroidBackupDocumentProvider
import com.yaprak.reader.data.backup.DefaultLibraryBackup
import com.yaprak.reader.data.removing.DefaultBookRemover
import com.yaprak.reader.domain.library.BookFormat
import com.yaprak.reader.domain.library.BookImporter
import com.yaprak.reader.domain.library.BookRemover
import com.yaprak.reader.domain.library.LibraryRepository
import com.yaprak.reader.formats.BookMetadataReader
import com.yaprak.reader.formats.epub.EpubMetadataReader
import com.yaprak.reader.formats.epub.EpubReaderLoader
import com.yaprak.reader.domain.reader.ReadingStateRepository
import com.yaprak.reader.reader.ReaderLoader
import com.yaprak.reader.formats.pdf.PdfMetadataReader
import com.yaprak.reader.formats.pdf.PdfReaderLoader
import com.yaprak.reader.reader.FormatReaderLoader
import java.io.File
import com.yaprak.reader.data.settings.DataStoreEpubSettings
import com.yaprak.reader.domain.settings.EpubSettingsRepository
import com.yaprak.reader.domain.settings.ScreenAwakeSettingsRepository
import com.yaprak.reader.domain.settings.ReaderOrientationSettingsRepository
import com.yaprak.reader.domain.settings.PdfNightSettingsRepository
import com.yaprak.reader.domain.library.LibraryPreferencesRepository
import com.yaprak.reader.domain.bookmarks.BookmarkRepository
import com.yaprak.reader.data.persistence.RoomBookmarkRepository
import com.yaprak.reader.data.persistence.RoomBookThemeRepository
import com.yaprak.reader.domain.settings.BookThemeRepository
import com.yaprak.reader.data.persistence.RoomBookFontRepository
import com.yaprak.reader.domain.settings.BookFontRepository
import com.yaprak.reader.domain.settings.BookTextAlignmentRepository
import com.yaprak.reader.domain.settings.BookReadingModeRepository
import com.yaprak.reader.data.persistence.RoomBookReadingModeRepository
import com.yaprak.reader.data.persistence.RoomBookTextAlignmentRepository
import com.yaprak.reader.data.persistence.RoomBookFontSizeRepository
import com.yaprak.reader.domain.settings.BookFontSizeRepository
import com.yaprak.reader.data.persistence.RoomBookMarginsRepository
import com.yaprak.reader.domain.settings.BookMarginsRepository
import com.yaprak.reader.data.persistence.RoomBookLineHeightRepository
import com.yaprak.reader.domain.settings.BookLineHeightRepository
import com.yaprak.reader.domain.settings.BookSpacingRepository
import com.yaprak.reader.data.persistence.RoomBookSpacingRepository
import com.yaprak.reader.data.persistence.RoomBookAppearanceResetRepository
import com.yaprak.reader.domain.settings.BookAppearanceResetRepository
import com.yaprak.reader.data.persistence.RoomSavedWordRepository
import com.yaprak.reader.domain.vocabulary.SavedWordRepository
import com.yaprak.reader.data.persistence.RoomBookNoteRepository
import com.yaprak.reader.domain.notes.BookNoteRepository
import com.yaprak.reader.domain.notes.LibraryNotesRepository
import com.yaprak.reader.data.persistence.RoomLibraryNotesRepository
import com.yaprak.reader.data.exporting.AndroidExportDocumentProvider
import com.yaprak.reader.data.exporting.DefaultBookExporter
import com.yaprak.reader.data.exporting.DefaultBookStudyExporter
import com.yaprak.reader.data.exporting.AndroidStudyPdfRenderer
import com.yaprak.reader.data.exporting.RoomBookStudySource
import com.yaprak.reader.data.storage.VerifiedBookFile
import com.yaprak.reader.domain.library.BookExporter
import com.yaprak.reader.domain.library.BookStudyExporter
import com.yaprak.reader.domain.backup.LibraryBackup
import com.yaprak.reader.data.persistence.RoomEpubPassageRepository
import com.yaprak.reader.domain.passages.EpubPassageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.withLock

/** Small, explicit composition root. Introduce a DI framework only if it earns its cost. */
class AppContainer(context: Context) {
    private val sharedSettings = DataStoreEpubSettings(context)
    val settings: EpubSettingsRepository = sharedSettings
    val tapZones: com.yaprak.reader.domain.settings.TapZoneSettingsRepository = sharedSettings
    val volumeKeys: com.yaprak.reader.domain.settings.VolumeKeySettingsRepository = sharedSettings
    val screenAwake: ScreenAwakeSettingsRepository = sharedSettings
    val readerOrientation: ReaderOrientationSettingsRepository = sharedSettings
    val pdfNightSettings: PdfNightSettingsRepository = sharedSettings
    val libraryPreferences: LibraryPreferencesRepository = sharedSettings
    val bookFolders: com.yaprak.reader.domain.library.BookFolderRepository =
        com.yaprak.reader.data.discovery.PersistentBookFolderRepository(context)
    val weeklyReadingGoal: com.yaprak.reader.domain.statistics.WeeklyReadingGoalRepository = sharedSettings
    val readingGoal: com.yaprak.reader.domain.statistics.DailyReadingGoalRepository = sharedSettings
    val englishDictionary: com.yaprak.reader.domain.dictionary.EnglishDictionary by lazy {
        com.yaprak.reader.data.dictionary.OnlineEnglishDictionary()
    }
    val englishTurkishTranslation: com.yaprak.reader.domain.translation.EnglishTurkishTranslation by lazy {
        com.yaprak.reader.data.translation.LocalEnglishTurkishTranslation(com.yaprak.reader.data.translation.MlKitEnglishTurkishBackend())
    }
    private val answerService by lazy {
        com.yaprak.reader.data.assistant.HttpBookAnswerService()
    }
    val bookAnswers: com.yaprak.reader.domain.assistant.BookAnswerService get() = answerService
    val selectedTextAnswers: com.yaprak.reader.domain.assistant.SelectedTextAnswerService get() = answerService
    private val database = Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
        .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4, LibraryDatabase.MIGRATION_4_5, LibraryDatabase.MIGRATION_5_6, LibraryDatabase.MIGRATION_6_7, LibraryDatabase.MIGRATION_7_8, LibraryDatabase.MIGRATION_8_9, LibraryDatabase.MIGRATION_9_10, LibraryDatabase.MIGRATION_10_11, LibraryDatabase.MIGRATION_11_12, LibraryDatabase.MIGRATION_12_13, LibraryDatabase.MIGRATION_13_14, LibraryDatabase.MIGRATION_14_15, LibraryDatabase.MIGRATION_15_16, LibraryDatabase.MIGRATION_16_17, LibraryDatabase.MIGRATION_17_18, LibraryDatabase.MIGRATION_18_19, LibraryDatabase.MIGRATION_19_20, LibraryDatabase.MIGRATION_20_21, LibraryDatabase.MIGRATION_21_22, LibraryDatabase.MIGRATION_22_23, LibraryDatabase.MIGRATION_23_24, LibraryDatabase.MIGRATION_24_25, LibraryDatabase.MIGRATION_25_26).build()
    val library: LibraryRepository = RoomLibraryRepository(database.books())
    val libraryLabels: com.yaprak.reader.domain.library.LibraryLabelsRepository by lazy {
        com.yaprak.reader.data.persistence.RoomLibraryLabelsRepository(database,
            fileMutex = bookOperations.fileMutex, validateAccess = {
                if (startup.state.value != StartupState.Ready || restoreJournal.exists())
                    throw com.yaprak.reader.domain.library.LibraryLabelException(
                        com.yaprak.reader.domain.library.LibraryLabelFailure.RECOVERY)
            })
    }
    val discovery: BookDiscovery = DefaultBookDiscovery(AndroidDiscoveryDocumentProvider(context.contentResolver))
    val reading: ReadingStateRepository = RoomReadingStateRepository(database)
    val bookmarks: BookmarkRepository = RoomBookmarkRepository(database)
    val bookThemes: BookThemeRepository = RoomBookThemeRepository(database)
    val bookFontSizes: BookFontSizeRepository = RoomBookFontSizeRepository(database)
    val bookFonts: BookFontRepository = RoomBookFontRepository(database)
    val bookTextAlignments: BookTextAlignmentRepository = RoomBookTextAlignmentRepository(database)
    val bookReadingModes: BookReadingModeRepository = RoomBookReadingModeRepository(database)
    val bookMargins: BookMarginsRepository = RoomBookMarginsRepository(database)
    val bookLineHeights: BookLineHeightRepository = RoomBookLineHeightRepository(database)
    val bookSpacings: BookSpacingRepository = RoomBookSpacingRepository(database)
    val bookAppearanceReset: BookAppearanceResetRepository = RoomBookAppearanceResetRepository(database)
    val savedWords: SavedWordRepository = RoomSavedWordRepository(database)
    val bookNotes: BookNoteRepository = RoomBookNoteRepository(database)
    private val cbrCache = com.yaprak.reader.formats.cbr.CbrPublicationCache(context.cacheDir)
    private val bookFiles = LocalBookFiles(File(context.filesDir, "books"))
    private val bookOperations = BookOperationCoordinator()
    private val coverDirectory = File(context.filesDir, "covers")
    private val coverFiles = LocalCoverFiles(coverDirectory)
    val coverEditor: BookCoverEditor = DefaultBookCoverEditor(
        database, coverFiles, AndroidCoverDocumentProvider(context.contentResolver), bookOperations,
    )
    val covers: BookCoverLoader = LocalBookCoverLoader(
        context, VerifiedBookFile(File(context.filesDir, "books")),
        customFiles = VerifiedCoverFile(coverDirectory), operations = bookOperations, cbrCache = cbrCache,
    )
    val remover: BookRemover = DefaultBookRemover(database, bookFiles, bookOperations, covers = coverFiles)
    val studyExporter: BookStudyExporter = DefaultBookStudyExporter(
        source = RoomBookStudySource(database, validateRead = {
            check(startup.state.value == StartupState.Ready && !restoreJournal.exists()) {
                "Study records require completed restore recovery"
            }
        }),
        documents = AndroidExportDocumentProvider(context.contentResolver),
        fileMutex = bookOperations.fileMutex,
        pdfRenderer = AndroidStudyPdfRenderer(),
    )
    val exporter: BookExporter = DefaultBookExporter(
        library = library,
        sourceFiles = VerifiedBookFile(File(context.filesDir, "books")),
        documents = AndroidExportDocumentProvider(context.contentResolver),
        fileMutex = bookOperations.fileMutex,
    )
    private val restoreJournal = File(context.filesDir, "backup/restore.pending")
    private val backupEngine: LibraryBackup = DefaultLibraryBackup(
        database = database,
        booksDirectory = File(context.filesDir, "books"),
        stagingDirectory = File(context.cacheDir, "backup-staging"),
        journalFile = restoreJournal,
        settings = settings,
        documents = AndroidBackupDocumentProvider(context.contentResolver),
        fileMutex = bookOperations.fileMutex,
        coversDirectory = coverDirectory, cbrCache = cbrCache,
    )
    val startup = StartupGate(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        recoverRestore = backupEngine::recoverPendingRestore,
        cleanBookFiles = {
            bookOperations.fileMutex.withLock {
                bookFiles.recover(library.storedFileNames())
                coverFiles.recover(database.bookCovers().storedFileNames())
                com.yaprak.reader.formats.txt.TxtDerivedFiles(File(context.cacheDir, "txt-viewing")).recoverAbandoned()
                com.yaprak.reader.formats.fb2.Fb2DerivedFiles(File(context.cacheDir, "fb2-viewing")).recoverAbandoned()
                com.yaprak.reader.formats.docx.DocxDerivedFiles(File(context.cacheDir, "docx-viewing")).recoverAbandoned()
            }
        },
    )
    private val libraryNotesSource = RoomLibraryNotesRepository(
        database = database,
        fileMutex = bookOperations.fileMutex,
        validateRead = {
            // Called on IO after acquiring the shared file mutex. Never await startup here:
            // startup cleanup uses this same mutex, and a restore may have just finished.
            check(startup.state.value == StartupState.Ready && !restoreJournal.exists()) {
                "Library notes require completed restore recovery"
            }
        },
    )
    val libraryNotes: LibraryNotesRepository = libraryNotesSource
    val libraryNotesExporter: com.yaprak.reader.domain.notes.LibraryNotesExporter =
        com.yaprak.reader.data.exporting.DefaultLibraryNotesExporter(libraryNotesSource,
            AndroidExportDocumentProvider(context.contentResolver),
            pdfRenderer = com.yaprak.reader.data.exporting.AndroidLibraryNotesPdfRenderer())
    val wordReviews: com.yaprak.reader.domain.vocabulary.WordReviewRepository =
        com.yaprak.reader.data.persistence.RoomWordReviewRepository(database,
            fileMutex = bookOperations.fileMutex, validateAccess = {
                check(startup.state.value == StartupState.Ready && !restoreJournal.exists())
            })
    val libraryWords: com.yaprak.reader.domain.vocabulary.LibraryWordsRepository =
        com.yaprak.reader.data.persistence.RoomLibraryWordsRepository(database,
            fileMutex = bookOperations.fileMutex, validateRead = {
                check(startup.state.value == StartupState.Ready && !restoreJournal.exists()) {
                    "Library vocabulary requires completed restore recovery"
                }
            })
    private val textIndexDatabase by lazy {
        Room.databaseBuilder(context, com.yaprak.reader.data.search.TextIndexDatabase::class.java,
            "library-text-index.db").addMigrations(com.yaprak.reader.data.search.TextIndexDatabase.MIGRATION_1_2).build()
    }
    private val localLibraryTextIndex by lazy {
        com.yaprak.reader.data.search.LocalLibraryTextIndex(
            textIndexDatabase, library, com.yaprak.reader.data.search.LocalBookTextExtractor(context),
            fileMutex = bookOperations.fileMutex,
            validateRead = {
                if (startup.state.value != StartupState.Ready || restoreJournal.exists()) {
                    throw com.yaprak.reader.domain.search.LibraryTextIndexException(
                        com.yaprak.reader.domain.search.LibraryTextIndexFailure.RECOVERY)
                }
            },
        )
    }
    val libraryTextIndex: com.yaprak.reader.domain.search.LibraryTextIndex get() = localLibraryTextIndex
    val bookSources: com.yaprak.reader.domain.search.BookSourceRetriever get() = localLibraryTextIndex
    val selectedTextSources: com.yaprak.reader.domain.assistant.SelectedTextSourceProvider by lazy {
        val epub = com.yaprak.reader.formats.epub.EpubSelectedTextSources(context)
        val pdf = com.yaprak.reader.formats.pdf.PdfSelectedTextSources(context)
        com.yaprak.reader.data.assistant.LocalSelectedTextSources(library,
            com.yaprak.reader.data.assistant.SelectedTextOriginalSource { book, request ->
                when (book.format) {
                    com.yaprak.reader.domain.library.BookFormat.EPUB -> epub.read(book, request)
                    com.yaprak.reader.domain.library.BookFormat.PDF -> pdf.read(book, request)
                    else -> com.yaprak.reader.domain.assistant.failAnswer(com.yaprak.reader.domain.assistant.BookAnswerFailure.SOURCE)
                }
            }, bookOperations.fileMutex, validateAccess = {
                if (startup.state.value != StartupState.Ready || restoreJournal.exists())
                    com.yaprak.reader.domain.assistant.failAnswer(com.yaprak.reader.domain.assistant.BookAnswerFailure.SOURCE)
            })
    }
    val bookCompletions: com.yaprak.reader.domain.statistics.BookCompletionRepository by lazy {
        com.yaprak.reader.data.persistence.RoomBookCompletionRepository(database, bookOperations.fileMutex,
            validateAccess = {
                if (startup.state.value != StartupState.Ready || restoreJournal.exists())
                    throw com.yaprak.reader.domain.statistics.BookCompletionException(
                        com.yaprak.reader.domain.statistics.BookCompletionFailure.RECOVERY)
            })
    }
    val readingDuration: com.yaprak.reader.domain.statistics.ReadingDurationRepository by lazy {
        com.yaprak.reader.data.persistence.RoomReadingDurationRepository(
            database = database,
            fileMutex = bookOperations.fileMutex,
            validateAccess = {
                if (startup.state.value != StartupState.Ready || restoreJournal.exists()) {
                    throw com.yaprak.reader.domain.statistics.ReadingDurationException(
                        com.yaprak.reader.domain.statistics.ReadingDurationFailure.RECOVERY)
                }
            },
        )
    }
    val bookPdfViewPreferences: com.yaprak.reader.domain.settings.BookPdfViewPreferencesRepository by lazy {
        com.yaprak.reader.data.persistence.RoomBookPdfViewPreferencesRepository(
            database = database,
            fileMutex = bookOperations.fileMutex,
            validateAccess = {
                if (startup.state.value != StartupState.Ready || restoreJournal.exists()) {
                    throw com.yaprak.reader.domain.settings.BookPdfViewPreferencesException(
                        com.yaprak.reader.domain.settings.BookPdfViewPreferencesFailure.SOURCE)
                }
            },
        )
    }
    val bookComicViewPreferences: com.yaprak.reader.domain.settings.BookComicViewPreferencesRepository by lazy {
        com.yaprak.reader.data.persistence.RoomBookComicViewPreferencesRepository(
            database = database,
            fileMutex = bookOperations.fileMutex,
            validateAccess = {
                if (startup.state.value != StartupState.Ready || restoreJournal.exists()) {
                    throw com.yaprak.reader.domain.settings.BookComicViewPreferencesException(
                        com.yaprak.reader.domain.settings.BookComicViewPreferencesFailure.SOURCE)
                }
            },
        )
    }
    val pdfAnnotations: com.yaprak.reader.domain.pdfannotations.PdfAnnotationRepository by lazy {
        com.yaprak.reader.data.persistence.RoomPdfAnnotationRepository(database,
            com.yaprak.reader.formats.pdf.PdfOriginalAnnotations(File(context.filesDir, "books")),
            bookOperations.fileMutex, validateAccess = {
                check(startup.state.value == StartupState.Ready && !restoreJournal.exists()) {
                    "PDF annotations require completed restore recovery"
                }
            })
    }
    val epubPassages: EpubPassageRepository = RoomEpubPassageRepository(
        database = database,
        fileMutex = bookOperations.fileMutex,
        validateAccess = {
            check(startup.state.value == StartupState.Ready && !restoreJournal.exists()) {
                "EPUB passages require completed restore recovery"
            }
        },
    )
    val backup: LibraryBackup = RecoveryGatedLibraryBackup(backupEngine, startup)
    val reader: ReaderLoader by lazy {
        val ocr = com.yaprak.reader.formats.pdf.BundledLatinPdfOcrRecognizer()
        val tracked = bookOperations.track(FormatReaderLoader(library,
            EpubReaderLoader(context, library, reading, epubPassages), PdfReaderLoader(context, library, reading, pdfAnnotations,
                ocr, bookOperations.fileMutex, { expected ->
                    check(startup.state.value == StartupState.Ready && !restoreJournal.exists())
                    check(library.findById(expected.id) == expected)
                }), com.yaprak.reader.formats.txt.TxtReaderLoader(context, library, reading, bookOperations.fileMutex, {
                    check(startup.state.value == StartupState.Ready && !restoreJournal.exists())
                }), fb2 = com.yaprak.reader.formats.fb2.Fb2ReaderLoader(context, library, reading, bookOperations.fileMutex, {
                    check(startup.state.value == StartupState.Ready && !restoreJournal.exists())
                }), docx = com.yaprak.reader.formats.docx.DocxReaderLoader(context, library, reading, bookOperations.fileMutex, {
                    check(startup.state.value == StartupState.Ready && !restoreJournal.exists())
                }), cbz = com.yaprak.reader.formats.cbz.CbzReaderLoader(context, library, reading, bookOperations.fileMutex, {
                    check(startup.state.value == StartupState.Ready && !restoreJournal.exists())
                }, cbrCache = cbrCache)))
        ReaderLoader { bookId -> startup.awaitReady(); tracked.open(bookId) }
    }
    private val epub = EpubMetadataReader(context.contentResolver)
    private val pdf = PdfMetadataReader()
    val importer: BookImporter = DefaultBookImporter(
        documents = AndroidDocumentProvider(context.contentResolver),
        files = bookFiles,
        metadata = BookMetadataReader { file, format ->
            when (format) {
                BookFormat.EPUB -> epub.read(file)
                BookFormat.PDF -> pdf.read(file)
                BookFormat.TXT -> { com.yaprak.reader.formats.txt.StrictTxt.read(file); com.yaprak.reader.formats.BookMetadata() }
                BookFormat.CBZ -> com.yaprak.reader.formats.cbz.CbzMetadataReader().read(file)
                BookFormat.CBR -> com.yaprak.reader.formats.cbr.CbrMetadataReader(cbrCache).read(file)
                BookFormat.FB2 -> com.yaprak.reader.formats.fb2.Fb2MetadataReader().read(file)
                BookFormat.DOCX -> com.yaprak.reader.formats.docx.DocxMetadataReader().read(file)
            }
        },
        library = library,
        mutex = bookOperations.fileMutex,
    )

}
