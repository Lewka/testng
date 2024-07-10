package org.testng.internal.thread.graph;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.testng.IDynamicGraph;
import org.testng.collections.Maps;
import org.testng.internal.AutoCloseableLock;
import org.testng.internal.RuntimeBehavior;
import org.testng.internal.Utils;
import org.testng.log4testng.Logger;
import org.testng.thread.IThreadWorkerFactory;
import org.testng.thread.IWorker;

/**
 * An orchestrator that works with a {@link IDynamicGraph} graph to execute nodes from the DAG in an
 * concurrent fashion by using a {@link ThreadPoolExecutor}
 */
public class GraphOrchestrator<T> {
  private final ExecutorService service;
  private final IDynamicGraph<T> graph;
  private final Map<T, IWorker<T>> mapping = Maps.newConcurrentMap();
  private final Map<T, T> upstream = Maps.newConcurrentMap();
  private final Comparator<T> comparator;
  private final IThreadWorkerFactory<T> factory;

  private final AutoCloseableLock internalLock = new AutoCloseableLock();

  public GraphOrchestrator(
      ExecutorService service,
      IThreadWorkerFactory<T> factory,
      IDynamicGraph<T> graph,
      Comparator<T> comparator) {
    this.service = service;
    this.graph = graph;
    this.comparator = comparator;
    this.factory = factory;
  }

  public void run() {
    try (AutoCloseableLock ignore = internalLock.lock()) {
      List<T> freeNodes = graph.getFreeNodes();
      if (comparator != null) {
        freeNodes.sort(comparator);
      }
      Utils.log("Running graph orchestrator");
      runNodes(freeNodes);
      Utils.log("Graph orchestrator completed");
    }
  }

  private void runNodes(List<T> freeNodes) {
    Utils.log("Run nodes");
    List<IWorker<T>> workers = factory.createWorkers(freeNodes);
    Utils.log("Creating workers");
    mapNodeToWorker(workers, freeNodes);
    Utils.log("Done creating workers " + workers + " for " + freeNodes);
    for (IWorker<T> worker : workers) {
      Utils.log("Running worker " + worker);
      mapNodeToParent(freeNodes);
      Utils.log("Map worker " + freeNodes);
      freeNodes.forEach(n -> Utils.log("\n node: " + n));
      setStatus(worker, IDynamicGraph.Status.RUNNING);
      Utils.log("Running worker#2 ");
      try {
        TestNGFutureTask<T> task = new TestNGFutureTask<>(worker, this::afterExecute);
        Utils.log("Running task " + task);
        service.execute(task);
        Utils.log("Finished run of task " + task);
      } catch (Exception ex) {
        Utils.log("###Exception in runNodes() " + ex.getMessage());
        Logger.getLogger(GraphOrchestrator.class).error(ex.getMessage(), ex);
      }
    }
  }

  private void mapNodeToParent(List<T> freeNodes) {
    if (!RuntimeBehavior.enforceThreadAffinity()) {
      return;
    }
    for (T freeNode : freeNodes) {
      List<T> nodes = graph.getDependenciesFor(freeNode);
      nodes.forEach(eachNode -> upstream.put(eachNode, freeNode));
    }
  }

  private void afterExecute(IWorker<T> r, Throwable t) {
    Utils.log("After execute for r=" + r + " t=" + t);
    try (AutoCloseableLock ignore = internalLock.lock()) {
      Utils.log("Setting status");
      IDynamicGraph.Status status = computeStatus(r);
      Utils.log("Status = " + status);
      setStatus(r, status);
      Utils.log("After set status");
      if (graph.getNodeCount() == graph.getNodeCountWithStatus(IDynamicGraph.Status.FINISHED)) {
        Utils.log("Shutting down");
        service.shutdown();
        Utils.log("Complete shutting down");
      } else {
        Utils.log("NO!");
        List<T> freeNodes = graph.getFreeNodes();
        if (comparator != null) {
          freeNodes.sort(comparator);
        }
        Utils.log("Sort free nodes...");
        handleThreadAffinity(freeNodes);
        Utils.log("Handle thread affinity");
        runNodes(freeNodes);
        Utils.log("Run nodes " + freeNodes + " completed");
      }
    }
  }

  private void handleThreadAffinity(List<T> freeNodes) {
    Utils.log("Handle thread affinity...");
    if (!RuntimeBehavior.enforceThreadAffinity()) {
      Utils.log("Not enough thread affinity");
      return;
    }
    for (T node : freeNodes) {
      Utils.log("Free node " + node);
      T upstreamNode = upstream.get(node);
      Utils.log("Upstream node " + upstreamNode);
      if (upstreamNode == null) {
        Utils.log("Upstream node is null");
        continue;
      }
      IWorker<T> w = mapping.get(upstreamNode);
      Utils.log("Mapping node " + w);
      if (w != null) {
        long threadId = w.getCurrentThreadId();
        PhoneyWorker<T> value = new PhoneyWorker<>(threadId);
        Utils.log("Phoney worker " + value);
        mapping.put(node, value);
      }
    }
  }

  private IDynamicGraph.Status computeStatus(IWorker<T> worker) {
    IDynamicGraph.Status status = IDynamicGraph.Status.FINISHED;
    if (RuntimeBehavior.enforceThreadAffinity() && !worker.completed()) {
      status = IDynamicGraph.Status.READY;
    }
    return status;
  }

  private void setStatus(IWorker<T> worker, IDynamicGraph.Status status) {
    try (AutoCloseableLock ignore = internalLock.lock()) {
      for (T m : worker.getTasks()) {
        graph.setStatus(m, status);
      }
    }
  }

  private void mapNodeToWorker(List<IWorker<T>> runnables, List<T> freeNodes) {
    if (!RuntimeBehavior.enforceThreadAffinity()) {
      return;
    }
    for (IWorker<T> runnable : runnables) {
      for (T freeNode : freeNodes) {
        IWorker<T> w = mapping.get(freeNode);
        if (w != null) {
          long current = w.getThreadIdToRunOn();
          runnable.setThreadIdToRunOn(current);
        }
        if (runnable.toString().contains(freeNode.toString())) {
          mapping.put(freeNode, runnable);
        }
      }
    }
  }
}
