// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.log.ui.frame

import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.DiffPreviewProvider
import com.intellij.openapi.vcs.changes.PreviewDiffVirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.vcs.log.impl.CommonUiProperties
import com.intellij.vcs.log.impl.VcsLogUiProperties
import com.intellij.vcs.log.impl.VcsLogUiProperties.PropertiesChangeListener
import com.intellij.vcs.log.impl.VcsLogUiProperties.VcsLogUiProperty
import javax.swing.JComponent

fun toggleDiffPreviewOnPropertyChange(uiProperties: VcsLogUiProperties,
                                      parent: Disposable,
                                      showDiffPreview: (Boolean) -> Unit) {
  val propertiesChangeListener: PropertiesChangeListener = object : PropertiesChangeListener {
    override fun <T> onPropertyChanged(property: VcsLogUiProperty<T>) {
      if (CommonUiProperties.SHOW_DIFF_PREVIEW == property) {
        showDiffPreview(uiProperties.get(CommonUiProperties.SHOW_DIFF_PREVIEW))
      }
    }
  }
  uiProperties.addChangeListener(propertiesChangeListener)
  Disposer.register(parent, Disposable { uiProperties.removeChangeListener(propertiesChangeListener) })
}

abstract class FrameDiffPreview<D : DiffRequestProcessor>(protected val previewDiff: D,
                                                          uiProperties: VcsLogUiProperties,
                                                          mainComponent: JComponent,
                                                          splitterProportionKey: String,
                                                          vertical: Boolean = false,
                                                          defaultProportion: Float = 0.7f) {
  private val previewDiffSplitter: Splitter = OnePixelSplitter(vertical, splitterProportionKey, defaultProportion)

  val mainComponent: JComponent
    get() = previewDiffSplitter

  init {
    previewDiffSplitter.setHonorComponentsMinimumSize(false)
    previewDiffSplitter.firstComponent = mainComponent

    toggleDiffPreviewOnPropertyChange(uiProperties, previewDiff, this::showDiffPreview)
    invokeLater { showDiffPreview(uiProperties.get(CommonUiProperties.SHOW_DIFF_PREVIEW)) }
  }

  abstract fun updatePreview(state: Boolean)

  private fun showDiffPreview(state: Boolean) {
    previewDiffSplitter.secondComponent = if (state) previewDiff.component else null
    updatePreview(state)
  }
}

abstract class EditorDiffPreview(project: Project, private val uiProperties: VcsLogUiProperties) : DiffPreviewProvider {
  init {
    toggleDiffPreviewOnPropertyChange(uiProperties, owner) { state ->
      if (state) {
        openPreviewInEditor(project, this, getOwnerComponent())
      }
      else {
        //'equals' for such files is overridden and means the equality of its owner
        FileEditorManager.getInstance(project).closeFile(PreviewDiffVirtualFile(this))
      }
    }

    @Suppress("LeakingThis")
    addSelectionListener {
      if (uiProperties.get(CommonUiProperties.SHOW_DIFF_PREVIEW)) {
        openPreviewInEditor(project, this, getOwnerComponent())
      }
    }
  }

  abstract override fun getOwner(): Disposable

  abstract fun getOwnerComponent(): JComponent

  abstract fun addSelectionListener(listener: () -> Unit)
}

class VcsLogEditorDiffPreview(project: Project, uiProperties: VcsLogUiProperties, private val mainFrame: MainFrame) :
  EditorDiffPreview(project, uiProperties) {

  override fun createDiffRequestProcessor(): DiffRequestProcessor {
    val preview = mainFrame.createDiffPreview(true, owner)
    preview.updatePreview(true)
    return preview
  }

  override fun getOwner(): Disposable {
    return mainFrame.changesBrowser
  }

  override fun getEditorTabName(): String {
    return "Repository Diff"
  }

  override fun getOwnerComponent(): JComponent = mainFrame.changesBrowser.preferredFocusedComponent

  override fun addSelectionListener(listener: () -> Unit) {
    mainFrame.changesBrowser.viewer.addSelectionListener(Runnable {
      if (mainFrame.changesBrowser.selectedChanges.isNotEmpty()) {
        listener()
      }
    }, owner)
  }
}

private fun openPreviewInEditor(project: Project, diffPreviewProvider: DiffPreviewProvider, componentToFocus: JComponent) {
  val fileEditorManager = FileEditorManager.getInstance(project)

  val previewDiffVirtualFile = PreviewDiffVirtualFile(diffPreviewProvider)
  val wasOpen = fileEditorManager.isFileOpen(previewDiffVirtualFile)
  val fileEditor = fileEditorManager.openFile(previewDiffVirtualFile, false, true).singleOrNull() ?: return
  if (!wasOpen) {
    val action: DumbAwareAction = object : DumbAwareAction() {
      override fun actionPerformed(e: AnActionEvent) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS)
        toolWindow?.activate({
                               IdeFocusManager.getInstance(project).requestFocus(componentToFocus, true)
                             }, false)
      }

      init {
        shortcutSet = CommonShortcuts.ESCAPE
      }
    }
    action.registerCustomShortcutSet(fileEditor.component, fileEditor)
  }
}