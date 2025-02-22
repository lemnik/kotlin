/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.nj2k.postProcessing.processings

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.RangeMarker
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.core.util.EDT
import org.jetbrains.kotlin.idea.core.util.range
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.nj2k.NewJ2kConverterContext
import org.jetbrains.kotlin.nj2k.inference.common.BoundTypeCalculatorImpl
import org.jetbrains.kotlin.nj2k.inference.common.ByInfoSuperFunctionsProvider
import org.jetbrains.kotlin.nj2k.inference.common.ConstraintsCollectorAggregator
import org.jetbrains.kotlin.nj2k.inference.common.InferenceFacade
import org.jetbrains.kotlin.nj2k.inference.common.collectors.CallExpressionConstraintCollector
import org.jetbrains.kotlin.nj2k.inference.common.collectors.CommonConstraintsCollector
import org.jetbrains.kotlin.nj2k.inference.common.collectors.FunctionConstraintsCollector
import org.jetbrains.kotlin.nj2k.inference.mutability.*
import org.jetbrains.kotlin.nj2k.inference.nullability.*
import org.jetbrains.kotlin.nj2k.postProcessing.GeneralPostProcessing
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.elementsInRange

abstract class InferenceProcessing : GeneralPostProcessing {
    final override suspend fun runProcessing(file: KtFile, rangeMarker: RangeMarker?, converterContext: NewJ2kConverterContext) {
        val elements = if (rangeMarker != null) {
            val range = rangeMarker.range ?: return
            runReadAction { file.elementsInRange(range) }.filterIsInstance<KtElement>()
        } else listOf(file)
        val resolutionFacade = runReadAction { file.getResolutionFacade() }

        withContext(EDT) {
            CommandProcessor.getInstance().runUndoTransparentAction {
                createInferenceFacade(resolutionFacade, converterContext).runOn(elements)
            }
        }
    }

    abstract fun createInferenceFacade(
        resolutionFacade: ResolutionFacade,
        converterContext: NewJ2kConverterContext
    ): InferenceFacade
}

class NullabilityInferenceProcessing : InferenceProcessing() {
    override fun createInferenceFacade(
        resolutionFacade: ResolutionFacade,
        converterContext: NewJ2kConverterContext
    ): InferenceFacade = InferenceFacade(
        NullabilityContextCollector(resolutionFacade, converterContext),
        ConstraintsCollectorAggregator(
            resolutionFacade,
            NullabilityConstraintBoundProvider(),
            listOf(
                CommonConstraintsCollector(),
                CallExpressionConstraintCollector(),
                FunctionConstraintsCollector(ByInfoSuperFunctionsProvider(resolutionFacade, converterContext)),
                NullabilityConstraintsCollector()
            )
        ),
        BoundTypeCalculatorImpl(resolutionFacade, NullabilityBoundTypeEnhancer(resolutionFacade)),
        NullabilityStateUpdater(),
        NullabilityDefaultStateProvider()
    )
}

class MutabilityInferenceProcessing : InferenceProcessing() {
    override fun createInferenceFacade(
        resolutionFacade: ResolutionFacade,
        converterContext: NewJ2kConverterContext
    ): InferenceFacade = InferenceFacade(
        MutabilityContextCollector(resolutionFacade, converterContext),
        ConstraintsCollectorAggregator(
            resolutionFacade,
            MutabilityConstraintBoundProvider(),
            listOf(
                CommonConstraintsCollector(),
                CallExpressionConstraintCollector(),
                FunctionConstraintsCollector(ByInfoSuperFunctionsProvider(resolutionFacade, converterContext)),
                MutabilityConstraintsCollector()
            )
        ),
        MutabilityBoundTypeCalculator(resolutionFacade, MutabilityBoundTypeEnhancer()),
        MutabilityStateUpdater(),
        MutabilityDefaultStateProvider()
    )
}