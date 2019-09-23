/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.actions.internal.jumperDebugger

import com.intellij.debugger.engine.DebugProcessEvents
import com.intellij.debugger.engine.jdi.ThreadReferenceProxy
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.sun.jdi.ReferenceType
import com.sun.jdi.ThreadReference
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.StepRequest
import org.jetbrains.kotlin.idea.actions.internal.jumperDebugger.injectionUtils.updateClassWithGotoLinePrefix
import java.nio.file.Files

internal fun debuggerJump(eventThread: ThreadReference, declaredType: ReferenceType, originalClassFile: ByteArray, selectedLine: Int, tp: ThreadReferenceProxyImpl) {

    val vm = eventThread.virtualMachine()
    val (classToRedefine, stopLine) = updateClassWithGotoLinePrefix(originalClassFile, selectedLine)

    // Get values of all variables that are visible and print
    val path = java.io.File("C:\\Projects\\Test\\out\\production\\Test\\sd\\java\\TestClass_test.class").toPath()
    Files.write(path, classToRedefine)

    val currentFrame = tp.frame(0)
    val localVariables = currentFrame.visibleVariables().map { it to currentFrame.getValue(it) }

    tp.popFrames(currentFrame)

    vm.redefineClasses(mapOf(declaredType to classToRedefine))

    val stopPreloadLocation = declaredType.allLineLocations().first {
        it.lineNumber() == stopLine
    }

    val eventRequest = tp.virtualMachineProxy.eventRequestManager()
    val prefaceBreakPoint = eventRequest.createBreakpointRequest(stopPreloadLocation)
    prefaceBreakPoint.enable()

    DebugProcessEvents.enableRequestWithHandler(prefaceBreakPoint) { newEvent ->
        prefaceBreakPoint.disable()
        newEvent.request().disable()

        val targetLocation = declaredType.allLineLocations().first {
            it.lineNumber() == selectedLine
        }

        val newStackFrame = tp.frame(0)

        val prefaceVariable = newStackFrame.visibleVariables().first { it.name() == "$$" }
        newStackFrame.setValue(prefaceVariable, tp.virtualMachineProxy.mirrorOf(1))

        val targetBreakPoint = eventRequest.createBreakpointRequest(targetLocation)
        targetBreakPoint.enable()

        DebugProcessEvents.enableRequestWithHandler(targetBreakPoint) { newEvent ->
            tp.virtualMachineProxy.suspend()

            targetBreakPoint.disable()
            newEvent.request().disable()

            newStackFrame.visibleVariables().forEach { newVariable ->
                localVariables.firstOrNull { it.first.name() == newVariable.name() }?.let {
                    newStackFrame.setValue(newVariable, it.second)
                }
            }
        }
    }

    tp.virtualMachineProxy.resume()
}


//vm.redefineClasses(mapOf(declaredType to originalClassFile))
//            val methodName = newStackFrame.location().method().name()
//            val methodExitRequest = stopEventRequest.createMethodExitRequest()
//            methodExitRequest.enable()
//            DebugProcessEvents.enableRequestWithHandler(methodExitRequest) {
//                if (tp.frame(0).location().method().name() == methodName) {
//                    methodExitRequest.disable()
//                    vm.redefineClasses(mapOf(declaredType to originalClassFile))
//                }
//            }