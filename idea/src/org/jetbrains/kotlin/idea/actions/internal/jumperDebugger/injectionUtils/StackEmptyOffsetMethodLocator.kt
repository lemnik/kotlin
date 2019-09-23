/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.actions.internal.jumperDebugger.injectionUtils

import org.jetbrains.org.objectweb.asm.*
import java.util.*

class StackEmptyOffsetMethodLocator(api: Int) : MethodVisitor(api) {

    private val firstLabel: LabelWrapper

    private var currentBlock: LabelWrapper? = null
    private var previousBlock: LabelWrapper? = null

    /**
     * The (relative) stack size after the last visited instruction. This size
     * is relative to the beginning of the current basic block, i.e., the true
     * stack size after the last visited instruction is equal to the
     * [MaxStackFrameSizeAndLocalsCalculator.LabelWrapper.inputStackSize] of the current basic block
     * plus <tt>stackSize</tt>.
     */
    private var stackSize: Int = 0

    /**
     * The (relative) maximum stack size after the last visited instruction.
     * This size is relative to the beginning of the current basic block, i.e.,
     * the true maximum stack size after the last visited instruction is equal
     * to the [MaxStackFrameSizeAndLocalsCalculator.LabelWrapper.inputStackSize] of the current basic
     * block plus <tt>stackSize</tt>.
     */
    private var maxStackSize: Int = 0

    /**
     * Maximum stack size of this method.
     */
    private var maxStack: Int = 0

    private val exceptionHandlers = LinkedList<ExceptionHandler>()
    private val labelWrappersTable = mutableMapOf<Label, LabelWrapper>()

    init {

        firstLabel = getLabelWrapper(Label())
        processLabel(firstLabel.label)
    }

    override fun visitFrame(type: Int, nLocal: Int, local: Array<Any>, nStack: Int, stack: Array<Any>) {
        throw AssertionError("We don't support visitFrame because currently nobody needs")
    }

    override fun visitInsn(opcode: Int) {
        increaseStackSize(FRAME_SIZE_CHANGE_BY_OPCODE[opcode])

        // if opcode == ATHROW or xRETURN, ends current block (no successor)
        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) {
            noSuccessor()
        }

        super.visitInsn(opcode)
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        if (opcode != Opcodes.NEWARRAY) {
            // updates current and max stack sizes only if it's not NEWARRAY
            // (stack size variation is 0 for NEWARRAY and +1 BIPUSH or SIPUSH)
            increaseStackSize(1)
        }

