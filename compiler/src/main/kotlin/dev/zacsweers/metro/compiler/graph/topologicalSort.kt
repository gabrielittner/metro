/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.tracing.Tracer
import dev.zacsweers.metro.compiler.tracing.traceNested
import java.util.PriorityQueue

/**
 * Returns a new list where each element is preceded by its results in [sourceToTarget]. The first
 * element will return no values in [sourceToTarget].
 *
 * Modifications from Zipline
 * - Add [onMissing] check
 * - Add [onCycle] for customizing how cycle errors are handled
 * - Add [isDeferrable] for indicating deferrable dependencies
 * - Implementation modified to instead use a Tarjan-processed SCC DAG
 *
 * @param sourceToTarget a function that returns nodes that should precede the argument in the
 *   result.
 * @see <a href="Adapted from
 *   https://github.com/cashapp/zipline/blob/30ca7c9d782758737e9d20e8d9505930178d1992/zipline/src/hostMain/kotlin/app/cash/zipline/internal/topologicalSort.kt">Adapted
 *   from Zipline's implementation</a>
 */
internal fun <T : Comparable<T>> Iterable<T>.topologicalSort(
  sourceToTarget: (T) -> Iterable<T>,
  onCycle: (List<T>) -> Nothing = { cycle ->
    val message = buildString {
      append("No topological ordering is possible for these items:")

      for (unorderedItem in cycle.reversed()) {
        append("\n  ")
        append(unorderedItem)
        val unsatisfiedDeps = sourceToTarget(unorderedItem).toSet()
        unsatisfiedDeps.joinTo(this, separator = ", ", prefix = " (", postfix = ")")
      }
    }
    throw IllegalArgumentException(message)
  },
  isDeferrable: (from: T, to: T) -> Boolean = { _, _ -> false },
  onMissing: (source: T, missing: T) -> Unit = { source, missing ->
    throw IllegalArgumentException("No element for $missing found for $source")
  },
): List<T> {
  val fullAdjacency = buildFullAdjacency(sourceToTarget, onMissing)
  val (sortedKeys, _) = topologicalSort(fullAdjacency, isDeferrable, onCycle)
  return sortedKeys
}

internal fun <T> List<T>.isTopologicallySorted(sourceToTarget: (T) -> Iterable<T>): Boolean {
  val seenNodes = mutableSetOf<T>()
  for (node in this) {
    if (sourceToTarget(node).any { it !in seenNodes }) return false
    seenNodes.add(node)
  }
  return true
}

internal fun <T> Iterable<T>.buildFullAdjacency(
  sourceToTarget: (T) -> Iterable<T>,
  onMissing: (source: T, missing: T) -> Unit,
): Map<T, List<T>> {
  val set = toSet()
  val adjacency = mutableMapOf<T, MutableList<T>>()

  for (key in set) {
    val dependencies = adjacency.getOrPut(key, ::mutableListOf)

    for (targetKey in sourceToTarget(key)) {
      if (targetKey !in set) {
        // may throw, or silently allow
        onMissing(key, targetKey)
        // If we got here, this missing target is allowable (i.e. a default value). Just ignore it
        continue
      }
      dependencies += targetKey
    }
  }
  return adjacency
}

/**
 * Builds the full adjacency list.
 * * Keeps all edges (strict _and_ deferrable).
 * * Prunes edges whose target isn't in [bindings], delegating the decision to [onMissing].
 */
internal fun <TypeKey, Binding> buildFullAdjacency(
  bindings: Map<TypeKey, Binding>,
  dependenciesOf: (Binding) -> Iterable<TypeKey>,
  onMissing: (source: TypeKey, missing: TypeKey) -> Unit,
): Map<TypeKey, List<TypeKey>> {
  return bindings.keys.buildFullAdjacency(
    sourceToTarget = { key -> dependenciesOf(bindings.getValue(key)) },
    onMissing = onMissing,
  )
}

/**
 * @param sortedKeys Topologically sorted list of keys.
 * @param deferredTypes Vertices that sit inside breakable cycles.
 */
internal data class TopoSortResult<T>(val sortedKeys: List<T>, val deferredTypes: List<T>)

