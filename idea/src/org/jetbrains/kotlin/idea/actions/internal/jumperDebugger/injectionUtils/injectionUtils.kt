/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.actions.internal.jumperDebugger.injectionUtils

import org.jetbrains.org.objectweb.asm.*

private class StackEmptyOffsetClassLocator : ClassVisitor(Opcodes.ASM6) {

    private val locator = StackEmptyOffsetMethodLocator(Opcodes.ASM6)

    val validLines get() = locator.validLines

    override fun visitMethod(
        access: Int,
        name: String?,
        desc: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        return if (name == "LOL") locator else object : MethodVisitor(Opcodes.ASM6) {}
    }
}

private class LocalVariableCounterClassVisitor : ClassVisitor(Opcodes.ASM6) {
    private val result = mutableListOf<Pair<Type, Int>>()
    val defaultValueAndName get() : List<Pair<Type, Int>> = result

    private inner class LocalVariableCounterMethodVisitor : MethodVisitor(Opcodes.ASM6) {
        override fun visitLocalVariable(name: String?, descriptor: String?, signature: String?, start: Label?, end: Label?, index: Int) {
            if (descriptor !== null) {
                result.add(Type.getType(descriptor) to index)
            }
        }
    }

    override fun visitMethod(access: Int, name: String?, desc: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        return if (name == "LOL") LocalVariableCounterMethodVisitor() else object : MethodVisitor(Opcodes.ASM6) {}
    }
}

private class Transformer(private val line: Int, private val locals: List<Pair<Type, Int>>, visitor: ClassVisitor) : ClassVisitor(Opcodes.ASM6, visitor) {

    var stopLineNumber = 0
        private set

    private inner class MethodTransformer(private val targetLine: Int, visitor: MethodVisitor) : MethodVisitor(Opcodes.ASM6, visitor) {

        private val labelToMark = Label()
        private val stopLabel = Label()

        private val Type.defaultValue get() = when(this) {
            Type.BOOLEAN_TYPE -> false
            Type.BYTE_TYPE -> 0.toByte()
            Type.CHAR_TYPE -> 0.toChar()
            Type.DOUBLE_TYPE -> 0.0
            Type.FLOAT_TYPE -> 0.0.toFloat()
            Type.INT_TYPE -> 0
            Type.LONG_TYPE -> 0.toLong()
            Type.SHORT_TYPE -> 0.toShort()
            else -> null
        }



        override fun visitCode() {

            locals.forEach {
                super.visitLdcInsn(it.first.defaultValue)
                super.visitVarInsn(Opcodes.ISTORE, it.second)
            }

            val extraVariable = locals.count()



            //C:\Projects\Test\out\production\Test\sd\java\TestClass_test.class
            super.visitLdcInsn(0)
            super.visitVarInsn(Opcodes.ISTORE, extraVariable)





            super.visitLabel(stopLabel)


            super.visitCode()
        }

        private var isStartLabel = true

        override fun visitLineNumber(line: Int, start: Label?) {

            if (isStartLabel) {
                isStartLabel = false

                stopLineNumber = line

                super.visitLabel(start)
                super.visitLineNumber(line, start) //<---

                super.visitVarInsn(Opcodes.ILOAD, locals.count())
                super.visitLdcInsn(0)
                super.visitVarInsn(Opcodes.ISTORE, locals.count())
                super.visitJumpInsn(Opcodes.IFNE, labelToMark)
                return
            }


            if (targetLine == line) {
                super.visitLabel(labelToMark)
                super.visitLocalVariable("$$", "I", null, stopLabel, labelToMark, locals.count())
            }
            super.visitLineNumber(line, start)
        }
    }

    override fun visitMethod(access: Int, name: String?, desc: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        return if (name == "LOL") {
            MethodTransformer(
                line,
                super.visitMethod(access, name, desc, signature, exceptions)
            )
        } else {
            super.visitMethod(access, name, desc, signature,exceptions)
        }
    }
}

internal fun getAvailableGotoLines(klass: ByteArray) : Set<Int> {
    val classReader = ClassReader(klass)
    val offsetClassLocator = StackEmptyOffsetClassLocator()
    classReader.accept(offsetClassLocator, 0)
    return offsetClassLocator.validLines
}

data class ClassAndFirstLine(val klass: ByteArray, val stopLineNumber: Int)

internal fun updateClassWithGotoLinePrefix(klass: ByteArray, line: Int): ClassAndFirstLine {
    val classReaderToWrite = ClassReader(klass)
    val writer = ClassWriter(classReaderToWrite, ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)

    val localCalculator = LocalVariableCounterClassVisitor()
    classReaderToWrite.accept(localCalculator, 0)

    val transformer = Transformer(line, localCalculator.defaultValueAndName, writer)

    classReaderToWrite.accept(transformer, 0)

    return ClassAndFirstLine(writer.toByteArray(), transformer.stopLineNumber)
}