/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.actions.internal.jumperDebugger

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.DebugProcessEvents
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.ui.breakpoints.StackCapturingLineBreakpoint
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompilerPaths
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.sun.jdi.ReferenceType
import com.sun.jdi.request.EventRequest
import org.jetbrains.kotlin.idea.actions.internal.jumperDebugger.injectionUtils.getAvailableGotoLines
import org.jetbrains.kotlin.idea.debugger.invokeInManagerThread
import java.io.File
import java.nio.file.Files


class JumperDebuggerAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val debuggerManager = DebuggerManagerEx.getInstance(project) as DebuggerManagerEx

        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        val selectedLine = editor.caretModel.primaryCaret.logicalPosition.line + 1

        val session = debuggerManager.sessions.firstOrNull() ?: return


        val breakpointManager = DebuggerManagerEx.getInstanceEx(project).breakpointManager
        session.process.invokeInManagerThread {

            tryMakeJumpOnLine(session, selectedLine, project)

//            session.process.onHotSwapFinished()

//            val context = session.contextManager.context
//            val suspendContext = context.suspendContext
//            suspendContext?.activeExecutionStack?.initTopFrame()
        }

//
//
        session.xDebugSession?.run {
            ApplicationManager.getApplication().invokeLater {
                session.refresh(true)
                //rebuildViews()
                showExecutionPoint()
            }
        }
    }

    private fun tryMakeJumpOnLine(session: DebuggerSession, selectedLine: Int, project: Project) {

        val process = session.process

        if (!process.virtualMachineProxy.isSuspended) return

        val context = process.debuggerContext
        val frame = context.frameProxy ?: return
        val threadProxy = context.threadProxy ?: return

        val recursive = threadProxy.frames().count {
            it.location().method().name() == frame.location().method().name()
        } > 1
        if (recursive) return

        val threadReference = threadProxy.threadReference ?: return

        val location = frame.location()
        val classType = location.declaringType()

        val classFile = tryLocateClassFile(classType, project) ?: return

        val classFileContent = Files.readAllBytes(classFile.toPath())

        val availableGotoLines = getAvailableGotoLines(classFileContent)

        if (!availableGotoLines.contains(selectedLine)) return

        if (availableGotoLines.first() != selectedLine) {
            debuggerJump(threadReference, classType, classFileContent, selectedLine, threadProxy)
        } else {

            val currentFrame = threadProxy.frame(0)
            threadProxy.popFrames(currentFrame)

            val firstLineLocation = classType.allLineLocations().first { it.lineNumber() == selectedLine }

            val eventRequest = threadProxy.virtualMachineProxy.eventRequestManager()
            val prefaceBreakPoint = eventRequest.createBreakpointRequest(firstLineLocation)
            prefaceBreakPoint.enable()

            DebugProcessEvents.enableRequestWithHandler(prefaceBreakPoint) { newEvent ->
                threadProxy.virtualMachineProxy.suspend()
                prefaceBreakPoint.disable()
                newEvent.request().disable()
            }

            threadProxy.virtualMachineProxy.resume()

        }
    }

    private fun tryLocateClassFile(type: ReferenceType, project: Project): File? {
        val className = type.name().replace('.', '/') + ".class"
        val outputPaths = CompilerPaths.getOutputPaths(ModuleManager.getInstance(project).modules)

        return outputPaths.map { path ->
            val directory = File(path)
            if (!directory.exists() || !directory.isDirectory) return@map null
            val classFilePath = File(directory, className)
            if (classFilePath.exists() && classFilePath.isFile && classFilePath.canRead()) classFilePath else null
        }.firstOrNull()
    }



}