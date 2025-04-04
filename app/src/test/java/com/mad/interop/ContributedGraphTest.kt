package com.mad.interop

import com.google.common.truth.Truth.assertThat
import com.mad.dagger.factory.impl.MyJavaTypeAccessor
import com.mad.feature.graph.FeatureEntryPointProvider
import com.mad.interop.scopes.GraphHolder
import com.mad.interop.scopes.attemptDaggerChildComponentCreation
import com.mad.interop.scopes.createGraphInterop
import com.mad.multibinding.impl.AppScopeRunnerAccessor
import com.mad.multibinding.impl.ScopedUseCaseAccessor
import org.junit.Test

class ContributedGraphTest {

  @Test
  fun `app graph can be built`() {
    val appGraph = createGraphInterop<AppGraph>()
    assertThat(appGraph).isInstanceOf(AppGraph::class.java)
  }

  @Test
  fun `scoped type and binding are the same instance`() {
    val appGraph = createGraphInterop<AppGraph>()
    val bindingInstance = (appGraph as ScopedUseCaseAccessor).scopedUseCase()
    val multibindingInstance = (appGraph as AppScopeRunnerAccessor).appScopeRunner().scopedType
      .single()

    assertThat(bindingInstance).isEqualTo(multibindingInstance)
  }

  @Test
  fun `contributed graph can be created via public api`() {
    GraphHolder.appGraph = createGraphInterop<AppGraph>()
    GraphHolder.loggedInGraph = attemptDaggerChildComponentCreation<LoggedInGraph>(
      GraphHolder.appGraph!!
    )

    // Referencing only types in feature-with-graph/public to create ContributedFeatureGraph
    val contributedGraph = GraphHolder.loggedInGraph<FeatureEntryPointProvider>()
      .featureEntryPoint()
      .startFeature()

    assertThat(contributedGraph).isEqualTo("Contributed Feature Text")
  }

  @Test
  fun `dagger generated factory's can be used`() {
    val appGraph = createGraphInterop<AppGraph>()
    val javaTypeAccessor = appGraph as MyJavaTypeAccessor

    assertThat(javaTypeAccessor.myJavaType)
      .isEqualTo(javaTypeAccessor.myJavaTypeInjector.myJavaType)
  }
}