/**
 * Returns the vertices in a valid topological order. Every edge in [fullAdjacency] is respected;
 * strict cycles throw, breakable cycles (those containing a deferrable edge) are deferred.
 *
 * @param fullAdjacency outgoing‑edge map (every vertex key must be present)
 * @param isDeferrable predicate for "edge may break a cycle"
 * @param onCycle called with the offending cycle if no deferrable edge
 */
internal fun <V : Comparable<V>> topologicalSort(
  fullAdjacency: Map<V, List<V>>,
  isDeferrable: (from: V, to: V) -> Boolean,
  onCycle: (List<V>) -> Nothing,
  parentTracer: Tracer = Tracer.NONE,
): TopoSortResult<V> {
  val deferredTypes = mutableSetOf<V>()

  // Collapse the graph into strongly‑connected components
  val (components, componentOf) =
    parentTracer.traceNested("Compute SCCs") { fullAdjacency.computeStronglyConnectedComponents() }

  // Check for cycles
  parentTracer.traceNested("Check for cycles") {
    for (component in components) {
      val vertices = component.vertices

      if (vertices.size == 1) {
        val isSelfLoop = fullAdjacency[vertices[0]].orEmpty().any { it == vertices[0] }
        if (!isSelfLoop) {
          // trivial acyclic
          continue
        }
      }

      // Look for cycles
      val contributorsToCycle = buildSet {
        for (from in vertices) {
          for (to in fullAdjacency[from].orEmpty()) {
            if (
              // stays inside SCC
              componentOf[to] == component.id &&
                // deferrable edge
                isDeferrable(from, to)
            ) {
              // Only 'from' is deferred
              add(from)
            }
          }
        }
      }

      if (contributorsToCycle.isEmpty()) {
        // no deferrable -> hard cycle
        onCycle(vertices)
      } else {
        deferredTypes += contributorsToCycle
      }
    }
  }

  val componentDag =
    parentTracer.traceNested("Build component DAG") {
      buildComponentDag(fullAdjacency, componentOf)
    }
  val componentOrder =
    parentTracer.traceNested("Topo sort component DAG") {
      topologicallySortComponentDag(componentDag, components.size)
    }

  val sortedKeys =
    parentTracer.traceNested("Expand components") {
      componentOrder.flatMap { id ->
        // All these components' vertices are equal, so fallback to their natural sorting order
        components[id].vertices.sorted()
      }
    }
  return TopoSortResult(
    // Expand each component back into its original vertices
    sortedKeys,
    deferredTypes.toList(),
  )
}

internal data class Component<V>(val id: Int, val vertices: MutableList<V> = mutableListOf())

/**
 * Computes the strongly connected components (SCCs) of a directed graph using Tarjan's algorithm.
 *
 * @param this A map representing the directed graph where the keys are vertices of type [V] and the
 *   values are lists of vertices to which each key vertex has outgoing edges.
 * @return A pair where the first element is a list of components (each containing an ID and its
 *   associated vertices) and the second element is a map that associates each vertex with the ID of
 *   its component.
 * @see <a
 *   href="https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm">Tarjan's
 *   algorithm</a>
 */
internal fun <V : Comparable<V>> Map<V, List<V>>.computeStronglyConnectedComponents():
  Pair<List<Component<V>>, Map<V, Int>> {
  var nextIndex = 0
  var nextComponentId = 0

  // vertices of the *current* DFS branch
  val stack = ArrayDeque<V>()
  val onStack = mutableSetOf<V>()

  // DFS discovery time of each vertex
  // Analogous to "v.index" refs in the linked algo
  val indexMap = mutableMapOf<V, Int>()
  // The lowest discovery index that v can reach without
  // leaving the current DFS stack.
  // Analogous to "v.lowlink" refs in the linked algo
  val lowLinkMap = mutableMapOf<V, Int>()
  // Mapping of V to the id of the SCC that v ends up in
  val componentOf = mutableMapOf<V, Int>()
  val components = mutableListOf<Component<V>>()

  fun strongConnect(v: V) {
    // Set the depth index for v to the smallest unused index
    indexMap[v] = nextIndex
    lowLinkMap[v] = nextIndex
    nextIndex++

    stack += v
    onStack += v

    for (w in this[v].orEmpty().sorted()) {
      if (w !in indexMap) {
        // Successor w has not yet been visited; recurse on it
        strongConnect(w)
        lowLinkMap[v] = minOf(lowLinkMap.getValue(v), lowLinkMap.getValue(w))
      } else if (w in onStack) {
        // Successor w is in stack S and hence in the current SCC
        // If w is not on stack, then (v, w) is an edge pointing to an SCC already found and must be
        // ignored
        // See below regarding the next line
        lowLinkMap[v] = minOf(lowLinkMap.getValue(v), indexMap.getValue(w))
      }
    }

    // If v is a root node, pop the stack and generate an SCC
    if (lowLinkMap.getValue(v) == indexMap.getValue(v)) {
      val component = Component<V>(nextComponentId++)
      while (true) {
        val popped = stack.removeLast()
        onStack -= popped
        component.vertices += popped
        componentOf[popped] = component.id
        if (popped == v) {
          break
        }
      }
      components += component
    }
  }

  // Sorted for determinism
  for (v in keys.sorted()) {
    if (v !in indexMap) {
      strongConnect(v)
    }
  }

  return components to componentOf
}

