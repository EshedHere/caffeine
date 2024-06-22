package com.github.benmanes.caffeine.cache.simulator.policy.esp;


import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.PipelineTinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.greedy_dual.GDWheelPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SampledPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.SegmentedLruPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TwoQueuePolicy;
import com.tangosol.util.Base;
import com.typesafe.config.Config;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.SuperPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.SharedBuffer;
import org.checkerframework.checker.units.qual.Length;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import java.util.*;
import static java.util.Locale.US;


/**
 * Your PipelinePolicy class.
 * <p>
 * This implementation is based on your PipelinePolicy class. You can access and use methods from
 * the TuQueuePolicy instance as needed.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "esp.PipelinePolicy")
public final class PipelinePolicy implements KeyOnlyPolicy {

  private final SuperPolicy superPolicy;
  public PolicyStats pipeLineStats;
  private BaseNode baseNode;
  public String pipelineOrder;
  final int maximumSize;
  private final HashMap<Long, Integer> lookUptable;
  int maxEntries;
  String pipelineList;
  int pipeline_length;
  String[] pipelineArray;
  List<Policy> pipelinePolicies =new ArrayList<>();
  PolicyConstructor policyConstructor;
  Config confTest;
  private final PipelineTinyLfu admittor;
  int extCount=0; //used for tracking nodes in the pipeline + pipeline current stage
  private int keyTest=0;
  static class PipelineSettings extends BasicSettings {
  public PipelineSettings(Config config) {
    super(config);
  }
  public double pipelineLength() {
    // Redirect to relevant field in the config file
    return config().getDouble("pipeline.length");
  }
  public String pipelineOrder() {
    // Redirect to relevant field in the config file
    return config().getString("esp.pipeline.order");
  }
}
  public PipelinePolicy(Config config) {
    this.policyConstructor = new PolicyConstructor(config);
//------------------INIT--------------------
    superPolicy = new SuperPolicy(config);
    this.pipeLineStats = new PolicyStats("PipeLine");
    PipelineSettings settings = new PipelineSettings(config);
    this.maximumSize = Math.toIntExact(settings.maximumSize());
//    this.maxEntries = 512;
    //NOTE - the lookup table structure is affecting the results, each run is different
    this.lookUptable = new HashMap<Long, Integer>();//load factor affects the results can also be used with linked HashMap;
    //------------EXTRACT THE PIPELINE ORDER----------------
    this.pipelineList = settings.pipelineOrder();
    this.pipelineArray = this.pipelineList.split(",");
    System.out.println(this.pipelineArray[0]);
    this.pipeline_length = this.pipelineArray.length;
    this.baseNode = new BaseNode();
    this.keyTest=0;
    this.admittor = PipelineTinyLfu.getInstance(config, pipeLineStats);

    System.out.println("pipeline lengtgh is " + this.pipeline_length);

    //-----------------BUILD THE PIPELINE-------------------
//    policyConstructor = new PolicyConstructor(config);
    for (int i = 0; i < this.pipeline_length; i++) {
      pipelinePolicies.add(this.policyConstructor.createPolicy(this.pipelineArray[i]));
//      pipelinePolicies.add(superPolicy.segmentedLRUPolicy);
//      pipelinePolicies.add(superPolicy.gdWheelPolicy);

    }

  }

  @Override
  public void record(long key) {

    this.admittor.sketch.increment(key); //increase freq value in sketch
    this.baseNode.key=key;
    SharedBuffer.insertData(this.baseNode);
    SharedBuffer.resetCounter();
    extCount =0;

    //------------ON HIT----------
    if(lookUptable.get(this.baseNode.key) != null) {
      pipeLineStats.recordOperation();
      pipeLineStats.recordHit();
      int blockIndex = lookUptable.get(this.baseNode.key);
      if (pipelinePolicies.get(blockIndex) instanceof KeyOnlyPolicy) {
        // Handle the event as a key-only event
        ((KeyOnlyPolicy) pipelinePolicies.get(blockIndex)).record(SharedBuffer.getBufferKey());
      } else {
        // Handle the event for a generic policy
        AccessEvent event = new AccessEvent(SharedBuffer.getBufferKey()/* Additional details here */);
        (pipelinePolicies.get(blockIndex)).record(event);

      }
      return;
      //PROPAGATION

      //------------ON MISS----------
    } else {
      lookUptable.put(this.baseNode.key, 0);
      pipeLineStats.recordAdmission();
      pipeLineStats.recordOperation();
      //print miss key
//      System.out.println("Pipeline miss key is " + key);
      pipeLineStats.recordMiss();
      }

    //----------MAIN PIPELINE LOOP----------
for (int i = 0; i <= this.pipeline_length; i++) {
        //Read from the SharedBuffer
//    System.out.println("extCount "+extCount+" and i is " + i + " length is " + this.pipeline_length);

  extCount = SharedBuffer.getCounter();
        //If the SharedBuffer is increased by 1, activate the next block


  if(extCount==i) {

    //print i
//    System.out.println("Pipeline got "+key+" and i is " + i);
          if(i==this.pipeline_length) {
//            System.out.println(extCount);
//            System.out.println("Pipeline data before eviction "+this.lookUptable);

            lookUptable.remove(SharedBuffer.getBufferKey());
//            System.out.println("Pipeline got "+this.baseNode.key+" and victim key is " + SharedBuffer.getBufferKey());
//            System.out.println("Pipeline data after eviction "+this.lookUptable);
            continue;
          }
          lookUptable.put(SharedBuffer.getBufferKey(), i);
          //Activate the next block
          if (pipelinePolicies.get(i) instanceof KeyOnlyPolicy) {
            // Handle the event as a key-only event

//            if(SharedBuffer.getBufferKey()==0) {
//              System.out.println("The buffer key key is: " + SharedBuffer.getBufferKey() + "   "+this.baseNode.key+"   block is  "+i);
//
//            }
            ((KeyOnlyPolicy) pipelinePolicies.get(i)).record(this.baseNode.key);
          } else {
            // Handle the event for a generic policy
            AccessEvent event = new AccessEvent(key/* Additional details here */);
            pipelinePolicies.get(i).record(event);
          }

        }
      }

    }

  @Override
  public PolicyStats stats() {
    // You can also access and use the statistics from the TwoQueuePolicy instance
    //return superPolicy.twoQueuePolicy.stats();
    return pipeLineStats;

  }
  @Override
  public void finished() {
    // Ensure that all resources are properly cleaned up
//    System.out.println(data.size());
    superPolicy.twoQueuePolicy.finished();
  }



}


