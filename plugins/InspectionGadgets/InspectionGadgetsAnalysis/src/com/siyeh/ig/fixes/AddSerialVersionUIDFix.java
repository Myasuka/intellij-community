/*
 * Copyright 2003-2017 Dave Griffith, Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.fixes;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.ClassUtil;
import com.intellij.util.lang.UrlClassLoader;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.InspectionGadgetsFix;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.ObjectStreamClass;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class AddSerialVersionUIDFix extends InspectionGadgetsFix {
  private static final Logger LOG = Logger.getInstance(AddSerialVersionUIDFix.class);

  @Override
  @NotNull
  public String getFamilyName() {
    return InspectionGadgetsBundle.message("add.serialversionuidfield.quickfix");
  }

  @Override
  public void doFix(Project project, ProblemDescriptor descriptor) {
    final PsiElement classIdentifier = descriptor.getPsiElement();
    final PsiClass aClass = (PsiClass)classIdentifier.getParent();
    assert aClass != null;
    final PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(aClass.getProject());
    Class<?> className = null;
    final ProjectFileIndex index = ProjectRootManager.getInstance(project).getFileIndex();
    final Module module = index.getModuleForFile(aClass.getContainingFile().getVirtualFile());
    final List<URL> urls = new ArrayList<>();
    List<String> list = OrderEnumerator.orderEntries(module).recursively().runtimeOnly().getPathsList().getPathList();
    for (String path : list) {
      try {
        urls.add(new File(FileUtil.toSystemIndependentName(path)).toURI().toURL());
      }
      catch (MalformedURLException exception) {
        LOG.error(exception);
      }
    }
    try {
      UrlClassLoader loader =
        UrlClassLoader.build().urls(urls).get();
      className = Class.forName(ClassUtil.getJVMClassName(aClass), false, loader);
    }
    catch (ClassNotFoundException exception) {
      LOG.error(exception);
    }

    final long serialVersionUID = ObjectStreamClass.lookup(className).getSerialVersionUID();
    final PsiField field =
      elementFactory.createFieldFromText("private static final long serialVersionUID = " + serialVersionUID + "L;", aClass);
    aClass.add(field);
  }
}
