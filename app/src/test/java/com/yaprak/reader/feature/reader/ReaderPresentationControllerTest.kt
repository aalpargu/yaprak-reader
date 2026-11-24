package com.yaprak.reader.feature.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderPresentationControllerTest {
    private fun reader() = ReaderPresentationController().apply { attach("surface-1") }

    @Test fun startsReadingAndCannotOpenMenuWithoutSurface() {
        val controller = ReaderPresentationController()
        controller.openMenu(ReaderPanelKind.CONTENT)
        assertNull(controller.state.value.activePanel)
        controller.attach("surface-1")
        assertFalse(controller.state.value.controlsVisible)
        assertFalse(controller.state.value.blocksReading)
    }

    @Test fun nestedEditorReplacesWindowThenReturnsToOrigin() {
        val controller = reader()
        controller.setControls(true)
        controller.openMenu(ReaderPanelKind.CONTENT)
        val origin = controller.state.value.activePanel!!
        val effect = controller.defer(origin)!!
        assertNull(controller.state.value.activePanel)
        assertTrue(controller.state.value.blocksReading)
        assertTrue(controller.consume(effect))
        controller.finish(effect, setOf(ReaderPanelKind.BOOKMARKS))
        assertEquals(ReaderPanelKind.BOOKMARKS, controller.state.value.activePanel!!.kind)
        controller.synchronizeRequests(emptySet())
        assertReturnedParent(origin, controller.state.value.activePanel)
        controller.closeMenu(controller.state.value.activePanel!!)
        assertFalse(controller.state.value.blocksReading)
        assertTrue(controller.state.value.controlsVisible)
    }

    @Test fun staleDismissDoesNotCloseReopenedMenu() {
        val controller = reader()
        controller.openMenu(ReaderPanelKind.CONTENT)
        val first = controller.state.value.activePanel!!
        controller.closeMenu(first)
        controller.openMenu(ReaderPanelKind.CONTENT)
        val second = controller.state.value.activePanel!!
        controller.closeMenu(first)
        assertEquals(second, controller.state.value.activePanel)
        assertNull(controller.defer(first))
    }

    @Test fun deferredEffectIsSingleUseAndNoModalActionClosesOrigin() {
        val controller = reader()
        controller.openMenu(ReaderPanelKind.TOOLS)
        val effect = controller.defer(controller.state.value.activePanel!!)!!
        assertTrue(controller.consume(effect))
        assertFalse(controller.consume(effect))
        controller.finish(effect, emptySet())
        assertFalse(controller.state.value.blocksReading)
        assertFalse(controller.consume(effect))
    }

    @Test fun pauseRevokesFocusWaitingActionButKeepsOrigin() {
        val controller = reader()
        controller.openMenu(ReaderPanelKind.CONTENT)
        val origin = controller.state.value.activePanel!!
        val effect = controller.defer(origin)!!
        controller.pause()
        assertFalse(controller.consume(effect))
        controller.finish(effect, setOf(ReaderPanelKind.NOTES))
        assertEquals(origin, controller.state.value.activePanel)
    }

    @Test fun surfaceReplacementRevokesEveryOldReference() {
        val controller = reader()
        controller.openMenu(ReaderPanelKind.APPEARANCE)
        val effect = controller.defer(controller.state.value.activePanel!!)!!
        controller.attach("surface-2")
        assertFalse(controller.consume(effect))
        controller.finish(effect, setOf(ReaderPanelKind.SETTINGS))
        assertFalse(controller.state.value.blocksReading)
        assertEquals("surface-2", controller.state.value.owner)
    }

    @Test fun editorCannotBeReplacedByMenuAndPauseKeepsItsDraftReference() {
        val controller = reader()
        controller.synchronizeRequests(setOf(ReaderPanelKind.ANNOTATION))
        val editor = controller.state.value.activePanel!!
        controller.openMenu(ReaderPanelKind.TOOLS)
        controller.closeMenu(editor)
        controller.pause()
        assertEquals(editor, controller.state.value.activePanel)
    }

    @Test fun onlyTopRequestIsVisibleAndClosingItRevealsLiveParent() {
        val controller = reader()
        controller.synchronizeRequests(setOf(ReaderPanelKind.SELECTION))
        val parent = controller.state.value.activePanel!!
        controller.synchronizeRequests(linkedSetOf(ReaderPanelKind.SELECTION, ReaderPanelKind.PDF_ANNOTATION))
        assertEquals(ReaderPanelKind.PDF_ANNOTATION, controller.state.value.activePanel!!.kind)
        controller.synchronizeRequests(setOf(ReaderPanelKind.SELECTION))
        assertReturnedParent(parent, controller.state.value.activePanel)
        controller.synchronizeRequests(emptySet())
        assertFalse(controller.state.value.blocksReading)
    }

    @Test fun unchangedRequestDoesNotRecreateItsEditor() {
        val controller = reader()
        controller.synchronizeRequests(setOf(ReaderPanelKind.NOTES))
        val editor = controller.state.value.activePanel
        repeat(10) { controller.synchronizeRequests(setOf(ReaderPanelKind.NOTES)) }
        assertEquals(editor, controller.state.value.activePanel)
    }

    @Test fun acceptedTargetDropsOriginAndReturnsToReadingAfterEditorCloses() {
        val controller = reader()
        controller.setControls(true)
        controller.openMenu(ReaderPanelKind.CONTENT)
        val effect = controller.defer(controller.state.value.activePanel!!)!!
        assertTrue(controller.consume(effect))
        controller.finish(effect, setOf(ReaderPanelKind.CONTENTS))
        controller.setControls(false)
        // A storage-owning editor is never forcibly dismissed by hiding the bars.
        assertEquals(ReaderPanelKind.CONTENTS, controller.state.value.activePanel!!.kind)
        controller.synchronizeRequests(emptySet())
        assertNull(controller.state.value.activePanel)
        assertFalse(controller.state.value.blocksReading)
        assertFalse(controller.state.value.controlsVisible)
    }

    @Test fun returningToReadingRevokesFocusWaitingAction() {
        val controller = reader()
        controller.openMenu(ReaderPanelKind.TOOLS)
        val effect = controller.defer(controller.state.value.activePanel!!)!!
        controller.setControls(false)
        assertFalse(controller.consume(effect))
        controller.finish(effect, setOf(ReaderPanelKind.WORDS))
        assertFalse(controller.state.value.blocksReading)
    }

    @Test fun migratedFlagsShareThePanelStackAndKeepItsParent() {
        val controller = reader()
        controller.openMenu(ReaderPanelKind.CONTENT)
        val parent = controller.state.value.activePanel
        controller.request(ReaderPanelKind.BOOKMARKS, true)
        val editor = controller.state.value.activePanel
        controller.request(ReaderPanelKind.BOOKMARKS, true)
        assertEquals(editor, controller.state.value.activePanel)
        assertTrue(controller.hasRequest(ReaderPanelKind.BOOKMARKS))
        controller.request(ReaderPanelKind.BOOKMARKS, false)
        assertFalse(controller.hasRequest(ReaderPanelKind.BOOKMARKS))
        assertReturnedParent(parent, controller.state.value.activePanel)
    }

    @Test fun detachedRequestsCannotOpenNewWindows() {
        val controller = reader()
        controller.request(ReaderPanelKind.NOTES, true)
        controller.attach(null)
        controller.request(ReaderPanelKind.WORDS, true)
        controller.synchronizeRequests(setOf(ReaderPanelKind.NOTES))
        assertTrue(controller.state.value.blocksReading)
        assertNull(controller.state.value.activePanel)
        assertEquals(ReaderPhase.Loading, controller.state.value.phase)
    }

    @Test fun blockingFailureRevokesUnadmittedActionsAndRetainsTheAdmittedEditorForItsWriteOwner() {
        val controller = reader()
        controller.setControls(true)
        controller.openMenu(ReaderPanelKind.CONTENT)
        val deferred = controller.defer(controller.state.value.activePanel!!, ReaderPanelKind.NOTES.focusKey)!!
        controller.updateStatus(ReaderPhase.BlockingError(ReaderBlockingReason.SAVE), ReaderNoticeKind.SAVE)
        assertFalse(controller.consume(deferred))
        controller.request(ReaderPanelKind.SELECTION, true)
        controller.synchronizeRequests(setOf(ReaderPanelKind.SELECTION))
        assertNull(controller.state.value.activePanel)
        assertNotNull(controller.state.value.notice)

        controller.updateStatus(ReaderPhase.Ready, null)
        controller.request(ReaderPanelKind.NOTES, true)
        val editor = controller.state.value.activePanel
        controller.updateStatus(ReaderPhase.BlockingError(ReaderBlockingReason.SOURCE), ReaderNoticeKind.LOAD)
        controller.synchronizeRequests(setOf(ReaderPanelKind.NOTES, ReaderPanelKind.SEARCH))
        assertEquals(editor, controller.state.value.activePanel)
        assertFalse(controller.hasRequest(ReaderPanelKind.SEARCH))
        controller.openMenu(ReaderPanelKind.TOOLS)
        assertEquals(editor, controller.state.value.activePanel)
    }

    @Test fun noticeNeverOpensToolsAndRecoveryRemainsUsableWithoutOpeningSourceControls() {
        val controller = reader()
        controller.updateStatus(ReaderPhase.BlockingError(ReaderBlockingReason.SAVE), ReaderNoticeKind.SAVE)
        val notice = controller.state.value.notice
        assertNull(controller.state.value.activePanel)
        controller.setControls(true)
        controller.setControls(false)
        assertEquals(notice, controller.state.value.notice)
        controller.openMenu(ReaderPanelKind.CONTENT)
        assertNull(controller.state.value.activePanel)
        controller.openMenu(ReaderPanelKind.TOOLS)
        val recovery = controller.state.value.activePanel!!
        val retry = controller.defer(recovery)!!
        // Ordinary recomposition/status publication must not revoke an explicitly admitted recovery action.
        controller.updateStatus(ReaderPhase.BlockingError(ReaderBlockingReason.SAVE), ReaderNoticeKind.SAVE)
        assertTrue(controller.consume(retry))
        controller.finish(retry, emptySet())
        assertEquals(notice, controller.state.value.notice)
        controller.updateStatus(ReaderPhase.Ready, null)
        assertNull(controller.state.value.notice)
        assertNull(controller.state.value.activePanel)
    }

    @Test fun renderFailureAllowsOnlyPdfThumbnailRecoveryAndSourceOrSaveCannotAdmitIt() {
        val controller = reader()
        controller.openMenu(ReaderPanelKind.CONTENT)
        val deferred = controller.defer(controller.state.value.activePanel!!)!!
        controller.updateStatus(ReaderPhase.BlockingError(ReaderBlockingReason.RENDER), ReaderNoticeKind.RENDER)
        assertFalse(controller.consume(deferred))
        controller.finish(deferred, setOf(ReaderPanelKind.NOTES))
        controller.request(ReaderPanelKind.SEARCH, true)
        controller.setSelection(ReaderSelectionRef("blocked-selection"))
        assertNull(controller.state.value.activePanel)
        assertNull(controller.state.value.selection)

        controller.synchronizeRequests(linkedSetOf(ReaderPanelKind.PDF_THUMBNAILS, ReaderPanelKind.SEARCH))
        val browser = controller.state.value.activePanel!!
        assertEquals(listOf(ReaderPanelKind.PDF_THUMBNAILS), controller.state.value.panels.map { it.kind })
        controller.request(ReaderPanelKind.NOTES, true)
        assertEquals(browser, controller.state.value.activePanel)
        assertEquals(ReaderBackDecision.ClosePanel(browser), controller.back(false, false))
        controller.request(ReaderPanelKind.PDF_THUMBNAILS, false)
        assertNull(controller.state.value.activePanel)
        assertTrue(controller.state.value.blocksReading)
        controller.request(ReaderPanelKind.PDF_THUMBNAILS, true)
        assertEquals(ReaderPanelKind.PDF_THUMBNAILS, controller.state.value.activePanel!!.kind)
        controller.request(ReaderPanelKind.PDF_THUMBNAILS, false)

        listOf(ReaderBlockingReason.SOURCE to ReaderNoticeKind.LOAD,
            ReaderBlockingReason.SAVE to ReaderNoticeKind.SAVE).forEach { (reason, notice) ->
            controller.updateStatus(ReaderPhase.BlockingError(reason), notice)
            controller.request(ReaderPanelKind.PDF_THUMBNAILS, true)
            controller.synchronizeRequests(setOf(ReaderPanelKind.PDF_THUMBNAILS, ReaderPanelKind.NOTES))
            assertNull(controller.state.value.activePanel)
            assertTrue(controller.state.value.blocksReading)
        }
        controller.updateStatus(ReaderPhase.Ready, null)
        controller.request(ReaderPanelKind.SEARCH, true)
        assertEquals(ReaderPanelKind.SEARCH, controller.state.value.activePanel!!.kind)
    }

    @Test fun backFollowsImeTopPanelSelectionControlsThenTheExistingSafeExit() {
        val controller = reader()
        controller.setControls(true)
        controller.setSelection(ReaderSelectionRef("selection"))
        controller.openMenu(ReaderPanelKind.CONTENT)
        val menu = controller.state.value.activePanel!!
        assertEquals(ReaderBackDecision.HideIme, controller.back(true, true))
        assertEquals(ReaderBackDecision.ClosePanel(menu), controller.back(false, true))
        controller.closeMenu(menu)
        assertEquals(ReaderBackDecision.ClearSelection, controller.back(false, true))
        controller.setSelection(null)
        assertEquals(ReaderBackDecision.HideControls, controller.back(false, false))
        controller.setControls(false)
        assertEquals(ReaderBackDecision.Exit, controller.back(false, false))
    }

    @Test fun backCancelsFocusWaitingActionBeforeItCanOpenAChild() {
        val controller = reader()
        controller.openMenu(ReaderPanelKind.TOOLS)
        val effect = controller.defer(controller.state.value.activePanel!!)!!
        assertEquals(ReaderBackDecision.HideIme, controller.back(true, false))
        assertEquals(ReaderBackDecision.CancelDeferred, controller.back(false, false))
        controller.pause()
        assertFalse(controller.consume(effect))
        assertTrue(controller.back(false, false) is ReaderBackDecision.ClosePanel)
    }

    @Test fun childDismissProducesOneFocusReturnToItsCallerAndPauseRevokesIt() {
        val controller = reader()
        controller.setControls(true)
        controller.openMenu(ReaderPanelKind.CONTENT)
        val menu = controller.state.value.activePanel!!
        val effect = controller.defer(menu, ReaderPanelKind.BOOKMARKS.focusKey)!!
        assertTrue(controller.consume(effect))
        controller.finish(effect, setOf(ReaderPanelKind.BOOKMARKS))
        controller.synchronizeRequests(emptySet())
        val focus = controller.state.value.returnFocus!!
        assertEquals(ReaderPanelKind.BOOKMARKS.focusKey, focus.key)
        assertTrue(controller.isReturnFocusCurrent(focus))
        controller.acknowledgeReturnFocus(focus)
        assertFalse(controller.isReturnFocusCurrent(focus))
        controller.closeMenu(controller.state.value.activePanel!!)
        val footerFocus = controller.state.value.returnFocus!!
        assertEquals(ReaderPanelKind.CONTENT.focusKey, footerFocus.key)
        controller.acknowledgeReturnFocus(focus)
        assertTrue(controller.isReturnFocusCurrent(footerFocus))
        controller.pause()
        assertFalse(controller.isReturnFocusCurrent(footerFocus))
    }

    @Test fun appearanceChildRetainsItsLogicalParentAndRenewsTheExposedWindow() {
        val controller = reader()
        controller.request(ReaderPanelKind.BOOK_APPEARANCE, true)
        val parent = controller.state.value.activePanel!!
        controller.synchronizeRequests(linkedSetOf(ReaderPanelKind.BOOK_APPEARANCE, ReaderPanelKind.BOOK_THEME))
        assertEquals(ReaderPanelKind.BOOK_THEME, controller.state.value.activePanel!!.kind)
        controller.synchronizeRequests(setOf(ReaderPanelKind.BOOK_APPEARANCE))
        assertReturnedParent(parent, controller.state.value.activePanel)
        assertEquals(ReaderPanelKind.BOOK_THEME.focusKey, controller.state.value.returnFocus!!.key)
    }
    private fun assertReturnedParent(previous: ReaderPanelEntry?, current: ReaderPanelEntry?) {
        assertNotNull(previous); assertNotNull(current)
        assertEquals(previous!!.id, current!!.id)
        assertEquals(previous.kind, current.kind)
        assertEquals(previous.returnFocusKey, current.returnFocusKey)
        assertNotEquals(previous.exposureId, current.exposureId)
    }

    @Test fun returningParentKeepsItsScrollKeyButRejectsItsDisposedWindowCommands() {
        val controller = reader()
        controller.setControls(true)
        controller.openMenu(ReaderPanelKind.CONTENT)
        val retired = controller.state.value.activePanel!!
        val launch = controller.defer(retired, ReaderPanelKind.NOTES.focusKey)!!
        assertTrue(controller.consume(launch))
        controller.finish(launch, setOf(ReaderPanelKind.NOTES))
        controller.synchronizeRequests(emptySet())
        val returned = controller.state.value.activePanel!!
        assertReturnedParent(retired, returned)
        assertNull(controller.defer(retired))
        controller.closeMenu(retired)
        assertEquals(returned, controller.state.value.activePanel)
        val currentLaunch = controller.defer(returned)!!
        assertTrue(controller.consume(currentLaunch))
        controller.finish(currentLaunch, setOf(ReaderPanelKind.NOTES))
        controller.synchronizeRequests(emptySet())
        val returnedAgain = controller.state.value.activePanel!!
        assertReturnedParent(returned, returnedAgain)
        controller.closeMenu(returnedAgain)
        assertNull(controller.state.value.activePanel)
        assertFalse(controller.state.value.blocksReading)
    }

}
