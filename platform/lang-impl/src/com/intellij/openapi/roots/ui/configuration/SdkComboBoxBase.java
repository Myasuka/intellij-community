// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.roots.ui.configuration;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import static com.intellij.ui.AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED;

public abstract class SdkComboBoxBase<T> extends ComboBox<T> {
  private final Logger LOG = Logger.getInstance(getClass());
  @NotNull protected final SdkListModelBuilder myModel;

  protected SdkComboBoxBase(@NotNull SdkListModelBuilder model) {
    super();
    myModel = model;
    myModel.addModelListener(new SdkListModelBuilder.ModelListener() {
      @Override
      public void syncModel(@NotNull SdkListModel model) {
        SdkComboBoxBase.this.onModelUpdated(model);
      }

      @Override
      public void onNewSdkAdded(@NotNull Sdk sdk) {
        SdkComboBoxBase.this.onNewSdkAdded(sdk);
      }
    });

    UIUtil.putClientProperty(this, ANIMATION_IN_RENDERER_ALLOWED, true);
    setMinimumAndPreferredWidth(JBUI.scale(300));
    setMaximumRowCount(30);
    setSwingPopup(false);
    putClientProperty("ComboBox.jbPopup.supportUpdateModel", true);
  }

  protected abstract void onModelUpdated(@NotNull SdkListModel model);

  protected abstract void onNewSdkAdded(@NotNull Sdk sdk);

  public void setInvalidJdk(String name) {
    setSelectedItem(myModel.setInvalidSdk(name));
  }

  public void showProjectSdkItem() {
    myModel.showProjectSdkItem();
  }

  public void showNoneSdkItem() {
    myModel.showNoneSdkItem();
  }

  public void reloadModel() {
    myModel.reloadSdks();
  }

  /**
   * @deprecated Use the {@link JdkComboBox} API to manage shown items,
   * this call is ignored
   */
  @Override
  @Deprecated
  public void addItem(T item) {
    LOG.warn("JdkComboBox#addItem() is deprecated!" + item, new RuntimeException());
  }

  /**
   * @deprecated Use the {@link JdkComboBox} API to manage shown items,
   * this call is ignored
   */
  @Override
  @Deprecated
  public void insertItemAt(T item, int index) {
    LOG.warn("insertItemAt() is deprecated!" + item + " at " + index, new RuntimeException());
  }

  /**
   * @deprecated Use the {@link JdkComboBox} API to manage shown items,
   * this call is ignored
   */
  @Override
  @Deprecated
  public void removeItem(Object anObject) {
    LOG.warn("removeItem() is deprecated!", new RuntimeException());
  }

  /**
   * @deprecated Use the {@link JdkComboBox} API to manage shown items,
   * this call is ignored
   */
  @Override
  @Deprecated
  public void removeItemAt(int anIndex) {
    LOG.warn("removeItemAt() is deprecated!", new RuntimeException());
  }

  /**
   * @deprecated Use the {@link JdkComboBox} API to manage shown items,
   * this call is ignored
   */
  @Override
  @Deprecated
  public void removeAllItems() {
    LOG.warn("removeAllItems() is deprecated!", new RuntimeException());
  }
}