/**
 * Builds a DAG of SCCs from the original graph edges.
 *
 * In this DAG, nodes represent SCCs of the input graph, and edges represent dependencies between
 * SCCs. The graph is constructed such that arrows are reversed for dependency tracking (Kahn's
 * algorithm compatibility).
 *
 * @param originalEdges A map representing the edges of the original graph, where the key is a
 *   vertex and the value is a list of vertices it points to.
 * @param componentOf A map associating each vertex with its corresponding SCC number.
 * @return A map representing the DAG, where the key is the SCC number, and the value is a set of
 *   SCCs it depends on.
 */
private fun <V> buildComponentDag(
  originalEdges: Map<V, List<V>>,
  componentOf: Map<V, Int>,
): Map<Int, Set<Int>> {
  val dag = mutableMapOf<Int, MutableSet<Int>>()

  for ((fromVertex, outs) in originalEdges) {
    // prerequisite side
    val prereqComp = componentOf.getValue(fromVertex)
    for (toVertex in outs) {
      // dependent side
      val dependentComp = componentOf.getValue(toVertex)
      if (prereqComp != dependentComp) {
        // Reverse the arrow so Kahn sees "prereq → dependent"
        dag.getOrPut(dependentComp, ::mutableSetOf) += prereqComp
      }
    }
  }
  return dag
}

/**
 * Performs a Kahn topological sort on the [dag] and returns the sorted order.
 *
 * @param dag A map representing the DAG, where keys are node identifiers and values are sets of
 *   child node identifiers (edges).
 * @param componentCount The total number of components (nodes) in the graph.
 * @return A list of integers representing the topologically sorted order of the nodes. Throws an
 *   exception if a cycle remains in the graph, which should be impossible after a proper SCC
 *   collapse.
 * @see <a href="https://en.wikipedia.org/wiki/Topological_sorting">Topological sorting</a>
 * @see <a href="https://www.interviewcake.com/concept/java/topological-sort">Topological sort</a>
 */
private fun topologicallySortComponentDag(dag: Map<Int, Set<Int>>, componentCount: Int): List<Int> {
  val inDegree = IntArray(componentCount)
  dag.values.flatten().forEach { inDegree[it]++ }

  /**
   * Why a [PriorityQueue] instead of a FIFO queue like [ArrayDeque]?
   *
   * ```
   * (0)──▶(2)
   *  │
   *  └───▶(1)
   * ```
   *
   * After we process component 0, both 1 and 2 are "ready". A plain ArrayDeque would enqueue them
   * in whatever order the [dag]'s keys are, which isn't deterministic.
   *
   * Using a PriorityQueue means we *always* dequeue the lowest id first (1 before 2 in this
   * example). That keeps generated code consistent across builds.
   */
  val queue =
    PriorityQueue<Int>().apply {
      // Seed the work‑queue with every component whose in‑degree is 0.
      for (id in 0 until componentCount) {
        if (inDegree[id] == 0) {
          add(id)
        }
      }
    }

  val order = mutableListOf<Int>()
  while (queue.isNotEmpty()) {
    val c = queue.remove()
    order += c
    for (n in dag[c].orEmpty()) {
      if (--inDegree[n] == 0) {
        queue += n
      }
    }
  }
  check(order.size == componentCount) { "Cycle remained after SCC collapse (should be impossible)" }
  return order
}
