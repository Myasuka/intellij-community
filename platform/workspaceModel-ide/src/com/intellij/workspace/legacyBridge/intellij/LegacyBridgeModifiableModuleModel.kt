package com.intellij.workspace.legacyBridge.intellij

import com.google.common.collect.HashBiMap
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleWithNameAlreadyExists
import com.intellij.openapi.module.impl.getModuleNameByFilePath
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.PathUtil
import com.intellij.workspace.api.*
import com.intellij.workspace.ide.JpsFileEntitySource
import com.intellij.workspace.ide.WorkspaceModel
import com.intellij.workspace.ide.storagePlace
import com.intellij.workspace.legacyBridge.libraries.libraries.LegacyBridgeModifiableBase

internal class LegacyBridgeModifiableModuleModel(
  private val project: Project,
  private val moduleManager: LegacyBridgeModuleManagerComponent,
  diff: TypedEntityStorageBuilder
) : LegacyBridgeModifiableBase(diff), ModifiableModuleModel {

  override fun getProject(): Project = project

  private val myModulesToAdd = HashBiMap.create<String, LegacyBridgeModule>()
  private val myModulesToDispose = HashBiMap.create<String, LegacyBridgeModule>()

  private val myNewNameToModule = HashBiMap.create<String, LegacyBridgeModule>()

  // TODO Add cache?
  override fun getModules(): Array<Module> {
    val modules = moduleManager.modules.toMutableList()
    modules.removeAll(myModulesToDispose.values)
    modules.addAll(myModulesToAdd.values)
    return modules.toTypedArray()
  }

  override fun newModule(filePath: String, moduleTypeId: String): Module =
    newModule(filePath, moduleTypeId, null)

  override fun newModule(filePath: String, moduleTypeId: String, options: MutableMap<String, String>?): Module {
    // TODO Handle filePath, add correct iml source with a path

    // TODO Must be in sync with module loading. It is not now
    val canonicalPath = FileUtil.toSystemIndependentName(FileUtil.resolveShortWindowsName(filePath))

    val existingModule = getModuleByFilePath(canonicalPath)
    if (existingModule != null) {
      return existingModule
    }

    val moduleName = getModuleNameByFilePath(canonicalPath)
    if (findModuleByName(moduleName) != null) {
      throw ModuleWithNameAlreadyExists("Module already exists: $moduleName", moduleName)
    }

    // TODO get entity source from ProjectModelExternalSource instead
    val entitySource = JpsFileEntitySource.FileInDirectory(VirtualFileUrlManager.fromPath(PathUtil.getParentPath(canonicalPath)), project.storagePlace!!)

    val moduleEntity = diff.addModuleEntity(
      name = moduleName,
      dependencies = listOf(ModuleDependencyItem.ModuleSourceDependency),
      source = entitySource
    )

    val moduleInstance = moduleManager.createModuleInstance(moduleEntity, entityStoreOnDiff, diff = diff, isNew = true)
    myModulesToAdd[moduleName] = moduleInstance

    moduleInstance.setModuleType(moduleTypeId)
    // TODO Don't forget to store options in module entities
    if (options != null) {
      for ((key, value) in options) {
        @Suppress("DEPRECATION")
        moduleInstance.setOption(key, value)
      }
    }

    return moduleInstance
  }

  private fun getModuleByFilePath(filePath: String): LegacyBridgeModule? {
    for (module in modules) {
      val sameFilePath = when (SystemInfo.isFileSystemCaseSensitive) {
        true -> module.moduleFilePath == filePath
        false -> module.moduleFilePath.equals(filePath, ignoreCase = true)
      }

      if (sameFilePath) {
        return module as LegacyBridgeModule
      }
    }

    return null
  }

  // TODO Actually load module content
  override fun loadModule(filePath: String): Module =
    newModule(filePath, "", null)

  override fun disposeModule(module: Module) {
    module as LegacyBridgeModule

    if (findModuleByName(module.name) == null) {
      error("Module '${module.name}' is not found. Probably it's already disposed.")
    }

    if (myModulesToAdd.inverse().remove(module) != null) {
      Disposer.dispose(module)
    }

    myNewNameToModule.inverse().remove(module)

    myModulesToDispose[module.name] = module
  }

  override fun findModuleByName(name: String): Module? {
    val addedModule = myModulesToAdd[name]
    if (addedModule != null) return addedModule

    if (myModulesToDispose.containsKey(name)) return null

    val newNameModule = myNewNameToModule[name]
    if (newNameModule != null) return null

    return moduleManager.findModuleByName(name)
  }

  override fun dispose() {
    assertModelIsLive()

    ApplicationManager.getApplication().assertWriteAccessAllowed()

    for (moduleToAdd in myModulesToAdd.values) {
      Disposer.dispose(moduleToAdd)
    }

    myModulesToAdd.clear()
    myModulesToDispose.clear()
    myNewNameToModule.clear()
  }

  override fun isChanged(): Boolean =
    myModulesToAdd.isNotEmpty() ||
    myModulesToDispose.isNotEmpty() ||
    myNewNameToModule.isNotEmpty()

  override fun commit() {
    ApplicationManager.getApplication().assertWriteAccessAllowed()

    val storage = entityStoreOnDiff.current

    val removedModuleIds = mutableSetOf<ModuleId>()
    for (moduleToDispose in myModulesToDispose.values) {
      val moduleEntity = storage.resolve(moduleToDispose.moduleEntityId)
                         ?: error("Could not find module to remove by id: ${moduleToDispose.moduleEntityId}")
      removedModuleIds += moduleToDispose.moduleEntityId
      diff.removeEntity(moduleEntity)
    }

    moduleManager.setNewModuleInstances(myModulesToAdd.values.toList())

    val changedModuleIdsMap = mutableMapOf<ModuleId, ModuleId>()
    for (entry in myNewNameToModule.entries) {
      val entity = storage.resolve(entry.value.moduleEntityId) ?:
        error("Unable to resolve module by id: ${entry.value.moduleEntityId}")
      diff.modifyEntity(ModifiableModuleEntity::class.java, entity) {
        name = entry.key
        changedModuleIdsMap[entry.value.moduleEntityId] = this.persistentId()
      }
    }

    updateModuleDependencyIfNeeded(changedModuleIdsMap, removedModuleIds)

    WorkspaceModel.getInstance(project).updateProjectModel {
      it.addDiff(diff)
    }
  }

  private fun updateModuleDependencyIfNeeded(changedModulesIdMap: Map<ModuleId, ModuleId>, removedModuleIds: Set<ModuleId>) {
    if (changedModulesIdMap.isEmpty() && removedModuleIds.isEmpty()) return

    // Walkthrough the whole modules and update dependencies for them
    entityStoreOnDiff.current.entities(ModuleEntity::class.java).forEach { moduleEntity ->
      var containsOldDependency = false
      val newDependencies = moduleEntity.dependencies.mapNotNull {
        when(it) {
          is ModuleDependencyItem.Exportable.ModuleDependency -> {
            // Remove old dependency
            if (removedModuleIds.contains(it.module)) {
              containsOldDependency = true
              return@mapNotNull null
            }

            // Update dependency after rename
            val newModuleId = changedModulesIdMap[it.module]
            if (newModuleId != null) {
              containsOldDependency = true
              it.copy(module = newModuleId)
            } else it
          }
          else -> it
        }
      }

      if (containsOldDependency) {
        diff.modifyEntity(ModifiableModuleEntity::class.java, moduleEntity) {
          dependencies = newDependencies
        }
      }
    }
  }

  override fun renameModule(module: Module, newName: String) {
    module as LegacyBridgeModule

    val oldModule = findModuleByName(newName)

    myNewNameToModule.inverse().remove(module)
    myNewNameToModule.remove(newName)

    if (module.name != newName) { // if renaming to itself, forget it altogether
      myNewNameToModule[newName] = module
    }

    if (oldModule != null) {
      throw ModuleWithNameAlreadyExists(ProjectBundle.message("module.already.exists.error", newName), newName)
    }
  }

  override fun getModuleToBeRenamed(newName: String): Module? = myNewNameToModule[newName]
  override fun getNewName(module: Module): String? = myNewNameToModule.inverse()[module]
  override fun getActualName(module: Module): String = getNewName(module) ?: module.name

  override fun getModuleGroupPath(module: Module): Array<String>? =
    LegacyBridgeModuleManagerComponent.getModuleGroupPath(module, entityStoreOnDiff)

  override fun hasModuleGroups(): Boolean = LegacyBridgeModuleManagerComponent.hasModuleGroups(entityStoreOnDiff)

  override fun setModuleGroupPath(module: Module, groupPath: Array<out String>?) {
    val moduleId = (module as LegacyBridgeModule).moduleEntityId

    val storage = entityStoreOnDiff.current

    val moduleEntity = storage.resolve(moduleId) ?: error("Could not resolve module by moduleId: $moduleId")
    val moduleGroupEntity = moduleEntity.groupPath
    val groupPathList = groupPath?.toList()

    // TODO How to deduplicate with ModuleCustomImlDataEntity ?
    if (moduleGroupEntity?.path != groupPathList) {
      when {
        moduleGroupEntity == null && groupPathList != null -> diff.addModuleGroupPathEntity(
          module = moduleEntity,
          path = groupPathList,
          source = moduleEntity.entitySource
        )

        moduleGroupEntity == null && groupPathList == null -> Unit

        moduleGroupEntity != null && groupPathList == null -> diff.removeEntity(moduleGroupEntity)

        moduleGroupEntity != null && groupPathList != null -> diff.modifyEntity(ModifiableModuleGroupPathEntity::class.java,
          moduleGroupEntity) {
          path = groupPathList
        }

        else -> error("Should not be reached")
      }
    }
  }
}
