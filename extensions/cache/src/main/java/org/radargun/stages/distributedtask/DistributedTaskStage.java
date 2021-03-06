package org.radargun.stages.distributedtask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.radargun.DistStageAck;
import org.radargun.StageResult;
import org.radargun.config.Property;
import org.radargun.config.Stage;
import org.radargun.reporting.Report;
import org.radargun.stages.AbstractDistStage;
import org.radargun.stages.cache.RandomDataStage;
import org.radargun.state.WorkerState;
import org.radargun.stats.BasicStatistics;
import org.radargun.stats.DataOperationStats;
import org.radargun.stats.Request;
import org.radargun.stats.Statistics;
import org.radargun.traits.CacheInformation;
import org.radargun.traits.Clustered;
import org.radargun.traits.DistributedTaskExecutor;
import org.radargun.traits.InjectTrait;
import org.radargun.utils.KeyValueProperty;
import org.radargun.utils.Utils;

/**
 * Executes a Callable or DistributedCallable against the cache using the
 * DistributedExecutorService. The execution and failover policies can be specified. If the IP
 * address of a node is specified in <code>nodeAddress</code>, then the Callable is only executed on
 * that node. Public String Fields on the Callable object can be set using the
 * <code>distributedExecutionParams</code> property.
 *
 * @author Alan Field &lt;afield@redhat.com&gt;
 */
@Stage(doc = "Stage which executes a MapReduce Task against all keys in the cache.")
public class DistributedTaskStage<K, V, T> extends AbstractDistStage {

   // TODO: use approach similar to generators
   @Property(optional = false, doc = "Fully qualified class name of the "
      + "java.util.concurrent.Callable implementation to execute.")
   public String callable;

   @Property(doc = "A list of key-value pairs in the form of "
      + "'methodName:methodParameter;methodName1:methodParameter1' that allows"
      + " invoking a method on the callable. The method must be public and take a String parameter. Default is none.",
      complexConverter = KeyValueProperty.KeyValuePairListConverter.class)
   public Collection<KeyValueProperty> callableParams;

   @Property(doc = "The name of the execution policy. The default is default policy of the service.")
   public String executionPolicy;

   @Property(doc = "The name of the failover policy. The default is default policy of the service.")
   public String failoverPolicy;

   // TODO: specify rather the worker ids/groups - RadarGun identifier.
   // However, another stage + trait to gather these data would be required.
   @Property(doc = "The node address where the task will be "
      + "executed. The default is null, and tasks will be executed against all nodes in the cluster.")
   public String nodeAddress;

   @Property(doc = "The number of times to execute the Callable. The default is 1.")
   public int numExecutions = 1;

   @Property(doc = "The name of the key in the MainState object that returns the total number of "
      + "bytes processed by the Callable. The default is RandomDataStage.RANDOMDATA_TOTALBYTES_KEY.")
   public String totalBytesKey = RandomDataStage.RANDOMDATA_TOTALBYTES_KEY;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   private DistributedTaskExecutor<T> executor;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   private CacheInformation cacheInformation;

   @InjectTrait(dependency = InjectTrait.Dependency.MANDATORY)
   private Clustered clustered;

   @Override
   public StageResult processAckOnMain(List<DistStageAck> acks) {
      StageResult result = super.processAckOnMain(acks);

      Report report = mainState.getReport();
      Report.Test test = report.createTest("Distributed_Task_Stage", null, true);
      int testIteration = test.getIterations().size();

      Map<Integer, Report.WorkerResult> durationsResult = new HashMap<Integer, Report.WorkerResult>();

      for (DistributedTaskAck ack : instancesOf(acks, DistributedTaskAck.class)) {
         if (ack.stats != null) {
            DataOperationStats opStats = (DataOperationStats) ack.stats.getOperationStats(DistributedTaskExecutor.EXECUTE.name);
            opStats.setTotalBytes((Long) mainState.get(totalBytesKey));
            durationsResult.put(ack.getWorkerIndex(), new Report.WorkerResult(opStats.getResponseTimes(), false));
            test.addResult(testIteration, new Report.TestResult("Callable durations", durationsResult, "", false));
            test.addStatistics(testIteration, ack.getWorkerIndex(), Collections.singletonList(ack.stats));
         }
      }

      return result;
   }

   @Override
   public DistStageAck executeOnWorker() {
      if (!isServiceRunning()) {
         return errorResponse("Service not running", null);
      }

      if (callable == null) {
         return errorResponse("The distributed task or callable class must be specified.", null);
      }

      if (workerState.getWorkerIndex() == 0) {
         return executeTask();
      } else {
         return new DistributedTaskAck(workerState);
      }
   }

   private DistStageAck executeTask() {
      DistributedTaskAck ack = new DistributedTaskAck(workerState);
      Statistics stats = new BasicStatistics(new DataOperationStats());

      stats.begin();
      for (int i = 0; i < numExecutions; i++) {
         Callable<T> callable = Utils.instantiate(this.callable);
         callable = Utils.invokeMethodWithProperties(callable, callableParams);

         DistributedTaskExecutor.Builder<T> builder = executor.builder(null).callable(callable);
         if (executionPolicy != null)
            builder.executionPolicy(executionPolicy);
         if (failoverPolicy != null)
            builder.failoverPolicy(failoverPolicy);
         if (nodeAddress != null)
            builder.nodeAddress(nodeAddress);
         DistributedTaskExecutor.Task<T> task = builder.build();

         log.info("--------------------");
         List<T> resultList = new ArrayList<T>();

         Request request = stats.startRequest();
         List<Future<T>> futureList = task.execute();
         if (futureList == null) {
            ack.error("No future objects returned from executing the distributed task.");
         } else {
            for (Future<T> future : futureList) {
               try {
                  resultList.add(future.get());
               } catch (InterruptedException e) {
                  ack.error("The distributed task was interrupted.", e);
               } catch (ExecutionException e) {
                  ack.error("An error occurred executing the distributed task.", e);
               }
            }
         }
         request.succeeded(DistributedTaskExecutor.EXECUTE);

         log.info("Distributed Execution task completed in "
            + Utils.prettyPrintTime(request.duration(), TimeUnit.NANOSECONDS));
         log.infof("%d nodes were used. %d entries on this node", clustered.getMembers().size(), cacheInformation
            .getCache(null).getLocallyStoredSize());
         log.info("Distributed execution results:");
         log.info("--------------------");
         for (T t : resultList) {
            log.info(t.toString());
         }
         log.info("--------------------");
      }
      stats.end();
      ack.setStats(stats);
      return ack;
   }

   private static class DistributedTaskAck extends DistStageAck {
      private Statistics stats;

      public DistributedTaskAck(WorkerState workerState) {
         super(workerState);
      }

      public void setStats(Statistics stats) {
         this.stats = stats;
      }

   }
}
