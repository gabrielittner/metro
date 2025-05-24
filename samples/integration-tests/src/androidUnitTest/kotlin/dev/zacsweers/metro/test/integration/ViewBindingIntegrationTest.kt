package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertNotNull
import dev.zacsweers.metro.test.integration.android.databinding.ActivityMainBinding

/*
 Compilation-only regression test for https://github.com/ZacSweers/metro/pull/462
 */

interface BaseFactory<B, T> {
  fun create(b: B): T
}

@DependencyGraph(AppScope::class)
interface AppGraph

@ContributesTo(AppScope::class)
interface TestRendererGraph {
  val factory: TestRenderer.Factory
}

class TestRenderer @Inject constructor(@Assisted private val binding: ActivityMainBinding) {
  @AssistedFactory abstract class Factory : BaseFactory<ActivityMainBinding, TestRenderer>
}

class ViewBindingIntegrationTest {
  @Test
  fun graphInit() {
    val graph = createGraph<AppGraph>()
    assertNotNull(graph.factory)
  }
}
