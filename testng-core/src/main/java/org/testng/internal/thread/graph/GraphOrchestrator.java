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
      Utils.log("Running graph orchestrator with free nodes: " + freeNodes);
      runNodes(freeNodes);
      Utils.log("Graph orchestrator completed");
    } catch (Exception ex) {
      Utils.log("###Exception in run() " + ex.getMessage());
      Logger.getLogger(GraphOrchestrator.class).error(ex.getMessage(), ex);
    }
  }

  private void runNodes(List<T> freeNodes) {
    Utils.log("Run nodes: " + freeNodes);
    List<IWorker<T>> workers = factory.createWorkers(freeNodes);
    Utils.log("Creating workers for nodes: " + freeNodes);
    mapNodeToWorker(workers, freeNodes);
    Utils.log("Done creating workers " + workers + " for nodes " + freeNodes);
    for (IWorker<T> worker : workers) {
      Utils.log("Running worker " + worker + " for nodes: " + worker.getTasks());
      mapNodeToParent(freeNodes);
      Utils.log("Mapped nodes to parent: " + freeNodes);
      freeNodes.forEach(n -> Utils.log("Node: " + n));
      setStatus(worker, IDynamicGraph.Status.RUNNING);
      Utils.log("Worker status set to RUNNING for worker: " + worker);
      try {
        TestNGFutureTask<T> task = new TestNGFutureTask<>(worker, this::afterExecute);
        Utils.log("Running task " + task);
        service.execute(task);
        Utils.log("Finished execution of task " + task);
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
    Utils.log("After execute for worker: " + r + " with throwable: " + t);
    try (AutoCloseableLock ignore = internalLock.lock()) {
      Utils.log("Setting status for worker: " + r);
      IDynamicGraph.Status status = computeStatus(r);
      Utils.log("Computed status for worker: " + r + " is " + status);
      setStatus(r, status);
      Utils.log("Status set for worker: " + r);
      int totalNodes = graph.getNodeCount();
      int finishedNodes = graph.getNodeCountWithStatus(IDynamicGraph.Status.FINISHED);
      Utils.log("Total nodes: " + totalNodes + ", Finished nodes: " + finishedNodes);

      if (totalNodes == finishedNodes) {
        Utils.log("All nodes finished. Shutting down service.");
        service.shutdown();
        Utils.log("Service shutdown complete.");
      } else {
        Utils.log("Not all nodes finished. Continuing execution.");
        List<T> freeNodes = graph.getFreeNodes();
        if (comparator != null) {
          freeNodes.sort(comparator);
        }
        Utils.log("Sorted free nodes: " + freeNodes);
        handleThreadAffinity(freeNodes);
        Utils.log("Handled thread affinity for nodes: " + freeNodes);
        runNodes(freeNodes);
        Utils.log("Execution of nodes " + freeNodes + " completed.");
      }
    } catch (Exception ex) {
      Utils.log("###Exception in afterExecute() " + ex.getMessage());
      Logger.getLogger(GraphOrchestrator.class).error(ex.getMessage(), ex);
    }
  }

  private void handleThreadAffinity(List<T> freeNodes) {
    Utils.log("Handle thread affinity for nodes: " + freeNodes);
    if (!RuntimeBehavior.enforceThreadAffinity()) {
      Utils.log("Thread affinity not enforced.");
      return;
    }
    for (T node : freeNodes) {
      Utils.log("Free node: " + node);
      T upstreamNode = upstream.get(node);
      Utils.log("Upstream node: " + upstreamNode);
      if (upstreamNode == null) {
        Utils.log("Upstream node is null for node: " + node);
        continue;
      }
      IWorker<T> w = mapping.get(upstreamNode);
      Utils.log("Mapping node " + node + " to worker: " + w);
      if (w != null) {
        long threadId = w.getCurrentThreadId();
        PhoneyWorker<T> value = new PhoneyWorker<>(threadId);
        Utils.log("Phoney worker created: " + value + " for node: " + node);
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
        Utils.log("Setting status for node " + m + " to " + status);
        graph.setStatus(m, status);
      }
    } catch (Exception ex) {
      Utils.log("###Exception in setStatus() " + ex.getMessage());
      Logger.getLogger(GraphOrchestrator.class).error(ex.getMessage(), ex);
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