        super.visitIntInsn(opcode, operand)
    }

    override fun visitVarInsn(opcode: Int, `var`: Int) {
        increaseStackSize(FRAME_SIZE_CHANGE_BY_OPCODE[opcode])

        super.visitVarInsn(opcode, `var`)
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        if (opcode == Opcodes.NEW) {
            // updates current and max stack sizes only if opcode == NEW
            // (no stack change for ANEWARRAY, CHECKCAST, INSTANCEOF)
            increaseStackSize(1)
        }

        super.visitTypeInsn(opcode, type)
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
        val stackSizeVariation: Int

        // computes the stack size variation
        val c = desc[0]
        when (opcode) {
            Opcodes.GETSTATIC -> stackSizeVariation = if (c == 'D' || c == 'J') 2 else 1
            Opcodes.PUTSTATIC -> stackSizeVariation = if (c == 'D' || c == 'J') -2 else -1
            Opcodes.GETFIELD -> stackSizeVariation = if (c == 'D' || c == 'J') 1 else 0
            // case Constants.PUTFIELD:
            else -> stackSizeVariation = if (c == 'D' || c == 'J') -3 else -2
        }

        increaseStackSize(stackSizeVariation)

        super.visitFieldInsn(opcode, owner, name, desc)
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
        val argSize = Type.getArgumentsAndReturnSizes(desc)
        val sizeVariation: Int
        if (opcode == Opcodes.INVOKESTATIC) {
            sizeVariation = (argSize and 0x03) - (argSize shr 2) + 1
        } else {
            sizeVariation = (argSize and 0x03) - (argSize shr 2)
        }

        increaseStackSize(sizeVariation)

        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }

    override fun visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle, vararg bsmArgs: Any) {
        val argSize = Type.getArgumentsAndReturnSizes(desc)
        increaseStackSize((argSize and 0x03) - (argSize shr 2) + 1)

        super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs)
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        if (currentBlock != null) {
            // updates current stack size (max stack size unchanged
            // because stack size variation always negative in this
            // case)
            stackSize += FRAME_SIZE_CHANGE_BY_OPCODE[opcode]
            addSuccessor(getLabelWrapper(label), stackSize)

            if (opcode == Opcodes.GOTO) {
                noSuccessor()
            }
        }

        super.visitJumpInsn(opcode, label)
    }

    override fun visitLabel(label: Label) {
        processLabel(label)
        super.visitLabel(label)
    }

    private fun processLabel(label: Label) {
        val wrapper = getLabelWrapper(label)

        if (currentBlock != null) {
            // ends current block (with one new successor)
            currentBlock!!.outputStackMax = maxStackSize
            addSuccessor(wrapper, stackSize)
        }

        // begins a new current block
        currentBlock = wrapper
        // resets the relative current and max stack sizes
        stackSize = 0
        maxStackSize = 0

        if (previousBlock != null) {
            previousBlock!!.nextLabel = wrapper
        }

        previousBlock = wrapper
    }

    override fun visitLdcInsn(cst: Any) {
        // computes the stack size variation
        if (cst is Long || cst is Double) {
            increaseStackSize(2)
        } else {
            increaseStackSize(1)
        }

        super.visitLdcInsn(cst)
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
        visitSwitchInsn(dflt, *labels)

        super.visitTableSwitchInsn(min, max, dflt, *labels)
    }

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, vararg labels: Label) {
        visitSwitchInsn(dflt, *labels)

        super.visitLookupSwitchInsn(dflt, keys, labels)
    }

    private fun visitSwitchInsn(dflt: Label, vararg labels: Label) {
        if (currentBlock != null) {
            // updates current stack size (max stack size unchanged)
            --stackSize
            // adds current block successors
            addSuccessor(getLabelWrapper(dflt), stackSize)
            for (label in labels) {
                addSuccessor(getLabelWrapper(label), stackSize)
            }
            // ends current block
            noSuccessor()
        }
    }

    override fun visitMultiANewArrayInsn(desc: String, dims: Int) {
        if (currentBlock != null) {
            increaseStackSize(dims - 1)
        }

        super.visitMultiANewArrayInsn(desc, dims)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        // completes the control flow graph with exception handler blocks
        for (handler in exceptionHandlers) {
            var l: LabelWrapper? = handler.start
            val e = handler.end

            while (l !== e) {
                checkNotNull(l) { "Bad exception handler end" }

                l.addSuccessor(handler.handlerLabel, 0, true)
                l = l.nextLabel
            }
        }

        /*
         * control flow analysis algorithm: while the block stack is not
         * empty, pop a block from this stack, update the max stack size,
         * compute the true (non relative) begin stack size of the
         * successors of this block, and push these successors onto the
         * stack (unless they have already been pushed onto the stack).
         * Note: by hypothesis, the {@link LabelWrapper#inputStackSize} of the
         * blocks in the block stack are the true (non relative) beginning
         * stack sizes of these blocks.
         */
        var max = 0
        val stack = Stack<LabelWrapper>()
        val pushed = mutableSetOf<LabelWrapper>()

        stack.push(firstLabel)
        pushed.add(firstLabel)

        while (!stack.empty()) {
            val current = stack.pop()
            val start = current.inputStackSize
            val blockMax = start + current.outputStackMax

            // updates the global max stack size
            if (blockMax > max) {
                max = blockMax
            }

            // analyzes the successors of the block
            for (edge in current.successors) {
                val successor = edge.successor

                if (!pushed.contains(successor)) {
                    // computes its true beginning stack size...
                    successor.inputStackSize = if (edge.isExceptional) 1 else start + edge.outputStackSize
                    // ...and pushes it onto the stack
                    pushed.add(successor)
                    stack.push(successor)
                }
            }
        }

        this.maxStack = Math.max(this.maxStack, Math.max(maxStack, max))

        super.visitMaxs(this.maxStack, maxLocals)
    }

    override fun visitTryCatchBlock(
        start: Label, end: Label,
        handler: Label, type: String
    ) {
        val exceptionHandler = ExceptionHandler(
            getLabelWrapper(start), getLabelWrapper(end), getLabelWrapper(handler)
        )

        exceptionHandlers.add(exceptionHandler)

        super.visitTryCatchBlock(start, end, handler, type)
    }

    private class ExceptionHandler(
        val start: LabelWrapper,
        val end: LabelWrapper,
        val handlerLabel: LabelWrapper
    )

    private class ControlFlowEdge(
        val successor: LabelWrapper,
        val outputStackSize: Int,
        val isExceptional: Boolean
    )

    private class LabelWrapper(val label: Label) {
        var nextLabel: LabelWrapper? = null
        var successors = LinkedList<ControlFlowEdge>()

        var outputStackMax = 0
        var inputStackSize = 0

        fun addSuccessor(successor: LabelWrapper, outputStackSize: Int, isExceptional: Boolean) {
            successors.add(ControlFlowEdge(successor, outputStackSize, isExceptional))
        }
    }

    // ------------------------------------------------------------------------
    // Utility methods
    // ------------------------------------------------------------------------

    private fun getLabelWrapper(label: Label): LabelWrapper {
        return labelWrappersTable.getOrPut(label, { LabelWrapper(label) })
    }

    private fun increaseStackSize(variation: Int) {
        updateStackSize(stackSize + variation)
    }

    val validLines = mutableSetOf<Int>()

    override fun visitLineNumber(line: Int, start: Label?) {
        if (stackSize == 0 && start !== null) {
            validLines.add(line)
        }
    }


    private fun updateStackSize(size: Int) {
        if (size > maxStackSize) {
            maxStackSize = size
        }

        stackSize = size
    }

    private fun addSuccessor(successor: LabelWrapper, outputStackSize: Int) {
        currentBlock!!.addSuccessor(successor, outputStackSize, false)
    }

    /**
     * Ends the current basic block. This method must be used in the case where
     * the current basic block does not have any successor.
     */
    private fun noSuccessor() {
        if (currentBlock != null) {
            currentBlock!!.outputStackMax = maxStackSize
            currentBlock = null
        }
    }

    companion object {
        private val FRAME_SIZE_CHANGE_BY_OPCODE: IntArray

        init {
            // copy-pasted from org.jetbrains.org.objectweb.asm.Frame
            var i: Int
            val b = IntArray(202)
            val s = ("EFFFFFFFFGGFFFGGFFFEEFGFGFEEEEEEEEEEEEEEEEEEEEDEDEDDDDD"
                    + "CDCDEEEEEEEEEEEEEEEEEEEEBABABBBBDCFFFGGGEDCDCDCDCDCDCDCDCD"
                    + "CDCEEEEDDDDDDDCDCDCEFEFDDEEFFDEDEEEBDDBBDDDDDDCCCCCCCCEFED"
                    + "DDCDCDEEEEEEEEEEFEEEEEEDDEEDDEE")
            i = 0
            while (i < b.size) {
                b[i] = s[i] - 'E'
                ++i
            }

            FRAME_SIZE_CHANGE_BY_OPCODE = b
        }
    }
}